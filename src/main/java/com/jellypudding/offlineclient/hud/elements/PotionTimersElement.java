package com.jellypudding.offlineclient.hud.elements;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.hud.HudElement;
import com.jellypudding.offlineclient.hud.RowCache;
import com.jellypudding.offlineclient.hud.TextRow;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.EffectText;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.ARGB;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

public final class PotionTimersElement extends HudElement {

    public enum Order { SOONEST_FIRST, LONGEST_FIRST, STRONGEST_FIRST, HARMFUL_FIRST, HELPFUL_FIRST, NAME }

    private record Line(MobEffectInstance effect, String text) {
    }

    private static final int SECONDS_PER_MINUTE = 60;

    private static final List<MobEffectInstance> SAMPLE = List.of(
        new MobEffectInstance(MobEffects.SPEED, 90 * SharedConstants.TICKS_PER_SECOND, 1),
        new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 8 * SECONDS_PER_MINUTE * SharedConstants.TICKS_PER_SECOND));

    // The game's own effect icons pulse through the last ten seconds of an effect.
    private static final int ENDING_TICKS = 10 * SharedConstants.TICKS_PER_SECOND;
    private static final int PULSE_TICKS = 10;
    private static final float PULSE_DEPTH = 0.6f;

    // A few effects such as Blindness are nearly black. Their rows keep the hue but are never
    // darker than this.
    private static final float LEAST_BRIGHTNESS = 0.6f;

    private static final int ICON_GAP = 2;
    private static final double MIDDLE = 0.5;

    private static final Comparator<MobEffectInstance> NAMES =
        Comparator.comparing(EffectText::plainName, String.CASE_INSENSITIVE_ORDER);
    // A beacon or a conduit keeps topping its effect up. Those sit below the rest and rows never
    // swap on their own.
    private static final Comparator<MobEffectInstance> SOONEST = Comparator
        .comparing(MobEffectInstance::isAmbient)
        .thenComparing(MobEffectInstance::isInfiniteDuration)
        .thenComparingInt(MobEffectInstance::getDuration)
        .thenComparing(NAMES);
    private static final Comparator<MobEffectInstance> LONGEST = Comparator
        .comparing(MobEffectInstance::isAmbient)
        .thenComparing(MobEffectInstance::isInfiniteDuration, Comparator.reverseOrder())
        .thenComparing(MobEffectInstance::getDuration, Comparator.reverseOrder())
        .thenComparing(NAMES);
    private static final Comparator<MobEffectInstance> STRONGEST = Comparator
        .comparingInt(MobEffectInstance::getAmplifier).reversed()
        .thenComparing(SOONEST);
    private static final Comparator<MobEffectInstance> HARMFUL = byKind(MobEffectCategory.HARMFUL,
        MobEffectCategory.NEUTRAL, MobEffectCategory.BENEFICIAL);
    private static final Comparator<MobEffectInstance> HELPFUL = byKind(MobEffectCategory.BENEFICIAL,
        MobEffectCategory.NEUTRAL, MobEffectCategory.HARMFUL);
    private static final Comparator<MobEffectInstance> BY_NAME = NAMES.thenComparing(SOONEST);

    private final EnumSetting<Order> order = new EnumSetting<>("Potion order",
        "How the effects are ordered.", Order.SOONEST_FIRST)
        .describe(Order.SOONEST_FIRST, "The one ending soonest at the top with endless effects and beacon or"
            + " conduit effects last.")
        .describe(Order.LONGEST_FIRST, "The one lasting longest at the top with endless effects first and beacon"
            + " or conduit effects last.")
        .describe(Order.STRONGEST_FIRST, "The highest level at the top and equal levels by time left.")
        .describe(Order.HARMFUL_FIRST, "Harmful effects at the top then neutral and helpful ones each by time left.")
        .describe(Order.HELPFUL_FIRST, "Helpful effects at the top then neutral and harmful ones each by time left.")
        .describe(Order.NAME, "By name from A to Z.");
    private final RegistryListSetting<MobEffect> hidden = new RegistryListSetting<>("Potion hidden effects",
        "Effects left out of the list. Click to pick them.", BuiltInRegistries.MOB_EFFECT, List.of());
    private final BoolSetting hideAmbient = new BoolSetting("Hide ambient",
        "Leaves out the effects a beacon or conduit gives.", false);
    private final BoolSetting ownColours = new BoolSetting("Potion colours",
        "Writes each effect in its own colour.", true);
    private final ColorSetting rowColor = labelColor("Potion row colour",
        "Colour of every row whilst Potion colours is off.").unless(ownColours);
    private final BoolSetting pulse = new BoolSetting("Potion pulse",
        "Fades a row in and out over its last ten seconds like the game's own effect icons.", true);
    private final BoolSetting icons = new BoolSetting("Potion icons",
        "Draws each effect's icon beside its row.", false);
    private final RowCache<Line> lines = new RowCache<>(this::build);

    public PotionTimersElement() {
        super("Potion timers", "The effects on you and how long they last.", false, 0, 78);
        add(order, hidden, hideAmbient, ownColours, rowColor, pulse, icons);
    }

    private static Comparator<MobEffectInstance> byKind(MobEffectCategory... kinds) {
        List<MobEffectCategory> ranked = List.of(kinds);
        return Comparator.<MobEffectInstance>comparingInt(
            effect -> ranked.indexOf(effect.getEffect().value().getCategory())).thenComparing(SOONEST);
    }

    private Comparator<MobEffectInstance> comparator() {
        return switch (order.getValue()) {
            case SOONEST_FIRST -> SOONEST;
            case LONGEST_FIRST -> LONGEST;
            case STRONGEST_FIRST -> STRONGEST;
            case HARMFUL_FIRST -> HARMFUL;
            case HELPFUL_FIRST -> HELPFUL;
            case NAME -> BY_NAME;
        };
    }

    @Override
    public boolean visible() {
        return isActive() && !lines().isEmpty();
    }

    private List<Line> lines() {
        List<Line> built = lines.get();
        return built.isEmpty() && editing() ? linesOf(SAMPLE) : built;
    }

    private List<Line> build() {
        Player player = OfflineClient.MC.player;
        return player == null ? List.of() : linesOf(player.getActiveEffects());
    }

    private List<Line> linesOf(Collection<MobEffectInstance> effects) {
        List<MobEffectInstance> shown = new ArrayList<>();
        for (MobEffectInstance effect : effects) {
            if (!hidden.contains(effect.getEffect().value()) && !(hideAmbient.isOn() && effect.isAmbient())) {
                shown.add(effect);
            }
        }
        shown.sort(comparator());
        List<Line> built = new ArrayList<>(shown.size());
        for (MobEffectInstance effect : shown) {
            built.add(new Line(effect, EffectText.name(effect) + " " + EffectText.timeLeft(effect)));
        }
        return built;
    }

    private List<TextRow> rowsOf(Font font, List<Line> shown) {
        List<TextRow> rows = new ArrayList<>(shown.size());
        for (int i = 0; i < shown.size(); i++) {
            MobEffectInstance effect = shown.get(i).effect();
            int tint = ownColours.isOn() ? readable(effect.getEffect().value().getColor()) : rowColor.rowColor(i);
            rows.add(TextRow.of(font, shown.get(i).text(), ColorUtil.fade(tint, alphaOf(effect))));
        }
        return rows;
    }

    private static int readable(int color) {
        float[] hsv = ColorUtil.hsvOf(color);
        return hsv[2] >= LEAST_BRIGHTNESS ? ARGB.opaque(color) : ColorUtil.hsv(hsv[0], hsv[1], LEAST_BRIGHTNESS);
    }

    // Beacon and conduit effects are topped up before they run out and never pulse.
    private float alphaOf(MobEffectInstance effect) {
        if (!pulse.isOn() || effect.isAmbient() || !effect.endsWithin(ENDING_TICKS)) {
            return 1;
        }
        double phase = effect.getDuration() * 2 * Math.PI / PULSE_TICKS;
        return (float) (1 - PULSE_DEPTH * (1 - Math.cos(phase)) / 2);
    }

    @Override
    public void render(GuiGraphicsExtractor context, Font font) {
        List<Line> shown = lines();
        List<TextRow> rows = rowsOf(font, shown);
        if (!icons.isOn()) {
            TextRow.draw(context, font, rows, alignment());
            return;
        }
        int widest = TextRow.widest(rows);
        boolean after = alignment() > MIDDLE;
        for (int i = 0; i < rows.size(); i++) {
            TextRow row = rows.get(i);
            MobEffectInstance effect = shown.get(i).effect();
            int y = i * TextRow.LINE;
            int left = row.leftIn(widest, alignment()) + (after ? 0 : iconRoom(font));
            int iconLeft = after ? left + row.width() + ICON_GAP : left - iconRoom(font);
            context.blitSprite(RenderPipelines.GUI_TEXTURED, Hud.getMobEffectSprite(effect.getEffect()), iconLeft, y,
                font.lineHeight, font.lineHeight, alphaOf(effect));
            row.draw(context, font, left, y);
        }
    }

    private static int iconRoom(Font font) {
        return font.lineHeight + ICON_GAP;
    }

    @Override
    public int width(Font font) {
        int widest = TextRow.widest(rowsOf(font, lines()));
        return icons.isOn() ? widest + iconRoom(font) : widest;
    }

    @Override
    public int height(Font font) {
        return TextRow.height(font, lines().size());
    }
}
