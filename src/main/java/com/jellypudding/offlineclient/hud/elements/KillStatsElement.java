package com.jellypudding.offlineclient.hud.elements;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.hud.HudElement;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.util.KillTracker;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// Your kills and deaths on this server. Each row lines up with the side the element is anchored to.
public final class KillStatsElement extends HudElement {

    private static final int LINE = 10;

    private final BoolSetting deaths = new BoolSetting("Show deaths",
        "Write how many times you have died.", true);
    private final BoolSetting ratio = new BoolSetting("Show kill ratio",
        "Write your kills for each death.", true);
    private final BoolSetting streaks = new BoolSetting("Show streaks",
        "Write your kills since you last died and the most you have managed.", true);
    private final ColorSetting color = new ColorSetting("Kill stats colour",
        "Colour of the row names. The numbers stay white.", 190, 0.15f, 0.85f, false);

    public KillStatsElement() {
        super("Kill stats", "Your kills and deaths on this server.", false, 0, 45);
        add(deaths, ratio, streaks, color);
    }

    @Override
    public boolean visible() {
        return isActive() && OfflineClient.MC.player != null;
    }

    private List<String> rows() {
        KillTracker tracker = KillTracker.INSTANCE;
        List<String> rows = new ArrayList<>(5);
        rows.add(row("Kills", String.valueOf(tracker.kills())));
        if (deaths.isOn()) {
            rows.add(row("Deaths", String.valueOf(tracker.deaths())));
        }
        if (ratio.isOn()) {
            rows.add(row("K/D", String.format(Locale.ROOT, "%.2f", tracker.ratio())));
        }
        if (streaks.isOn()) {
            rows.add(row("Streak", String.valueOf(tracker.streak())));
            rows.add(row("Best streak", String.valueOf(tracker.bestStreak())));
        }
        return rows;
    }

    private static String row(String name, String value) {
        return name + " §f" + value;
    }

    @Override
    public void render(GuiGraphicsExtractor context, Font font) {
        int widest = width(font);
        int y = 0;
        for (String row : rows()) {
            int x = (int) Math.round((widest - font.width(row)) * alignment());
            context.text(font, row, x, y, color.getColor(), true);
            y += LINE;
        }
    }

    @Override
    public int width(Font font) {
        int widest = 1;
        for (String row : rows()) {
            widest = Math.max(widest, font.width(row));
        }
        return widest;
    }

    @Override
    public int height(Font font) {
        return rows().size() * LINE;
    }
}
