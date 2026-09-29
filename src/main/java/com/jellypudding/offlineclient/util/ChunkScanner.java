package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.world.level.ChunkPos;
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
import java.util.function.BiConsumer;
import java.util.function.Predicate;

// Scans loaded chunks on a background thread and caches what each one held. A chunk is
// only scanned again once the server changes it or once the neighbours it lacked arrive.
public final class ChunkScanner<T> {

    // Two workers keep one slow module from stalling another.
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "OfflineClient ChunkScanner");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });

    // The client chunk cache holds chunks this many rings past the radius the server gives
    // and reaches at least the smallest radius plus those rings.
    private static final int CACHE_MARGIN = 3;
    private static final int SMALLEST_RADIUS = 2;

    // Runs on the scanner thread.
    public interface Scan<T> {
        void run(View view, List<T> out);
    }

    // Handed world coordinates and the state standing there.
    public interface BlockVisitor {
        void accept(int x, int y, int z, BlockState state);
    }

    // A chunk and the chunks around it out to the reach captured on the main thread.
    public static final class View {

        private final LevelChunk centre;
        private final ChunkWindow window;

        private View(LevelChunk centre, int reach) {
            this.centre = centre;
            this.window = ChunkWindow.capture(centre.getPos().x(), centre.getPos().z(), reach);
        }

        public ChunkPos pos() {
            return centre.getPos();
        }

        // False whilst a chunk of the window has not arrived. It reads as void air. A scan
        // that needs its neighbours can hold back. The chunk is scanned again once they come.
        public boolean complete() {
            return window.complete();
        }

        public int minY() {
            return window.minY();
        }

        public int maxY() {
            return window.maxY();
        }

        public BlockState get(int x, int y, int z) {
            return window.get(x, y, z);
        }

        public BlockState get(BlockPos pos) {
            return window.get(pos);
        }

        // Every block of the centre chunk the test accepts. A section whose palette cannot
        // hold one is skipped whole. A section of cave air counts as empty and is still read.
        public void forEachMatching(Predicate<BlockState> wanted, BlockVisitor out) {
            forEachMatching(minY(), maxY(), wanted, out);
        }

        // The same between two heights. A section wholly outside them is never read.
        public void forEachMatching(int fromY, int toY, Predicate<BlockState> wanted, BlockVisitor out) {
            LevelChunkSection[] sections = centre.getSections();
            int baseX = centre.getPos().getMinBlockX();
            int baseZ = centre.getPos().getMinBlockZ();
            for (int index = 0; index < sections.length; index++) {
                int baseY = centre.getMinY() + (index << 4);
                int lowY = Math.max(0, fromY - baseY);
                int highY = Math.min(SectionPos.SECTION_MAX_INDEX, toY - baseY);
                LevelChunkSection section = sections[index];
                if (lowY > highY || section == null || !section.maybeHas(wanted)) {
                    continue;
                }
                for (int y = lowY; y <= highY; y++) {
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

    // How many chunks out from the scanned one each view reaches.
    private final int reach;
    // A block changed this close to a chunk border also rescans the chunk across it.
    private int border;
    private final Map<ChunkPos, List<T>> results = new HashMap<>();
    private final Map<ChunkPos, Future<List<T>>> pending = new HashMap<>();
    // Chunks scanned whilst part of their window was missing.
    private final Set<ChunkPos> partial = new HashSet<>();
    // Chunks the server changed. Filled from the network thread.
    private final Set<ChunkPos> changed = ConcurrentHashMap.newKeySet();
    // Changes seen last tick. The game applies a packet after the scanner sees it.
    private Set<ChunkPos> due = new HashSet<>();
    private List<T> collected = List.of();
    // Set whenever a chunk result is added or dropped.
    private boolean stale;
    // A rejoin and a dimension change both hand out a new world. Scans of the last one are dropped.
    private final WorldWatch world = new WorldWatch();
    private BiConsumer<ChunkPos, List<T>> landed = (pos, found) -> {
    };

    // Each view holds the chunk and its eight neighbours.
    public ChunkScanner() {
        this(1);
    }

    // A scan that looks further than the next chunk over asks for a wider view.
    public ChunkScanner(int reach) {
        this.reach = reach;
    }

    // How far from you the client can hold chunks. A scan this wide reads every chunk the
    // server sent whatever your own render distance.
    public static int heldRadius() {
        LocalPlayer player = OfflineClient.MC.player;
        int radius = player == null ? 0 : player.connection.serverChunkRadius;
        return Math.max(SMALLEST_RADIUS, radius) + CACHE_MARGIN;
    }

    // Hands each chunk result to the listener on the main thread as it lands. A chunk scanned
    // again hands over its new result.
    public ChunkScanner<T> onResult(BiConsumer<ChunkPos, List<T>> listener) {
        landed = listener;
        return this;
    }

    // A scan that keeps a find at a spot other than the blocks it was read from needs the
    // chunks beside a changed block read again. Blocks this close to their border count.
    public ChunkScanner<T> borderReach(int blocks) {
        border = Math.min(blocks, reach * SectionPos.SECTION_SIZE);
        return this;
    }

    // Safe from any thread.
    public void markChanged(Packet<?> packet) {
        switch (packet) {
            case ClientboundBlockUpdatePacket update -> changedAt(update.getPos());
            case ClientboundSectionBlocksUpdatePacket update -> update.runUpdates((pos, state) -> changedAt(pos));
            case ClientboundLevelChunkWithLightPacket chunk -> changed.add(new ChunkPos(chunk.x(), chunk.z()));
            default -> {
            }
        }
    }

    // The chunk of a changed block and every chunk across a border it lies close to.
    private void changedAt(BlockPos pos) {
        for (int x = (pos.getX() - border) >> 4; x <= (pos.getX() + border) >> 4; x++) {
            for (int z = (pos.getZ() - border) >> 4; z <= (pos.getZ() + border) >> 4; z++) {
                changed.add(new ChunkPos(x, z));
            }
        }
    }

    public void reset() {
        for (Future<List<T>> future : pending.values()) {
            future.cancel(true);
        }
        pending.clear();
        results.clear();
        partial.clear();
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

    // True once every loaded chunk in range has been read and nothing waits on the workers.
    public boolean idle() {
        return pending.isEmpty();
    }

    // Called once per tick from the main thread.
    public void update(int radius, Scan<T> scan) {
        Minecraft mc = OfflineClient.MC;
        if (mc.level == null || mc.player == null) {
            return;
        }
        if (world.changed()) {
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
        Set<ChunkPos> filledIn = filledIn(changedLastTick);

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                ChunkPos pos = new ChunkPos(centerX + dx, centerZ + dz);
                LevelChunk chunk = mc.level.getChunkSource()
                    .getChunk(pos.x(), pos.z(), ChunkStatus.FULL, false);
                if (chunk == null) {
                    stale |= results.remove(pos) != null;
                    partial.remove(pos);
                    continue;
                }
                if (changedLastTick.contains(pos) || filledIn.contains(pos)) {
                    stale |= results.remove(pos) != null;
                    Future<List<T>> old = pending.remove(pos);
                    if (old != null) {
                        old.cancel(true);
                    }
                }
                if (!results.containsKey(pos) && !pending.containsKey(pos)) {
                    View view = new View(chunk, reach);
                    if (view.complete()) {
                        partial.remove(pos);
                    } else {
                        partial.add(pos);
                    }
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

    // Chunks scanned with part of their window missing that a chunk arriving within reach
    // has made whole. Waiting for the whole window scans each of them once more at most.
    private Set<ChunkPos> filledIn(Set<ChunkPos> arrived) {
        if (partial.isEmpty() || arrived.isEmpty()) {
            return Set.of();
        }
        Set<ChunkPos> filled = new HashSet<>();
        for (ChunkPos pos : arrived) {
            for (int dx = -reach; dx <= reach; dx++) {
                for (int dz = -reach; dz <= reach; dz++) {
                    ChunkPos near = new ChunkPos(pos.x() + dx, pos.z() + dz);
                    if (partial.contains(near) && ChunkWindow.capture(near.x(), near.z(), reach).complete()) {
                        filled.add(near);
                    }
                }
            }
        }
        return filled;
    }

    private void dropOutOfRange(int centerX, int centerZ, int radius) {
        partial.removeIf(pos -> outOfRange(pos, centerX, centerZ, radius));
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
        Map<ChunkPos, List<T>> finished = new HashMap<>();
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
                List<T> found = future.get();
                results.put(entry.getKey(), found);
                finished.put(entry.getKey(), found);
                stale = true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException e) {
                // A chunk that unloaded mid scan is scanned again later.
                OfflineClient.LOG.debug("Chunk scan failed", e.getCause());
            }
        }
        // Told once the pending map is settled. A listener may then ask the scanner anything.
        finished.forEach(landed);
    }
}
