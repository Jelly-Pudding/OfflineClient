package com.jellypudding.offlineclient.hud;

import com.jellypudding.offlineclient.gui.GuiTheme;
import com.jellypudding.offlineclient.setting.ColorSetting;
import net.minecraft.client.gui.Font;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class CountRows {

    public record Counted(String name, int count) {
    }

    private static final Comparator<Counted> ORDER = Comparator.comparingInt(Counted::count).reversed()
        .thenComparing(Counted::name, String.CASE_INSENSITIVE_ORDER);

    private CountRows() {
    }

    public static List<Counted> ranked(Map<String, Integer> counts) {
        List<Counted> counted = new ArrayList<>(counts.size());
        counts.forEach((name, count) -> counted.add(new Counted(name, count)));
        counted.sort(ORDER);
        return counted;
    }

    public static List<TextRow> rows(Font font, List<Counted> counted, ColorSetting color) {
        List<TextRow> rows = new ArrayList<>(counted.size());
        for (int row = 0; row < counted.size(); row++) {
            Counted kind = counted.get(row);
            rows.add(TextRow.of(font, List.of(kind.name() + " ", String.valueOf(kind.count())),
                List.of(color.rowColor(row), GuiTheme.HUD_TEXT)));
        }
        return rows;
    }
}
