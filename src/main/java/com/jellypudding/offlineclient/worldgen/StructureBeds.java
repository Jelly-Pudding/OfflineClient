package com.jellypudding.offlineclient.worldgen;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;

// The beds the world puts down by itself and what always stands around them. Each was checked
// against every structure template of 26.3. Villages use nine colours and the igloo a red bed.
// Abandoned camps use straw beds and trial chambers every colour. Woodland mansions hold none.
public final class StructureBeds {

    // Every village piece lies within eighty blocks of the bell at the village centre.
    public static final int VILLAGE_REACH_CHUNKS = 5;
    // Tuff bricks or waxed copper stand within four blocks of every trial chamber bed.
    public static final int CHAMBER_REACH = 4;
    // White wool stairs hang within four blocks of every camp bed.
    public static final int CAMP_REACH = 4;

    private static final Set<Block> VILLAGE_COLOURS = Set.of(Blocks.BED.white(), Blocks.BED.orange(),
        Blocks.BED.blue(), Blocks.BED.purple(), Blocks.BED.yellow(), Blocks.BED.lime(), Blocks.BED.green(),
        Blocks.BED.red(), Blocks.BED.cyan());

    private static final Set<Block> CHAMBER_BLOCKS = Set.of(Blocks.TUFF_BRICKS, Blocks.CHISELED_TUFF_BRICKS,
        Blocks.POLISHED_TUFF, Blocks.CHISELED_TUFF, Blocks.COPPER_BLOCK.waxed().unaffected(),
        Blocks.COPPER_BLOCK.waxed().oxidized(), Blocks.CUT_COPPER.waxed().oxidized(),
        Blocks.COPPER_BULB.waxed().unaffected());

    // The bell and the job sites. Every village centre has a bell but one zombie taiga square.
    private static final Set<ResourceKey<PoiType>> VILLAGE_POINTS = Set.of(PoiTypes.MEETING, PoiTypes.ARMORER,
        PoiTypes.BUTCHER, PoiTypes.CARTOGRAPHER, PoiTypes.CLERIC, PoiTypes.FARMER, PoiTypes.FISHERMAN,
        PoiTypes.FLETCHER, PoiTypes.LEATHERWORKER, PoiTypes.LIBRARIAN, PoiTypes.MASON, PoiTypes.SHEPHERD,
        PoiTypes.TOOLSMITH, PoiTypes.WEAPONSMITH);

    private StructureBeds() {
    }

    public static boolean villageColour(Block bed) {
        return VILLAGE_COLOURS.contains(bed);
    }

    public static boolean chamberBlock(BlockState state) {
        return CHAMBER_BLOCKS.contains(state.getBlock());
    }

    public static boolean campRoof(BlockState state) {
        return state.is(Blocks.WOOL_STAIRS.white());
    }

    // The igloo's red bed has snow blocks against three of its sides.
    public static boolean iglooWall(BlockState state) {
        return state.is(Blocks.SNOW_BLOCK);
    }

    public static boolean villageBlock(BlockState state) {
        return PoiTypes.forState(state).filter(type -> type.is(VILLAGE_POINTS::contains)).isPresent();
    }

    // Abandoned villages keep zombie villagers in place of villagers.
    public static boolean villager(EntityType<?> type) {
        return type == EntityTypes.VILLAGER || type == EntityTypes.ZOMBIE_VILLAGER;
    }
}
