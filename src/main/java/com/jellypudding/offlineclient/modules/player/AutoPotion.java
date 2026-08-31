package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.UseHold;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;

import java.util.List;
import java.util.function.Predicate;

/**
 * Drinks a potion when health drops or the player catches fire or an effect
 * is about to run out. Bottles anywhere in the inventory count.
 */
public final class AutoPotion extends Module {

    // Ticks to wait after a drink whilst the effect and the health land.
    private static final int SETTLE_TICKS = 10;

    private static final int SECOND = 20;

    private final NumberSetting health = new NumberSetting("Health",
        "Drink a healing potion at or below this many hearts. Zero turns it off.",
        7, 0, 10, 0.5, " hearts").min(0).max(20);
    private final RegistryListSetting<Item> potions = new RegistryListSetting<>("Potions",
        "Bottles that may be drunk. Click to pick them.", BuiltInRegistries.ITEM,
        List.of(Items.POTION));
    private final BoolSetting fire = new BoolSetting("Fire resistance",
        "Drink fire resistance whilst you are burning.", true);
    private final BoolSetting topUp = new BoolSetting("Top up",
        "Drink again when an effect you already have is about to run out.", true);
    private final NumberSetting topUpAt = new NumberSetting("Top up at",
        "Seconds of effect left before topping up.", 10, 1, 60, 1, "s")
        .min(1).under(topUp);

    private boolean drinking;
    private final UseHold use = new UseHold();
    private int settle;
    private final InventoryUtil.HotbarLoan loan = new InventoryUtil.HotbarLoan();

    public AutoPotion() {
        super("AutoPotion", "Drinks a potion when you are hurt or burning or running low.",
            Category.PLAYER);
        addSettings(health, potions, fire, topUp, topUpAt);
        searchTags("potion", "auto drink", "strength", "fire resistance");
    }

    public boolean isDrinking() {
        return isEnabled() && drinking;
    }

    @Override
    public String getSuffix() {
        return drinking ? "drinking" : null;
    }

    @Override
    protected void onDisable() {
        stopDrinking();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.player.isSpectator()) {
            stopDrinking();
            return;
        }
        if (mc.player.isDeadOrDying()) {
            // Respawn rebuilds the inventory.
            loan.forget();
            stopDrinking();
            settle = 0;
            return;
        }
        if (settle > 0) {
            settle--;
            return;
        }

        if (drinking) {
            continueDrinking();
            return;
        }
        if (!InventoryUtil.inventoryFree()) {
            return;
        }
        if (handBusy()) {
            return;
        }

        int slot = findWanted();
        if (slot == -1) {
            return;
        }
        beginDrinking(slot);
    }

    private boolean handBusy() {
        return Modules.feeding(this);
    }

    private int findWanted() {
        if (EntityUtil.healthAtOrBelow(health.getValue())) {
            int slot = findPotion(effect -> effect == MobEffects.INSTANT_HEALTH.value()
                || effect == MobEffects.REGENERATION.value());
            if (slot != -1) {
                return slot;
            }
        }
        if (fire.isOn() && mc.player.isOnFire()
            && !mc.player.hasEffect(MobEffects.FIRE_RESISTANCE)) {
            int slot = findPotion(effect -> effect == MobEffects.FIRE_RESISTANCE.value());
            if (slot != -1) {
                return slot;
            }
        }
        if (!topUp.isOn()) {
            return -1;
        }
        int threshold = topUpAt.getInt() * SECOND;
        for (MobEffectInstance active : mc.player.getActiveEffects()) {
            if (!active.endsWithin(threshold)) {
                continue;
            }
            MobEffect wanted = active.getEffect().value();
            int slot = findPotion(effect -> effect == wanted);
            if (slot != -1) {
                return slot;
            }
        }
        return -1;
    }

    private int findPotion(Predicate<MobEffect> wanted) {
        for (int i = 0; i < InventoryUtil.WHOLE_INVENTORY; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!isPotion(stack)) {
                continue;
            }
            PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
            for (MobEffectInstance effect : contents.getAllEffects()) {
                if (wanted.test(effect.getEffect().value())) {
                    return i;
                }
            }
        }
        return -1;
    }

    // Splash and lingering bottles are thrown and not drunk.
    private boolean isPotion(ItemStack stack) {
        return potions.contains(stack.getItem())
            && stack.has(DataComponents.POTION_CONTENTS)
            && stack.has(DataComponents.CONSUMABLE);
    }

    private void beginDrinking(int slot) {
        if (!loan.select(slot)) {
            return;
        }
        drinking = true;
        use.begin();
    }

    private void continueDrinking() {
        // A screen swallows the use key.
        if (mc.gui.screen() != null || !isPotion(mc.player.getInventory().getSelectedItem())) {
            stopDrinking();
            return;
        }
        if (!use.tick()) {
            finishDrink();
        }
    }

    private void finishDrink() {
        stopDrinking();
        settle = SETTLE_TICKS;
    }

    private void stopDrinking() {
        if (drinking) {
            drinking = false;
            use.release();
        }
        // A loan whose return was refused earlier gets another go.
        loan.giveBack();
    }
}
