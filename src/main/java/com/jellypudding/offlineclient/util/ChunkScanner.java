package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Predicate;

/**
 * Scans loaded chunks on a background thread and caches what each one
 * held. A chunk is only scanned again once the server changes it.
 */
public final class ChunkScanner<T> {

    // Two workers keep one slow module from stalling another.
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "OfflineClient ChunkScanner");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });

    // Runs on the scanner thread.
    public interface Scan<T> {
        void run(View view, List<T> out);
    }

    // Handed world coordinates and the state standing there.
    public interface BlockVisitor {
        void accept(int x, int y, int z, BlockState state);
    }

    /**
     * A chunk and its eight neighbours captured on the main thread. Reads
     * outside the captured area come back as void air.
     */
    public static final class View {

        private static final BlockState OUTSIDE = Blocks.VOID_AIR.defaultBlockState();

        private final LevelChunk centre;
        private final LevelChunk[] around = new LevelChunk[9];
        private final int minY;
        private final int maxY;

        private View(LevelChunk centre, Minecraft mc) {
            this.centre = centre;
            this.minY = centre.getMinY();
            this.maxY = centre.getMaxY();
            int cx = centre.getPos().x();
            int cz = centre.getPos().z();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    around[(dx + 1) * 3 + dz + 1] = mc.level.getChunkSource()
                        .getChunk(cx + dx, cz + dz, ChunkStatus.FULL, false);
                }
            }
        }

        public ChunkPos pos() {
            return centre.getPos();
        }

        public int minY() {
            return minY;
        }

        public int maxY() {
            return maxY;
        }

        public BlockState get(int x, int y, int z) {
            if (y < minY || y > maxY) {
                return OUTSIDE;
            }
            int dx = (x >> 4) - centre.getPos().x();
            int dz = (z >> 4) - centre.getPos().z();
            if (dx < -1 || dx > 1 || dz < -1 || dz > 1) {
                return OUTSIDE;
            }
            LevelChunk chunk = around[(dx + 1) * 3 + dz + 1];
            if (chunk == null) {
                return OUTSIDE;
            }
            LevelChunkSection section = chunk.getSections()[chunk.getSectionIndex(y)];
            if (section == null || section.hasOnlyAir()) {
                return Blocks.AIR.defaultBlockState();
            }
            return section.getBlockState(x & 15, y & 15, z & 15);
        }

        public BlockState get(BlockPos pos) {
            return get(pos.getX(), pos.getY(), pos.getZ());
        }

        /**
         * Every block of the centre chunk the test accepts. A section whose
         * palette cannot hold one is skipped whole.
         */
        public void forEachMatching(Predicate<BlockState> wanted, BlockVisitor out) {
            LevelChunkSection[] sections = centre.getSections();
            int baseX = centre.getPos().getMinBlockX();
            int baseZ = centre.getPos().getMinBlockZ();
            for (int index = 0; index < sections.length; index++) {
                LevelChunkSection section = sections[index];
                if (section == null || section.hasOnlyAir() || !section.maybeHas(wanted)) {
                    continue;
                }
                int baseY = minY + (index << 4);
                for (int y = 0; y < 16; y++) {
                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            BlockState state = section.getBlockState(x, y, z);
                            if (wanted.test(state)) {
                                out.accept(baseX + x, baseY + y, baseZ + z, state);
                            }
                        }
                    }
                }
            }
        }
    }

    private final Map<ChunkPos, List<T>> results = new HashMap<>();
    private final Map<ChunkPos, Future<List<T>>> pending = new HashMap<>();
    // Chunks the server changed. Filled from the network thread.
    private final Set<ChunkPos> changed = ConcurrentHashMap.newKeySet();
    // Changes seen last tick. The game applies a packet after the scanner sees it.
    private Set<ChunkPos> due = new HashSet<>();
    private List<T> collected = List.of();
    // Set whenever a chunk result is added or dropped.
    private boolean stale;
    private ResourceKey<Level> dimension;

    // Safe from any thread.
    public void markChanged(Packet<?> packet) {
        ChunkPos pos = affectedChunk(packet);
        if (pos != null) {
            changed.add(pos);
        }
    }

    public void reset() {
        for (Future<List<T>> future : pending.values()) {
            future.cancel(true);
        }
        pending.clear();
        results.clear();
        changed.clear();
        due.clear();
        collected = List.of();
        stale = false;
    }

    public List<T> results() {
        return collected;
    }

    public int size() {
        return collected.size();
    }

    // Called once per tick from the main thread.
    public void update(int radius, Scan<T> scan) {
        Minecraft mc = OfflineClient.MC;
        if (mc.level == null || mc.player == null) {
            return;
        }
        if (mc.level.dimension() != dimension) {
            dimension = mc.level.dimension();
            reset();
        }

        Set<ChunkPos> changedLastTick = due;
        due = new HashSet<>();
        for (Iterator<ChunkPos> it = changed.iterator(); it.hasNext(); ) {
            due.add(it.next());
            it.remove();
        }

        collectFinished();

        int centerX = mc.player.chunkPosition().x();
        int centerZ = mc.player.chunkPosition().z();
        dropOutOfRange(centerX, centerZ, radius);

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                ChunkPos pos = new ChunkPos(centerX + dx, centerZ + dz);
                LevelChunk chunk = mc.level.getChunkSource()
                    .getChunk(pos.x(), pos.z(), ChunkStatus.FULL, false);
                if (chunk == null) {
                    stale |= results.remove(pos) != null;
                    continue;
                }
                if (changedLastTick.contains(pos)) {
                    stale |= results.remove(pos) != null;
                    Future<List<T>> old = pending.remove(pos);
                    if (old != null) {
                        old.cancel(true);
                    }
                }
                if (!results.containsKey(pos) && !pending.containsKey(pos)) {
                    View view = new View(chunk, mc);
                    pending.put(pos, POOL.submit(() -> {
                        List<T> found = new ArrayList<>();
                        scan.run(view, found);
                        return found;
                    }));
                }
            }
        }

        if (stale) {
            collected = List.copyOf(flatten());
            stale = false;
        }
    }

    private void dropOutOfRange(int centerX, int centerZ, int radius) {
        stale |= results.keySet().removeIf(pos -> outOfRange(pos, centerX, centerZ, radius));
        pending.entrySet().removeIf(entry -> {
            if (outOfRange(entry.getKey(), centerX, centerZ, radius)) {
                entry.getValue().cancel(true);
                return true;
            }
            return false;
        });
    }

    private List<T> flatten() {
        List<T> all = new ArrayList<>();
        for (List<T> list : results.values()) {
            all.addAll(list);
        }
        return all;
    }

    private static boolean outOfRange(ChunkPos pos, int centerX, int centerZ, int radius) {
        return Math.abs(pos.x() - centerX) > radius || Math.abs(pos.z() - centerZ) > radius;
    }

    private void collectFinished() {
        for (Iterator<Map.Entry<ChunkPos, Future<List<T>>>> it = pending.entrySet().iterator();
             it.hasNext(); ) {
            Map.Entry<ChunkPos, Future<List<T>>> entry = it.next();
            Future<List<T>> future = entry.getValue();
            if (!future.isDone()) {
                continue;
            }
            it.remove();
            if (future.isCancelled()) {
                continue;
            }
            try {
                results.put(entry.getKey(), future.get());
                stale = true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException e) {
                // A chunk that unloaded mid scan is scanned again later.
                OfflineClient.LOG.debug("Chunk scan failed", e.getCause());
            }
        }
    }

    private static ChunkPos affectedChunk(Packet<?> packet) {
        if (packet instanceof ClientboundBlockUpdatePacket update) {
            return ChunkPos.containing(update.getPos());
        }
        if (packet instanceof ClientboundSectionBlocksUpdatePacket update) {
            ChunkPos[] holder = new ChunkPos[1];
            update.runUpdates((pos, state) -> {
                if (holder[0] == null) {
                    holder[0] = ChunkPos.containing(pos);
                }
            });
            return holder[0];
        }
        if (packet instanceof ClientboundLevelChunkWithLightPacket chunk) {
            return new ChunkPos(chunk.getX(), chunk.getZ());
        }
        return null;
    }
}
