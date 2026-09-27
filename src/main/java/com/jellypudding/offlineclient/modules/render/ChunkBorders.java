package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.render.FarShapes;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

// Every line shows through walls and far ends are pulled in along the view.
public final class ChunkBorders extends Module {

    private static final int REGION_BLOCKS = ChunkPos.REGION_SIZE * SectionPos.SECTION_SIZE;

    private final ColorSetting chunkColor = new ColorSetting("Chunk colour",
        "Colour of the chunk you stand in.", 60, false);
    private final BoolSetting grid = new BoolSetting("Grid",
        "Also draws the chunks around the one you stand in.", true);
    private final NumberSetting gridRadius = new NumberSetting("Grid radius",
        "How many chunks out from yours the grid reaches.", 4, 1, 16, 1, " chunks").min(1)
        .under(grid);
    private final ColorSetting gridColor = new ColorSetting("Grid colour",
        "Colour of the chunks around it.", 200, 0.4f, 0.8f, false).under(grid);
    private final BoolSetting regions = new BoolSetting("Regions",
        "Draws the edges of the region file you stand in. Each region holds 32 by 32 chunks.", false);
    private final ColorSetting regionColor = new ColorSetting("Region colour",
        "Colour of the region edges.", 0, false).under(regions);
    private final BoolSetting followHeight = new BoolSetting("Follow height",
        "Draws the squares at your own height instead of a fixed one.", true);
    private final NumberSetting drawHeight = new NumberSetting("Height",
        "The height the squares sit at.", 64, -64, 320, 1)
        .min(-2048).max(2048).unless(followHeight);

    public ChunkBorders() {
        super("ChunkBorders", "Outlines the chunk you stand in and the chunks and region around it.",
            Category.RENDER);
        addSettings(chunkColor, grid, gridRadius, gridColor, regions, regionColor, followHeight, drawHeight);
        searchTags("chunk grid", "debug borders", "region file", "slime chunk");
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!inGame()) {
            return;
        }
        DrawBatch batch = event.getBatch();
        Vec3 feet = mc.player.getPosition(event.getPartialTicks());
        double y = followHeight.isOn() ? feet.y : drawHeight.getValue();
        // Corner posts run the whole height of the world and past it to wherever you are.
        double bottom = Math.min(mc.level.getMinY(), Math.min(y, feet.y));
        double top = Math.max(mc.level.getMaxY() + 1, Math.max(y, feet.y));
        ChunkPos here = mc.player.chunkPosition();

        if (grid.isOn()) {
            drawGrid(batch, here, y);
        }
        int minX = here.getMinBlockX();
        int minZ = here.getMinBlockZ();
        drawBox(batch, minX, minZ, SectionPos.SECTION_SIZE, y, bottom, top, chunkColor.getColor());
        if (regions.isOn()) {
            ChunkPos corner = ChunkPos.minFromRegion(here.getRegionX(), here.getRegionZ());
            drawBox(batch, corner.getMinBlockX(), corner.getMinBlockZ(), REGION_BLOCKS, y, bottom, top,
                regionColor.getColor());
        }
    }

    // One long line for each chunk edge that crosses the grid.
    private void drawGrid(DrawBatch batch, ChunkPos here, double y) {
        int radius = gridRadius.getInt();
        int color = gridColor.getColor();
        double west = SectionPos.sectionToBlockCoord(here.x() - radius);
        double east = SectionPos.sectionToBlockCoord(here.x() + radius + 1);
        double north = SectionPos.sectionToBlockCoord(here.z() - radius);
        double south = SectionPos.sectionToBlockCoord(here.z() + radius + 1);
        for (int i = -radius; i <= radius + 1; i++) {
            double x = SectionPos.sectionToBlockCoord(here.x() + i);
            double z = SectionPos.sectionToBlockCoord(here.z() + i);
            FarShapes.line(batch, new Vec3(x, y, north), new Vec3(x, y, south), color);
            FarShapes.line(batch, new Vec3(west, y, z), new Vec3(east, y, z), color);
        }
    }

    // A square at the given height with a post up each corner.
    private static void drawBox(DrawBatch batch, int x, int z, int size, double y, double bottom, double top,
                                int color) {
        double[] xs = {x, x + size, x + size, x};
        double[] zs = {z, z, z + size, z + size};
        for (int i = 0; i < xs.length; i++) {
            int next = (i + 1) % xs.length;
            FarShapes.line(batch, new Vec3(xs[i], y, zs[i]), new Vec3(xs[next], y, zs[next]), color);
            FarShapes.line(batch, new Vec3(xs[i], bottom, zs[i]), new Vec3(xs[i], top, zs[i]), color);
        }
    }
}
