package com.jellypudding.offlineclient.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.MapColor.Brightness;

// Reads the land of a chunk the way a held map does. A column takes the colour of the first block
// from the top that has one. Water darkens with its depth and land brightens where it rises from
// the column north of it and darkens where it falls. Game thread only.
final class TerrainColumns {

    // A column whose height is not known.
    static final int UNKNOWN = Integer.MIN_VALUE;

    // Water deeper than this shades the same as this.
    private static final int DEEPEST = 10;
    // The shading a held map uses. Water goes by depth and land by its rise from the north with a
    // fine checker that softens each step.
    private static final double DEPTH_STEP = 0.1;
    private static final double WATER_CHECKER = 0.2;
    private static final double SHALLOW = 0.5;
    private static final double DEEP = 0.9;
    private static final double RISE_STEP = 0.8;
    private static final double LAND_CHECKER = 0.4;
    private static final double CHECKER_MIDDLE = 0.5;
    private static final double STEEP = 0.6;
    // The layer you stand on looks this far down from your head for a floor.
    private static final int CAVE_DEPTH = 32;

    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    // What the last column read found.
    private MapColor colour = MapColor.NONE;
    private int height;
    private int depth;
    private boolean wall;

    // The tile of a chunk seen from the top of the world. Under a roof such as the Nether's the
    // land starts at the first open space below it. The chunk to the north shades the first row
    // and that row stays flat whilst it has not arrived.
    byte[] surface(LevelChunk chunk, LevelChunk north, boolean roofed) {
        return tile(chunk, north, UNKNOWN, roofed);
    }

    // The tile of a chunk seen from your head down. A solid block at your head is a wall and is
    // drawn dark.
    byte[] layer(LevelChunk chunk, LevelChunk north, int headY) {
        return tile(chunk, north, headY, false);
    }

    // The height to stand at on the first floor under the roof of a column. Unknown for a column
    // with nothing to stand on.
    int floorY(LevelChunk chunk, int x, int z) {
        read(chunk, x, z, UNKNOWN, true);
        return colour == MapColor.NONE ? UNKNOWN : height + 1;
    }

    private byte[] tile(LevelChunk chunk, LevelChunk north, int headY, boolean roofed) {
        byte[] tile = new byte[TerrainLayer.TILE_SIZE];
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        for (int dx = 0; dx < SectionPos.SECTION_SIZE; dx++) {
            int x = baseX + dx;
            int above = UNKNOWN;
            if (north != null) {
                read(north, x, baseZ - 1, headY, roofed);
                above = height;
            }
            for (int dz = 0; dz < SectionPos.SECTION_SIZE; dz++) {
                int z = baseZ + dz;
                read(chunk, x, z, headY, roofed);
                tile[TerrainLayer.cell(x, z)] = packed(x, z, above);
                above = height;
            }
        }
        return tile;
    }

    // Leaves the colour and height and water depth of one column behind.
    private void read(LevelChunk chunk, int x, int z, int headY, boolean roofed) {
        depth = 0;
        wall = false;
        int bottom = chunk.getMinY();
        int top;
        if (headY == UNKNOWN) {
            top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            if (roofed) {
                top = belowRoof(chunk, x, z, top, bottom);
            }
        } else {
            BlockState head = chunk.getBlockState(pos.set(x, headY, z));
            MapColor headColour = head.getMapColor(chunk, pos);
            if (BlockUtil.blocksMotion(head) && headColour != MapColor.NONE) {
                colour = headColour;
                height = headY;
                wall = true;
                return;
            }
            top = headY;
            bottom = Math.max(bottom, headY - CAVE_DEPTH);
        }
        down(chunk, x, z, top, bottom);
    }

    // Walks down from the top to the first block with a colour. Water counts its depth and a
    // block standing in water that is open on top shows as the water.
    private void down(LevelChunk chunk, int x, int z, int top, int bottom) {
        int y = top + 1;
        BlockState state = null;
        colour = MapColor.NONE;
        while (y > bottom) {
            y--;
            state = chunk.getBlockState(pos.set(x, y, z));
            colour = state.getMapColor(chunk, pos);
            if (colour != MapColor.NONE) {
                break;
            }
        }
        height = y;
        if (colour == MapColor.NONE || y <= bottom) {
            return;
        }
        FluidState fluid = state.getFluidState();
        if (fluid.isEmpty()) {
            return;
        }
        int below = y - 1;
        BlockState under;
        do {
            under = chunk.getBlockState(pos.set(x, below--, z));
            depth++;
        } while (below > bottom && depth < DEEPEST && !under.getFluidState().isEmpty());
        if (!state.isFaceSturdy(chunk, pos.set(x, y, z), Direction.UP)) {
            colour = fluid.createLegacyBlock().getMapColor(chunk, pos);
        }
    }

    // The first open space under the roof of a column. The top itself when there is none.
    private int belowRoof(LevelChunk chunk, int x, int z, int top, int bottom) {
        for (int y = top; y > bottom; y--) {
            if (chunk.getBlockState(pos.set(x, y, z)).isAir()) {
                return y;
            }
        }
        return top;
    }

    // The colour of the last column read shaded against the height of the column north of it.
    private byte packed(int x, int z, int above) {
        if (colour == MapColor.NONE) {
            return TerrainLayer.VOID;
        }
        if (wall) {
            return colour.getPackedId(Brightness.LOWEST);
        }
        int checker = (x + z) & 1;
        Brightness brightness;
        if (colour == MapColor.WATER) {
            double shade = depth * DEPTH_STEP + checker * WATER_CHECKER;
            brightness = shade < SHALLOW ? Brightness.HIGH : shade > DEEP ? Brightness.LOW : Brightness.NORMAL;
        } else if (above == UNKNOWN) {
            brightness = Brightness.NORMAL;
        } else {
            double shade = (height - above) * RISE_STEP + (checker - CHECKER_MIDDLE) * LAND_CHECKER;
            brightness = shade > STEEP ? Brightness.HIGH : shade < -STEEP ? Brightness.LOW : Brightness.NORMAL;
        }
        return colour.getPackedId(brightness);
    }
}
