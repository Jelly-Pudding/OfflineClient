package com.jellypudding.offlineclient.gui;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.RenderUtil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.DefaultedRegistry;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Two column picker for a registry list setting. Unchosen entries sit on
 * the left and chosen ones on the right and clicking moves one across.
 */
public final class RegistryPickerScreen<T> extends Screen {

    private record Entry<T>(T value, String name, String lowerName, String id, ItemStack icon) {
    }

    private static final int COL_WIDTH = 156;
    private static final int COL_GAP = 12;
    private static final int ROW_HEIGHT = 18;
    private static final int SEARCH_WIDTH = 200;
    private static final int SEARCH_TOP = 10;
    private static final int SEARCH_BOTTOM = 24;
    private static final int LIST_TOP = 48;

    private final ClickGuiScreen parent;
    private final RegistryListSetting<T> setting;

    /** Every registry entry sorted by name. Built once. */
    private final List<Entry<T>> all = new ArrayList<>();
    private final Map<T, Entry<T>> byValue = new HashMap<>();

    private List<Entry<T>> available = List.of();
    private List<Entry<T>> chosen = List.of();

    private String search = "";
    private int leftScroll;
    private int rightScroll;
    private String tooltip;

    public RegistryPickerScreen(ClickGuiScreen parent, RegistryListSetting<T> setting) {
        super(Component.literal(setting.getName()));
        this.parent = parent;
        this.setting = setting;

        Identifier defaultKey = setting.getRegistry() instanceof DefaultedRegistry<T> defaulted
            ? defaulted.getDefaultKey() : null;
        setting.getRegistry().stream().forEach(value -> {
            Identifier id = setting.getRegistry().getKey(value);
            if (id == null || id.equals(defaultKey)) {
                return;
            }
            String name = setting.displayName(value);
            Entry<T> entry = new Entry<>(value, name,
                name.toLowerCase(Locale.ROOT), id.toString(), setting.icon(value));
            all.add(entry);
            byValue.put(value, entry);
        });
        all.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        refresh();
    }

    /** Rebuilds both columns from the search text and the setting. */
    private void refresh() {
        String query = search.toLowerCase(Locale.ROOT).trim();
        List<Entry<T>> left = new ArrayList<>();
        for (Entry<T> entry : all) {
            if (setting.isChosen(entry.value())) {
                continue;
            }
            if (query.isEmpty() || entry.lowerName().contains(query) || entry.id().contains(query)) {
                left.add(entry);
            }
        }
        available = left;

        List<Entry<T>> right = new ArrayList<>();
        for (Identifier id : setting.getValue()) {
            if (setting.getRegistry().containsKey(id)) {
                Entry<T> entry = byValue.get(setting.getRegistry().getValue(id));
                if (entry != null) {
                    right.add(entry);
                }
            }
        }
        chosen = right;

        leftScroll = clampScroll(leftScroll, available);
        rightScroll = clampScroll(rightScroll, chosen);
    }

    private int listHeight() {
        return Math.max(ROW_HEIGHT, height - LIST_TOP - 36);
    }

    private int clampScroll(int scroll, List<Entry<T>> list) {
        return Math.clamp(scroll, 0, Math.max(0, list.size() * ROW_HEIGHT - listHeight()));
    }

    private int leftX() {
        return width / 2 - COL_WIDTH - COL_GAP / 2;
    }

    private int rightX() {
        return width / 2 + COL_GAP / 2;
    }

    private int doneX() {
        return width / 2 - 70;
    }

    private int clearX() {
        return width / 2 + 10;
    }

    private int doneY() {
        return LIST_TOP + listHeight() + 8;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float deltaTicks) {
        // Same soft dark gradient as the ClickGUI.
        context.fillGradient(0, 0, width, height, 0x70101018, 0xA0060610);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float partialTicks) {
        tooltip = null;
        Font font = OfflineClient.MC.font;

        // Title above the search box.
        context.centeredText(font, setting.getName(), width / 2, SEARCH_TOP - 10, GuiTheme.TEXT);

        // The search box is always focused.
        int barX = width / 2 - SEARCH_WIDTH / 2;
        RenderUtil.borderedRect(context, barX, SEARCH_TOP, barX + SEARCH_WIDTH, SEARCH_BOTTOM,
            GuiTheme.BG_PANEL, GuiTheme.accent());
        context.guiRenderState.up();
        boolean placeholder = search.isEmpty();
        String searchText = placeholder ? "type to search" : search;
        context.text(font, searchText, barX + 5, SEARCH_TOP + 3,
            placeholder ? GuiTheme.TEXT_DIM : GuiTheme.TEXT, false);
        if ((System.currentTimeMillis() / 500) % 2 == 0) {
            int caretX = barX + 5 + (search.isEmpty() ? 0 : font.width(search) + 1);
            context.fill(caretX, SEARCH_TOP + 3, caretX + 1, SEARCH_TOP + 11, GuiTheme.TEXT);
        }

        renderColumn(context, font, leftX(), "available", available, leftScroll, mouseX, mouseY);
        renderColumn(context, font, rightX(), "chosen " + chosen.size(), chosen, rightScroll, mouseX, mouseY);

        // Done and clear all buttons.
        boolean overDone = isOver(mouseX, mouseY, doneX(), doneY(), 60, 16);
        RenderUtil.borderedRect(context, doneX(), doneY(), doneX() + 60, doneY() + 16,
            overDone ? GuiTheme.BG_ROW_HOVER : GuiTheme.BG_PANEL,
            overDone ? GuiTheme.accent() : GuiTheme.OUTLINE);
        context.guiRenderState.up();
        context.centeredText(font, "done", doneX() + 30, doneY() + 4, GuiTheme.TEXT);

        boolean overClear = isOver(mouseX, mouseY, clearX(), doneY(), 60, 16);
        RenderUtil.borderedRect(context, clearX(), doneY(), clearX() + 60, doneY() + 16,
            overClear ? GuiTheme.BG_ROW_HOVER : GuiTheme.BG_PANEL,
            overClear ? GuiTheme.accent() : GuiTheme.OUTLINE);
        context.guiRenderState.up();
        context.centeredText(font, "clear all", clearX() + 30, doneY() + 4,
            chosen.isEmpty() ? GuiTheme.TEXT_DIM : GuiTheme.TEXT);

        if (tooltip != null && !tooltip.isEmpty()) {
            renderTooltip(context, font, mouseX, mouseY);
        }
    }

    private void renderColumn(GuiGraphicsExtractor context, Font font, int x, String header,
                              List<Entry<T>> list, int scroll, int mouseX, int mouseY) {
        int top = LIST_TOP;
        int h = listHeight();

        context.text(font, header, x + 2, top - 11, GuiTheme.TEXT_DIM, false);
        RenderUtil.borderedRect(context, x - 1, top - 1, x + COL_WIDTH + 1, top + h + 1,
            GuiTheme.BG_PANEL, GuiTheme.OUTLINE);
        context.guiRenderState.up();

        boolean mouseInside = isOver(mouseX, mouseY, x, top, COL_WIDTH, h);
        context.enableScissor(x, top, x + COL_WIDTH, top + h);
        int rowY = top - scroll;
        for (Entry<T> entry : list) {
            if (rowY + ROW_HEIGHT > top && rowY < top + h) {
                boolean hovered = mouseInside && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT;
                if (hovered) {
                    context.fill(x, rowY, x + COL_WIDTH, rowY + ROW_HEIGHT, GuiTheme.BG_ROW_HOVER);
                    tooltip = entry.id();
                }
                context.guiRenderState.up();
                if (!entry.icon().isEmpty()) {
                    context.item(entry.icon(), x + 2, rowY + 1);
                }
                context.guiRenderState.up();
                String name = entry.name();
                int room = COL_WIDTH - 24;
                while (font.width(name) > room && name.length() > 2) {
                    name = name.substring(0, name.length() - 2) + ".";
                }
                context.text(font, name, x + 21, rowY + 5,
                    hovered ? GuiTheme.accent() : GuiTheme.TEXT, false);
            }
            rowY += ROW_HEIGHT;
        }
        if (list.isEmpty()) {
            context.text(font, "nothing here", x + 5, top + 4, GuiTheme.TEXT_DIM, false);
        }
        context.disableScissor();

        // Scrollbar when the column overflows.
        int total = list.size() * ROW_HEIGHT;
        if (total > h) {
            int trackX = x + COL_WIDTH - 3;
            context.fill(trackX, top, trackX + 3, top + h, GuiTheme.BG_ROW);
            int thumbH = Math.max(10, h * h / total);
            int thumbY = top + (h - thumbH) * scroll / Math.max(1, total - h);
            context.guiRenderState.up();
            context.fill(trackX, thumbY, trackX + 3, thumbY + thumbH, GuiTheme.TEXT_DIM);
        }
    }

    private void renderTooltip(GuiGraphicsExtractor context, Font font, int mouseX, int mouseY) {
        int w = font.width(tooltip);
        int tx = Math.min(mouseX + 10, width - w - 12);
        int ty = Math.min(mouseY + 10, height - 16);
        context.guiRenderState.up();
        RenderUtil.borderedRect(context, tx, ty, tx + w + 8, ty + 14, 0xF80C0C14, GuiTheme.accent());
        context.guiRenderState.up();
        context.text(font, tooltip, tx + 4, ty + 4, GuiTheme.TEXT, false);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x();
        double my = event.y();

        if (isOver(mx, my, doneX(), doneY(), 60, 16)) {
            onClose();
            return true;
        }
        if (isOver(mx, my, clearX(), doneY(), 60, 16)) {
            setting.clear();
            OfflineClient.INSTANCE.getConfigManager().saveSoon();
            refresh();
            return true;
        }
        if (clickColumn(mx, my, leftX(), available, leftScroll, true)) {
            return true;
        }
        if (clickColumn(mx, my, rightX(), chosen, rightScroll, false)) {
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    private boolean clickColumn(double mx, double my, int x, List<Entry<T>> list,
                                int scroll, boolean adding) {
        if (!isOver(mx, my, x, LIST_TOP, COL_WIDTH, listHeight())) {
            return false;
        }
        int index = (int) ((my - LIST_TOP + scroll) / ROW_HEIGHT);
        if (index >= 0 && index < list.size()) {
            Entry<T> entry = list.get(index);
            if (adding) {
                setting.add(entry.value());
            } else {
                setting.remove(entry.value());
            }
            OfflineClient.INSTANCE.getConfigManager().saveSoon();
            refresh();
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int dy = (int) Math.round(scrollY * 16);
        if (isOver(mouseX, mouseY, leftX(), LIST_TOP, COL_WIDTH, listHeight())) {
            leftScroll = clampScroll(leftScroll - dy, available);
            return true;
        }
        if (isOver(mouseX, mouseY, rightX(), LIST_TOP, COL_WIDTH, listHeight())) {
            rightScroll = clampScroll(rightScroll - dy, chosen);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_ENTER) {
            onClose();
            return true;
        }
        if (key == GLFW.GLFW_KEY_BACKSPACE && !search.isEmpty()) {
            search = search.substring(0, search.length() - 1);
            refresh();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        char c = (char) event.codepoint();
        if (c >= ' ' && c < 127) {
            search += c;
            refresh();
            return true;
        }
        return super.charTyped(event);
    }

    /** Back to the ClickGUI. The same instance keeps its panel positions. */
    @Override
    public void onClose() {
        OfflineClient.INSTANCE.getConfigManager().saveNow();
        OfflineClient.MC.gui.setScreen(parent);
    }

    private static boolean isOver(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
}
