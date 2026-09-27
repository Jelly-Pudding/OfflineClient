package com.jellypudding.offlineclient.worldgen;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;

// Works out the ground the ore features met from a finished world as a client receives it.
// Anti xray sends a hidden ore as plain stone and the features after the ores covered some
// of the ground. Both are turned back into what stood there when the ores went in.
public final class OreGround implements OreSimulation.Ground {

    // The finished world as the client holds it.
    @FunctionalInterface
    public interface Seen {
        BlockState block(int x, int y, int z);
    }

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState WATER = Blocks.WATER.defaultBlockState();
    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState DEEPSLATE = Blocks.DEEPSLATE.defaultBlockState();
    private static final BlockState NETHERRACK = Blocks.NETHERRACK.defaultBlockState();

    // These turn back into stone like the ores do. Anti xray hides some and the rest replaced
    // stone after the ores went in.
    private static final Set<Block> OVERWORLD_STONE = Set.of(
        Blocks.RAW_IRON_BLOCK, Blocks.RAW_COPPER_BLOCK, Blocks.RAW_GOLD_BLOCK,
        Blocks.INFESTED_STONE, Blocks.INFESTED_DEEPSLATE, Blocks.CLAY, Blocks.MAGMA_BLOCK,
        Blocks.DRIPSTONE_BLOCK, Blocks.SCULK, Blocks.MOSS_BLOCK);

    // Paper anti xray in its obfuscating modes fills enclosed stone with a random pick of the
    // blocks it hides. These two stay real wherever they touch open space.
    private static final Set<Block> DECOYS = Set.of(Blocks.MOSSY_COBBLESTONE, Blocks.OBSIDIAN);

    private static final Direction[] SIDES = Direction.values();

    // Growth that filled open air after the ores went in.
    private static final Set<Block> GROWTH = Set.of(
        Blocks.GLOW_LICHEN, Blocks.VINE, Blocks.CAVE_VINES, Blocks.CAVE_VINES_PLANT, Blocks.SPORE_BLOSSOM,
        Blocks.HANGING_ROOTS, Blocks.MOSS_CARPET, Blocks.PALE_MOSS_CARPET, Blocks.PALE_HANGING_MOSS,
        Blocks.BIG_DRIPLEAF, Blocks.BIG_DRIPLEAF_STEM, Blocks.SMALL_DRIPLEAF,
        Blocks.POINTED_DRIPSTONE, Blocks.SULFUR_SPIKE, Blocks.SCULK_VEIN, Blocks.SCULK_SENSOR,
        Blocks.SCULK_SHRIEKER, Blocks.SCULK_CATALYST, Blocks.SNOW, Blocks.SWEET_BERRY_BUSH,
        Blocks.SUGAR_CANE, Blocks.BAMBOO, Blocks.BAMBOO_SAPLING, Blocks.CACTUS, Blocks.PUMPKIN, Blocks.MELON,
        Blocks.LILY_PAD, Blocks.BROWN_MUSHROOM, Blocks.RED_MUSHROOM, Blocks.BROWN_MUSHROOM_BLOCK,
        Blocks.RED_MUSHROOM_BLOCK, Blocks.MUSHROOM_STEM, Blocks.BEE_NEST, Blocks.COCOA,
        Blocks.CREAKING_HEART, Blocks.RESIN_CLUMP, Blocks.MANGROVE_ROOTS,
        Blocks.WEEPING_VINES, Blocks.WEEPING_VINES_PLANT, Blocks.TWISTING_VINES, Blocks.TWISTING_VINES_PLANT,
        Blocks.CRIMSON_FUNGUS, Blocks.WARPED_FUNGUS, Blocks.NETHER_WART_BLOCK, Blocks.WARPED_WART_BLOCK,
        Blocks.SHROOMLIGHT);

    private final Seen seen;
    private final BiomeResolver biomes;
    private final OreTables.Table table;
    // One above the highest blocking block of each column once worked out.
    private final Long2IntOpenHashMap floors = new Long2IntOpenHashMap();

    public OreGround(Seen seen, BiomeResolver biomes, OreTables.Table table) {
        this.seen = seen;
        this.biomes = biomes;
        this.table = table;
    }

    @Override
    public BlockState block(int x, int y, int z) {
        BlockState state = seen.block(x, y, z);
        if (!state.getFluidState().isEmpty() && !state.getFluidState().isSource()) {
            // Springs spill into caves after the ores went in.
            return AIR;
        }
        if (isGrowth(state)) {
            // Waterlogged growth stood in water.
            return state.getFluidState().isEmpty() ? AIR : state.getFluidState().createLegacyBlock();
        }
        boolean ore = OreTables.kindOf(state.getBlock()) != null;
        if (table.nether()) {
            return ore ? NETHERRACK : state;
        }
        if (ore || OVERWORLD_STONE.contains(state.getBlock())
            || DECOYS.contains(state.getBlock()) && enclosed(x, y, z)) {
            return y < 0 ? DEEPSLATE : STONE;
        }
        if (state.is(BlockTags.CORAL_BLOCKS) || state.is(Blocks.ICE) && seen.block(x, y - 1, z).is(Blocks.WATER)) {
            // Reefs grow in water and the top layer freezes after the ores went in.
            return WATER;
        }
        return state;
    }

    // Anti xray only swaps blocks that no open side shows.
    private boolean enclosed(int x, int y, int z) {
        for (Direction side : SIDES) {
            BlockState next = seen.block(x + side.getStepX(), y + side.getStepY(), z + side.getStepZ());
            if (!next.isRedstoneConductor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isGrowth(BlockState state) {
        return GROWTH.contains(state.getBlock()) || state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES)
            || state.is(BlockTags.REPLACEABLE_BY_TREES) || state.is(BlockTags.FLOWERS) || state.is(BlockTags.SAPLINGS);
    }

    // The heightmap the ores read stops changing when the terrain step ends. The finished
    // column with the growth taken off is the closest the client can get to it.
    @Override
    public int floor(int x, int z) {
        long key = ChunkPos.pack(x, z);
        int known = floors.getOrDefault(key, Integer.MIN_VALUE);
        if (known != Integer.MIN_VALUE) {
            return known;
        }
        int floor = table.minBuildY();
        for (int y = table.maxBuildY() - 1; y >= table.minBuildY(); y--) {
            if (block(x, y, z).is(BlockTags.BLOCKS_MOTION_IN_HEIGHTMAP)) {
                floor = y + 1;
                break;
            }
        }
        floors.put(key, floor);
        return floor;
    }

    @Override
    public Holder<Biome> noiseBiome(int quartX, int quartY, int quartZ) {
        return biomes.getNoiseBiome(quartX, quartY, quartZ);
    }
}
