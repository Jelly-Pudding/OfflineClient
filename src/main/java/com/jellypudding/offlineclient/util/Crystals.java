package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

// Where an end crystal may go and the click that puts it there.
public final class Crystals {

    private static final Minecraft MC = OfflineClient.MC;

    private Crystals() {
    }

    // An end crystal only goes down on obsidian or bedrock.
    public static boolean crystalBase(BlockState state) {
        return state.is(Blocks.OBSIDIAN) || state.is(Blocks.BEDROCK);
    }

    // The server refuses a crystal whilst any entity touches its block or the one above.
    public static AABB crystalSpace(BlockPos crystal) {
        return new AABB(crystal.getX(), crystal.getY(), crystal.getZ(),
            crystal.getX() + 1, crystal.getY() + 2, crystal.getZ() + 1);
    }

    // The click on top of the base that places a crystal.
    public static BlockHitResult crystalClick(BlockPos base) {
        return new BlockHitResult(Vec3.atCenterOf(base).add(0, 0.5, 0), Direction.UP, base, false);
    }

    public static boolean hasCrystals() {
        return MC.player.getOffhandItem().is(Items.END_CRYSTAL)
            || InventoryUtil.hotbarSlot(stack -> stack.is(Items.END_CRYSTAL)) != -1;
    }

    // Places an end crystal on the base from the offhand or else from the hotbar. A hotbar
    // slot stays selected for the caller to give back. False when no crystal is at hand or
    // the click was refused.
    public static boolean placeCrystal(InventoryUtil.SlotSwap slots, BlockPos base, SwingMode swing) {
        InteractionHand hand = InteractionHand.OFF_HAND;
        if (!MC.player.getOffhandItem().is(Items.END_CRYSTAL)) {
            int slot = InventoryUtil.hotbarSlot(stack -> stack.is(Items.END_CRYSTAL));
            if (slot == -1) {
                return false;
            }
            slots.select(slot);
            hand = InteractionHand.MAIN_HAND;
        }
        if (!MC.gameMode.useItemOn(MC.player, hand, crystalClick(base)).consumesAction()) {
            return false;
        }
        swing.use(hand);
        return true;
    }
}
