package com.jellypudding.offlineclient.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

// The single chests the world puts down by itself and what always stands round them. The dungeon
// feature sets its chests inside the room at the height of the spawner. Every trial chamber chest
// stands a block from tuff or waxed copper. Most camp chests have white wool stairs within two
// blocks and the rest a campfire and a dirt path within four. The chamber and camp rules were
// checked against every structure template of 26.3.
public final class StructureChests {

    // Every test here reads blocks within this many chunks of the chest.
    public static final int REACH_CHUNKS = 1;

    // A dungeon room reaches at most this far either way from its spawner.
    private static final int DUNGEON_HALF = 3;
    private static final int CHAMBER_REACH = 1;
    private static final int CAMP_ROOF_REACH = 2;
    private static final int CAMP_REACH = 4;

    private StructureChests() {
    }

    public static boolean inDungeon(SeenBlocks world, BlockPos chest) {
        Vec3 middle = Vec3.atCenterOf(chest);
        for (int dx = -DUNGEON_HALF; dx <= DUNGEON_HALF; dx++) {
            for (int dz = -DUNGEON_HALF; dz <= DUNGEON_HALF; dz++) {
                SpawnerRooms.Room room = SpawnerRooms.dungeon(world, chest.offset(dx, 0, dz));
                if (room != null && room.box().contains(middle)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean inTrialChamber(SeenBlocks world, BlockPos chest) {
        return SeenBlocks.near(world, chest.getX(), chest.getY(), chest.getZ(), CHAMBER_REACH,
            (x, y, z, state) -> StructureBeds.chamberBlock(state));
    }

    public static boolean atCamp(SeenBlocks world, BlockPos chest) {
        int x = chest.getX();
        int y = chest.getY();
        int z = chest.getZ();
        if (SeenBlocks.near(world, x, y, z, CAMP_ROOF_REACH, (bx, by, bz, state) -> StructureBeds.campRoof(state))) {
            return true;
        }
        // A campfire alone is common in a player's kitchen. The camp's dirt path has to lie by it too.
        return SeenBlocks.near(world, x, y, z, CAMP_REACH, (bx, by, bz, state) -> state.is(Blocks.CAMPFIRE))
            && SeenBlocks.near(world, x, y, z, CAMP_REACH, (bx, by, bz, state) -> state.is(Blocks.DIRT_PATH));
    }
}
