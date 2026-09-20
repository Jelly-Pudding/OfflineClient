package com.jellypudding.offlineclient.gui;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.RenderUtil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;

// A row of tabs across the top of the GUI. Each one can be switched off in
// the ClickGUI settings and the whole row can be hidden.
public final class TabStrip {

    public enum Tab {
        FRIENDS("Friends"),
        MACROS("Macros"),
        HUD("HUD"),
        PROFILES("Profiles");

        private final String label;
        private final BoolSetting setting;

        Tab(String label) {
            this.label = label;
            this.setting = new BoolSetting(label + " tab", "Show the " + label + " tab.", true);
        }

        public String getLabel() {
            return label;
        }

        public BoolSetting getSetting() {
            return setting;
        }

        public boolean isShown() {
            return setting.isOn();
        }
    }

    public static final int HEIGHT = 11;

    private static final int TOP = 2;
    private static final int PAD = 6;
    private static final int UNDERLINE = 1;

    @FunctionalInterface
    public interface Action {
        void run(Tab tab);
    }

    private record Slot(Tab tab, int x, int width) {
    }

    private final List<Slot> slots = new ArrayList<>();

    // Where the content under the tabs may start.
    public static int bottom() {
        return TOP + HEIGHT + UNDERLINE;
    }

    // Laid out every frame because a tab can be switched off at any time.
    private void layout(int screenWidth) {
        Font font = OfflineClient.MC.font;
        slots.clear();
        int total = 0;
        for (Tab tab : Tab.values()) {
            if (tab.isShown()) {
                total += font.width(tab.getLabel()) + PAD * 2;
            }
        }
        if (total == 0) {
            return;
        }
        int x = (screenWidth - total) / 2;
        for (Tab tab : Tab.values()) {
            if (!tab.isShown()) {
                continue;
            }
            int w = font.width(tab.getLabel()) + PAD * 2;
            slots.add(new Slot(tab, x, w));
            x += w;
        }
    }

    public boolean isEmpty() {
        return slots.isEmpty();
    }

    public void render(GuiGraphicsExtractor context, int screenWidth, int mouseX, int mouseY,
                       Tab active) {
        layout(screenWidth);
        if (slots.isEmpty()) {
            return;
        }
        Font font = OfflineClient.MC.font;
        Slot first = slots.getFirst();
        Slot last = slots.getLast();
        int left = first.x();
        int right = last.x() + last.width();
        int base = TOP + HEIGHT;

        RenderUtil.roundedRect(context, left, TOP, right, base, GuiTheme.CORNER,
            GuiTheme.bgHeader(), true, false);
        context.fill(left, base, right, base + UNDERLINE, GuiTheme.edge());
        context.guiRenderState.up();

        for (Slot slot : slots) {
            boolean on = slot.tab() == active;
            boolean hovered = SettingWidget.isOver(mouseX, mouseY, slot.x(), TOP,
                slot.width(), HEIGHT);
            if (on || hovered) {
                RenderUtil.roundedRect(context, slot.x(), TOP, slot.x() + slot.width(), base,
                    GuiTheme.CORNER, on ? GuiTheme.accentOn(GuiTheme.bgHeader(), 0.5f)
                        : GuiTheme.bgRowHover(), true, false);
                context.guiRenderState.up();
            }
            if (on) {
                context.fill(slot.x(), base, slot.x() + slot.width(), base + UNDERLINE,
                    GuiTheme.accent());
            }
            if (slot != first) {
                context.fill(slot.x(), TOP + 2, slot.x() + 1, base - 2, GuiTheme.edge());
            }
            int colour = on ? GuiTheme.accentText() : (hovered ? GuiTheme.text() : GuiTheme.textDim());
            context.text(font, slot.tab().getLabel(),
                slot.x() + (slot.width() - font.width(slot.tab().getLabel())) / 2,
                GuiTheme.textY(TOP, HEIGHT), colour, false);
        }
    }

    // True when the click landed on a tab.
    public boolean mouseClicked(double mx, double my, int button, Action action) {
        if (!InputUtil.isLeft(button)) {
            return false;
        }
        for (Slot slot : slots) {
            if (SettingWidget.isOver(mx, my, slot.x(), TOP, slot.width(), HEIGHT)) {
                action.run(slot.tab());
                return true;
            }
        }
        return false;
    }
}
