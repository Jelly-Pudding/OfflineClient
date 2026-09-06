package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.UseHold;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

// Eats golden apples to hold buffs up.
// Only the enchanted apple grants fire resistance.
public final class AutoGap extends Module {

    // Ticks to wait after a bite whilst the effects land. A laggy server needs a moment.
    private static final int SETTLE_TICKS = 20;

    // Minimum ticks between two apples so a slow server has time to register one.
    private static final int MEAL_GAP = 100;

    public enum Choice { PLAIN_FIRST, PLAIN_ONLY, ENCHANTED_FIRST, ENCHANTED_ONLY }

    private final BoolSetting always = new BoolSetting("Always",
        "Eats apples back to back for as long as the module is on whatever your health or effects.",
        false);
    private final NumberSetting health = new NumberSetting("Health",
        "Eat at or below this many hearts. Zero turns it off.", 7, 0, 10, 0.5, " hearts")
        .min(0).max(20)
        .unless(always);
    private final EnumSetting<Choice> choice = new EnumSetting<>("Choice",
        "Which apple to reach for.", Choice.PLAIN_FIRST)
        .describe(Choice.PLAIN_FIRST, "Eats a plain apple and only takes an enchanted one when there is none.")
        .describe(Choice.PLAIN_ONLY, "Never eats an enchanted apple.")
        .describe(Choice.ENCHANTED_FIRST, "Eats an enchanted apple and falls back to a plain one.")
        .describe(Choice.ENCHANTED_ONLY, "Never eats a plain apple.");
    private final BoolSetting absorption = new BoolSetting("Absorption",
        "Eat to keep the absorption effect topped up.", true)
        .unless(always);
    private final BoolSetting regeneration = new BoolSetting("Regeneration",
        "Eat to keep the regeneration effect topped up.", false)
        .unless(always);
    private final BoolSetting fireResistance = new BoolSetting("Fire resistance",
        "Eat to keep the fire resistance effect topped up. Only an enchanted apple gives it.", false)
        .visibleWhen(() -> !always.isOn() && !choice.is(Choice.PLAIN_ONLY));
    private final NumberSetting expiry = new NumberSetting("Expiry",
        "Start eating this many ticks before an effect runs out.", 60, 0, 200, 10, " ticks")
        .min(0)
        .unless(always);
    private final BoolSetting hold = new BoolSetting("Keep held",
        "Stay on the apple slot between bites.", true);
    private final BoolSetting pauseCombat = new BoolSetting("Pause combat",
        "Holds the combat modules back whilst you eat.", true);
    private final BoolSetting noSlowdown = new BoolSetting("No slowdown",
        "Keeps your normal speed whilst an apple goes down.", false);

    private boolean eating;
    private final UseHold use = new UseHold();
    private boolean needsEnchanted;
    private int settle;
    private int lastMeal = Integer.MIN_VALUE / 2;
    private final InventoryUtil.HotbarLoan loan = new InventoryUtil.HotbarLoan();

    public AutoGap() {
        super("AutoGap", "Eats golden apples to hold your buffs and your health up.", Category.PLAYER);
        addSettings(always, health, choice, absorption, regeneration, fireResistance, expiry, hold,
            pauseCombat, noSlowdown);
        searchTags("gapple", "golden apple", "egap");
    }

    public boolean isEating() {
        return isEnabled() && eating && pauseCombat.isOn();
    }

    public boolean isBusy() {
        return isEnabled() && eating;
    }

    // Read by LocalPlayerMixin to stop the apple slowing you. Manual bites count too.
    public boolean suppressesSlowdown() {
        if (!isEnabled() || !noSlowdown.isOn() || mc.player == null || !mc.player.isUsingItem()) {
            return false;
        }
        ItemStack using = mc.player.getUseItem();
        return using.is(Items.GOLDEN_APPLE) || using.is(Items.ENCHANTED_GOLDEN_APPLE);
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
        // A fresh world hands you a player whose tick count starts again at zero.
        if (mc.player.tickCount < lastMeal) {
            lastMeal = Integer.MIN_VALUE / 2;
        }
        if (mc.player.isDeadOrDying()) {
            // Respawn rebuilds the inventory and restarts the tick count.
            loan.forget();
            stopEating();
            settle = 0;
            lastMeal = Integer.MIN_VALUE / 2;
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
        if (!InventoryUtil.inventoryFree()) {
            return;
        }

        needsEnchanted = false;
        if (!wantsApple()) {
            loan.giveBack();
            return;
        }
        // Health that is actually low is never made to wait. A buff top up is.
        if (!always.isOn() && !EntityUtil.healthAtOrBelow(health.getValue())
            && mc.player.tickCount - lastMeal < MEAL_GAP) {
            return;
        }
        int slot = findApple();
        if (slot == -1) {
            loan.giveBack();
            return;
        }
        beginEating(slot);
    }

    private boolean handBusy() {
        return Modules.feeding(this);
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

    private void beginEating(int slot) {
        if (!loan.select(slot)) {
            return;
        }
        eating = true;
        use.begin();
    }

    private void continueEating() {
        ItemStack held = mc.player.getInventory().getSelectedItem();
        if (mc.gui.screen() != null || !isApple(held)) {
            stopEating();
            return;
        }
        if (!use.tick()) {
            finishBite();
        }
    }

    private void finishBite() {
        boolean keep = hold.isOn() && !loan.isLent();
        use.release();
        eating = false;
        settle = SETTLE_TICKS;
        lastMeal = mc.player.tickCount;
        if (!keep) {
            loan.giveBack();
        }
    }

    private void stopEating() {
        if (eating) {
            use.release();
            eating = false;
        }
        loan.giveBack();
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
        return stack.is(Items.GOLDEN_APPLE) || stack.is(Items.ENCHANTED_GOLDEN_APPLE);
    }
}
