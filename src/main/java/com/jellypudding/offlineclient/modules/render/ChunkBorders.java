package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.render.FarShapes;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.MapGrid;
import com.jellypudding.offlineclient.util.ServerWatch;
import com.jellypudding.offlineclient.worldgen.SeedWorlds;
import com.jellypudding.offlineclient.worldgen.SlimeChunks;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.Vec3;

import java.util.OptionalLong;

// Every line shows through walls and far ends are pulled in along the view.
public final class ChunkBorders extends Module {

    private static final int REGION_BLOCKS = ChunkPos.REGION_SIZE * SectionPos.SECTION_SIZE;

    // A slime chunk mark sits this far inside the chunk and stays clear of the grid lines.
    private static final int SLIME_INSET = 2;

    // The slime chunks around one chunk for one seed.
    private record SlimeMarks(long seed, long centre, int radius, long[] chunks) {

        private boolean fits(long seed, long centre, int radius) {
            return this.seed == seed && this.centre == centre && this.radius == radius;
        }
    }

    private final ColorSetting chunkColor = new ColorSetting("Chunk colour",
        "Colour of the chunk you stand in.", 60, false);
    private final BoolSetting grid = new BoolSetting("Grid",
        "Also draws the chunks around the one you stand in.", true);
    private final NumberSetting gridRadius = new NumberSetting("Grid radius",
        "How many chunks out from yours the grid reaches.", 4, 1, 16, 1, " chunks")
        .under(grid);
    private final ColorSetting gridColor = new ColorSetting("Grid colour",
        "Colour of the chunks around it.", 200, 0.4f, 0.8f, false).under(grid);
    private final BoolSetting regions = new BoolSetting("Regions",
        "Draws the edges of the region file you stand in. Each region holds 32 by 32 chunks.", false);
    private final ColorSetting regionColor = new ColorSetting("Region colour",
        "Colour of the region edges.", 0, false).under(regions);
    private final BoolSetting mapSquare = new BoolSetting("Map square",
        "Draws the edges of the square a map made where you stand would show.", false);
    private final NumberSetting mapZoom = new NumberSetting("Map zoom",
        "How often the map has been zoomed out. Nought is a fresh map of 128 blocks a side and each step"
            + " doubles that.", 0, 0, MapItemSavedData.MAX_SCALE, 1).max(MapItemSavedData.MAX_SCALE)
        .under(mapSquare);
    private final ColorSetting mapColor = new ColorSetting("Map colour",
        "Colour of the map square.", 30, false).under(mapSquare);
    private final BoolSetting slime = new BoolSetting("Slime chunks",
        "Marks the Overworld chunks slimes spawn in below height 40 as the saved seed places them.", false);
    private final NumberSetting slimeRadius = new NumberSetting("Slime radius",
        "How many chunks out from yours slime chunks are marked.", 8, 1, 16, 1, " chunks")
        .under(slime);
    private final ColorSetting slimeColor = new ColorSetting("Slime colour",
        "Colour of the slime chunk marks.", 120, false).under(slime);
    private final BoolSetting followHeight = new BoolSetting("Follow height",
        "Draws the squares at your own height instead of a fixed one.", true);
    private final NumberSetting drawHeight = new NumberSetting("Height",
        "The height the squares sit at.", 64, -64, 320, 1)
        .min(DimensionType.MIN_Y).unless(followHeight);

    // Null whilst no slime chunk is marked.
    private SlimeMarks slimeMarks;
    // The missing seed is pointed out once on each server.
    private final ServerWatch told = new ServerWatch();

    public ChunkBorders() {
        super("ChunkBorders",
            "Outlines the chunk you stand in and the chunks and region and map around it and marks slime chunks.",
            Category.RENDER);
        addSettings(chunkColor, grid, gridRadius, gridColor, regions, regionColor, mapSquare, mapZoom, mapColor,
            slime, slimeRadius, slimeColor, followHeight, drawHeight);
        searchTags("chunk grid", "debug borders", "region file", "slime chunk", "map square", "map art");
    }

    @Override
    protected void onEnable() {
        told.forget();
    }

    // The slime rule seeds a random source for every chunk it is asked about. The marks are
    // kept until you cross into another chunk.
    @Subscribe
    private void onTick(TickEvent event) {
        if (!slime.isOn() || mc.level.dimension() != Level.OVERWORLD) {
            slimeMarks = null;
            return;
        }
        OptionalLong seed = SeedWorlds.seed();
        if (seed.isEmpty()) {
            slimeMarks = null;
            if (told.changed()) {
                ChatUtil.error("ChunkBorders needs the seed of this server to mark slime chunks. Type "
                    + OfflineClient.INSTANCE.getCommandManager().getPrefix() + "seed set <seed>");
            }
            return;
        }
        ChunkPos here = mc.player.chunkPosition();
        int radius = slimeRadius.getInt();
        if (slimeMarks != null && slimeMarks.fits(seed.getAsLong(), here.pack(), radius)) {
            return;
        }
        LongArrayList chunks = new LongArrayList();
        for (int x = here.x() - radius; x <= here.x() + radius; x++) {
            for (int z = here.z() - radius; z <= here.z() + radius; z++) {
                if (SlimeChunks.isSlimeChunk(seed.getAsLong(), x, z)) {
                    chunks.add(ChunkPos.pack(x, z));
                }
            }
        }
        slimeMarks = new SlimeMarks(seed.getAsLong(), here.pack(), radius, chunks.toLongArray());
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
        if (slimeMarks != null) {
            drawSlimeMarks(batch, y);
        }
        int minX = here.getMinBlockX();
        int minZ = here.getMinBlockZ();
        drawBox(batch, minX, minZ, SectionPos.SECTION_SIZE, y, bottom, top, chunkColor.getColor());
        if (regions.isOn()) {
            ChunkPos corner = ChunkPos.minFromRegion(here.getRegionX(), here.getRegionZ());
            drawBox(batch, corner.getMinBlockX(), corner.getMinBlockZ(), REGION_BLOCKS, y, bottom, top,
                regionColor.getColor());
        }
        if (mapSquare.isOn()) {
            int zoom = mapZoom.getInt();
            drawBox(batch, MapGrid.edge(feet.x, zoom), MapGrid.edge(feet.z, zoom), MapGrid.side(zoom), y, bottom,
                top, mapColor.getColor());
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

    // A smaller square inside each slime chunk.
    private void drawSlimeMarks(DrawBatch batch, double y) {
        int color = slimeColor.getColor();
        for (long chunk : slimeMarks.chunks()) {
            drawSquare(batch, SectionPos.sectionToBlockCoord(ChunkPos.getX(chunk)) + SLIME_INSET,
                SectionPos.sectionToBlockCoord(ChunkPos.getZ(chunk)) + SLIME_INSET,
                SectionPos.SECTION_SIZE - 2 * SLIME_INSET, y, color);
        }
    }

    // A square at the given height with a post up each corner.
    private static void drawBox(DrawBatch batch, int x, int z, int size, double y, double bottom, double top,
                                int color) {
        drawSquare(batch, x, z, size, y, color);
        for (int east = x; east <= x + size; east += size) {
            for (int south = z; south <= z + size; south += size) {
                FarShapes.line(batch, new Vec3(east, bottom, south), new Vec3(east, top, south), color);
            }
        }
    }

    private static void drawSquare(DrawBatch batch, int x, int z, int size, double y, int color) {
        double[] xs = {x, x + size, x + size, x};
        double[] zs = {z, z, z + size, z + size};
        for (int i = 0; i < xs.length; i++) {
            int next = (i + 1) % xs.length;
            FarShapes.line(batch, new Vec3(xs[i], y, zs[i]), new Vec3(xs[next], y, zs[next]), color);
        }
    }
}
