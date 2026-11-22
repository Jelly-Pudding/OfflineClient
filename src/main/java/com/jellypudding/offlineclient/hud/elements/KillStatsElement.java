package com.jellypudding.offlineclient.hud.elements;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.hud.RowsElement;
import com.jellypudding.offlineclient.hud.TextRow;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.util.KillTracker;
import net.minecraft.client.gui.Font;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class KillStatsElement extends RowsElement {

    private final BoolSetting deaths = new BoolSetting("Show deaths",
        "Writes how many times you have died.", true);
    private final BoolSetting ratio = new BoolSetting("Show kill ratio",
        "Writes your kills for each death.", true);
    private final BoolSetting streaks = new BoolSetting("Show streaks",
        "Writes your kills since you last died and the most you have managed.", true);
    private final ColorSetting color = labelColor("Kill stats colour",
        "Colour of the row names. The numbers stay white.");

    public KillStatsElement() {
        super("Kill stats", "Your kills and deaths on this server this session as the client counts them.", false,
            0, 45);
        add(deaths, ratio, streaks, color);
    }

    @Override
    public boolean visible() {
        return isActive() && OfflineClient.MC.player != null;
    }

    private List<String> lines() {
        KillTracker tracker = KillTracker.INSTANCE;
        List<String> lines = new ArrayList<>(5);
        lines.add(line("Kills", String.valueOf(tracker.kills())));
        if (deaths.isOn()) {
            lines.add(line("Deaths", String.valueOf(tracker.deaths())));
        }
        if (ratio.isOn()) {
            lines.add(line("K/D", String.format(Locale.ROOT, "%.2f", tracker.ratio())));
        }
        if (streaks.isOn()) {
            lines.add(line("Streak", String.valueOf(tracker.streak())));
            lines.add(line("Best streak", String.valueOf(tracker.bestStreak())));
        }
        return lines;
    }

    private static String line(String name, String value) {
        return name + " §f" + value;
    }

    @Override
    protected List<TextRow> rows(Font font) {
        return TextRow.lines(font, lines(), color);
    }
}
