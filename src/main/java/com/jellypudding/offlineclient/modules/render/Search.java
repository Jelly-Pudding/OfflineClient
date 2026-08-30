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
import com.jellypudding.offlineclient.util.ChunkScanner;
import com.jellypudding.offlineclient.util.ColorUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Every loaded chunk in range is scanned once on a background thread and only
 * scanned again when the server sends a change for it.
 */
public final class Search extends Module {

    // The box is built once on the scanner thread.
    private record Target(AABB box, int color) {
    }

    // Hand tuned colours for the usual targets.
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

    private final NumberSetting range = new NumberSetting("Range",
        "Chunk radius to scan around you.", 4, 1, 8, 1, " chunks").max(16);
    private final NumberSetting limit = new NumberSetting("Limit",
        "Most blocks drawn at once with the nearest first.", 2000, 100, 5000, 100).min(1);
    private final BoolSetting tracers = new BoolSetting("Tracers",
        "Draw a line to every found block.", false);
    private final RegistryListSetting<Block> blocks = new RegistryListSetting<>("Blocks",
        "The blocks to find.", BuiltInRegistries.BLOCK,
        List.of(Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE, Blocks.ANCIENT_DEBRIS,
            Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE,
            Blocks.SPAWNER, Blocks.TRIAL_SPAWNER, Blocks.END_PORTAL_FRAME,
            Blocks.BEACON, Blocks.ENCHANTING_TABLE));

    // Read from the scanner thread and replaced whole.
    private volatile Map<Block, Integer> wanted = Map.of();

    private final ChunkScanner<Target> scanner = new ChunkScanner<>();
    // Set from the settings screen and read on the next tick.
    private volatile boolean listChanged = true;
    private List<Target> drawn = List.of();

    // The scan list and the chunk the last draw list was cut for.
    private List<Target> cutFrom;
    private long cutChunk;

    public Search() {
        super("Search", "Highlights chosen blocks through walls.", Category.RENDER);
        addSettings(range, limit, tracers, blocks);
        searchTags("block esp", "ore esp");
        blocks.onChange(() -> listChanged = true);
    }

    @Override
    public String getSuffix() {
        return count(drawn.size());
    }

    @Override
    protected void onEnable() {
        listChanged = true;
        reset();
    }

    @Override
    protected void onDisable() {
        reset();
    }

    private void reset() {
        scanner.reset();
        drawn = List.of();
    }

    // True if the wanted blocks changed.
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
        scanner.markChanged(event.getPacket());
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            return;
        }
        if (listChanged) {
            listChanged = false;
            if (snapshot()) {
                reset();
            }
        }

        Map<Block, Integer> query = wanted;
        scanner.update(range.getInt(), (view, out) -> view.forEachMatching(
            state -> query.containsKey(state.getBlock()),
            (x, y, z, state) -> out.add(new Target(
                DrawBatch.blockBox(new BlockPos(x, y, z)), query.get(state.getBlock())))));

        rebuildDrawList();
    }

    // The nearest blocks win when there are more than the limit allows.
    private void rebuildDrawList() {
        List<Target> all = scanner.results();
        int max = limit.getInt();
        long chunk = ChunkPos.pack(mc.player.blockPosition());
        if (all == cutFrom && chunk == cutChunk && drawn.size() == Math.min(max, all.size())) {
            return;
        }
        cutFrom = all;
        cutChunk = chunk;
        if (all.size() > max) {
            Vec3 eye = mc.player.getEyePosition();
            all = new ArrayList<>(all);
            all.sort((a, b) -> Double.compare(distanceSqr(eye, a.box()), distanceSqr(eye, b.box())));
            all = all.subList(0, max);
        }
        drawn = all;
    }

    private static double distanceSqr(Vec3 eye, AABB box) {
        double dx = (box.minX + box.maxX) / 2 - eye.x;
        double dy = (box.minY + box.maxY) / 2 - eye.y;
        double dz = (box.minZ + box.maxZ) / 2 - eye.z;
        return dx * dx + dy * dy + dz * dz;
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        DrawBatch batch = event.getBatch();
        boolean lines = tracers.isOn();
        for (Target target : drawn) {
            batch.outlineBox(target.box(), target.color(), true);
            if (lines) {
                batch.tracer(target.box().getCenter(), target.color(), true);
            }
        }
    }
}
