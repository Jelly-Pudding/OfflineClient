package com.jellypudding.offlineclient.gui;

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

    private static final int LEFT = 6;
    private static final int TOP = 6;
    private static final int HEADER = 13;
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

    private int listTop;
    private int listHeight;
    private boolean collapsed;
    private HudElement hovered;

    public HudElementList(List<HudElement> elements) {
        this.elements = elements;
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

    public void render(GuiGraphicsExtractor context, int screenHeight, int mouseX, int mouseY) {
        Font font = OfflineClient.MC.font;
        hovered = null;
        listTop = TOP + HEADER;
        int room = screenHeight - listTop - hintHeight() - BOTTOM_GAP - TOP;
        listHeight = collapsed ? 0 : Math.max(ROW, Math.min(contentHeight(), room));
        int boxBottom = bottom();

        RenderUtil.shadow(context, LEFT, TOP, LEFT + WIDTH, boxBottom, 3);
        context.guiRenderState.up();
        RenderUtil.roundedBorderedRect(context, LEFT, TOP, LEFT + WIDTH, boxBottom,
            GuiTheme.CORNER + 1, GuiTheme.bgSolid(), GuiTheme.outline());
        context.guiRenderState.up();

        RenderUtil.roundedRect(context, LEFT, TOP, LEFT + WIDTH, TOP + HEADER,
            GuiTheme.CORNER + 1, GuiTheme.bgHeader(), true, collapsed);
        if (!collapsed) {
            context.fill(LEFT, TOP + HEADER - 1, LEFT + WIDTH, TOP + HEADER, GuiTheme.accent());
        }
        context.guiRenderState.up();
        context.text(font, TITLE, LEFT + PAD, GuiTheme.textY(TOP, HEADER - 1),
            GuiTheme.accentText(), false);
        RenderUtil.chevron(context, LEFT + WIDTH - 12, TOP + (HEADER - 5) / 2, collapsed,
            GuiTheme.textDim());
        if (collapsed) {
            return;
        }

        scrollBar.update(mouseY, contentHeight(), listHeight);
        int rowW = ScrollBar.rowWidth(WIDTH, contentHeight(), listHeight);
        context.enableScissor(LEFT, listTop, LEFT + rowW, listTop + listHeight);
        renderRows(context, font, rowW, mouseX, mouseY);
        context.disableScissor();

        if (contentHeight() > listHeight) {
            int trackX = ScrollBar.trackX(LEFT, WIDTH);
            scrollBar.render(context, trackX, listTop, listHeight, contentHeight(),
                ScrollBar.isOverTrack(mouseX, mouseY, trackX, listTop, listHeight));
        }

        int hintY = listTop + listHeight + PAD - 1;
        for (String hint : HINTS) {
            context.text(font, hint, LEFT + PAD, hintY, GuiTheme.textFaint(), false);
            hintY += 9;
        }
    }

    private void renderRows(GuiGraphicsExtractor context, Font font, int rowW,
                            int mouseX, int mouseY) {
        boolean inView = mouseX >= LEFT && mouseX < LEFT + rowW
            && mouseY >= listTop && mouseY < listTop + listHeight;
        int y = listTop - scrollBar.getOffset();
        for (HudElement element : elements) {
            if (y + ROW > listTop && y < listTop + listHeight) {
                boolean over = inView && SettingWidget.isOver(mouseX, mouseY, LEFT, y, rowW, ROW);
                boolean on = element.isActive();
                if (over) {
                    context.fill(LEFT, y, LEFT + rowW, y + ROW, GuiTheme.bgRowHover());
                    context.guiRenderState.up();
                    hovered = element;
                }
                int room = rowW - PAD * 2 - PILL_WIDTH - 4;
                context.text(font, SettingWidget.trimEnd(font, element.getName(), room),
                    LEFT + PAD, GuiTheme.textY(y, ROW),
                    on ? GuiTheme.text() : GuiTheme.textFaint(), false);
                RenderUtil.toggle(context, LEFT + rowW - PAD - PILL_WIDTH,
                    y + (ROW - PILL_HEIGHT) / 2, PILL_WIDTH, PILL_HEIGHT, on,
                    GuiTheme.accent(), GuiTheme.bgSetting(),
                    on ? GuiTheme.contrastText(GuiTheme.accent()) : GuiTheme.textDim());
            }
            y += ROW;
        }
    }

    private int bottom() {
        return collapsed ? TOP + HEADER : listTop + listHeight + hintHeight();
    }

    public boolean isOver(double mx, double my) {
        return mx >= LEFT && mx < LEFT + WIDTH && my >= TOP && my < bottom();
    }

    public void wheel(double amount) {
        scrollBar.scroll(ScrollBar.wheelDelta(amount, contentHeight(), listHeight),
            contentHeight(), listHeight);
    }

    public void release() {
        scrollBar.release();
    }

    // True when the click belonged to the panel.
    public boolean mouseClicked(double mx, double my, int button) {
        if (!isOver(mx, my)) {
            return false;
        }
        if (my < TOP + HEADER) {
            if (InputUtil.isLeft(button)) {
                collapsed = !collapsed;
            }
            return true;
        }
        int rowW = ScrollBar.rowWidth(WIDTH, contentHeight(), listHeight);
        int trackX = ScrollBar.trackX(LEFT, WIDTH);
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
            if (SettingWidget.isOver(mx, my, LEFT, y, rowW, ROW)) {
                element.setActive(!element.isActive());
                OfflineClient.INSTANCE.getConfigManager().saveSoon();
                return true;
            }
            y += ROW;
        }
        return true;
    }
}
