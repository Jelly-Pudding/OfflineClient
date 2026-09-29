package com.jellypudding.offlineclient.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;

// What Paper's anti xray does to the chunks it sends. It only swaps a solid block whose six
// neighbours are all solid. A block that is see through or touches one always arrives as it is.
public final class AntiXray {

    // Paper adds these to every low section it loads in its hiding mode.
    public static final Set<Block> FILLERS = Set.of(Blocks.STONE, Blocks.DEEPSLATE, Blocks.NETHERRACK,
        Blocks.END_STONE);

    // The blocks Paper hides by default and the nether ores servers add to it. It swaps a buried
    // one for a filler in the packet and leaves its palette entry unused. It also lists their
    // default states in every low section.
    public static final Set<Block> HIDDEN = Set.of(Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE,
        Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE, Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE,
        Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE, Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE,
        Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE, Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE,
        Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE, Blocks.RAW_IRON_BLOCK, Blocks.RAW_COPPER_BLOCK,
        Blocks.MOSSY_COBBLESTONE, Blocks.OBSIDIAN, Blocks.CHEST, Blocks.ENDER_CHEST, Blocks.CLAY,
        Blocks.OAK_PLANKS, Blocks.ANCIENT_DEBRIS, Blocks.NETHER_GOLD_ORE, Blocks.NETHER_QUARTZ_ORE);

    // Paper treats these as see through although they fill their space.
    private static final Set<Block> SEE_THROUGH = Set.of(Blocks.SPAWNER, Blocks.BARRIER, Blocks.SHULKER_BOX,
        Blocks.SLIME_BLOCK, Blocks.MANGROVE_ROOTS);

    // A server can have still lava hide what it touches and the client never learns it does.
    // It counts as solid here.
    private static final BlockState STILL_LAVA = Blocks.LAVA.defaultBlockState();

    private AntiXray() {
    }

    // True when the block at that spot cannot be a fake. Safe on a scanner thread.
    public static boolean shownTruly(ChunkScanner.View view, int x, int y, int z) {
        if (seeThrough(view.get(x, y, z))) {
            return true;
        }
        for (Direction side : Direction.values()) {
            if (seeThrough(view.get(x + side.getStepX(), y + side.getStepY(), z + side.getStepZ()))) {
                return true;
            }
        }
        return false;
    }

    // Paper's own test with an empty world behind it.
    private static boolean seeThrough(BlockState state) {
        return (!state.isRedstoneConductor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)
            || SEE_THROUGH.contains(state.getBlock())) && state != STILL_LAVA;
    }
}
