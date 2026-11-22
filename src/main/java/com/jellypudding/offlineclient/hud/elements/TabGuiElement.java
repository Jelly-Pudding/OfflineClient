package com.jellypudding.offlineclient.hud.elements;

import com.jellypudding.offlineclient.hud.HudElement;
import com.jellypudding.offlineclient.modules.misc.TabGui;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.List;

public final class TabGuiElement extends HudElement {

    private static final int ROW = 11;
    private static final int PAD = 3;
    private static final int MIN_WIDTH = 60;
    private static final int CURSOR = 0x30FFFFFF;

    public TabGuiElement() {
        super("TabGUI", "A module list you steer with the arrow keys. Its colours are in the TabGUI module.",
            TabGui.class, 0, 0, Background.ON);
    }

    @Override
    public boolean visible() {
        return !rows().isEmpty();
    }

    private static List<TabGui.Row> rows() {
        TabGui tabGui = Modules.active(TabGui.class);
        return tabGui == null ? List.of() : tabGui.rows();
    }

    @Override
    public void render(GuiGraphicsExtractor context, Font font) {
        TabGui tabGui = Modules.active(TabGui.class);
        if (tabGui == null) {
            return;
        }
        List<TabGui.Row> rows = tabGui.rows();
        int wide = widthOf(rows, font);
        for (int i = 0; i < rows.size(); i++) {
            int top = PAD + i * ROW;
            if (i == tabGui.cursor()) {
                context.fill(0, top - 1, wide, top + ROW - 1, CURSOR);
            }
            write(context, font, rows.get(i).name(), PAD, top, rows.get(i).colour());
        }
    }

    @Override
    public int width(Font font) {
        return widthOf(rows(), font);
    }

    @Override
    public int height(Font font) {
        return rows().size() * ROW + PAD * 2;
    }

    private static int widthOf(List<TabGui.Row> rows, Font font) {
        int wide = MIN_WIDTH;
        for (TabGui.Row row : rows) {
            wide = Math.max(wide, font.width(row.name()) + PAD * 2 + ROW);
        }
        return wide;
    }
}
