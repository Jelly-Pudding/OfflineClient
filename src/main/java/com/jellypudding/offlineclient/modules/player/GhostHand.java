package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.RightClickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.Hands;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.SwingMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

public final class GhostHand extends Module {

    private final BoolSetting everything = new BoolSetting("Any container",
        "Lets you reach through walls for anything that opens a screen. Ender chests count.", true);
    private final RegistryListSetting<Block> blocks = new RegistryListSetting<>("Blocks",
        "The blocks you can reach through walls. A lever or a door is pressed from the far side. A block"
            + " that takes no click such as an ore is only reached with the option below.",
        BuiltInRegistries.BLOCK, defaultBlocks())
        .unless(everything);

    private final BoolSetting seeThrough = new BoolSetting("See through walls",
        "Lets the crosshair pass through every other block. Whatever it reaches can be opened or pressed"
            + " or mined from behind the wall. Nothing else can be clicked whilst this is on.",
        false);

    public GhostHand() {
        super("GhostHand", "Reaches containers and other blocks you pick through walls.", Category.PLAYER);
        addSettings(everything, blocks, seeThrough);
        searchTags("through walls", "chest", "lever", "hand no clip");
    }

    // Read by BlockStateBaseMixin.
    public boolean passesThrough(BlockPos pos) {
        return isEnabled() && seeThrough.isOn() && inGame() && !wanted(pos);
    }

    private static List<Block> defaultBlocks() {
        List<Block> list = new ArrayList<>(List.of(Blocks.CHEST, Blocks.TRAPPED_CHEST,
            Blocks.ENDER_CHEST, Blocks.BARREL, Blocks.DISPENSER, Blocks.DROPPER, Blocks.HOPPER));
        BuiltInRegistries.BLOCK.stream().filter(block -> block instanceof ShulkerBoxBlock)
            .forEach(list::add);
        return list;
    }

    // An ender chest opens a screen and yet hands out no menu since its block entity holds nothing.
    private boolean wanted(BlockPos pos) {
        BlockState state = mc.level.getBlockState(pos);
        if (!everything.isOn()) {
            return blocks.contains(state.getBlock());
        }
        return state.getBlock() instanceof EnderChestBlock || state.getMenuProvider(mc.level, pos) != null;
    }

    // A blank click stays a click into the air and a block NoInteract protects is never clicked.
    @Subscribe
    private void onRightClick(RightClickEvent event) {
        if (event.isBlank() || !inGame() || mc.gameMode == null) {
            return;
        }
        if (Hands.tied() || mc.player.isShiftKeyDown()) {
            return;
        }
        BlockHitResult aimed = BlockUtil.aimedBlock();
        if (aimed != null && wanted(aimed.getBlockPos())) {
            return;
        }

        Vec3 eye = mc.player.getEyePosition();
        Vec3 end = eye.add(mc.player.getViewVector(1f).scale(mc.player.blockInteractionRange()));
        // Each block the view crosses comes up once and in order. The position handed in is mutable.
        BlockPos target = BlockGetter.traverseBlocks(eye, end, null,
            (_, pos) -> wanted(pos) && !Modules.keepsFromUse(pos) ? pos.immutable() : null, _ -> null);
        if (target == null) {
            return;
        }
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(target), Direction.UP, target, true);
        InteractionResult result = mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
        // A block that takes no click leaves the click to the game. Food is still eaten.
        if (result.consumesAction()) {
            // Cancelling skips the delay the game would have set itself.
            mc.rightClickDelay = InputUtil.USE_DELAY;
            SwingMode.swingUse(InteractionHand.MAIN_HAND);
            event.cancel();
        }
    }
}
