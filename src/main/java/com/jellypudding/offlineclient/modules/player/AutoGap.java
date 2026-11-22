package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.Feeding;
import com.jellypudding.offlineclient.util.Hands;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.ItemUtil;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

// Only the enchanted apple grants fire resistance.
public final class AutoGap extends Module {

    private static final int SETTLE_TICKS = 20;

    private static final int MEAL_GAP = 100;

    public enum Choice { PLAIN_FIRST, PLAIN_ONLY, ENCHANTED_FIRST, ENCHANTED_ONLY }

    private final BoolSetting always = new BoolSetting("Always",
        "Eats apples back to back for as long as the module is on whatever your health or effects.",
        false);
    private final NumberSetting health = new NumberSetting("Health",
        "Eats at or below this many hearts. Zero turns it off.", 7, 0, 10, 0.5, " hearts")
        .unless(always);
    private final EnumSetting<Choice> choice = new EnumSetting<>("Choice",
        "Which apple to reach for.", Choice.PLAIN_FIRST)
        .describe(Choice.PLAIN_FIRST, "Eats a plain apple and only takes an enchanted one when there is none.")
        .describe(Choice.PLAIN_ONLY, "Never eats an enchanted apple.")
        .describe(Choice.ENCHANTED_FIRST, "Eats an enchanted apple and falls back to a plain one.")
        .describe(Choice.ENCHANTED_ONLY, "Never eats a plain apple.");
    private final BoolSetting absorption = new BoolSetting("Absorption",
        "Eats to keep the absorption effect topped up.", true)
        .unless(always);
    private final BoolSetting regeneration = new BoolSetting("Regeneration",
        "Eats to keep the regeneration effect topped up.", false)
        .unless(always);
    private final BoolSetting fireResistance = new BoolSetting("Fire resistance",
        "Eats to keep the fire resistance effect topped up. Only an enchanted apple gives it.", false)
        .visibleWhen(() -> !always.isOn() && !choice.is(Choice.PLAIN_ONLY));
    private final NumberSetting expiry = new NumberSetting("Expiry",
        "Starts eating this many ticks before an effect runs out.", 60, 0, 200, 10, " ticks")
        .unless(always);
    private final BoolSetting hold = new BoolSetting("Keep held",
        "Stays on the apple slot between bites.", true);
    private final BoolSetting pauseCombat = new BoolSetting("Pause combat",
        "Holds the combat modules back whilst you eat.", true);

    private final Feeding feeding = new Feeding(this, () -> true, pauseCombat::isOn);
    private boolean needsEnchanted;
    private int lastMeal = Integer.MIN_VALUE / 2;

    public AutoGap() {
        super("AutoGap", "Eats golden apples to hold your buffs and your health up.", Category.PLAYER);
        addSettings(always, health, choice, absorption, regeneration, fireResistance, expiry, hold,
            pauseCombat);
        searchTags("gapple", "golden apple", "egap");
    }

    @Override
    public String getSuffix() {
        return feeding.isActive() ? "eating" : null;
    }

    @Override
    protected void onDisable() {
        feeding.stop();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (Hands.tied()) {
            feeding.stop();
            return;
        }
        // A respawn or a fresh world starts the tick count again at zero.
        if (mc.player.tickCount < lastMeal) {
            lastMeal = Integer.MIN_VALUE / 2;
        }
        if (feeding.waiting()) {
            return;
        }
        if (feeding.isActive()) {
            continueEating();
            return;
        }
        if (handBusy()) {
            return;
        }
        if (!InventoryUtil.inventoryFree()) {
            return;
        }

        needsEnchanted = false;
        if (!wantsApple()) {
            feeding.loan().giveBack();
            return;
        }
        // Health that is actually low is never made to wait. A buff top up is.
        if (!always.isOn() && !EntityUtil.healthAtOrBelow(health.getValue())
            && mc.player.tickCount - lastMeal < MEAL_GAP) {
            return;
        }
        int slot = findApple();
        if (slot == -1) {
            feeding.loan().giveBack();
            return;
        }
        feeding.begin(slot);
    }

    private boolean handBusy() {
        return Feeding.busy(this) || Feeding.bottleLanding() || Modules.packing();
    }

    private boolean wantsApple() {
        if (always.isOn() || EntityUtil.healthAtOrBelow(health.getValue())) {
            return true;
        }
        if (absorption.isOn() && isRunningOut(MobEffects.ABSORPTION)) {
            return true;
        }
        if (fireResistance.isOn() && !choice.is(Choice.PLAIN_ONLY)
            && isRunningOut(MobEffects.FIRE_RESISTANCE)) {
            needsEnchanted = true;
            return true;
        }
        return regeneration.isOn() && isRunningOut(MobEffects.REGENERATION);
    }

    private boolean isRunningOut(Holder<MobEffect> effect) {
        MobEffectInstance instance = mc.player.getEffect(effect);
        return instance == null || instance.getDuration() <= expiry.getInt();
    }

    private void continueEating() {
        ItemStack held = mc.player.getInventory().getSelectedItem();
        if (mc.gui.screen() != null || !isApple(held)) {
            feeding.stop();
            return;
        }
        if (!feeding.tick()) {
            feeding.finish(SETTLE_TICKS, hold.isOn() && !feeding.loan().isLent());
            lastMeal = mc.player.tickCount;
        }
    }

    private int findApple() {
        int plain = -1;
        int enchanted = -1;
        for (int i = 0; i < InventoryUtil.WHOLE_INVENTORY; i++) {
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
        if (choice.is(Choice.PLAIN_ONLY)) {
            return plain;
        }
        if (choice.is(Choice.ENCHANTED_FIRST)) {
            return enchanted != -1 ? enchanted : plain;
        }
        return plain != -1 ? plain : enchanted;
    }

    private boolean isApple(ItemStack stack) {
        if (needsEnchanted || choice.is(Choice.ENCHANTED_ONLY)) {
            return stack.is(Items.ENCHANTED_GOLDEN_APPLE);
        }
        if (choice.is(Choice.PLAIN_ONLY)) {
            return stack.is(Items.GOLDEN_APPLE);
        }
        return ItemUtil.isGoldenApple(stack);
    }
}
