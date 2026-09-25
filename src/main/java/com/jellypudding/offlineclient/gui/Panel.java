package com.jellypudding.offlineclient.gui;

import com.jellypudding.offlineclient.module.Module;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// A draggable column of module rows under a category header.
public final class Panel extends PanelFrame {

    private final List<ModuleRow> rows = new ArrayList<>();
    private final List<ModuleRow> rowsView = Collections.unmodifiableList(rows);
    private final SettingWidget.Host host;

    public Panel(String title, List<Module> modules, SettingWidget.Host host, int x, int y) {
        super(title, x, y, GuiTheme.PANEL_WIDTH);
        this.host = host;
        refresh(modules);
    }

    // Rebuilds the rows and leaves open the ones that were open.
    public void refresh(List<Module> modules) {
        Set<String> open = new HashSet<>();
        for (ModuleRow row : rows) {
            if (row.isExpanded()) {
                open.add(row.getModule().getName());
            }
        }
        rows.clear();
        for (Module module : modules) {
            ModuleRow row = new ModuleRow(module, host);
            row.setExpanded(open.contains(module.getName()));
            rows.add(row);
        }
    }

    public List<ModuleRow> getRows() {
        return rowsView;
    }

    @Override
    protected int contentHeight() {
        return ModuleRow.totalHeight(rows);
    }

    @Override
    protected void renderContent(GuiGraphicsExtractor context, int viewTop, int view,
                                 int rowWidth, int mouseX, int mouseY) {
        ModuleRow.renderAll(context, rows, getX(), viewTop, view, rowWidth, getScrollOffset(), mouseX, mouseY);
    }

    @Override
    protected boolean clickContent(double mx, double my, int button, int viewTop,
                                   int view, int rowWidth) {
        for (ModuleRow row : rows) {
            if (row.mouseClicked(mx, my, button)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void releaseContent() {
        for (ModuleRow row : rows) {
            row.mouseReleased();
        }
    }
}
