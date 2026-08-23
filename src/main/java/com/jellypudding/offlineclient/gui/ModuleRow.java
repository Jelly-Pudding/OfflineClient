package com.jellypudding.offlineclient.gui;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.RenderUtil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

// One module entry inside a panel.
public final class ModuleRow {

    // The chevron hit area on the right of a row.
    private static final int ARROW_ZONE = 14;

    private final Module module;
    private final SettingWidget.Host host;
    private boolean expanded;

    // Position and size assigned by the panel each frame.
    private int x;
    private int y;
    private int width = GuiTheme.PANEL_WIDTH;

    private boolean placed;

    // Dragging still uses the real mouse position even outside the panel.
    private boolean hoverActive = true;

    private float hoverFade;
    private long lastFrame;

    private final SettingWidget.Drag drag = new SettingWidget.Drag();

    public ModuleRow(Module module, SettingWidget.Host host) {
        this.module = module;
        this.host = host;
    }

    public Module getModule() {
        return module;
    }

    public boolean isExpanded() {
        return expanded;
    }

    public void setExpanded(boolean expanded) {
        this.expanded = expanded;
    }

    public int getHeight() {
        if (!expanded) {
            return GuiTheme.ROW_HEIGHT;
        }
        return GuiTheme.ROW_HEIGHT + SettingWidget.blockHeight(module);
    }

    // Called for every row each frame including the clipped ones.
    public void place(int x, int y, int width, int mouseX, int mouseY, boolean hoverAllowed) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.hoverActive = hoverAllowed;
        this.placed = true;
        fadeHover(hoverAllowed
            && SettingWidget.isOver(mouseX, mouseY, x, y, width, GuiTheme.ROW_HEIGHT));
    }

    // Draws the row where place last put it.
    public void render(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        Font font = OfflineClient.MC.font;
        int w = width;
        // The last pixel of the row pitch is the rule between rows.
        int h = GuiTheme.ROW_HEIGHT - 1;

        boolean hovered = hoverActive
            && SettingWidget.isOver(mouseX, mouseY, x, y, w, GuiTheme.ROW_HEIGHT);

        boolean on = module.isEnabled();
        int rest = on ? GuiTheme.accentOn(GuiTheme.BG_ROW, 0.26f) : GuiTheme.BG_ROW;
        int lit = on ? GuiTheme.accentOn(GuiTheme.BG_ROW, 0.46f) : GuiTheme.BG_ROW_HOVER;
        context.fill(x, y, x + w, y + h, ColorUtil.lerp(rest, lit, hoverFade));
        context.fill(x, y + h, x + w, y + GuiTheme.ROW_HEIGHT, GuiTheme.RULE);

        context.guiRenderState.up();
        if (on) {
            context.fill(x, y, x + 2, y + h, GuiTheme.accent());
        }
        int textColor = on ? GuiTheme.TEXT : GuiTheme.TEXT_DIM;
        // A fixed inset keeps the name still when the accent bar appears.
        int nameRoom = w - 7 - ARROW_ZONE;
        context.text(font, SettingWidget.trimEnd(font, module.getName(), nameRoom), x + 7,
            GuiTheme.textY(y, h), textColor, false);
        RenderUtil.chevron(context, x + w - 11, y + (h - 3) / 2, !expanded,
            hovered ? GuiTheme.TEXT : GuiTheme.TEXT_FAINT);

        if (hovered) {
            host.setTooltip(module.getDescription());
        }

        if (expanded) {
            drag.follow(SettingWidget.blockContentX(x), SettingWidget.blockContentWidth(w), mouseX);
            SettingWidget.renderBlock(context, font, module, x, y + GuiTheme.ROW_HEIGHT, w,
                mouseX, mouseY, hoverActive, host);
        }
    }

    private void fadeHover(boolean hovered) {
        long now = System.currentTimeMillis();
        float delta = lastFrame == 0 ? 0.05f : Math.min(0.1f, (now - lastFrame) / 1000f);
        lastFrame = now;
        float step = delta * 9f;
        hoverFade = hovered ? Math.min(1f, hoverFade + step) : Math.max(0f, hoverFade - step);
    }

    public boolean mouseClicked(double mx, double my, int button) {
        if (!placed) {
            return false;
        }
        int w = width;

        if (SettingWidget.isOver(mx, my, x, y, w, GuiTheme.ROW_HEIGHT)) {
            if (button != 0 && button != 1) {
                return true;
            }
            if (button == 1 || mx >= x + w - ARROW_ZONE || !module.isTogglable()) {
                expanded = !expanded;
            } else {
                module.toggle();
                OfflineClient.INSTANCE.getConfigManager().saveSoon();
            }
            return true;
        }

        if (!expanded) {
            return false;
        }
        return SettingWidget.clickBlock(module, mx, my, x, y + GuiTheme.ROW_HEIGHT, w,
            button, host, drag);
    }

    public void mouseReleased() {
        drag.release();
    }
}
