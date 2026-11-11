package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffectUtil;

// How an effect reads wherever the client writes one.
public final class EffectText {

    // The levels a potion can reach in Roman numerals as the game writes them. A higher level is
    // written in digits.
    private static final String[] LEVELS = {"", " II", " III", " IV", " V", " VI"};

    private EffectText() {
    }

    // The name without its level.
    public static String plainName(MobEffectInstance effect) {
        return effect.getEffect().value().getDisplayName().getString();
    }

    // The name and then the level from II up.
    public static String name(MobEffectInstance effect) {
        int level = effect.getAmplifier();
        return plainName(effect) + (level < LEVELS.length ? LEVELS[level] : " " + (level + 1));
    }

    // Hours and minutes and seconds at the speed the world runs. An endless effect shows the
    // infinity sign.
    public static String timeLeft(MobEffectInstance effect) {
        ClientLevel level = OfflineClient.MC.level;
        float tickRate = level == null ? SharedConstants.TICKS_PER_SECOND : level.tickRateManager().tickrate();
        return MobEffectUtil.formatDuration(effect, 1, tickRate).getString();
    }
}
