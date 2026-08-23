package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
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
 * Scans nearby chunks once a second through the section palette and merges
 * touching portal blocks into one box per portal.
 */
public final class Portals extends Module {

    private record Group(AABB box, int color) {
    }

    private static final int NETHER_COLOR = 0xFFB050FF;
    private static final int END_COLOR = 0xFF50FFB0;
    private static final int GATEWAY_COLOR = 0xFF40C8FF;

    private final NumberSetting range = new NumberSetting("Range",
        "Chunk radius to scan around you.", 6, 1, 8, 1, " chunks").max(16);
    private final BoolSetting nether = new BoolSetting("Nether portals",
        "Show nether portals.", true);
    private final BoolSetting end = new BoolSetting("End portals",
        "Show end portals and gateways.", true);
    private final BoolSetting tracers = new BoolSetting("Tracers",
        "Draw a line to every portal.", false);

    private List<Group> groups = List.of();
    private int timer;
    private ResourceKey<Level> dimension;

    public Portals() {
        super("Portals", "Highlights portals through walls.", Category.RENDER);
        addSettings(range, nether, end, tracers);
        searchTags("portal esp", "nether portal", "end portal");
    }

    @Override
    public String getSuffix() {
        return String.valueOf(groups.size());
    }

    @Override
    protected void onEnable() {
        timer = 0;
    }

    @Override
    protected void onDisable() {
        groups = List.of();
    }

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
    private void onTick(TickEvent event) {
        if (!inGame()) {
            return;
        }
        if (mc.level.dimension() != dimension) {
            dimension = mc.level.dimension();
            groups = List.of();
            timer = 0;
        }
        if (--timer > 0) {
            return;
        }
        timer = 20;
        groups = scan();
    }

    private List<Group> scan() {
        Map<Block, Set<BlockPos>> found = new HashMap<>();
        int r = range.getInt();
        int centerX = mc.player.chunkPosition().x();
        int centerZ = mc.player.chunkPosition().z();

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                LevelChunk chunk = mc.level.getChunkSource()
                    .getChunk(centerX + dx, centerZ + dz, ChunkStatus.FULL, false);
                if (chunk != null) {
                    scanChunk(chunk, found);
                }
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

    private void scanChunk(LevelChunk chunk, Map<Block, Set<BlockPos>> found) {
        LevelChunkSection[] sections = chunk.getSections();
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (section == null || section.hasOnlyAir()
                || !section.maybeHas(state -> colorOf(state.getBlock()) != 0)) {
                continue;
            }
            int baseY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(i));
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        Block block = section.getBlockState(x, y, z).getBlock();
                        if (colorOf(block) != 0) {
                            found.computeIfAbsent(block, key -> new HashSet<>())
                                .add(new BlockPos(baseX + x, baseY + y, baseZ + z));
                        }
                    }
                }
            }
        }
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
