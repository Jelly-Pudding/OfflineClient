package com.jellypudding.offlineclient.gui;

import com.google.gson.JsonObject;
import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.hud.HudElement;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.RenderUtil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.List;

// The panel down the side of the HUD editor. Every piece of the overlay with
// a switch beside it and the handful of hints that belong here rather than
// over the top of the overlay itself.
public final class HudElementList {

    public static final int WIDTH = 106;

    private static final int MARGIN = 6;
    private static final int HEADER = 13;
    // The right end of the header collapses the panel rather than moving it.
    private static final int MARKER_ZONE = 14;
    private static final String STATE_KEY = "hudList";
    private static final int ROW = 11;
    private static final int PILL_WIDTH = 16;
    private static final int PILL_HEIGHT = 7;
    private static final int PAD = 5;
    private static final int BOTTOM_GAP = 6;

    private static final String TITLE = "Overlay";
    private static final String[] HINTS = {
        "drag to move",
        "corner to resize",
        "shift skips snapping",
    };

    private final List<HudElement> elements;
    private final ScrollBar scrollBar = new ScrollBar();

    private int left = MARGIN;
    private int top = MARGIN;
    private int listTop;
    private int listHeight;
    private boolean collapsed;
    private boolean dragging;
    private int grabX;
    private int grabY;
    private HudElement hovered;

    public HudElementList(List<HudElement> elements) {
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
            left = state.get("x").getAsInt();
            top = state.get("y").getAsInt();
        }
        if (state.has("collapsed")) {
            collapsed = state.get("collapsed").getAsBoolean();
        }
    }

    public void save() {
        JsonObject state = new JsonObject();
        state.addProperty("x", left);
        state.addProperty("y", top);
        state.addProperty("collapsed", collapsed);
        OfflineClient.INSTANCE.getConfigManager().getGuiState().add(STATE_KEY, state);
    }

    // The element the pointer is over in the list. Null whenever it is not.
    public HudElement getHovered() {
        return hovered;
    }

    private int hintHeight() {
        return HINTS.length * 9 + PAD;
    }

    private int contentHeight() {
        return elements.size() * ROW;
    }

    public void render(GuiGraphicsExtractor context, int screenWidth, int screenHeight,
                       int mouseX, int mouseY) {
        Font font = OfflineClient.MC.font;
        hovered = null;
        if (dragging) {
            left = Math.clamp(mouseX - grabX, 0, Math.max(0, screenWidth - WIDTH));
            top = Math.clamp(mouseY - grabY, 0, Math.max(0, screenHeight - HEADER));
        }
        listTop = top + HEADER;
        int room = screenHeight - listTop - hintHeight() - BOTTOM_GAP;
        listHeight = collapsed ? 0 : Math.max(ROW, Math.min(contentHeight(), room));
        int boxBottom = bottom();

        RenderUtil.shadow(context, left, top, left + WIDTH, boxBottom, 3);
        context.guiRenderState.up();
        RenderUtil.roundedBorderedRect(context, left, top, left + WIDTH, boxBottom,
            GuiTheme.CORNER + 1, GuiTheme.bgSolid(), GuiTheme.outline());
        context.guiRenderState.up();

        RenderUtil.roundedRect(context, left, top, left + WIDTH, top + HEADER,
            GuiTheme.CORNER + 1, GuiTheme.bgHeader(), true, collapsed);
        if (!collapsed) {
            context.fill(left, top + HEADER - 1, left + WIDTH, top + HEADER, GuiTheme.accent());
        }
        context.guiRenderState.up();
        context.text(font, TITLE, left + PAD, GuiTheme.textY(top, HEADER - 1),
            GuiTheme.accentText(), false);
        RenderUtil.chevron(context, left + WIDTH - 12, top + (HEADER - 5) / 2, collapsed,
            GuiTheme.textDim());
        if (collapsed) {
            return;
        }

        scrollBar.update(mouseY, contentHeight(), listHeight);
        int rowW = ScrollBar.rowWidth(WIDTH, contentHeight(), listHeight);
        context.enableScissor(left, listTop, left + rowW, listTop + listHeight);
        renderRows(context, font, rowW, mouseX, mouseY);
        context.disableScissor();

        if (contentHeight() > listHeight) {
            int trackX = ScrollBar.trackX(left, WIDTH);
            scrollBar.render(context, trackX, listTop, listHeight, contentHeight(),
                ScrollBar.isOverTrack(mouseX, mouseY, trackX, listTop, listHeight));
        }

        int hintY = listTop + listHeight + PAD - 1;
        for (String hint : HINTS) {
            context.text(font, hint, left + PAD, hintY, GuiTheme.textFaint(), false);
            hintY += 9;
        }
    }

    private void renderRows(GuiGraphicsExtractor context, Font font, int rowW,
                            int mouseX, int mouseY) {
        boolean inView = mouseX >= left && mouseX < left + rowW
            && mouseY >= listTop && mouseY < listTop + listHeight;
        int y = listTop - scrollBar.getOffset();
        for (HudElement element : elements) {
            if (y + ROW > listTop && y < listTop + listHeight) {
                boolean over = inView && SettingWidget.isOver(mouseX, mouseY, left, y, rowW, ROW);
                boolean on = element.isActive();
                if (over) {
                    context.fill(left, y, left + rowW, y + ROW, GuiTheme.bgRowHover());
                    context.guiRenderState.up();
                    hovered = element;
                }
                int room = rowW - PAD * 2 - PILL_WIDTH - 4;
                context.text(font, SettingWidget.trimEnd(font, element.getName(), room),
                    left + PAD, GuiTheme.textY(y, ROW),
                    on ? GuiTheme.text() : GuiTheme.textFaint(), false);
                RenderUtil.toggle(context, left + rowW - PAD - PILL_WIDTH,
                    y + (ROW - PILL_HEIGHT) / 2, PILL_WIDTH, PILL_HEIGHT, on,
                    GuiTheme.accent(), GuiTheme.bgSetting(),
                    on ? GuiTheme.contrastText(GuiTheme.accent()) : GuiTheme.textDim());
            }
            y += ROW;
        }
    }

    private int bottom() {
        return collapsed ? top + HEADER : listTop + listHeight + hintHeight();
    }

    public boolean isOver(double mx, double my) {
        return mx >= left && mx < left + WIDTH && my >= top && my < bottom();
    }

    public void wheel(double amount) {
        scrollBar.scroll(ScrollBar.wheelDelta(amount, contentHeight(), listHeight),
            contentHeight(), listHeight);
    }

    public void release() {
        dragging = false;
        scrollBar.release();
    }

    // True when the click belonged to the panel.
    public boolean mouseClicked(double mx, double my, int button) {
        if (!isOver(mx, my)) {
            return false;
        }
        if (my < top + HEADER) {
            boolean marker = mx >= left + WIDTH - MARKER_ZONE;
            if (InputUtil.isRight(button) || (InputUtil.isLeft(button) && marker)) {
                collapsed = !collapsed;
            } else if (InputUtil.isLeft(button)) {
                dragging = true;
                grabX = (int) mx - left;
                grabY = (int) my - top;
            }
            return true;
        }
        int rowW = ScrollBar.rowWidth(WIDTH, contentHeight(), listHeight);
        int trackX = ScrollBar.trackX(left, WIDTH);
        if (contentHeight() > listHeight
            && ScrollBar.isOverTrack(mx, my, trackX, listTop, listHeight)) {
            scrollBar.beginDrag((int) my);
            return true;
        }
        if (my < listTop || my >= listTop + listHeight || !InputUtil.isLeft(button)) {
            return true;
        }
        int y = listTop - scrollBar.getOffset();
        for (HudElement element : elements) {
            if (SettingWidget.isOver(mx, my, left, y, rowW, ROW)) {
                element.setActive(!element.isActive());
                OfflineClient.INSTANCE.getConfigManager().saveSoon();
                return true;
            }
            y += ROW;
        }
        return true;
    }
}
