package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class BowSpam extends Module {

    // Stands in for an item in hand that is not being drawn at all.
    private static final int NOT_DRAWN = Integer.MAX_VALUE;

    // A bow discards anything under power 0.1 which is everything below three ticks.
    private static final int MIN_CHARGE = 3;

    private final NumberSetting charge = new NumberSetting("Charge",
        "Ticks to draw the bow before letting go.", 5, 3, 20, 1, " ticks");
    private final BoolSetting holdingUse = new BoolSetting("Whilst holding use",
        "Only fire whilst you hold the use key down.", false);
    private final BoolSetting onlyWithTarget = new BoolSetting("Only with target",
        "Hold fire until an enemy is in view.", false);
    private final NumberSetting targetRange = new NumberSetting("Target range",
        "How far away enemies are considered.", 40, 5, 100, 1, " blocks")
        .under(onlyWithTarget);
    private final NumberSetting viewAngle = new NumberSetting("View angle",
        "How far off the crosshair a target still counts.", 30, 5, 90, 1, " degrees")
        .under(onlyWithTarget);
    private final BoolSetting crossbows = new BoolSetting("Crossbows",
        "Fire every loaded crossbow in your hotbar as well. Each one is picked up and fired and put back.", true);
    private final NumberSetting crossbowDelay = new NumberSetting("Crossbow delay",
        "Ticks to wait between crossbow shots.", 10, 0, 20, 1, " ticks")
        .under(crossbows);
    private final BoolSetting searchInventory = new BoolSetting("Search inventory",
        "Also fetch loaded crossbows from the rest of your inventory into a hotbar slot that is empty or holds a crossbow or arrows.", true)
        .under(crossbows);

    private final SlotSwap slots = new SlotSwap();
    private int crossbowTimer;

    public BowSpam() {
        super("BowSpam", "Fires your bow or crossbow as fast as it will go.", Category.COMBAT);
        addSettings(charge, holdingUse, onlyWithTarget, targetRange, viewAngle,
            crossbows, crossbowDelay, searchInventory);
        searchTags("auto bow", "crossbow", "rapid fire");
    }

    @Override
    protected void onEnable() {
        crossbowTimer = 0;
        slots.forget();
    }

    @Override
    protected void onDisable() {
        slots.restore();
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
        if (holdingUse.isOn() && !InputUtil.physicallyHeld(mc.options.keyUse)) {
            return;
        }
        if (onlyWithTarget.isOn() && !targetInView()) {
            return;
        }

        if (crossbows.isOn()) {
            if (crossbowTimer == 0 && fireLoadedCrossbow()) {
                crossbowTimer = crossbowDelay.getInt();
                return;
            }
            InteractionHand spent = handWithCrossbow(false);
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

    // Fires the first loaded crossbow in either hand or the hotbar or the inventory.
    // True when a shot went out.
    private boolean fireLoadedCrossbow() {
        InteractionHand loaded = handWithCrossbow(true);
        if (loaded != null) {
            mc.gameMode.useItem(mc.player, loaded);
            return true;
        }
        int slot = InventoryUtil.hotbarSlot(BowSpam::isLoaded);
        if (slot == -1 && searchInventory.isOn()) {
            slot = fetchLoadedCrossbow();
        }
        if (slot == -1) {
            return false;
        }
        slots.select(slot);
        mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        slots.restore();
        return true;
    }

    // Swaps a loaded crossbow from the inventory into a hotbar slot that can spare
    // the room. The hotbar slot it landed in or minus one.
    private int fetchLoadedCrossbow() {
        int from = InventoryUtil.findSlot(BowSpam::isLoaded, InventoryUtil.WHOLE_INVENTORY);
        if (from < InventoryUtil.HOTBAR_SIZE || !InventoryUtil.canClick()
            || !InventoryUtil.carried().isEmpty()) {
            return -1;
        }
        int to = InventoryUtil.hotbarSlot(stack -> stack.isEmpty()
            || stack.getItem() instanceof CrossbowItem || stack.is(Items.ARROW));
        if (to == -1) {
            return -1;
        }
        mc.gameMode.handleContainerInput(0, InventoryUtil.networkSlot(from), to,
            ContainerInput.SWAP, mc.player);
        return to;
    }

    private static boolean isLoaded(ItemStack stack) {
        return stack.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(stack);
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
