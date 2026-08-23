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
import com.jellypudding.offlineclient.util.ChunkScanner;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Nearby chunks are scanned through the section palette on a background thread
 * and touching portal blocks are merged into one box per portal.
 */
public final class Portals extends Module {

    private record Spot(BlockPos pos, Block block) {
    }

    private record Group(AABB box, int color) {
    }

    private static final int NETHER_COLOR = 0xFFB050FF;
    private static final int END_COLOR = 0xFF50FFB0;
    private static final int GATEWAY_COLOR = 0xFF40C8FF;

    // Ticks between merges. The boxes only move when a portal is built or broken.
    private static final int MERGE_INTERVAL = 20;

    private final NumberSetting range = new NumberSetting("Range",
        "Chunk radius to scan around you.", 6, 1, 8, 1, " chunks").max(16);
    private final BoolSetting nether = new BoolSetting("Nether portals",
        "Show nether portals.", true);
    private final BoolSetting end = new BoolSetting("End portals",
        "Show end portals and gateways.", true);
    private final BoolSetting tracers = new BoolSetting("Tracers",
        "Draw a line to every portal.", false);

    private final ChunkScanner<Spot> scanner = new ChunkScanner<>();
    private List<Group> groups = List.of();
    private int timer;

    public Portals() {
        super("Portals", "Highlights portals through walls.", Category.RENDER);
        addSettings(range, nether, end, tracers);
        searchTags("portal esp", "nether portal", "end portal");
    }

    @Override
    public String getSuffix() {
        return groups.isEmpty() ? null : String.valueOf(groups.size());
    }

    @Override
    protected void onEnable() {
        timer = 0;
        scanner.reset();
    }

    @Override
    protected void onDisable() {
        groups = List.of();
        scanner.reset();
    }

    private static boolean isPortal(Block block) {
        return block == Blocks.NETHER_PORTAL || block == Blocks.END_PORTAL
            || block == Blocks.END_GATEWAY;
    }

    // Zero for a portal the settings hide.
    private int colorOf(Block block) {
        if (block == Blocks.NETHER_PORTAL) {
            return nether.isOn() ? NETHER_COLOR : 0;
        }
        if (block == Blocks.END_PORTAL) {
            return end.isOn() ? END_COLOR : 0;
        }
        if (block == Blocks.END_GATEWAY) {
            return end.isOn() ? GATEWAY_COLOR : 0;
        }
        return 0;
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
        scanner.update(range.getInt(), (view, out) -> view.forEachMatching(
            state -> isPortal(state.getBlock()),
            (x, y, z, state) -> out.add(new Spot(new BlockPos(x, y, z), state.getBlock()))));

        if (--timer > 0) {
            return;
        }
        timer = MERGE_INTERVAL;
        groups = group(scanner.results());
    }

    private List<Group> group(List<Spot> spots) {
        Map<Block, Set<BlockPos>> found = new HashMap<>();
        for (Spot spot : spots) {
            if (colorOf(spot.block()) != 0) {
                found.computeIfAbsent(spot.block(), key -> new HashSet<>()).add(spot.pos());
            }
        }

        List<Group> result = new ArrayList<>();
        for (Map.Entry<Block, Set<BlockPos>> entry : found.entrySet()) {
            int color = colorOf(entry.getKey());
            for (AABB box : merge(entry.getValue())) {
                result.add(new Group(box, color));
            }
        }
        return result;
    }

    // One box per patch of touching blocks.
    private static List<AABB> merge(Set<BlockPos> positions) {
        List<AABB> boxes = new ArrayList<>();
        Set<BlockPos> remaining = new HashSet<>(positions);
        while (!remaining.isEmpty()) {
            BlockPos start = remaining.iterator().next();
            remaining.remove(start);
            AABB box = new AABB(start);
            Deque<BlockPos> queue = new ArrayDeque<>();
            queue.add(start);
            while (!queue.isEmpty()) {
                BlockPos pos = queue.poll();
                for (Direction direction : Direction.values()) {
                    BlockPos next = pos.relative(direction);
                    if (remaining.remove(next)) {
                        box = box.minmax(new AABB(next));
                        queue.add(next);
                    }
                }
            }
            boxes.add(box);
        }
        return boxes;
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        DrawBatch batch = event.getBatch();
        for (Group group : groups) {
            batch.outlineBox(group.box(), group.color(), true);
            if (tracers.isOn()) {
                batch.tracer(group.box().getCenter(), group.color(), true);
            }
        }
    }
}
