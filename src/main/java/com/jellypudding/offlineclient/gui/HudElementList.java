package com.jellypudding.offlineclient.gui;

import com.google.gson.JsonObject;
import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.hud.HudElement;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.RenderUtil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.List;

// The panel in the HUD editor. Every piece of the overlay with a switch
// beside it and the hints that belong here rather than over the overlay.
public final class HudElementList extends PanelFrame {

    private static final int START_WIDTH = 108;
    private static final int ROW = 11;
    private static final int PILL_WIDTH = 16;
    private static final int PILL_HEIGHT = 7;
    private static final int PAD = 5;
    private static final int LINE = 9;
    private static final String STATE_KEY = "hudList";

    private static final String[] HINTS = {
        "drag to move",
        "corner to resize",
        "shift skips snapping",
    };

    private final List<HudElement> elements;
    private HudElement hovered;

    public HudElementList(List<HudElement> elements) {
        super("Overlay", 6, 6, START_WIDTH);
        this.elements = elements;
        restore();
    }

    private void restore() {
        JsonObject gui = OfflineClient.INSTANCE.getConfigManager().getGuiState();
        if (!gui.has(STATE_KEY) || !gui.get(STATE_KEY).isJsonObject()) {
            return;
        }
        JsonObject state = gui.getAsJsonObject(STATE_KEY);
        if (state.has("x") && state.has("y")) {
            setPosition(state.get("x").getAsInt(), state.get("y").getAsInt());
        }
        if (state.has("width")) {
            setWidth(state.get("width").getAsInt());
        }
        if (state.has("height")) {
            setViewHeight(state.get("height").getAsInt());
        }
        if (state.has("collapsed")) {
            setCollapsed(state.get("collapsed").getAsBoolean());
        }
    }

    public void save() {
        JsonObject state = new JsonObject();
        state.addProperty("x", getX());
        state.addProperty("y", getY());
        state.addProperty("width", getWidth());
        state.addProperty("height", getViewHeight());
        state.addProperty("collapsed", isCollapsed());
        OfflineClient.INSTANCE.getConfigManager().getGuiState().add(STATE_KEY, state);
    }

    // The element the pointer is over in the list. Null whenever it is not.
    public HudElement getHovered() {
        return hovered;
    }

    @Override
    protected boolean opaque() {
        return true;
    }

    @Override
    protected int contentHeight() {
        return elements.size() * ROW;
    }

    @Override
    protected int footerHeight() {
        return HINTS.length * LINE + PAD;
    }

    @Override
    protected void renderFooter(GuiGraphicsExtractor context, int top, int rowWidth) {
        Font font = OfflineClient.MC.font;
        int y = top + 1;
        for (String hint : HINTS) {
            context.text(font, SettingWidget.trimEnd(font, hint, rowWidth - PAD * 2),
                getX() + PAD, y, GuiTheme.textFaint(), false);
            y += LINE;
        }
    }

    @Override
    protected void renderContent(GuiGraphicsExtractor context, int viewTop, int view,
                                 int rowWidth, int mouseX, int mouseY) {
        Font font = OfflineClient.MC.font;
        hovered = null;
        boolean inView = mouseX >= getX() && mouseX < getX() + rowWidth
            && mouseY >= viewTop && mouseY < viewTop + view;
        int y = viewTop - getScrollOffset();
        for (HudElement element : elements) {
            if (y + ROW > viewTop && y < viewTop + view) {
                renderRow(context, font, element, y, rowWidth, mouseX, mouseY, inView);
            }
            y += ROW;
        }
    }

    private void renderRow(GuiGraphicsExtractor context, Font font, HudElement element, int y,
                           int rowWidth, int mouseX, int mouseY, boolean inView) {
        boolean over = inView && SettingWidget.isOver(mouseX, mouseY, getX(), y, rowWidth, ROW);
        boolean on = element.isActive();
        if (over) {
            context.fill(getX(), y, getX() + rowWidth, y + ROW, GuiTheme.bgRowHover());
            context.guiRenderState.up();
            hovered = element;
        }
        int room = rowWidth - PAD * 2 - PILL_WIDTH - 4;
        context.text(font, SettingWidget.trimEnd(font, element.getName(), room),
            getX() + PAD, GuiTheme.textY(y, ROW),
            on ? GuiTheme.text() : GuiTheme.textFaint(), false);
        RenderUtil.toggle(context, getX() + rowWidth - PAD - PILL_WIDTH,
            y + (ROW - PILL_HEIGHT) / 2, PILL_WIDTH, PILL_HEIGHT, on,
            GuiTheme.accent(), GuiTheme.bgSetting(),
            on ? GuiTheme.contrastText(GuiTheme.accent()) : GuiTheme.textDim());
    }

    @Override
    protected boolean clickContent(double mx, double my, int button, int viewTop,
                                   int view, int rowWidth) {
        if (!InputUtil.isLeft(button)) {
            return false;
        }
        int y = viewTop - getScrollOffset();
        for (HudElement element : elements) {
            if (SettingWidget.isOver(mx, my, getX(), y, rowWidth, ROW)) {
                element.setActive(!element.isActive());
                OfflineClient.INSTANCE.getConfigManager().saveSoon();
                return true;
            }
            y += ROW;
        }
        return false;
    }

    @Override
    protected void releaseContent() {
    }
}
