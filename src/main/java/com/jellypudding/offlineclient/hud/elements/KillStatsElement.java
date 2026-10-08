package com.jellypudding.offlineclient.hud.elements;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.hud.HudElement;
import com.jellypudding.offlineclient.hud.TextRow;
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

    private final BoolSetting deaths = new BoolSetting("Show deaths",
        "Write how many times you have died.", true);
    private final BoolSetting ratio = new BoolSetting("Show kill ratio",
        "Write your kills for each death.", true);
    private final BoolSetting streaks = new BoolSetting("Show streaks",
        "Write your kills since you last died and the most you have managed.", true);
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

    private List<TextRow> rows(Font font) {
        return TextRow.lines(font, lines(), color);
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
        return TextRow.height(font, lines().size());
    }
}
