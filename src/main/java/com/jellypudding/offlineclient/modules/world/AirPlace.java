package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.RightClickEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ArmorStandItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.EndCrystalItem;
import net.minecraft.world.item.FireChargeItem;
import net.minecraft.world.item.FlintAndSteelItem;
import net.minecraft.world.item.FireworkRocketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public final class AirPlace extends Module {

    private final NumberSetting range = new NumberSetting("Range",
        "How far away blocks can be placed. Your normal block reach grows to match whilst on.",
        4.5, 1, 6, 0.1).min(0.5);
    private final BoolSetting anyItem = new BoolSetting("Any item",
        "Use anything you hold on the empty spot and not just blocks.", false);
    private final BoolSetting guide = new BoolSetting("Guide",
        "Draws a box on the spot the block will land in.", true);
    private final BoxStyle style = new BoxStyle(BoxStyle.Shape.BOTH, 0).under(guide);

    private BlockPos target;

    public AirPlace() {
        super("AirPlace", "Places blocks in mid air where your crosshair points.", Category.WORLD);
        addSettings(range, anyItem, guide);
        addSettings(style.settings());
        searchTags("air place", "midair", "build");
    }

    // Read by LocalPlayerMixin so blocks placed in the air can be built onto
    // from the same distance.
    public double getRange() {
        return range.getValue();
    }

    @Override
    protected void onDisable() {
        target = null;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        target = null;
        if (!inGame() || mc.player.isSpectator() || mc.player.isHandsBusy()) {
            return;
        }
        if (heldPlaceableHand() == null) {
            return;
        }
        target = findSpot();
    }

    @Subscribe
    private void onRightClick(RightClickEvent event) {
        if (!inGame() || mc.player.isSpectator() || mc.gameMode.isDestroying()) {
            return;
        }
        // Swallowing the click whilst the hands are busy would eat a normal use.
        if (mc.player.isHandsBusy()) {
            return;
        }
        InteractionHand hand = heldPlaceableHand();
        if (hand == null) {
            return;
        }
        BlockPos spot = findSpot();
        if (spot == null) {
            return;
        }
        // The game sets this delay itself on a normal click.
        mc.rightClickDelay = 4;
        event.cancel();

        // The click lands on the empty spot itself. The server treats a
        // replaceable clicked block as the place to put the new one.
        Direction face = BlockUtil.facingSide(spot);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(spot), face, spot, false);
        InteractionResult result = mc.gameMode.useItemOn(mc.player, hand, hit);
        if (result.consumesAction()) {
            mc.player.swing(hand);
        }
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (target == null || !guide.isOn()) {
            return;
        }
        style.draw(event.getBatch(), target, false);
    }

    private BlockPos findSpot() {
        if (mc.hitResult != null && mc.hitResult.getType() != HitResult.Type.MISS) {
            return null;
        }
        HitResult reach = mc.player.pick(range.getValue(), 0, false);
        if (reach.getType() != HitResult.Type.MISS || !(reach instanceof BlockHitResult blockHit)) {
            return null;
        }
        BlockPos pos = blockHit.getBlockPos();
        if (!Level.isInSpawnableBounds(pos) || !BlockUtil.isReplaceable(pos)) {
            return null;
        }
        if (BlockUtil.intersectsPlayer(pos)) {
            return null;
        }
        return pos;
    }

    // A tool or food in the main hand keeps its normal use.
    private InteractionHand heldPlaceableHand() {
        ItemStack main = mc.player.getMainHandItem();
        if (placeable(main)) {
            return InteractionHand.MAIN_HAND;
        }
        if (main.isEmpty() && placeable(mc.player.getOffhandItem())) {
            return InteractionHand.OFF_HAND;
        }
        return null;
    }

    // Blocks and the few items that stand in a clicked spot on their own.
    // A rocket whilst gliding is a boost and not a placement.
    private boolean placeable(ItemStack stack) {
        Item item = stack.getItem();
        if (item instanceof FireworkRocketItem) {
            return !mc.player.isFallFlying();
        }
        if (anyItem.isOn()) {
            return !stack.isEmpty();
        }
        return item instanceof BlockItem
            || item instanceof SpawnEggItem
            || item instanceof ArmorStandItem
            || item instanceof EndCrystalItem
            || item instanceof FlintAndSteelItem
            || item instanceof FireChargeItem;
    }
}
