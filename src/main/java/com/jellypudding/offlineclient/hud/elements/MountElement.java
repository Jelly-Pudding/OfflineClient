package com.jellypudding.offlineclient.hud.elements;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.hud.HudElement;
import com.jellypudding.offlineclient.hud.TextRow;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.Mounts;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// The figures of what you ride worked out the way Nametags works them out above every
// mount. Each row lines up with the side the element is anchored to.
public final class MountElement extends HudElement {

    private final BoolSetting health = new BoolSetting("Mount health",
        "Writes its health and the most it can have.", true);
    private final BoolSetting speed = new BoolSetting("Mount speed",
        "Writes its top speed on flat ground.", true);
    private final BoolSetting jump = new BoolSetting("Mount jump",
        "Writes how high a full jump lifts it.", true);
    private final BoolSetting strength = new BoolSetting("Mount strength",
        "Writes a llama's strength. A stronger llama carries more.", true);
    private final ColorSetting color = new ColorSetting("Mount colour",
        "Colour of the row names. Each figure runs from red for the worst wild horse to green for the best.",
        190, 0.15f, 0.85f, false);
    private final BoolSetting placeholder = new BoolSetting("Mount placeholder",
        "Writes Not riding whilst you ride nothing.", false);
    private final ColorSetting placeholderColor = new ColorSetting("Mount placeholder colour",
        "Colour of that line.", 0, 0, 0.6f, false).under(placeholder);

    public MountElement() {
        super("Mount", "Health and top speed and jump height of what you ride.", false, 100, 75);
        add(health, speed, jump, strength, color, placeholder, placeholderColor);
    }

    @Override
    public boolean visible() {
        return isActive() && !rows(OfflineClient.MC.font).isEmpty();
    }

    // What you ride whilst it is a mount. Null otherwise.
    private static LivingEntity mount() {
        LocalPlayer player = OfflineClient.MC.player;
        if (player != null && player.getVehicle() instanceof LivingEntity ridden && Mounts.isMount(ridden)) {
            return ridden;
        }
        return null;
    }

    private List<TextRow> rows(Font font) {
        List<TextRow> rows = new ArrayList<>(Mounts.Measure.values().length);
        LivingEntity mount = mount();
        if (mount == null) {
            if (placeholder.isOn() && OfflineClient.MC.player != null) {
                rows.add(TextRow.of(font, List.of("Not riding"), List.of(placeholderColor.getColor())));
            }
            return rows;
        }
        for (Mounts.Figure figure : Mounts.figures(mount)) {
            if (shows(figure.measure())) {
                rows.add(rowOf(font, mount, figure));
            }
        }
        return rows;
    }

    private boolean shows(Mounts.Measure measure) {
        return switch (measure) {
            case HEALTH -> health.isOn();
            case SPEED -> speed.isOn();
            case JUMP -> jump.isOn();
            case STRENGTH -> strength.isOn();
        };
    }

    private static String labelOf(Mounts.Measure measure) {
        return switch (measure) {
            case HEALTH -> "Health";
            case SPEED -> "Speed";
            case JUMP -> "Jump";
            case STRENGTH -> "Strength";
        };
    }

    // Health now in the colour of how hurt the mount is and the most it can have in the
    // colour of how that compares with wild horses.
    private TextRow rowOf(Font font, LivingEntity mount, Mounts.Figure figure) {
        String label = labelOf(figure.measure()) + " ";
        int tint = ColorUtil.redToGreen(figure.share());
        if (figure.measure() == Mounts.Measure.HEALTH) {
            String now = String.format(Locale.ROOT, "%.0f", EntityUtil.totalHealth(mount));
            return TextRow.of(font, List.of(label, now, "/" + figure.value()),
                List.of(color.getColor(), ColorUtil.health(EntityUtil.healthShare(mount)), tint));
        }
        return TextRow.of(font, List.of(label, figure.value()), List.of(color.getColor(), tint));
    }

    @Override
    public void render(GuiGraphicsExtractor context, Font font) {
        TextRow.draw(context, font, rows(font), alignment());
    }

    @Override
    public int width(Font font) {
        return TextRow.widest(rows(font));
    }

    @Override
    public int height(Font font) {
        return Math.max(TextRow.LINE, rows(font).size() * TextRow.LINE);
    }
}
