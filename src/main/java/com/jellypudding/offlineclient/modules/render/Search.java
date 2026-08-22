package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.ColorUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

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

/**
 * Block ESP. Every loaded chunk in range is scanned once on a background
 * thread and only scanned again when the server sends a change for it.
 */
public final class Search extends Module {

    private record Target(BlockPos pos, int color) {
    }

    /** Hand tuned colors for the usual targets. Everything else gets a stable hue from its id. */
    private static final Map<Block, Integer> PRESET_COLORS = Map.ofEntries(
        Map.entry(Blocks.DIAMOND_ORE, 0xFF40E0FF),
        Map.entry(Blocks.DEEPSLATE_DIAMOND_ORE, 0xFF40E0FF),
        Map.entry(Blocks.ANCIENT_DEBRIS, 0xFFB08060),
        Map.entry(Blocks.EMERALD_ORE, 0xFF40FF80),
        Map.entry(Blocks.DEEPSLATE_EMERALD_ORE, 0xFF40FF80),
        Map.entry(Blocks.GOLD_ORE, 0xFFFFD040),
        Map.entry(Blocks.DEEPSLATE_GOLD_ORE, 0xFFFFD040),
        Map.entry(Blocks.NETHER_GOLD_ORE, 0xFFFFD040),
        Map.entry(Blocks.IRON_ORE, 0xFFD8C0A8),
        Map.entry(Blocks.DEEPSLATE_IRON_ORE, 0xFFD8C0A8),
        Map.entry(Blocks.REDSTONE_ORE, 0xFFFF4040),
        Map.entry(Blocks.DEEPSLATE_REDSTONE_ORE, 0xFFFF4040),
        Map.entry(Blocks.LAPIS_ORE, 0xFF4060FF),
        Map.entry(Blocks.DEEPSLATE_LAPIS_ORE, 0xFF4060FF),
        Map.entry(Blocks.COAL_ORE, 0xFF909090),
        Map.entry(Blocks.DEEPSLATE_COAL_ORE, 0xFF909090),
        Map.entry(Blocks.COPPER_ORE, 0xFFFF8050),
        Map.entry(Blocks.DEEPSLATE_COPPER_ORE, 0xFFFF8050),
        Map.entry(Blocks.NETHER_QUARTZ_ORE, 0xFFF0F0E0),
        Map.entry(Blocks.SPAWNER, 0xFFC050FF),
        Map.entry(Blocks.TRIAL_SPAWNER, 0xFFC050FF),
        Map.entry(Blocks.END_PORTAL_FRAME, 0xFF60FFC0),
        Map.entry(Blocks.BEACON, 0xFF80D0FF),
        Map.entry(Blocks.ENCHANTING_TABLE, 0xFFE070FF));

    private static final ExecutorService SCANNER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "OfflineClient Search");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });

    private final NumberSetting range = new NumberSetting("Range",
        "Chunk radius to scan around you.", 4, 1, 8, 1, " chunks").max(16);
    private final NumberSetting limit = new NumberSetting("Limit",
        "Most blocks drawn at once. The nearest ones win.", 2000, 100, 5000, 100).min(1);
    private final BoolSetting tracers = new BoolSetting("Tracers",
        "Draw a line to every found block.", false);
    private final RegistryListSetting<Block> blocks = new RegistryListSetting<>("Blocks",
        "The blocks to find. Click to pick them.", BuiltInRegistries.BLOCK,
        List.of(Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE, Blocks.ANCIENT_DEBRIS,
            Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE,
            Blocks.SPAWNER, Blocks.TRIAL_SPAWNER, Blocks.END_PORTAL_FRAME,
            Blocks.BEACON, Blocks.ENCHANTING_TABLE));

    /** Block to color. Read from the scanner thread and replaced whole. */
    private volatile Map<Block, Integer> wanted = Map.of();

    private final Map<ChunkPos, List<Target>> results = new HashMap<>();
    private final Map<ChunkPos, Future<List<Target>>> pending = new HashMap<>();
    /** Chunks the server changed. Filled from the network thread. */
    private final Set<ChunkPos> changed = ConcurrentHashMap.newKeySet();
    /** Changes seen last tick. The game applies a packet after we see it. */
    private Set<ChunkPos> due = new HashSet<>();
    private List<Target> drawn = List.of();
    private ResourceKey<Level> dimension;

    public Search() {
        super("Search", "Highlights chosen blocks through walls.", Category.RENDER);
        addSettings(range, limit, tracers, blocks);
        searchTags("block esp", "ore esp");
        // A picker change rescans the affected chunks right away.
        blocks.onChange(() -> {
            if (isEnabled() && snapshot()) {
                reset();
            }
        });
    }

    @Override
    public String getSuffix() {
        return String.valueOf(drawn.size());
    }

    @Override
    protected void onEnable() {
        snapshot();
        reset();
    }

    @Override
    protected void onDisable() {
        reset();
    }

    private void reset() {
        for (Future<List<Target>> future : pending.values()) {
            future.cancel(true);
        }
        pending.clear();
        results.clear();
        changed.clear();
        due.clear();
        drawn = List.of();
    }

    /** True if the wanted blocks changed. */
    private boolean snapshot() {
        Map<Block, Integer> next = new HashMap<>();
        for (Block block : blocks.resolved()) {
            next.put(block, colorFor(block));
        }
        boolean differs = !next.equals(wanted);
        if (differs) {
            wanted = Map.copyOf(next);
        }
        return differs;
    }

    private static int colorFor(Block block) {
        Integer preset = PRESET_COLORS.get(block);
        if (preset != null) {
            return preset;
        }
        // A hue derived from the id stays stable between sessions.
        int hue = Math.floorMod(BuiltInRegistries.BLOCK.getKey(block).toString().hashCode(), 360);
        return ColorUtil.hsv(hue, 0.65f, 1f);
    }

    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        ChunkPos pos = affectedChunk(event.getPacket());
        if (pos != null) {
            changed.add(pos);
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

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            return;
        }
        if (mc.level.dimension() != dimension || snapshot()) {
            dimension = mc.level.dimension();
            reset();
        }

        Set<ChunkPos> dirty = due;
        due = new HashSet<>();
        for (Iterator<ChunkPos> it = changed.iterator(); it.hasNext(); ) {
            due.add(it.next());
            it.remove();
        }

        collectFinished();

        int r = range.getInt();
        int centerX = mc.player.chunkPosition().x();
        int centerZ = mc.player.chunkPosition().z();
        results.keySet().removeIf(pos -> outOfRange(pos, centerX, centerZ, r));
        pending.entrySet().removeIf(entry -> {
            if (outOfRange(entry.getKey(), centerX, centerZ, r)) {
                entry.getValue().cancel(true);
                return true;
            }
            return false;
        });

        Map<Block, Integer> query = wanted;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                ChunkPos pos = new ChunkPos(centerX + dx, centerZ + dz);
                LevelChunk chunk = mc.level.getChunkSource().getChunk(pos.x(), pos.z(), ChunkStatus.FULL, false);
                if (chunk == null) {
                    results.remove(pos);
                    continue;
                }
                if (dirty.contains(pos)) {
                    results.remove(pos);
                    Future<List<Target>> old = pending.remove(pos);
                    if (old != null) {
                        old.cancel(true);
                    }
                }
                if (!results.containsKey(pos) && !pending.containsKey(pos)) {
                    pending.put(pos, SCANNER.submit(() -> scan(chunk, query)));
                }
            }
        }

        rebuildDrawList();
    }

    private static boolean outOfRange(ChunkPos pos, int centerX, int centerZ, int r) {
        return Math.abs(pos.x() - centerX) > r || Math.abs(pos.z() - centerZ) > r;
    }

    private void collectFinished() {
        for (Iterator<Map.Entry<ChunkPos, Future<List<Target>>>> it = pending.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<ChunkPos, Future<List<Target>>> entry = it.next();
            Future<List<Target>> future = entry.getValue();
            if (!future.isDone()) {
                continue;
            }
            it.remove();
            if (future.isCancelled()) {
                continue;
            }
            try {
                results.put(entry.getKey(), future.get());
            } catch (InterruptedException | ExecutionException ignored) {
                // A chunk that unloaded mid scan is simply scanned again later.
            }
        }
    }

    private void rebuildDrawList() {
        List<Target> all = new ArrayList<>();
        for (List<Target> list : results.values()) {
            all.addAll(list);
        }
        int max = limit.getInt();
        if (all.size() > max) {
            Vec3 eye = mc.player.getEyePosition();
            all.sort((a, b) -> Double.compare(
                eye.distanceToSqr(Vec3.atCenterOf(a.pos())), eye.distanceToSqr(Vec3.atCenterOf(b.pos()))));
            all = new ArrayList<>(all.subList(0, max));
        }
        drawn = all;
    }

    /** Runs on the scanner thread. Reads the chunk without touching the game. */
    private static List<Target> scan(LevelChunk chunk, Map<Block, Integer> wanted) {
        List<Target> found = new ArrayList<>();
        if (wanted.isEmpty()) {
            return found;
        }
        LevelChunkSection[] sections = chunk.getSections();
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (section == null || section.hasOnlyAir()
                || !section.maybeHas(state -> wanted.containsKey(state.getBlock()))) {
                continue;
            }
            int baseY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(i));
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        Integer color = wanted.get(state.getBlock());
                        if (color != null) {
                            found.add(new Target(new BlockPos(baseX + x, baseY + y, baseZ + z), color));
                        }
                    }
                }
            }
            if (Thread.currentThread().isInterrupted()) {
                return found;
            }
        }
        return found;
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        DrawBatch batch = event.getBatch();
        boolean lines = tracers.isOn();
        for (Target target : drawn) {
            AABB box = new AABB(target.pos()).deflate(0.02);
            batch.outlineBox(box, target.color(), true);
            if (lines) {
                batch.tracer(box.getCenter(), target.color(), true);
            }
        }
    }
}
