package com.jellypudding.offlineclient.hud.elements;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.hud.HudElement;
import com.jellypudding.offlineclient.modules.combat.KillAura;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.Locale;

// Whoever you are fighting. KillAura decides first and the crosshair second.
public final class CombatElement extends HudElement {

    private static final int BAR_WIDTH = 80;
    private static final int BAR_HEIGHT = 4;
    private static final int GAP = 2;

    // Hue zero is red and hue one hundred and twenty is green.
    private static final float GREEN_HUE = 120;

    private final NumberSetting range = new NumberSetting("Combat range",
        "How far away someone still counts as a target.", 16, 4, 64, 1, " blocks").min(1);
    private final BoolSetting nearest = new BoolSetting("Fall back to nearest",
        "Show the nearest enemy whilst nothing else is picked.", true);
    private final BoolSetting bar = new BoolSetting("Health bar",
        "Draw a bar under the name.", true);
    private final BoolSetting distance = new BoolSetting("Show distance",
        "Write how far away they are.", true);

    public CombatElement() {
        super("Combat", "Who you are fighting and how hurt they are.", false, 50, 62);
        add(range, nearest, bar, distance);
    }

    @Override
    public boolean visible() {
        return isActive() && target() != null;
    }

    private LivingEntity target() {
        if (OfflineClient.MC.player == null) {
            return null;
        }
        KillAura aura = Modules.active(KillAura.class);
        if (aura != null && aura.getTarget() != null) {
            return aura.getTarget();
        }
        Entity looked = OfflineClient.MC.crosshairPickEntity;
        if (looked instanceof LivingEntity living && living != OfflineClient.MC.player) {
            return living;
        }
        return nearest.isOn() ? EntityUtil.nearestEnemy(range.getValue()) : null;
    }

    private String label(LivingEntity target) {
        String name = target instanceof Player player
            ? EntityUtil.displayNameOf(player) : target.getName().getString();
        String health = String.format(Locale.ROOT, " %.1f", EntityUtil.totalHealth(target));
        if (!distance.isOn()) {
            return name + health;
        }
        return name + health + String.format(Locale.ROOT, " at %.1fm",
            OfflineClient.MC.player.distanceTo(target));
    }

    @Override
    public void render(GuiGraphicsExtractor context, Font font) {
        LivingEntity target = target();
        if (target == null) {
            return;
        }
        context.text(font, label(target), 0, 0, 0xFFECECF4, true);
        if (!bar.isOn()) {
            return;
        }
        float share = share(target);
        int top = font.lineHeight + GAP;
        context.fill(0, top, barWidth(font), top + BAR_HEIGHT, 0xC0202020);
        context.fill(0, top, Math.round(barWidth(font) * share), top + BAR_HEIGHT,
            ColorUtil.hsv(share * GREEN_HUE, 0.9f, 1f));
    }

    private static float share(LivingEntity target) {
        float max = EntityUtil.totalMaxHealth(target);
        return max <= 0 ? 0 : Math.clamp(EntityUtil.totalHealth(target) / max, 0f, 1f);
    }

    private int barWidth(Font font) {
        LivingEntity target = target();
        return target == null ? BAR_WIDTH : Math.max(BAR_WIDTH, font.width(label(target)));
    }

    @Override
    public int width(Font font) {
        LivingEntity target = target();
        int text = target == null ? 1 : font.width(label(target));
        return bar.isOn() ? Math.max(text, barWidth(font)) : Math.max(1, text);
    }

    @Override
    public int height(Font font) {
        return bar.isOn() ? font.lineHeight + GAP + BAR_HEIGHT : font.lineHeight;
    }
}
