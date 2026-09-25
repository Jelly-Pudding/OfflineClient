package com.jellypudding.offlineclient.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.hud.HudElement;
import com.jellypudding.offlineclient.util.RenderUtil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

// The panel in the HUD editor. Every piece of the overlay with a switch
// beside it and its own settings underneath.
public final class HudElementList extends PanelFrame {

    private static final int PILL_WIDTH = 16;
    private static final int PILL_HEIGHT = 7;
    private static final int ARROW_ZONE = 12;
    private static final int PAD = 5;
    private static final String STATE_KEY = "hudList";

    private final List<HudElement> elements;
    private final Set<String> expanded = new HashSet<>();
    private final SettingWidget.Host host;
    private final SettingWidget.Drag drag = new SettingWidget.Drag();

    private HudElement hovered;

    public HudElementList(List<HudElement> elements, SettingWidget.Host host) {
        super("Overlay", 6, 6, GuiTheme.PANEL_WIDTH);
        this.elements = elements;
        this.host = host;
        restore();
    }

    private void restore() {
        JsonObject gui = OfflineClient.INSTANCE.getConfigManager().getGuiState();
        if (!gui.has(STATE_KEY) || !gui.get(STATE_KEY).isJsonObject()) {
            return;
        }
        JsonObject state = gui.getAsJsonObject(STATE_KEY);
        readFrame(state);
        if (state.has("open")) {
            for (var name : state.getAsJsonArray("open")) {
                expanded.add(name.getAsString());
            }
        }
    }

    public void save() {
        JsonObject state = new JsonObject();
        writeFrame(state);
        JsonArray open = new JsonArray();
        expanded.forEach(open::add);
        state.add("open", open);
        OfflineClient.INSTANCE.getConfigManager().getGuiState().add(STATE_KEY, state);
    }

    // The element the pointer is over. Null whenever it is over none.
    public HudElement getHovered() {
        return hovered;
    }

    private int rowHeight(HudElement element) {
        if (!expanded.contains(element.getName())) {
            return GuiTheme.ROW_HEIGHT;
        }
        return GuiTheme.ROW_HEIGHT + SettingWidget.blockHeight(element.getOptions(), null);
    }

    @Override
    protected int contentHeight() {
        int height = 0;
        for (HudElement element : elements) {
            height += rowHeight(element);
        }
        return height;
    }

    @Override
    protected void renderContent(GuiGraphicsExtractor context, int viewTop, int view,
                                 int rowWidth, int mouseX, int mouseY) {
        Font font = OfflineClient.MC.font;
        hovered = null;
        boolean inView = mouseX >= getX() && mouseX < getX() + rowWidth
            && mouseY >= viewTop && mouseY < viewTop + view;
        drag.follow(SettingWidget.blockContentX(getX()),
            SettingWidget.blockContentWidth(rowWidth), mouseX);
        int y = viewTop - getScrollOffset();
        for (HudElement element : elements) {
            int h = rowHeight(element);
            if (y + h > viewTop && y < viewTop + view) {
                renderRow(context, font, element, y, rowWidth, mouseX, mouseY, inView);
            }
            y += h;
        }
    }

    private void renderRow(GuiGraphicsExtractor context, Font font, HudElement element, int y,
                           int rowWidth, int mouseX, int mouseY, boolean inView) {
        int h = GuiTheme.ROW_HEIGHT - 1;
        boolean over = inView
            && SettingWidget.isOver(mouseX, mouseY, getX(), y, rowWidth, GuiTheme.ROW_HEIGHT);
        boolean on = element.isActive();
        context.fill(getX(), y, getX() + rowWidth, y + h,
            on ? GuiTheme.accentOn(GuiTheme.bgRow(), over ? 0.46f : 0.26f)
                : (over ? GuiTheme.bgRowHover() : GuiTheme.bgRow()));
        context.fill(getX(), y + h, getX() + rowWidth, y + GuiTheme.ROW_HEIGHT, GuiTheme.RULE);
        context.guiRenderState.up();
        if (over) {
            hovered = element;
            host.setTooltip(element.getDescription());
        }

        int room = rowWidth - PAD * 2 - PILL_WIDTH - ARROW_ZONE;
        context.text(font, SettingWidget.trimEnd(font, element.getName(), room),
            getX() + PAD, GuiTheme.textY(y, h),
            on ? GuiTheme.text() : GuiTheme.textFaint(), false);
        RenderUtil.toggle(context, getX() + rowWidth - ARROW_ZONE - PILL_WIDTH,
            y + (h - PILL_HEIGHT) / 2, PILL_WIDTH, PILL_HEIGHT, on,
            GuiTheme.accent(), GuiTheme.bgSetting(),
            on ? GuiTheme.contrastText(GuiTheme.accent()) : GuiTheme.textDim());
        RenderUtil.chevron(context, getX() + rowWidth - 10, y + (h - RenderUtil.CHEVRON_HEIGHT) / 2,
            !expanded.contains(element.getName()),
            over ? GuiTheme.text() : GuiTheme.textFaint());

        if (expanded.contains(element.getName())) {
            SettingWidget.renderBlock(context, font, element.getOptions(), null, getX(),
                y + GuiTheme.ROW_HEIGHT, rowWidth, mouseX, mouseY, inView, host);
        }
    }

    @Override
    protected boolean clickContent(double mx, double my, int button, int viewTop,
                                   int view, int rowWidth) {
        int y = viewTop - getScrollOffset();
        for (HudElement element : elements) {
            int h = rowHeight(element);
            if (SettingWidget.isOver(mx, my, getX(), y, rowWidth, GuiTheme.ROW_HEIGHT)) {
                clickRow(element, mx, button, rowWidth);
                return true;
            }
            if (my >= y + GuiTheme.ROW_HEIGHT && my < y + h) {
                return SettingWidget.clickBlock(element.getOptions(), null, mx, my, getX(),
                    y + GuiTheme.ROW_HEIGHT, rowWidth, button, host, drag);
            }
            y += h;
        }
        return false;
    }

    // The same rules as a module row.
    private void clickRow(HudElement element, double mx, int button, int rowWidth) {
        ModuleRow.clickRow(button, mx >= getX() + rowWidth - ARROW_ZONE, () -> {
            element.setActive(!element.isActive());
            OfflineClient.INSTANCE.getConfigManager().saveSoon();
        }, () -> {
            if (!expanded.remove(element.getName())) {
                expanded.add(element.getName());
            }
        });
    }

    @Override
    protected void releaseContent() {
        drag.release();
    }
}
