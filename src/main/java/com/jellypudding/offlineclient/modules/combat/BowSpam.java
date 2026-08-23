package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.EntityUtil;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;

public final class BowSpam extends Module {

    // Stands in for an item in hand that is not being drawn at all.
    private static final int NOT_DRAWN = Integer.MAX_VALUE;

    // A bow discards anything under power 0.1 which is everything below three ticks.
    private static final int MIN_CHARGE = 3;

    private final NumberSetting charge = new NumberSetting("Charge",
        "Ticks to draw the bow before letting go.", 5, 3, 20, 1, " ticks");
    private final BoolSetting onlyWithTarget = new BoolSetting("Only with target",
        "Hold fire until an enemy is in view.", false);
    private final NumberSetting targetRange = new NumberSetting("Target range",
        "How far away enemies are considered.", 40, 5, 100, 1, " blocks")
        .visibleWhen(onlyWithTarget::isOn);
    private final NumberSetting viewAngle = new NumberSetting("View angle",
        "How far off the crosshair a target still counts.", 30, 5, 90, 1, " degrees")
        .visibleWhen(onlyWithTarget::isOn);
    private final BoolSetting crossbows = new BoolSetting("Crossbows",
        "Fire loaded crossbows as well.", true);
    private final NumberSetting crossbowDelay = new NumberSetting("Crossbow delay",
        "Ticks to wait between crossbow shots.", 10, 0, 20, 1, " ticks")
        .visibleWhen(crossbows::isOn);

    private int crossbowTimer;

    public BowSpam() {
        super("BowSpam", "Fires your bow or crossbow as fast as it will go.", Category.COMBAT);
        addSettings(charge, onlyWithTarget, targetRange, viewAngle, crossbows, crossbowDelay);
        searchTags("auto bow", "crossbow", "rapid fire");
    }

    @Override
    protected void onEnable() {
        crossbowTimer = 0;
    }

    @Override
    protected void onDisable() {
        // Leaving a draw hanging would keep the server thinking the bow is up.
        if (inGame() && mc.player.isUsingItem() && drawTicks() != NOT_DRAWN) {
            mc.gameMode.releaseUsingItem(mc.player);
        }
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.player.isSpectator()) {
            return;
        }
        if (mc.player.containerMenu != mc.player.inventoryMenu) {
            return;
        }
        if (crossbowTimer > 0) {
            crossbowTimer--;
        }

        if (mc.player.isUsingItem()) {
            if (mc.player.getTicksUsingItem() >= drawTicks()) {
                mc.gameMode.releaseUsingItem(mc.player);
            }
            return;
        }
        if (onlyWithTarget.isOn() && !targetInView()) {
            return;
        }

        if (crossbows.isOn()) {
            InteractionHand loaded = handWithCrossbow(true);
            if (loaded != null && crossbowTimer == 0) {
                mc.gameMode.useItem(mc.player, loaded);
                crossbowTimer = crossbowDelay.getInt();
                return;
            }
            InteractionHand spent = loaded == null ? handWithCrossbow(false) : null;
            // An empty crossbow has to be drawn before there is anything to fire.
            if (spent != null && hasAmmo(spent)) {
                mc.gameMode.useItem(mc.player, spent);
                return;
            }
        }

        InteractionHand hand = handWithBow();
        if (hand == null || !hasAmmo(hand)) {
            return;
        }
        mc.gameMode.useItem(mc.player, hand);
    }

    // Ticks the item in hand has to be held before letting go.
    private int drawTicks() {
        ItemStack using = mc.player.getUseItem();
        if (using.getItem() instanceof BowItem) {
            return Math.max(charge.getInt(), MIN_CHARGE);
        }
        if (using.getItem() instanceof CrossbowItem) {
            return CrossbowItem.getChargeDuration(using, mc.player);
        }
        return NOT_DRAWN;
    }

    private boolean hasAmmo(InteractionHand hand) {
        return mc.player.getAbilities().instabuild
            || !mc.player.getProjectile(mc.player.getItemInHand(hand)).isEmpty();
    }

    private InteractionHand handWithBow() {
        for (InteractionHand hand : InteractionHand.values()) {
            if (mc.player.getItemInHand(hand).getItem() instanceof BowItem) {
                return hand;
            }
        }
        return null;
    }

    private InteractionHand handWithCrossbow(boolean loaded) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = mc.player.getItemInHand(hand);
            if (stack.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(stack) == loaded) {
                return hand;
            }
        }
        return null;
    }

    private boolean targetInView() {
        Player target = EntityUtil.nearestEnemy(targetRange.getValue());
        if (target == null || !mc.player.hasLineOfSight(target)) {
            return false;
        }
        return EntityUtil.lookAngleTo(target) <= viewAngle.getValue();
    }
}
