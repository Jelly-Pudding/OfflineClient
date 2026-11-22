package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffectUtil;

public final class EffectText {

    // A higher level is written in digits.
    private static final String[] LEVELS = {"", " II", " III", " IV", " V", " VI"};

    private EffectText() {
    }

    public static String plainName(MobEffectInstance effect) {
        return effect.getEffect().value().getDisplayName().getString();
    }

    public static String name(MobEffectInstance effect) {
        int level = effect.getAmplifier();
        return plainName(effect) + (level < LEVELS.length ? LEVELS[level] : " " + (level + 1));
    }

    public static String timeLeft(MobEffectInstance effect) {
        ClientLevel level = OfflineClient.MC.level;
        float tickRate = level == null ? SharedConstants.TICKS_PER_SECOND : level.tickRateManager().tickrate();
        return MobEffectUtil.formatDuration(effect, 1, tickRate).getString();
    }
}
