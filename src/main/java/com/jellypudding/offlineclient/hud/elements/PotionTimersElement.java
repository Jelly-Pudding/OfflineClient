package com.jellypudding.offlineclient.hud.elements;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.hud.HudElement;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class PotionTimersElement extends HudElement {

    public enum Order { TIME, ALPHABETICAL }

    private static final int LINE = 10;
    private static final int TICKS_PER_SECOND = 20;
    private static final int SECONDS_PER_MINUTE = 60;

    // Roman numerals for the levels a potion can reach.
    private static final String[] LEVELS = {"", " II", " III", " IV", " V", " VI"};

    private final EnumSetting<Order> order = new EnumSetting<>("Potion order",
        "How the effects are ordered.", Order.TIME)
        .describe(Order.TIME, "The one running out soonest at the top.")
        .describe(Order.ALPHABETICAL, "By name from A to Z.")
        ;
    private final BoolSetting hideAmbient = new BoolSetting("Hide ambient",
        "Leave out the effects a beacon gives.", false);
    private final BoolSetting ownColours = new BoolSetting("Potion colours",
        "Write each effect in its own colour.", true);

    public PotionTimersElement() {
        super("Potion timers", "The effects on you and how long they last.", false, 0, 78);
        add(order, hideAmbient, ownColours);
    }

    @Override
    public boolean visible() {
        return isActive() && !effects().isEmpty();
    }

    private List<MobEffectInstance> effects() {
        Player player = OfflineClient.MC.player;
        List<MobEffectInstance> active = new ArrayList<>();
        if (player == null) {
            return active;
        }
        for (MobEffectInstance effect : player.getActiveEffects()) {
            if (!hideAmbient.isOn() || !effect.isAmbient()) {
                active.add(effect);
            }
        }
        if (order.is(Order.ALPHABETICAL)) {
            active.sort(Comparator.comparing(PotionTimersElement::nameOf, String.CASE_INSENSITIVE_ORDER));
        } else {
            active.sort(Comparator.comparingInt(MobEffectInstance::getDuration));
        }
        return active;
    }

    private static String nameOf(MobEffectInstance effect) {
        String name = effect.getEffect().value().getDisplayName().getString();
        int level = effect.getAmplifier();
        return level > 0 && level < LEVELS.length ? name + LEVELS[level]
            : level > 0 ? name + " " + (level + 1) : name;
    }

    // Minutes and seconds. An infinite effect has no clock at all.
    private static String timeOf(MobEffectInstance effect) {
        if (effect.isInfiniteDuration()) {
            return "";
        }
        int seconds = effect.getDuration() / TICKS_PER_SECOND;
        return String.format(Locale.ROOT, " %d:%02d",
            seconds / SECONDS_PER_MINUTE, seconds % SECONDS_PER_MINUTE);
    }

    private static String rowOf(MobEffectInstance effect) {
        return nameOf(effect) + timeOf(effect);
    }

    @Override
    public void render(GuiGraphicsExtractor context, Font font) {
        int y = 0;
        for (MobEffectInstance effect : effects()) {
            int tint = ownColours.isOn()
                ? 0xFF000000 | effect.getEffect().value().getColor() : 0xFFECECF4;
            context.text(font, rowOf(effect), 0, y, tint, true);
            y += LINE;
        }
    }

    @Override
    public int width(Font font) {
        int widest = 1;
        for (MobEffectInstance effect : effects()) {
            widest = Math.max(widest, font.width(rowOf(effect)));
        }
        return widest;
    }

    @Override
    public int height(Font font) {
        return Math.max(LINE, effects().size() * LINE);
    }
}
