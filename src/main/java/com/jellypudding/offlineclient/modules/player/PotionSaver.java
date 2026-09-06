package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.phys.Vec3;

import java.util.List;

// Holds the effect timers still whilst the player stands still.
// The count is client side and a server keeps its own.
public final class PotionSaver extends Module {

    // Friction leaves a small drift for a few ticks after the keys go up.
    private static final double STILL = 1.0E-3;

    private final BoolSetting chosenOnly = new BoolSetting("Chosen effects only",
        "Freeze only the effects picked below instead of every effect that does no harm.",
        false);
    private final RegistryListSetting<MobEffect> effects = new RegistryListSetting<>("Effects",
        "The effects to freeze. Click to pick them.", BuiltInRegistries.MOB_EFFECT, List.of(
            MobEffects.STRENGTH.value(), MobEffects.ABSORPTION.value(),
            MobEffects.RESISTANCE.value(), MobEffects.FIRE_RESISTANCE.value(),
            MobEffects.SPEED.value(), MobEffects.HASTE.value(),
            MobEffects.REGENERATION.value(), MobEffects.WATER_BREATHING.value(),
            MobEffects.SATURATION.value(), MobEffects.LUCK.value(),
            MobEffects.SLOW_FALLING.value(), MobEffects.DOLPHINS_GRACE.value(),
            MobEffects.CONDUIT_POWER.value(), MobEffects.HERO_OF_THE_VILLAGE.value()))
        .under(chosenOnly);
    private final BoolSetting harmful = new BoolSetting("Bad effects",
        "Also freeze harmful effects. Poison and wither run out normally when this is off.",
        false).unless(chosenOnly);
    private final BoolSetting onlyStill = new BoolSetting("Only whilst still",
        "Freeze only whilst you stand still. Off freezes the timers wherever you go.", true);
    private final BoolSetting inAir = new BoolSetting("In the air",
        "Keep freezing whilst you are falling or swimming.", false).under(onlyStill);

    public PotionSaver() {
        super("PotionSaver", "Stops your effect timers whilst you stand still.",
            Category.PLAYER);
        addSettings(chosenOnly, effects, harmful, onlyStill, inAir);
        searchTags("potion saver", "effect timer", "freeze effects");
    }

    // Called from the effect tick for every living entity. A single player world runs
    // that tick on the server thread where the client effect map must not be read.
    public boolean shouldFreeze(MobEffectInstance effect) {
        if (!isEnabled() || !mc.isSameThread() || !inGame()) {
            return false;
        }
        // Other entities carry their own instances of the same effect.
        if (mc.player.getActiveEffectsMap().get(effect.getEffect()) != effect) {
            return false;
        }
        if (!wanted(effect.getEffect().value())) {
            return false;
        }
        return !onlyStill.isOn() || standingStill();
    }

    private boolean wanted(MobEffect effect) {
        if (chosenOnly.isOn()) {
            return effects.contains(effect);
        }
        return harmful.isOn() || effect.getCategory() != MobEffectCategory.HARMFUL;
    }

    private boolean standingStill() {
        if (!inAir.isOn() && !mc.player.onGround()) {
            return false;
        }
        Vec3 motion = mc.player.getDeltaMovement();
        return Math.abs(motion.x) < STILL && Math.abs(motion.z) < STILL;
    }
}
