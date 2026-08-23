package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.module.ModuleManager;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Eats golden apples to hold buffs up. Only the enchanted apple grants fire
 * resistance.
 */
public final class AutoGap extends Module {

    // Ticks to wait after a bite whilst the effects land.
    private static final int SETTLE_TICKS = 5;

    // Ticks to give the hand before the bite is written off.
    private static final int START_TIMEOUT = 20;

    public enum Choice {
        PLAIN("Plain first"),
        ENCHANTED("Enchanted first"),
        ENCHANTED_ONLY("Enchanted only");

        private final String label;

        Choice(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private final NumberSetting health = new NumberSetting("Health",
        "Eat at or below this many hearts. Zero turns it off.", 7, 0, 10, 0.5, " hearts")
        .min(0).max(20);
    private final BoolSetting absorption = new BoolSetting("Absorption",
        "Eat to keep the absorption effect topped up.", true);
    private final BoolSetting regeneration = new BoolSetting("Regeneration",
        "Eat to keep the regeneration effect topped up.", false);
    private final BoolSetting fireResistance = new BoolSetting("Fire resistance",
        "Eat to keep the fire resistance effect topped up.", false);
    private final NumberSetting expiry = new NumberSetting("Expiry",
        "Start eating this many ticks before an effect runs out.", 60, 0, 200, 10, " ticks")
        .min(0);
    private final EnumSetting<Choice> choice = new EnumSetting<>("Choice",
        "Which apple to reach for.", Choice.PLAIN);
    private final BoolSetting hold = new BoolSetting("Keep held",
        "Stay on the apple slot between bites.", true);
    private final BoolSetting pauseCombat = new BoolSetting("Pause combat",
        "Holds the combat modules back whilst you eat.", true);

    private boolean eating;
    private boolean started;
    private boolean needsEnchanted;
    private int waited;
    private int settle;
    private int previousSlot = -1;

    private int swappedSlot = -1;
    private int swappedHotbar = -1;

    // Cached. The lookup walks every registered module.
    private AutoEat autoEat;
    private AutoPotion autoPotion;

    public AutoGap() {
        super("AutoGap", "Eats golden apples to hold your buffs and your health up.", Category.PLAYER);
        addSettings(health, absorption, regeneration, fireResistance, expiry, choice, hold, pauseCombat);
        searchTags("gapple", "golden apple", "egap");
    }

    public boolean isEating() {
        return isEnabled() && eating && pauseCombat.isOn();
    }

    public boolean isBusy() {
        return isEnabled() && eating;
    }

    @Override
    public String getSuffix() {
        return eating ? "eating" : null;
    }

    @Override
    protected void onDisable() {
        stopEating();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.player.isSpectator()) {
            stopEating();
            return;
        }
        if (settle > 0) {
            settle--;
            return;
        }
        if (eating) {
            continueEating();
            return;
        }
        if (handBusy()) {
            return;
        }
        // Slot swaps and container clicks need the survival inventory.
        if (mc.gui.screen() != null || mc.player.containerMenu.containerId != 0
            || !mc.player.containerMenu.getCarried().isEmpty()) {
            return;
        }

        needsEnchanted = false;
        if (!wantsApple()) {
            releaseHold();
            return;
        }
        int slot = findApple();
        if (slot == -1) {
            releaseHold();
            return;
        }
        beginEating(slot);
    }

    private boolean handBusy() {
        ModuleManager modules = OfflineClient.INSTANCE.getModuleManager();
        if (modules == null) {
            return false;
        }
        if (autoEat == null) {
            autoEat = modules.get(AutoEat.class);
        }
        if (autoPotion == null) {
            autoPotion = modules.get(AutoPotion.class);
        }
        return autoEat.isBusy() || autoPotion.isDrinking();
    }

    private boolean wantsApple() {
        if (EntityUtil.healthAtOrBelow(health.getValue())) {
            return true;
        }
        // Both apples grant absorption.
        if (absorption.isOn() && isRunningOut(MobEffects.ABSORPTION)) {
            return true;
        }
        if (fireResistance.isOn() && isRunningOut(MobEffects.FIRE_RESISTANCE)) {
            needsEnchanted = true;
            return true;
        }
        return regeneration.isOn() && isRunningOut(MobEffects.REGENERATION);
    }

    private boolean isRunningOut(Holder<MobEffect> effect) {
        MobEffectInstance instance = mc.player.getEffect(effect);
        return instance == null || instance.getDuration() <= expiry.getInt();
    }

    private void beginEating(int slot) {
        if (slot >= 9) {
            // A full hotbar means the held slot takes the apple.
            int hotbar = InventoryUtil.freeHotbarSlot(InventoryUtil.selectedSlot());
            mc.gameMode.handleContainerInput(0, slot, hotbar, ContainerInput.SWAP, mc.player);
            swappedSlot = slot;
            swappedHotbar = hotbar;
            slot = hotbar;
        }
        if (previousSlot == -1) {
            previousSlot = mc.player.getInventory().getSelectedSlot();
        }
        mc.player.getInventory().setSelectedSlot(slot);
        eating = true;
        started = false;
        waited = 0;
    }

    private void continueEating() {
        ItemStack held = mc.player.getInventory().getSelectedItem();
        if (mc.gui.screen() != null || !isApple(held)) {
            stopEating();
            return;
        }
        if (mc.player.isUsingItem()) {
            started = true;
        } else if (started) {
            finishBite();
            return;
        } else if (++waited > START_TIMEOUT) {
            finishBite();
            return;
        }
        mc.options.keyUse.setDown(true);
    }

    private void finishBite() {
        boolean keep = hold.isOn() && swappedSlot == -1;
        releaseKey();
        eating = false;
        started = false;
        settle = SETTLE_TICKS;
        if (!keep) {
            restoreSlot();
        }
    }

    private void stopEating() {
        if (eating) {
            releaseKey();
            eating = false;
            started = false;
        }
        restoreSlot();
    }

    private void releaseHold() {
        if (!eating) {
            restoreSlot();
        }
    }

    private void releaseKey() {
        // Give the key back without forcing it up.
        boolean physicallyHeld = InputConstants.isKeyDown(
            mc.getWindow(), mc.options.keyUse.key.getValue());
        mc.options.keyUse.setDown(physicallyHeld);
    }

    private void restoreSlot() {
        if (mc.player == null) {
            swappedSlot = -1;
            swappedHotbar = -1;
            previousSlot = -1;
            return;
        }
        if (swappedSlot != -1 && mc.gui.screen() == null
            && mc.player.containerMenu.containerId == 0
            && mc.player.containerMenu.getCarried().isEmpty()) {
            mc.gameMode.handleContainerInput(0, swappedSlot, swappedHotbar,
                ContainerInput.SWAP, mc.player);
            swappedSlot = -1;
            swappedHotbar = -1;
        }
        if (previousSlot != -1) {
            mc.player.getInventory().setSelectedSlot(previousSlot);
            previousSlot = -1;
        }
    }

    private int findApple() {
        int plain = -1;
        int enchanted = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (plain == -1 && stack.is(Items.GOLDEN_APPLE)) {
                plain = i;
            } else if (enchanted == -1 && stack.is(Items.ENCHANTED_GOLDEN_APPLE)) {
                enchanted = i;
            }
        }
        if (needsEnchanted || choice.is(Choice.ENCHANTED_ONLY)) {
            return enchanted;
        }
        if (choice.is(Choice.ENCHANTED)) {
            return enchanted != -1 ? enchanted : plain;
        }
        return plain != -1 ? plain : enchanted;
    }

    private boolean isApple(ItemStack stack) {
        if (needsEnchanted || choice.is(Choice.ENCHANTED_ONLY)) {
            return stack.is(Items.ENCHANTED_GOLDEN_APPLE);
        }
        return stack.is(Items.GOLDEN_APPLE) || stack.is(Items.ENCHANTED_GOLDEN_APPLE);
    }
}
