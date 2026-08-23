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
 * Two column picker for a registry list setting. Clicking an entry moves it
 * across.
 */
public final class RegistryPickerScreen<T> extends Screen {

    private record Entry<T>(T value, String name, String label, String lowerName, String id,
                            ItemStack icon) {
    }

    private static final int COL_WIDTH = 156;
    private static final int COL_GAP = 12;
    private static final int ROW_HEIGHT = 18;
    private static final int SEARCH_WIDTH = 200;
    private static final int TITLE_Y = 8;
    private static final int SEARCH_TOP = 20;
    private static final int LIST_TOP = 50;
    private static final int BUTTON_WIDTH = 64;
    private static final int BUTTON_HEIGHT = 16;
    private static final int FOOTER = 34;

    private final Screen parent;
    private final RegistryListSetting<T> setting;

    // Every registry entry sorted by name. Built once.
    private final List<Entry<T>> all = new ArrayList<>();
    private final Map<T, Entry<T>> byValue = new HashMap<>();

    private final List<Entry<T>> available = new ArrayList<>();
    private final List<Entry<T>> chosen = new ArrayList<>();
    private final ScrollBar leftBar = new ScrollBar();
    private final ScrollBar rightBar = new ScrollBar();

    private final TextField search = new TextField();
    private String tooltip;

    public RegistryPickerScreen(Screen parent, RegistryListSetting<T> setting) {
        super(Component.literal(setting.getName()));
        this.parent = parent;
        this.setting = setting;

        Font font = OfflineClient.MC.font;
        int room = COL_WIDTH - 26;
        Identifier defaultKey = setting.getRegistry() instanceof DefaultedRegistry<T> defaulted
            ? defaulted.getDefaultKey() : null;
        setting.getRegistry().stream().forEach(value -> {
            Identifier id = setting.getRegistry().getKey(value);
            if (id == null || id.equals(defaultKey)) {
                return;
            }
            String name = setting.displayName(value);
            // Trimmed once for the fixed column width.
            Entry<T> entry = new Entry<>(value, name, SettingWidget.trimEnd(font, name, room),
                name.toLowerCase(Locale.ROOT), id.toString(), setting.icon(value));
            all.add(entry);
            byValue.put(value, entry);
        });
        all.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        refresh();
    }

    private void refresh() {
        String query = search.get().toLowerCase(Locale.ROOT).trim();
        available.clear();
        for (Entry<T> entry : all) {
            if (setting.isChosen(entry.value())) {
                continue;
            }
            if (query.isEmpty() || entry.lowerName().contains(query) || entry.id().contains(query)) {
                available.add(entry);
            }
        }

        chosen.clear();
        for (Identifier id : setting.getValue()) {
            if (setting.getRegistry().containsKey(id)) {
                Entry<T> entry = byValue.get(setting.getRegistry().getValue(id));
                if (entry != null) {
                    chosen.add(entry);
                }
            }
        }
        int view = listHeight();
        leftBar.setOffset(ScrollBar.clamp(leftBar.getOffset(), available.size() * ROW_HEIGHT, view));
        rightBar.setOffset(ScrollBar.clamp(rightBar.getOffset(), chosen.size() * ROW_HEIGHT, view));
    }

    private int listHeight() {
        return Math.max(ROW_HEIGHT, height - LIST_TOP - FOOTER);
    }

    private int leftX() {
        return width / 2 - COL_WIDTH - COL_GAP / 2;
    }

    private int rightX() {
        return width / 2 + COL_GAP / 2;
    }

    private int doneX() {
        return width / 2 - BUTTON_WIDTH - 5;
    }

    private int clearX() {
        return width / 2 + 5;
    }

    private int buttonY() {
        return LIST_TOP + listHeight() + 8;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float deltaTicks) {
        context.fillGradient(0, 0, width, height, 0x70101018, 0xA0060610);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float partialTicks) {
        tooltip = null;
        Font font = OfflineClient.MC.font;

        context.centeredText(font, setting.getName(), width / 2, TITLE_Y, GuiTheme.TEXT);
        // The search box is always focused on this screen.
        GuiScreenBase.searchField(context, font, width / 2 - SEARCH_WIDTH / 2, SEARCH_TOP,
            SEARCH_WIDTH, search, "type to search", true, false, true, null);

        renderColumn(context, font, leftX(), "available", available, leftBar, mouseX, mouseY);
        renderColumn(context, font, rightX(), "chosen", chosen, rightBar, mouseX, mouseY);

        button(context, font, doneX(), "done", isOver(mouseX, mouseY, doneX(), buttonY(),
            BUTTON_WIDTH, BUTTON_HEIGHT), true);
        button(context, font, clearX(), "clear all", isOver(mouseX, mouseY, clearX(), buttonY(),
            BUTTON_WIDTH, BUTTON_HEIGHT), !chosen.isEmpty());

        if (tooltip != null && !tooltip.isEmpty()) {
            renderTooltip(context, font, mouseX, mouseY);
        }
    }

    private void button(GuiGraphicsExtractor context, Font font, int x, String label,
                        boolean hovered, boolean live) {
        int y = buttonY();
        RenderUtil.roundedBorderedRect(context, x, y, x + BUTTON_WIDTH, y + BUTTON_HEIGHT,
            GuiTheme.CORNER, hovered ? GuiTheme.BG_ROW_HOVER : GuiTheme.BG_PANEL,
            hovered ? GuiTheme.accent() : GuiTheme.EDGE);
        context.guiRenderState.up();
        context.centeredText(font, label, x + BUTTON_WIDTH / 2,
            GuiTheme.textY(y, BUTTON_HEIGHT),
            live ? (hovered ? GuiTheme.accentText() : GuiTheme.TEXT) : GuiTheme.TEXT_FAINT);
    }

    private void renderColumn(GuiGraphicsExtractor context, Font font, int x, String header,
                              List<Entry<T>> list, ScrollBar bar, int mouseX, int mouseY) {
        int top = LIST_TOP;
        int h = listHeight();
        int total = list.size() * ROW_HEIGHT;
        bar.update(mouseY, total, h);
        boolean overflow = total > h;
        int rowW = overflow ? COL_WIDTH - GuiTheme.SCROLLBAR - 2 : COL_WIDTH;

        context.text(font, header, x + 2, top - 11, GuiTheme.TEXT_DIM, false);
        String tally = String.valueOf(list.size());
        context.text(font, tally, x + COL_WIDTH - font.width(tally), top - 11,
            GuiTheme.TEXT_FAINT, false);
        RenderUtil.roundedBorderedRect(context, x - 1, top - 1, x + COL_WIDTH + 1, top + h + 1,
            GuiTheme.CORNER, GuiTheme.BG_PANEL, GuiTheme.EDGE);
        context.guiRenderState.up();

        boolean mouseInside = isOver(mouseX, mouseY, x, top, rowW, h);
        context.enableScissor(x, top, x + rowW, top + h);
        int rowY = top - bar.getOffset();
        for (Entry<T> entry : list) {
            if (rowY + ROW_HEIGHT > top && rowY < top + h) {
                boolean hovered = mouseInside && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT;
                if (hovered) {
                    context.fill(x, rowY, x + rowW, rowY + ROW_HEIGHT, GuiTheme.BG_ROW_HOVER);
                    tooltip = entry.id();
                }
                context.fill(x, rowY + ROW_HEIGHT - 1, x + rowW, rowY + ROW_HEIGHT, GuiTheme.RULE);
                context.guiRenderState.up();
                if (!entry.icon().isEmpty()) {
                    context.item(entry.icon(), x + 3, rowY + 1);
                }
                context.guiRenderState.up();
                context.text(font, entry.label(), x + 24, GuiTheme.textY(rowY, ROW_HEIGHT - 1),
                    hovered ? GuiTheme.accentText() : GuiTheme.TEXT, false);
            }
            rowY += ROW_HEIGHT;
        }
        if (list.isEmpty()) {
            context.text(font, "nothing here", x + 6, GuiTheme.textY(top, ROW_HEIGHT),
                GuiTheme.TEXT_FAINT, false);
        }
        context.disableScissor();

        if (overflow) {
            int trackX = x + COL_WIDTH - GuiTheme.SCROLLBAR;
            bar.render(context, trackX, top, h, total,
                ScrollBar.isOverTrack(mouseX, mouseY, trackX, top, h));
        }
    }

    private void renderTooltip(GuiGraphicsExtractor context, Font font, int mouseX, int mouseY) {
        int w = font.width(tooltip);
        int tx = Math.max(2, Math.min(mouseX + 10, width - w - 12));
        int ty = Math.max(2, Math.min(mouseY + 10, height - 18));
        context.guiRenderState.up();
        RenderUtil.roundedBorderedRect(context, tx, ty, tx + w + 8, ty + 14, GuiTheme.CORNER,
            GuiTheme.BG_WINDOW, GuiTheme.accent());
        context.guiRenderState.up();
        context.text(font, tooltip, tx + 4, GuiTheme.textY(ty, 14), GuiTheme.TEXT, false);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x();
        double my = event.y();

        if (isOver(mx, my, doneX(), buttonY(), BUTTON_WIDTH, BUTTON_HEIGHT)) {
            onClose();
            return true;
        }
        if (isOver(mx, my, clearX(), buttonY(), BUTTON_WIDTH, BUTTON_HEIGHT)) {
            setting.clear();
            OfflineClient.INSTANCE.getConfigManager().saveSoon();
            refresh();
            return true;
        }
        if (clickColumn(mx, my, leftX(), available, leftBar, true)) {
            return true;
        }
        if (clickColumn(mx, my, rightX(), chosen, rightBar, false)) {
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    private boolean clickColumn(double mx, double my, int x, List<Entry<T>> list,
                                ScrollBar bar, boolean adding) {
        int h = listHeight();
        if (!isOver(mx, my, x, LIST_TOP, COL_WIDTH, h)) {
            return false;
        }
        int total = list.size() * ROW_HEIGHT;
        int trackX = x + COL_WIDTH - GuiTheme.SCROLLBAR;
        if (total > h && ScrollBar.isOverTrack(mx, my, trackX, LIST_TOP, h)) {
            bar.beginDrag((int) my);
            return true;
        }
        int index = (int) ((my - LIST_TOP + bar.getOffset()) / ROW_HEIGHT);
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
    public boolean mouseReleased(MouseButtonEvent event) {
        leftBar.release();
        rightBar.release();
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int dy = (int) Math.round(scrollY * 16);
        int h = listHeight();
        if (isOver(mouseX, mouseY, leftX(), LIST_TOP, COL_WIDTH, h)) {
            leftBar.scroll(dy, available.size() * ROW_HEIGHT, h);
            return true;
        }
        if (isOver(mouseX, mouseY, rightX(), LIST_TOP, COL_WIDTH, h)) {
            rightBar.scroll(dy, chosen.size() * ROW_HEIGHT, h);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_ENTER
            || key == GLFW.GLFW_KEY_KP_ENTER) {
            onClose();
            return true;
        }
        String before = search.get();
        if (search.keyPressed(event, TextField.ANY)) {
            if (!search.get().equals(before)) {
                refresh();
            }
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (search.charTyped((char) event.codepoint(), TextField.ANY)) {
            refresh();
            return true;
        }
        return super.charTyped(event);
    }

    // The same ClickGUI instance is kept with its panel positions.
    @Override
    public void onClose() {
        OfflineClient.INSTANCE.getConfigManager().saveNow();
        OfflineClient.MC.gui.setScreen(parent);
    }

    private static boolean isOver(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
}
