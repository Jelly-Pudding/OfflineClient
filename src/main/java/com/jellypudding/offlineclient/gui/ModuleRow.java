package com.jellypudding.offlineclient.gui;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.RenderUtil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.List;

// One module entry inside a panel.
public final class ModuleRow {

    // The chevron hit area on the right of a row.
    private static final int ARROW_ZONE = 14;
    // The star sits beside it and shows whilst starred or hovered.
    private static final int STAR_ZONE = 12;

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

    public static int totalHeight(List<ModuleRow> rows) {
        int total = 0;
        for (ModuleRow row : rows) {
            total += row.getHeight();
        }
        return total;
    }

    // Lays out a scrolled column of rows and draws the ones inside the view. A slider
    // being dragged still gets the real pointer position.
    public static void renderAll(GuiGraphicsExtractor context, List<ModuleRow> rows, int x, int viewTop,
                                 int view, int width, int offset, int mouseX, int mouseY) {
        boolean inView = SettingWidget.isOver(mouseX, mouseY, x, viewTop, width, view);
        int rowY = viewTop - offset;
        for (ModuleRow row : rows) {
            int rowH = row.getHeight();
            row.place(x, rowY, width, mouseX, mouseY, inView);
            if (rowY + rowH > viewTop && rowY < viewTop + view) {
                row.render(context, mouseX, mouseY);
            }
            rowY += rowH;
        }
    }

    public int getHeight() {
        if (!expanded) {
            return GuiTheme.ROW_HEIGHT;
        }
        return GuiTheme.ROW_HEIGHT + SettingWidget.blockHeight(module);
    }

    public void place(int x, int y, int width, int mouseX, int mouseY, boolean hoverAllowed) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.hoverActive = hoverAllowed;
        this.placed = true;
        fadeHover(hoverAllowed
            && SettingWidget.isOver(mouseX, mouseY, x, y, width, GuiTheme.ROW_HEIGHT));
    }

    public void render(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        Font font = OfflineClient.MC.font;
        int w = width;
        // The last pixel of the row pitch is the rule between rows.
        int h = GuiTheme.ROW_HEIGHT - 1;

        boolean hovered = hoverActive
            && SettingWidget.isOver(mouseX, mouseY, x, y, w, GuiTheme.ROW_HEIGHT);

        boolean on = module.isEnabled();
        int rest = on ? GuiTheme.accentOn(GuiTheme.bgRow(), 0.26f) : GuiTheme.bgRow();
        int lit = on ? GuiTheme.accentOn(GuiTheme.bgRow(), 0.46f) : GuiTheme.bgRowHover();
        context.fill(x, y, x + w, y + h, ColorUtil.lerp(rest, lit, hoverFade));
        context.fill(x, y + h, x + w, y + GuiTheme.ROW_HEIGHT, GuiTheme.RULE);

        context.guiRenderState.up();
        if (on) {
            context.fill(x, y, x + 2, y + h, GuiTheme.accent());
        }
        int textColor = on ? GuiTheme.text() : GuiTheme.textDim();
        // A fixed inset keeps the name still when the accent bar appears.
        int nameRoom = w - 7 - ARROW_ZONE - STAR_ZONE;
        context.text(font, SettingWidget.trimEnd(font, module.getName(), nameRoom), x + 7,
            GuiTheme.textY(y, h), textColor, false);
        RenderUtil.chevron(context, x + w - 11, y + (h - RenderUtil.CHEVRON_HEIGHT) / 2, !expanded,
            hovered ? GuiTheme.text() : GuiTheme.textFaint());

        boolean starred = Favourites.has(module);
        boolean overStar = hovered && mouseX >= starX() && mouseX < starX() + STAR_ZONE;
        if (starred || hovered) {
            RenderUtil.star(context, starX() + 1, y + (h - RenderUtil.STAR_SIZE) / 2,
                starred ? GuiTheme.STAR : (overStar ? GuiTheme.text() : GuiTheme.textFaint()),
                starred);
        }

        if (hovered) {
            host.setTooltip(overStar
                ? (starred ? "Take out of your favourites" : "Add to your favourites")
                : module.getDescription());
        }

        if (expanded) {
            drag.follow(SettingWidget.blockContentX(x), SettingWidget.blockContentWidth(w), mouseX);
            SettingWidget.renderBlock(context, font, module, x, y + GuiTheme.ROW_HEIGHT, w,
                mouseX, mouseY, hoverActive, host);
        }
    }

    private int starX() {
        return x + width - ARROW_ZONE - STAR_ZONE;
    }

    private void fadeHover(boolean hovered) {
        long now = System.currentTimeMillis();
        float delta = lastFrame == 0 ? 0.05f : Math.min(0.1f, (now - lastFrame) / 1000f);
        lastFrame = now;
        float step = delta * 9f;
        hoverFade = hovered ? Math.min(1f, hoverFade + step) : Math.max(0f, hoverFade - step);
    }

    // The click rules both ClickGUI styles share. A left click on the star marks a
    // favourite. A right click or the arrow or a module with no toggle opens the settings.
    static void clickModule(Module module, int button, boolean onStar, boolean onArrow,
                            Runnable openSettings) {
        if (!InputUtil.isLeft(button) && !InputUtil.isRight(button)) {
            return;
        }
        if (InputUtil.isLeft(button) && onStar) {
            Favourites.toggle(module);
        } else if (InputUtil.isRight(button) || onArrow || !module.isTogglable()) {
            openSettings.run();
        } else {
            module.toggle();
        }
    }

    public boolean mouseClicked(double mx, double my, int button) {
        if (!placed) {
            return false;
        }
        int w = width;

        if (SettingWidget.isOver(mx, my, x, y, w, GuiTheme.ROW_HEIGHT)) {
            clickModule(module, button, mx >= starX() && mx < starX() + STAR_ZONE,
                mx >= x + w - ARROW_ZONE, () -> expanded = !expanded);
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
