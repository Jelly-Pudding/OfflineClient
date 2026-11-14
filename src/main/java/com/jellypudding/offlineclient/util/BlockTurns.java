package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.BlockTransformer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PotionItem;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

// What an item turns a block into when it is used on it. A hoe and a shovel and an axe carry
// their rules as data the server sends and a water bottle wets dirt by a rule of its own.
public final class BlockTurns {

    // A rule may ask for a random number. The game's own are left alone.
    private static final RandomSource RANDOM = RandomSource.create();

    // Farmland stays wet with water this many blocks away on its own level or the one above.
    private static final int WATER_REACH = 4;

    // Bone meal on moss lays a patch that reaches at most this many blocks out on each axis and
    // never fills its corners.
    private static final int MOSS_REACH = 3;
    // How far up or down a moss patch follows the ground.
    private static final int MOSS_CLIMB = 5;
    // Every column of the widest moss patch but the one the moss stands in.
    public static final int MOSS_COLUMNS = (2 * MOSS_REACH + 1) * (2 * MOSS_REACH + 1) - 5;

    private BlockTurns() {
    }

    // The state the block takes when the stack is used on that face of it. Null when the click
    // changes nothing.
    public static BlockState result(ItemStack stack, LevelAccessor level, BlockPos pos, Direction face) {
        if (isWater(stack)) {
            return wets(level.getBlockState(pos), face) ? Blocks.MUD.defaultBlockState() : null;
        }
        Holder<BlockTransformer> rules = stack.get(DataComponents.BLOCK_TRANSFORMER);
        if (rules == null) {
            return null;
        }
        for (BlockTransformer.BlockTransformData turn : rules.value().transforms()) {
            if (turn.disallowedFaces().contains(face)) {
                continue;
            }
            BlockState result = turn.blockStateProvider().value().getOptionalState(level, RANDOM, pos);
            if (result != null) {
                // Some rules let what comes out settle against its neighbours as the game does.
                return turn.updateFromNeighbors() ? Block.updateFromNeighbourShapes(result, level, pos) : result;
            }
        }
        return null;
    }

    // The same for the face of the block nearest the eyes which is the one a module clicks.
    public static BlockState result(ItemStack stack, BlockPos pos) {
        return result(stack, OfflineClient.MC.level, pos, BlockUtil.facingSide(pos));
    }

    // True when the hoe would turn the soil there into something else. Farmland only forms under
    // open air and rooted dirt under a block would lose its roots for nothing. Wet only passes
    // soil that would find water as farmland. Farmland with none dries up again.
    public static boolean tills(ItemStack hoe, BlockPos pos, boolean wetOnly) {
        return BlockUtil.state(pos.above()).isAir() && result(hoe, pos) != null
            && (!wetOnly || staysWet(OfflineClient.MC.level, pos));
    }

    // True when farmland at the spot would find the water that keeps it wet. The search is the
    // one the farmland block makes itself.
    public static boolean staysWet(BlockGetter level, BlockPos pos) {
        for (BlockPos near : BlockPos.betweenClosed(pos.offset(-WATER_REACH, 0, -WATER_REACH),
            pos.offset(WATER_REACH, 1, WATER_REACH))) {
            if (level.getFluidState(near).is(FluidTags.WATER)) {
                return true;
            }
        }
        return false;
    }

    // How many blocks round a moss block one feed of bone meal could still turn to moss. The
    // patch looks down each column from one block above the moss for the first ground with air
    // on it and only turns ground the game lists for it. Ground that is moss already is not counted.
    public static int mossSpread(BlockGetter level, BlockPos pos, Block moss) {
        int count = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -MOSS_REACH; dx <= MOSS_REACH; dx++) {
            for (int dz = -MOSS_REACH; dz <= MOSS_REACH; dz++) {
                if (Math.abs(dx) == MOSS_REACH && Math.abs(dz) == MOSS_REACH) {
                    continue;
                }
                cursor.setWithOffset(pos, dx, 1, dz);
                for (int step = 0; step < MOSS_CLIMB && level.getBlockState(cursor).isAir(); step++) {
                    cursor.move(Direction.DOWN);
                }
                for (int step = 0; step < MOSS_CLIMB && !level.getBlockState(cursor).isAir(); step++) {
                    cursor.move(Direction.UP);
                }
                if (!level.getBlockState(cursor).isAir()) {
                    continue;
                }
                cursor.move(Direction.DOWN);
                BlockState ground = level.getBlockState(cursor);
                if (ground.is(BlockTags.MOSS_REPLACEABLE) && !ground.is(moss)
                    && ground.isFaceSturdy(level, cursor, Direction.UP)) {
                    count++;
                }
            }
        }
        return count;
    }

    // True when a water bottle used on that face turns the block to mud.
    public static boolean wets(BlockState state, Direction face) {
        return face != Direction.DOWN && state.is(BlockTags.CONVERTIBLE_TO_MUD);
    }

    // True for a bottle or a splash or lingering bottle of plain water.
    public static boolean isWater(ItemStack stack) {
        PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
        return stack.getItem() instanceof PotionItem && contents != null && contents.is(Potions.WATER);
    }

    // Uses the main hand on the nearest face of the block. The game keeps a tool from working
    // whilst the other hand holds a shield and the player stands. The click is made sneaking then.
    public static boolean use(BlockPos pos) {
        LocalPlayer player = OfflineClient.MC.player;
        boolean shielded = player.getOffhandItem().has(DataComponents.BLOCKS_ATTACKS)
            && !player.isSecondaryUseActive();
        return shielded
            ? InputUtil.whileSneaking(() -> BlockUtil.useOn(pos, false, false))
            : BlockUtil.useOn(pos, false, false);
    }
}
