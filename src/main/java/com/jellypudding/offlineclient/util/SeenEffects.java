package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.ARGB;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// The potion effects an entity is seen to carry. The server only shares the swirls each
// effect gives off and an effect whose swirls are hidden never shows. Most swirls take the
// colour of their effect and the rest are a kind of particle of their own. Your own effects
// are known in full.
public final class SeenEffects {

    private static final int RGB = 0xFFFFFF;
    private static final int FULL = 255;

    // Built on first use. The effects are fixed once the game has started.
    private static Map<Integer, Holder<MobEffect>> byColour;
    private static Map<ParticleType<?>, Holder<MobEffect>> byKind;

    private SeenEffects() {
    }

    // Each effect once in the order the server lists them.
    public static List<Holder<MobEffect>> of(LivingEntity entity) {
        List<Holder<MobEffect>> seen = new ArrayList<>();
        if (entity == OfflineClient.MC.player) {
            for (MobEffectInstance effect : entity.getActiveEffects()) {
                seen.add(effect.getEffect());
            }
            return seen;
        }
        for (ParticleOptions swirl : entity.getEntityData().get(LivingEntity.DATA_EFFECT_PARTICLES)) {
            Holder<MobEffect> effect = effectOf(swirl);
            if (effect != null && !seen.contains(effect)) {
                seen.add(effect);
            }
        }
        return seen;
    }

    // Null for a swirl no effect gives off.
    private static Holder<MobEffect> effectOf(ParticleOptions swirl) {
        if (byColour == null) {
            index();
        }
        if (swirl instanceof ColorParticleOption colour && colour.getType() == ParticleTypes.ENTITY_EFFECT) {
            return byColour.get(rgb(colour));
        }
        return byKind.get(swirl.getType());
    }

    // Saturation shares its colour with instant health. It comes later and wins because an
    // instant effect never stays long enough to give off swirls.
    private static void index() {
        Map<Integer, Holder<MobEffect>> colours = new HashMap<>();
        Map<ParticleType<?>, Holder<MobEffect>> kinds = new HashMap<>();
        BuiltInRegistries.MOB_EFFECT.listElements().forEach(effect -> {
            ParticleOptions swirl = effect.value().createParticleOptions(new MobEffectInstance(effect));
            if (swirl instanceof ColorParticleOption colour && colour.getType() == ParticleTypes.ENTITY_EFFECT) {
                colours.put(rgb(colour), effect);
            } else {
                kinds.put(swirl.getType(), effect);
            }
        });
        byKind = kinds;
        byColour = colours;
    }

    // The colour without its alpha. A beacon's effects give off fainter swirls.
    private static int rgb(ColorParticleOption colour) {
        return ARGB.color(channel(colour.getRed()), channel(colour.getGreen()), channel(colour.getBlue())) & RGB;
    }

    // The particle keeps each channel as a share of the full value.
    private static int channel(float share) {
        return Math.round(share * FULL);
    }
}
