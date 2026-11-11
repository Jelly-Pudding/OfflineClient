package com.jellypudding.offlineclient.hud.elements;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.hud.RowsElement;
import com.jellypudding.offlineclient.hud.TextRow;
import com.jellypudding.offlineclient.render.ClientFonts;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.Mounts;
import net.minecraft.client.gui.Font;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// The figures of what you ride worked out the way Nametags works them out above every mount.
public final class MountElement extends RowsElement {

    // A fast horse that has lost a few hearts stands in whilst the editor is open and you ride
    // nothing. Each figure carries roughly where it sits against wild horses.
    private static final List<Mounts.Figure> SAMPLE = List.of(new Mounts.Figure(Mounts.Measure.HEALTH, "30", 1),
        new Mounts.Figure(Mounts.Measure.SPEED, "12.0 m/s", 0.73f),
        new Mounts.Figure(Mounts.Measure.JUMP, "4.1 m", 0.71f));
    private static final float SAMPLE_HEALTH = 24;
    private static final float SAMPLE_HEALTH_SHARE = 0.8f;

    private final BoolSetting health = new BoolSetting("Mount health",
        "Writes its health and the most it can have.", true);
    private final BoolSetting speed = new BoolSetting("Mount speed",
        "Writes its top speed on flat ground.", true);
    private final BoolSetting jump = new BoolSetting("Mount jump",
        "Writes how high a full jump lifts it.", true);
    private final BoolSetting strength = new BoolSetting("Mount strength",
        "Writes a llama's strength. A stronger llama carries more.", true);
    private final ColorSetting color = labelColor("Mount colour",
        "Colour of the row names. Each figure runs from red for the worst wild horse to green for the best.");
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
        return isActive() && !rows(ClientFonts.hud()).isEmpty();
    }

    // What you ride whilst it is a mount. Null otherwise.
    private static LivingEntity mount() {
        LocalPlayer player = OfflineClient.MC.player;
        if (player != null && player.getVehicle() instanceof LivingEntity ridden && Mounts.isMount(ridden)) {
            return ridden;
        }
        return null;
    }

    @Override
    protected List<TextRow> rows(Font font) {
        LivingEntity mount = mount();
        if (mount != null) {
            return rowsOf(font, Mounts.figures(mount), EntityUtil.totalHealth(mount), EntityUtil.healthShare(mount));
        }
        if (placeholder.isOn() && OfflineClient.MC.player != null) {
            return List.of(TextRow.of(font, "Not riding", placeholderColor.getColor()));
        }
        return editing() ? rowsOf(font, SAMPLE, SAMPLE_HEALTH, SAMPLE_HEALTH_SHARE) : List.of();
    }

    // The figures whose switches are on with the mount's health now and its share of the most.
    private List<TextRow> rowsOf(Font font, List<Mounts.Figure> figures, float healthNow, float healthShare) {
        List<TextRow> rows = new ArrayList<>(figures.size());
        for (Mounts.Figure figure : figures) {
            if (shows(figure.measure())) {
                rows.add(rowOf(font, figure, healthNow, healthShare, rows.size()));
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
    // colour of how that compares with wild horses. A rainbow name colour runs down the rows.
    private TextRow rowOf(Font font, Mounts.Figure figure, float healthNow, float healthShare, int row) {
        String label = labelOf(figure.measure()) + " ";
        int name = color.rowColor(row);
        int tint = ColorUtil.redToGreen(figure.share());
        if (figure.measure() == Mounts.Measure.HEALTH) {
            String now = String.format(Locale.ROOT, "%.0f", healthNow);
            return TextRow.of(font, List.of(label, now, "/" + figure.value()),
                List.of(name, ColorUtil.redToGreen(healthShare), tint));
        }
        return TextRow.of(font, List.of(label, figure.value()), List.of(name, tint));
    }
}
