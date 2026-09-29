package com.jellypudding.offlineclient.worldgen;

import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

// Blocks the world puts down on its own. Each one comes from the 26.3 terrain or its surface
// rules or ore veins or a feature some biome places or from what those grow and form into. Anything
// else found near you was most likely put there by a player. Every structure counts as placed and
// dungeons and desert wells and fossils do as well.
public final class NaturalBlocks {

    public static final List<Block> BLOCKS = build();

    private static final Set<Block> SET = Set.copyOf(BLOCKS);

    // The ground of a normal world never rises past this. Its density turns to air from 240 to 256.
    public static final int GROUND_TOP = 255;

    // What tree tops and weather leave above the ground. Leaves and logs and fire come from tags.
    private static final Set<Block> PLANT_TOPS = Set.of(Blocks.SNOW, Blocks.VINE, Blocks.BEE_NEST,
        Blocks.COCOA, Blocks.MOSS_CARPET, Blocks.PALE_MOSS_CARPET, Blocks.PALE_HANGING_MOSS,
        Blocks.CREAKING_HEART, Blocks.LEAF_LITTER, Blocks.SHELF_MUSHROOM, Blocks.MANGROVE_ROOTS,
        Blocks.MUDDY_MANGROVE_ROOTS, Blocks.MANGROVE_PROPAGULE, Blocks.MUSHROOM_STEM,
        Blocks.BROWN_MUSHROOM_BLOCK, Blocks.RED_MUSHROOM_BLOCK);

    private NaturalBlocks() {
    }

    public static boolean contains(Block block) {
        return SET.contains(block);
    }

    // True for a block that can sit above the highest ground without a player. Trees and snow
    // and lightning fire leave these and an enderman sets down whatever it carries.
    public static boolean aloft(BlockState state) {
        return state.is(BlockTags.LEAVES) || state.is(BlockTags.OVERWORLD_NATURAL_LOGS)
            || state.is(BlockTags.FIRE) || state.is(BlockTags.ENDERMAN_HOLDABLE)
            || PLANT_TOPS.contains(state.getBlock());
    }

    private static List<Block> build() {
        List<Block> blocks = new ArrayList<>(List.of(
            // Magma under water raises bubble columns.
            Blocks.AIR, Blocks.CAVE_AIR, Blocks.VOID_AIR, Blocks.WATER, Blocks.LAVA, Blocks.BUBBLE_COLUMN,
            // Obsidian forms where lava meets water.
            Blocks.STONE, Blocks.DEEPSLATE, Blocks.TUFF, Blocks.ANDESITE, Blocks.DIORITE, Blocks.GRANITE,
            Blocks.CALCITE, Blocks.SMOOTH_BASALT, Blocks.BEDROCK, Blocks.OBSIDIAN, Blocks.MAGMA_BLOCK,
            Blocks.MOSSY_COBBLESTONE, Blocks.INFESTED_STONE, Blocks.INFESTED_DEEPSLATE, Blocks.DRIPSTONE_BLOCK,
            Blocks.POINTED_DRIPSTONE, Blocks.AMETHYST_BLOCK, Blocks.BUDDING_AMETHYST, Blocks.SMALL_AMETHYST_BUD,
            Blocks.MEDIUM_AMETHYST_BUD, Blocks.LARGE_AMETHYST_BUD, Blocks.AMETHYST_CLUSTER, Blocks.SULFUR,
            Blocks.POTENT_SULFUR, Blocks.SULFUR_SPIKE, Blocks.CINNABAR,
            // Ore veins leave raw iron and raw copper blocks.
            Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE, Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE,
            Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE, Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE,
            Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE, Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE,
            Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE, Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE,
            Blocks.RAW_IRON_BLOCK, Blocks.RAW_COPPER_BLOCK,
            Blocks.DIRT, Blocks.COARSE_DIRT, Blocks.ROOTED_DIRT, Blocks.GRASS_BLOCK, Blocks.PODZOL,
            Blocks.MYCELIUM, Blocks.MUD, Blocks.CLAY, Blocks.GRAVEL, Blocks.SAND, Blocks.RED_SAND,
            Blocks.SANDSTONE, Blocks.RED_SANDSTONE, Blocks.TERRACOTTA, Blocks.SNOW, Blocks.SNOW_BLOCK,
            Blocks.POWDER_SNOW, Blocks.ICE, Blocks.PACKED_ICE, Blocks.BLUE_ICE, Blocks.MOSS_BLOCK,
            Blocks.MOSS_CARPET, Blocks.PALE_MOSS_BLOCK, Blocks.PALE_MOSS_CARPET,
            Blocks.OAK_LOG, Blocks.OAK_LEAVES, Blocks.SPRUCE_LOG, Blocks.SPRUCE_LEAVES, Blocks.BIRCH_LOG,
            Blocks.BIRCH_LEAVES, Blocks.JUNGLE_LOG, Blocks.JUNGLE_LEAVES, Blocks.ACACIA_LOG, Blocks.ACACIA_LEAVES,
            Blocks.DARK_OAK_LOG, Blocks.DARK_OAK_LEAVES, Blocks.MANGROVE_LOG, Blocks.MANGROVE_LEAVES,
            Blocks.MANGROVE_ROOTS, Blocks.MUDDY_MANGROVE_ROOTS, Blocks.MANGROVE_PROPAGULE, Blocks.CHERRY_LOG,
            Blocks.CHERRY_LEAVES, Blocks.PALE_OAK_LOG, Blocks.PALE_OAK_LEAVES, Blocks.PALE_HANGING_MOSS,
            Blocks.CREAKING_HEART, Blocks.POPLAR_LOG, Blocks.RED_POPLAR_LEAVES, Blocks.ORANGE_POPLAR_LEAVES,
            Blocks.YELLOW_POPLAR_LEAVES, Blocks.AZALEA_LEAVES, Blocks.FLOWERING_AZALEA_LEAVES, Blocks.BEE_NEST,
            Blocks.COCOA, Blocks.VINE, Blocks.LEAF_LITTER, Blocks.SHELF_MUSHROOM, Blocks.MUSHROOM_STEM,
            Blocks.BROWN_MUSHROOM_BLOCK, Blocks.RED_MUSHROOM_BLOCK,
            // Eyeblossoms open and close by themselves.
            Blocks.SHORT_GRASS, Blocks.TALL_GRASS, Blocks.FERN, Blocks.LARGE_FERN, Blocks.BUSH,
            Blocks.FIREFLY_BUSH, Blocks.DEAD_BUSH, Blocks.SHORT_DRY_GRASS, Blocks.TALL_DRY_GRASS, Blocks.RED_SHRUB,
            Blocks.SWEET_BERRY_BUSH, Blocks.SUGAR_CANE, Blocks.CACTUS, Blocks.CACTUS_FLOWER, Blocks.BAMBOO,
            Blocks.PUMPKIN, Blocks.MELON, Blocks.LILY_PAD, Blocks.BROWN_MUSHROOM, Blocks.RED_MUSHROOM,
            Blocks.DANDELION, Blocks.POPPY, Blocks.BLUE_ORCHID, Blocks.ALLIUM, Blocks.AZURE_BLUET,
            Blocks.RED_TULIP, Blocks.ORANGE_TULIP, Blocks.WHITE_TULIP, Blocks.PINK_TULIP, Blocks.OXEYE_DAISY,
            Blocks.CORNFLOWER, Blocks.LILY_OF_THE_VALLEY, Blocks.SUNFLOWER, Blocks.LILAC, Blocks.ROSE_BUSH,
            Blocks.PEONY, Blocks.WILDFLOWERS, Blocks.PINK_PETALS, Blocks.OPEN_EYEBLOSSOM, Blocks.CLOSED_EYEBLOSSOM,
            Blocks.SEAGRASS, Blocks.TALL_SEAGRASS, Blocks.KELP, Blocks.KELP_PLANT, Blocks.SEA_PICKLE,
            Blocks.TUBE_CORAL_BLOCK, Blocks.TUBE_CORAL, Blocks.TUBE_CORAL_FAN, Blocks.TUBE_CORAL_WALL_FAN,
            Blocks.BRAIN_CORAL_BLOCK, Blocks.BRAIN_CORAL, Blocks.BRAIN_CORAL_FAN, Blocks.BRAIN_CORAL_WALL_FAN,
            Blocks.BUBBLE_CORAL_BLOCK, Blocks.BUBBLE_CORAL, Blocks.BUBBLE_CORAL_FAN, Blocks.BUBBLE_CORAL_WALL_FAN,
            Blocks.FIRE_CORAL_BLOCK, Blocks.FIRE_CORAL, Blocks.FIRE_CORAL_FAN, Blocks.FIRE_CORAL_WALL_FAN,
            Blocks.HORN_CORAL_BLOCK, Blocks.HORN_CORAL, Blocks.HORN_CORAL_FAN, Blocks.HORN_CORAL_WALL_FAN,
            Blocks.GLOW_LICHEN, Blocks.CAVE_VINES, Blocks.CAVE_VINES_PLANT, Blocks.SPORE_BLOSSOM,
            Blocks.BIG_DRIPLEAF, Blocks.BIG_DRIPLEAF_STEM, Blocks.SMALL_DRIPLEAF, Blocks.AZALEA,
            Blocks.FLOWERING_AZALEA, Blocks.HANGING_ROOTS, Blocks.SCULK, Blocks.SCULK_VEIN, Blocks.SCULK_CATALYST,
            Blocks.SCULK_SENSOR, Blocks.SCULK_SHRIEKER,
            Blocks.NETHERRACK, Blocks.SOUL_SAND, Blocks.SOUL_SOIL, Blocks.BASALT, Blocks.BLACKSTONE,
            Blocks.GLOWSTONE, Blocks.NETHER_QUARTZ_ORE, Blocks.NETHER_GOLD_ORE, Blocks.ANCIENT_DEBRIS,
            Blocks.CRIMSON_NYLIUM, Blocks.WARPED_NYLIUM, Blocks.CRIMSON_STEM, Blocks.WARPED_STEM,
            Blocks.NETHER_WART_BLOCK, Blocks.WARPED_WART_BLOCK, Blocks.SHROOMLIGHT, Blocks.CRIMSON_FUNGUS,
            Blocks.WARPED_FUNGUS, Blocks.CRIMSON_ROOTS, Blocks.WARPED_ROOTS, Blocks.NETHER_SPROUTS,
            Blocks.WEEPING_VINES, Blocks.WEEPING_VINES_PLANT, Blocks.TWISTING_VINES, Blocks.TWISTING_VINES_PLANT,
            Blocks.FIRE, Blocks.SOUL_FIRE,
            Blocks.END_STONE, Blocks.CHORUS_PLANT, Blocks.CHORUS_FLOWER));
        // The bands of the badlands.
        blocks.addAll(List.of(Blocks.DYED_TERRACOTTA.white(), Blocks.DYED_TERRACOTTA.orange(),
            Blocks.DYED_TERRACOTTA.yellow(), Blocks.DYED_TERRACOTTA.brown(), Blocks.DYED_TERRACOTTA.red(),
            Blocks.DYED_TERRACOTTA.lightGray()));
        return List.copyOf(blocks);
    }
}
