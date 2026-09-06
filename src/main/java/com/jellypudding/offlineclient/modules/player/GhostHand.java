package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.RightClickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// Opens containers through walls.
public final class GhostHand extends Module {

    // The delay a normal right click leaves behind.
    private static final int USE_DELAY = 4;

    private final BoolSetting everything = new BoolSetting("Any container",
        "Reach through walls for anything that opens a screen.", true);
    private final RegistryListSetting<Block> blocks = new RegistryListSetting<>("Blocks",
        "The containers you can reach through walls.", BuiltInRegistries.BLOCK, defaultBlocks())
        .unless(everything);

    private final BoolSetting seeThrough = new BoolSetting("See through walls",
        "Empties the outline of every other block so the crosshair itself lands on the container. Nothing else can be clicked whilst this is on.",
        false);

    public GhostHand() {
        super("GhostHand", "Opens containers through walls.", Category.PLAYER);
        addSettings(everything, blocks, seeThrough);
        searchTags("through walls", "chest", "hand no clip");
    }

    // Read by BlockStateBaseMixin. True for a block the crosshair should pass through.
    public boolean passesThrough(BlockPos pos) {
        return isEnabled() && seeThrough.isOn() && inGame() && !wanted(pos);
    }

    // The usual storage plus every colour of shulker box.
    private static List<Block> defaultBlocks() {
        List<Block> list = new ArrayList<>(List.of(Blocks.CHEST, Blocks.TRAPPED_CHEST,
            Blocks.ENDER_CHEST, Blocks.BARREL, Blocks.DISPENSER, Blocks.DROPPER, Blocks.HOPPER));
        BuiltInRegistries.BLOCK.stream().filter(block -> block instanceof ShulkerBoxBlock)
            .forEach(list::add);
        return list;
    }

    // True for a block this module is allowed to open from here.
    private boolean wanted(BlockPos pos) {
        if (mc.level.getBlockState(pos).getMenuProvider(mc.level, pos) == null) {
            return false;
        }
        return everything.isOn() || blocks.contains(mc.level.getBlockState(pos).getBlock());
    }

    @Subscribe
    private void onRightClick(RightClickEvent event) {
        if (event.isCancelled() || !inGame() || mc.gameMode == null) {
            return;
        }
        if (mc.player.isSpectator() || mc.player.isShiftKeyDown()) {
            return;
        }
        if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
            && wanted(hit.getBlockPos())) {
            return;
        }

        double reach = mc.player.blockInteractionRange();
        Vec3 eye = mc.player.getEyePosition();
        Vec3 step = mc.player.getViewVector(1f).scale(0.1);
        Set<BlockPos> visited = new HashSet<>();

        for (int i = 1; i <= reach * 10; i++) {
            BlockPos pos = BlockPos.containing(eye.add(step.scale(i)));
            if (!visited.add(pos)) {
                continue;
            }
            if (!wanted(pos)) {
                continue;
            }
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, true);
            // Cancelling skips the delay the game would have set itself.
            mc.rightClickDelay = USE_DELAY;
            InteractionResult result = mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
            if (result.consumesAction()) {
                mc.player.swing(InteractionHand.MAIN_HAND);
            }
            event.cancel();
            return;
        }
    }
}
