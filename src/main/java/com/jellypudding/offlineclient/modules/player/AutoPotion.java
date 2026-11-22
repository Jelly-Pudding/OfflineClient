package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.Cooldowns;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.Feeding;
import com.jellypudding.offlineclient.util.Hands;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.ItemUtil;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

public final class AutoPotion extends Module {

    private static final int SETTLE_TICKS = 10;

    // An effect still wanted this long after a bottle went for it gets another one. A splash that
    // missed or a bottle the server ignored never empties the bag.
    private static final int RETRY_TICKS = 5 * SharedConstants.TICKS_PER_SECOND;

    private final NumberSetting health = new NumberSetting("Health",
        "Takes a healing potion at or below this many hearts. Zero turns it off.",
        7, 0, 10, 0.5, " hearts");
    private final RegistryListSetting<Item> potions = new RegistryListSetting<>("Potions",
        "Bottles that may be drunk. Click to pick them.", BuiltInRegistries.ITEM,
        List.of(Items.POTION));
    private final BoolSetting splash = new BoolSetting("Splash potions",
        "Throws a splash potion at your feet in place of a drink whenever one gives what is wanted."
            + " Quicker than drinking.", true);
    private final BoolSetting fire = new BoolSetting("Fire resistance",
        "Takes fire resistance whilst you are burning. AutoExtinguish puts you out instead whilst it is on.", true);
    private final RegistryListSetting<MobEffect> keep = new RegistryListSetting<>("Always keep",
        "Effects kept on you all the time. A bottle that gives one you lack is taken at once and Bad Omen"
            + " takes an ominous bottle. Click to pick them.", BuiltInRegistries.MOB_EFFECT, List.of())
        .only(effect -> !effect.isInstantaneous());
    private final BoolSetting topUp = new BoolSetting("Top up",
        "Takes another potion when a helpful effect you already have is about to run out.", true);
    private final NumberSetting topUpAt = new NumberSetting("Top up at",
        "Seconds of effect left before topping up.", 10, 1, 60, 1, "s")
        .under(topUp);

    private final Feeding feeding = new Feeding(this);
    private final Cooldowns<MobEffect> tried = new Cooldowns<>();
    private final Set<MobEffect> told = new HashSet<>();

    public AutoPotion() {
        super("AutoPotion", "Drinks or throws potions to heal you and keep your effects up.",
            Category.PLAYER);
        addSettings(health, potions, splash, fire, keep, topUp, topUpAt);
        searchTags("potion", "auto drink", "strength", "fire resistance", "keep effects", "ominous bottle",
            "bad omen");
    }

    @Override
    public String getSuffix() {
        return feeding.isActive() ? "drinking" : null;
    }

    @Override
    protected void onEnable() {
        tried.clear();
        told.clear();
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
        tried.tick();
        if (feeding.waiting()) {
            return;
        }
        if (feeding.isActive()) {
            continueDrinking();
            return;
        }
        if (!InventoryUtil.inventoryFree()) {
            return;
        }
        // Another feeder has the use key or a bottle thrown at your feet may still bring what is wanted.
        // ShulkerPacker keeps count of every stack.
        if (Feeding.busy(this) || Feeding.bottleLanding() || Modules.packing()) {
            return;
        }
        serveWants();
    }

    // Healing and fire go first. The rest waits whilst you use an item yourself.
    private void serveWants() {
        if (EntityUtil.healthAtOrBelow(health.getValue()) && serve(AutoPotion::heals)) {
            return;
        }
        if (fire.isOn() && !Modules.putsOutFire() && EntityUtil.burnHurts(mc.player)
            && serve(effect -> effect == MobEffects.FIRE_RESISTANCE.value())) {
            return;
        }
        if (mc.player.isUsingItem() || keepUp()) {
            return;
        }
        if (topUp.isOn()) {
            topUpEffects();
        }
    }

    private boolean serve(Predicate<MobEffect> wanted) {
        if (splash.isOn()) {
            int thrown = find(stack -> stack.is(Items.SPLASH_POTION), wanted);
            if (thrown != -1) {
                feeding.throwDown(thrown, SETTLE_TICKS);
                return true;
            }
        }
        int slot = find(this::isPotion, wanted);
        if (slot == -1) {
            return false;
        }
        feeding.begin(slot);
        return true;
    }

    private boolean keepUp() {
        for (MobEffect effect : keep.resolved()) {
            if (has(effect)) {
                told.remove(effect);
                continue;
            }
            if (tried.contains(effect)) {
                continue;
            }
            boolean served = effect == MobEffects.BAD_OMEN.value() ? drinkOminous() : serve(given -> given == effect);
            if (served) {
                told.remove(effect);
                tried.put(effect, RETRY_TICKS);
                return true;
            }
            if (told.add(effect)) {
                ChatUtil.error("You carry nothing that gives " + effect.getDisplayName().getString() + ".");
            }
        }
        return false;
    }

    // Bad Omen turns into Raid Omen in a village and into Trial Omen by a trial spawner. The omen
    // is kept whilst any of the three lasts.
    private boolean has(MobEffect effect) {
        if (effect == MobEffects.BAD_OMEN.value()) {
            return mc.player.hasEffect(MobEffects.BAD_OMEN) || mc.player.hasEffect(MobEffects.RAID_OMEN)
                || mc.player.hasEffect(MobEffects.TRIAL_OMEN);
        }
        return mc.player.hasEffect(BuiltInRegistries.MOB_EFFECT.wrapAsHolder(effect));
    }

    private boolean drinkOminous() {
        int slot = InventoryUtil.findSlot(stack -> stack.is(Items.OMINOUS_BOTTLE), InventoryUtil.WHOLE_INVENTORY);
        if (slot == -1) {
            return false;
        }
        feeding.begin(slot);
        return true;
    }

    private void topUpEffects() {
        int threshold = topUpAt.getInt() * SharedConstants.TICKS_PER_SECOND;
        for (MobEffectInstance active : mc.player.getActiveEffects()) {
            MobEffect wanted = active.getEffect().value();
            if (!wanted.isBeneficial() || !active.endsWithin(threshold)) {
                continue;
            }
            if (!tried.contains(wanted) && serve(effect -> effect == wanted)) {
                tried.put(wanted, RETRY_TICKS);
                return;
            }
        }
    }

    private int find(Predicate<ItemStack> bottle, Predicate<MobEffect> effect) {
        return InventoryUtil.findSlot(stack -> bottle.test(stack) && ItemUtil.carriesEffect(stack, effect),
            InventoryUtil.WHOLE_INVENTORY);
    }

    private static boolean heals(MobEffect effect) {
        return effect == MobEffects.INSTANT_HEALTH.value() || effect == MobEffects.REGENERATION.value();
    }

    private boolean isPotion(ItemStack stack) {
        return potions.contains(stack.getItem())
            && stack.has(DataComponents.POTION_CONTENTS)
            && stack.has(DataComponents.CONSUMABLE);
    }

    private boolean drinkable(ItemStack stack) {
        return isPotion(stack) || stack.is(Items.OMINOUS_BOTTLE);
    }

    private void continueDrinking() {
        // A screen swallows the use key.
        if (mc.gui.screen() != null || !drinkable(mc.player.getInventory().getSelectedItem())) {
            feeding.stop();
            return;
        }
        if (!feeding.tick()) {
            feeding.finish(SETTLE_TICKS, false);
        }
    }
}
