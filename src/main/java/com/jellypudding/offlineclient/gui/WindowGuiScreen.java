package com.jellypudding.offlineclient.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.RenderUtil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A single centred window with a category sidebar and a module list. The
 * other half of the ClickGUI style setting.
 */
public final class WindowGuiScreen extends GuiScreenBase {

    private static final int MAX_WIDTH = 640;
    private static final int MAX_HEIGHT = 400;
    private static final int MIN_WIDTH = 300;
    private static final int MIN_HEIGHT = 180;
    private static final int TITLE_HEIGHT = 22;
    private static final int DESC_HEIGHT = 18;
    private static final int SIDEBAR_WIDTH = 108;
    private static final int MARGIN = 6;
    private static final int SIDE_ROW = 18;
    private static final int SIDE_ROW_MIN = 12;
    private static final int MODULE_ROW = 18;
    // Click zones on the left and right of a module row.
    private static final int FAV_ZONE = 20;
    private static final int ARROW_ZONE = 18;
    private static final int PILL_WIDTH = 22;
    private static final int PILL_HEIGHT = 10;
    private static final int STAR_COLOR = 0xFFF2C744;

    private static final String FAVOURITES = "Favourites";
    private static final String ENABLED = "Enabled";
    private static final String ALL = "All";
    private static final String HINT =
        "Click a row to toggle. The arrow opens settings. The star favourites.";

    private static final String STATE_KEY = "window";

    private final List<String> tabs = new ArrayList<>();
    private final int[] tabCounts;
    private final Set<String> favourites = new LinkedHashSet<>();
    private final Set<String> expanded = new LinkedHashSet<>();
    private final SettingWidget.Drag drag = new SettingWidget.Drag();
    private final ScrollBar scrollBar = new ScrollBar();

    // The modules on show. Refilled in place to avoid a per frame allocation.
    private final List<Module> listed = new ArrayList<>();
    // getAll builds a fresh list on every call.
    private final List<Module> allModules =
        OfflineClient.INSTANCE.getModuleManager().getAll();

    private String tab;
    private String description;

    public WindowGuiScreen() {
        for (Category category : Category.values()) {
            tabs.add(category.getDisplayName());
        }
        tabs.add(FAVOURITES);
        tabs.add(ENABLED);
        tabs.add(ALL);
        tabCounts = new int[tabs.size()];
        tab = tabs.getFirst();
        restoreState();
    }

    private void restoreState() {
        JsonObject gui = OfflineClient.INSTANCE.getConfigManager().getGuiState();
        if (!gui.has(STATE_KEY) || !gui.get(STATE_KEY).isJsonObject()) {
            return;
        }
        JsonObject state = gui.getAsJsonObject(STATE_KEY);
        if (state.has("tab") && tabs.contains(state.get("tab").getAsString())) {
            tab = state.get("tab").getAsString();
        }
        if (state.has("scroll")) {
            scrollBar.setOffset(state.get("scroll").getAsInt());
        }
        readNames(state, "favourites", favourites);
        readNames(state, "expanded", expanded);
    }

    private static void readNames(JsonObject state, String key, Set<String> into) {
        if (!state.has(key) || !state.get(key).isJsonArray()) {
            return;
        }
        for (JsonElement element : state.getAsJsonArray(key)) {
            into.add(element.getAsString());
        }
    }

    private static JsonArray names(Set<String> from) {
        JsonArray array = new JsonArray();
        from.forEach(array::add);
        return array;
    }

    // The panel layout keeps its own keys.
    @Override
    protected void saveState() {
        JsonObject state = new JsonObject();
        state.addProperty("tab", tab);
        state.addProperty("scroll", scrollBar.getOffset());
        state.add("favourites", names(favourites));
        state.add("expanded", names(expanded));
        OfflineClient.INSTANCE.getConfigManager().getGuiState().add(STATE_KEY, state);
        OfflineClient.INSTANCE.getConfigManager().saveNow();
    }

    @Override
    protected void releaseDrags() {
        drag.release();
        scrollBar.release();
    }

    @Override
    protected boolean isWindowStyle() {
        return true;
    }

    @Override
    protected void onSearchChanged() {
        scrollBar.setOffset(0);
    }

    private int windowWidth() {
        return Math.clamp(width - 20, MIN_WIDTH, MAX_WIDTH);
    }

    private int windowHeight() {
        return Math.clamp(height - 20, MIN_HEIGHT, MAX_HEIGHT);
    }

    private int windowX() {
        return (width - windowWidth()) / 2;
    }

    private int windowY() {
        return (height - windowHeight()) / 2;
    }

    private int searchY() {
        return windowY() + TITLE_HEIGHT + 5;
    }

    private int contentTop() {
        return searchY() + SEARCH_HEIGHT + 5;
    }

    private int contentBottom() {
        return windowY() + windowHeight() - DESC_HEIGHT - 5;
    }

    private int contentHeight() {
        return Math.max(MODULE_ROW, contentBottom() - contentTop());
    }

    private int listX() {
        return windowX() + SIDEBAR_WIDTH;
    }

    private int listWidth() {
        return windowWidth() - SIDEBAR_WIDTH - MARGIN;
    }

    private int sidebarWidth() {
        return SIDEBAR_WIDTH - MARGIN - 4;
    }

    // Sidebar rows tighten up rather than run off a short window.
    private int sideRow() {
        return Math.clamp(contentHeight() / tabs.size(), SIDE_ROW_MIN, SIDE_ROW);
    }

    /**
     * A search covers every category rather than the chosen sidebar row.
     * Favourites come first.
     */
    private void refreshListed() {
        listed.clear();
        for (int pass = 0; pass < 2; pass++) {
            boolean wantFavourite = pass == 0;
            for (int i = 0; i < allModules.size(); i++) {
                Module module = allModules.get(i);
                if (favourites.contains(module.getName()) != wantFavourite) {
                    continue;
                }
                if (isSearching() ? module.matchesSearch(search()) : inTab(module, tab)) {
                    listed.add(module);
                }
            }
        }
    }

    private boolean inTab(Module module, String name) {
        return switch (name) {
            case FAVOURITES -> favourites.contains(module.getName());
            case ENABLED -> module.isEnabled();
            case ALL -> true;
            default -> module.getCategory().getDisplayName().equals(name);
        };
    }

    private void refreshCounts() {
        Arrays.fill(tabCounts, 0);
        int categories = Category.values().length;
        for (Module module : allModules) {
            tabCounts[module.getCategory().ordinal()]++;
            if (favourites.contains(module.getName())) {
                tabCounts[categories]++;
            }
            if (module.isEnabled()) {
                tabCounts[categories + 1]++;
            }
            tabCounts[categories + 2]++;
        }
    }

    private int heightOf(Module module) {
        if (!expanded.contains(module.getName())) {
            return MODULE_ROW;
        }
        return MODULE_ROW + SettingWidget.blockHeight(module);
    }

    private int totalHeight() {
        int total = 0;
        for (int i = 0; i < listed.size(); i++) {
            total += heightOf(listed.get(i));
        }
        return total;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float partialTicks) {
        description = null;
        Font font = OfflineClient.MC.font;
        int wx = windowX();
        int wy = windowY();
        int ww = windowWidth();
        int wh = windowHeight();

        refreshListed();
        refreshCounts();

        RenderUtil.shadow(context, wx, wy, wx + ww, wy + wh, 4);
        context.guiRenderState.up();
        RenderUtil.roundedBorderedRect(context, wx, wy, wx + ww, wy + wh,
            GuiTheme.CORNER + 2, GuiTheme.BG_WINDOW, GuiTheme.EDGE);
        context.guiRenderState.up();

        renderTitle(context, font, wx, wy, ww);
        renderSearchBox(context, font, wx + MARGIN, searchY(), ww - 2 * MARGIN,
            mouseX, mouseY, isSearching() ? listed.size() : -1);
        renderSidebar(context, font, mouseX, mouseY);
        renderList(context, font, mouseX, mouseY);
        renderDescription(context, font, wx, wy, ww, wh);
    }

    private void renderTitle(GuiGraphicsExtractor context, Font font, int wx, int wy, int ww) {
        RenderUtil.roundedRect(context, wx + 1, wy + 1, wx + ww - 1, wy + TITLE_HEIGHT,
            GuiTheme.CORNER + 1, GuiTheme.BG_HEADER, true, false);
        context.guiRenderState.up();
        int titleY = GuiTheme.textY(wy, TITLE_HEIGHT - 2);
        context.text(font, OfflineClient.NAME, wx + 10, titleY, GuiTheme.TEXT, false);
        context.text(font, "v" + OfflineClient.VERSION,
            wx + 14 + font.width(OfflineClient.NAME), titleY, GuiTheme.TEXT_FAINT, false);
        int on = tabCounts[Category.values().length + 1];
        String tally = on + " enabled";
        context.text(font, tally, wx + ww - 10 - font.width(tally), titleY,
            on > 0 ? GuiTheme.accentText() : GuiTheme.TEXT_FAINT, false);
        context.fill(wx + 1, wy + TITLE_HEIGHT - 1, wx + ww - 1, wy + TITLE_HEIGHT + 1,
            GuiTheme.accent());
    }

    private void renderSidebar(GuiGraphicsExtractor context, Font font, int mouseX, int mouseY) {
        int x = windowX() + MARGIN;
        int w = sidebarWidth();
        int top = contentTop();
        int pitch = sideRow();
        int h = pitch - 2;
        int y = top;
        context.enableScissor(x, top, x + w, top + contentHeight());
        for (int i = 0; i < tabs.size(); i++) {
            String name = tabs.get(i);
            boolean selected = name.equals(tab);
            boolean hovered = SettingWidget.isOver(mouseX, mouseY, x, y, w, h);
            if (selected || hovered) {
                RenderUtil.roundedRect(context, x, y, x + w, y + h, GuiTheme.CORNER,
                    selected ? GuiTheme.accentOn(GuiTheme.BG_ROW, 0.35f) : GuiTheme.BG_ROW_HOVER);
                context.guiRenderState.up();
            }
            if (selected) {
                RenderUtil.roundedRect(context, x, y + 2, x + 2, y + h - 2, 1, GuiTheme.accent());
            }
            int ty = GuiTheme.textY(y, h);
            String tally = String.valueOf(tabCounts[i]);
            int tallyX = x + w - MARGIN - font.width(tally);
            context.text(font, SettingWidget.trimEnd(font, name, tallyX - x - 12), x + 8, ty,
                selected || hovered ? GuiTheme.TEXT : GuiTheme.TEXT_DIM, false);
            context.text(font, tally, tallyX, ty,
                selected ? GuiTheme.TEXT_DIM : GuiTheme.TEXT_FAINT, false);
            y += pitch;
        }
        context.disableScissor();
    }

    private void renderList(GuiGraphicsExtractor context, Font font, int mouseX, int mouseY) {
        int x = listX();
        int top = contentTop();
        int h = contentHeight();
        int w = listWidth();
        int total = totalHeight();
        boolean overflow = total > h;
        int rowW = overflow ? w - GuiTheme.SCROLLBAR - 2 : w;

        scrollBar.update(mouseY, total, h);

        RenderUtil.roundedRect(context, x, top, x + w, top + h, GuiTheme.CORNER, GuiTheme.BG_PANEL);
        context.guiRenderState.up();

        boolean mouseInView = SettingWidget.isOver(mouseX, mouseY, x, top, rowW, h);
        drag.follow(SettingWidget.blockContentX(x), SettingWidget.blockContentWidth(rowW), mouseX);

        context.enableScissor(x, top, x + rowW, top + h);
        int rowY = top - scrollBar.getOffset();
        for (int i = 0; i < listed.size(); i++) {
            Module module = listed.get(i);
            int rowH = heightOf(module);
            if (rowY + rowH > top && rowY < top + h) {
                renderModule(context, font, module, x, rowY, rowW, mouseX, mouseY, mouseInView);
            }
            rowY += rowH;
        }
        if (listed.isEmpty()) {
            String empty = isSearching() ? "no matches" : "nothing here";
            context.text(font, empty, x + 8, GuiTheme.textY(top, MODULE_ROW),
                GuiTheme.TEXT_FAINT, false);
        }
        context.disableScissor();

        if (overflow) {
            int trackX = x + w - GuiTheme.SCROLLBAR;
            scrollBar.render(context, trackX, top, h, total,
                ScrollBar.isOverTrack(mouseX, mouseY, trackX, top, h));
        }
    }

    private void renderModule(GuiGraphicsExtractor context, Font font, Module module,
                              int x, int y, int w, int mouseX, int mouseY, boolean hoverAllowed) {
        boolean open = expanded.contains(module.getName());
        boolean on = module.isEnabled();
        boolean hovered = hoverAllowed && SettingWidget.isOver(mouseX, mouseY, x, y, w, MODULE_ROW);
        int rowH = MODULE_ROW - 2;
        int rowTop = y + 1;

        int bg = on
            ? GuiTheme.accentOn(GuiTheme.BG_ROW, hovered ? 0.42f : 0.26f)
            : (hovered ? GuiTheme.BG_ROW_HOVER : GuiTheme.BG_ROW);
        RenderUtil.roundedRect(context, x + 2, rowTop, x + w - 2, rowTop + rowH,
            GuiTheme.CORNER, bg);
        context.guiRenderState.up();
        if (on) {
            RenderUtil.roundedRect(context, x + 2, rowTop, x + 4, rowTop + rowH, 1,
                GuiTheme.accent());
        }

        boolean favourite = favourites.contains(module.getName());
        RenderUtil.star(context, x + 8, y + (MODULE_ROW - 7) / 2,
            favourite ? STAR_COLOR : (hovered ? GuiTheme.TEXT_FAINT : ColorUtil.withAlpha(GuiTheme.TEXT_FAINT, 90)));

        int ty = GuiTheme.textY(y, MODULE_ROW);
        int pillRight = x + w - ARROW_ZONE - 2;
        int pillLeft = pillRight - PILL_WIDTH;
        context.text(font, module.getName(), x + FAV_ZONE + 2, ty,
            on ? GuiTheme.TEXT : GuiTheme.TEXT_DIM, false);

        String suffix = on ? module.getSuffix() : null;
        int suffixX = x + FAV_ZONE + 5 + font.width(module.getName());
        int suffixRoom = pillLeft - 4 - suffixX;
        if (suffix != null && suffixRoom > 12) {
            context.text(font, SettingWidget.trimEnd(font, suffix, suffixRoom), suffixX, ty,
                GuiTheme.TEXT_FAINT, false);
        }

        if (module.isTogglable()) {
            int pillTop = y + (MODULE_ROW - PILL_HEIGHT) / 2;
            RenderUtil.toggle(context, pillLeft, pillTop, PILL_WIDTH, PILL_HEIGHT, on,
                GuiTheme.GREEN, GuiTheme.BG_SETTING,
                on ? 0xFF0B2415 : (hovered ? GuiTheme.TEXT : GuiTheme.TEXT_DIM));
        }
        RenderUtil.chevron(context, x + w - 14, y + (MODULE_ROW - 3) / 2, !open,
            hovered ? GuiTheme.TEXT : GuiTheme.TEXT_DIM);

        if (hovered) {
            description = module.getDescription();
        }
        if (open) {
            SettingWidget.renderBlock(context, font, module, x, y + MODULE_ROW, w,
                mouseX, mouseY, hoverAllowed, this);
        }
    }

    private void renderDescription(GuiGraphicsExtractor context, Font font,
                                   int wx, int wy, int ww, int wh) {
        int x = wx + 1;
        int y = wy + wh - DESC_HEIGHT - 1;
        RenderUtil.roundedRect(context, x, y, x + ww - 2, y + DESC_HEIGHT,
            GuiTheme.CORNER + 1, GuiTheme.BG_HEADER, false, true);
        context.fill(x, y, x + ww - 2, y + 1, GuiTheme.EDGE);
        context.guiRenderState.up();
        boolean empty = description == null || description.isEmpty();
        String text = empty ? HINT : description;
        context.text(font, SettingWidget.trimEnd(font, text, ww - 20), x + 9,
            GuiTheme.textY(y + 1, DESC_HEIGHT), empty ? GuiTheme.TEXT_FAINT : GuiTheme.TEXT, false);
    }

    @Override
    public void setTooltip(String tooltip) {
        description = tooltip;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x();
        double my = event.y();
        int button = event.button();

        beginClick();

        if (clickSearchBox(mx, my, windowX() + MARGIN, searchY(), windowWidth() - 2 * MARGIN)) {
            return true;
        }
        if (clickSidebar(mx, my)) {
            return true;
        }
        if (clickList(mx, my, button)) {
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    private boolean clickSidebar(double mx, double my) {
        int x = windowX() + MARGIN;
        int w = sidebarWidth();
        int pitch = sideRow();
        int y = contentTop();
        int bottom = y + contentHeight();
        for (String name : tabs) {
            if (y >= bottom) {
                break;
            }
            if (SettingWidget.isOver(mx, my, x, y, w, pitch - 2)) {
                tab = name;
                if (isSearching()) {
                    searchBox.clear();
                    onSearchChanged();
                }
                searchFocused = false;
                scrollBar.setOffset(0);
                return true;
            }
            y += pitch;
        }
        return false;
    }

    private boolean clickList(double mx, double my, int button) {
        int x = listX();
        int top = contentTop();
        int h = contentHeight();
        int w = listWidth();
        if (!SettingWidget.isOver(mx, my, x, top, w, h)) {
            return false;
        }
        refreshListed();
        int total = totalHeight();
        boolean overflow = total > h;
        int rowW = overflow ? w - GuiTheme.SCROLLBAR - 2 : w;

        int trackX = x + w - GuiTheme.SCROLLBAR;
        if (overflow && ScrollBar.isOverTrack(mx, my, trackX, top, h)) {
            scrollBar.beginDrag((int) my);
            return true;
        }

        int rowY = top - scrollBar.getOffset();
        for (int i = 0; i < listed.size(); i++) {
            Module module = listed.get(i);
            int rowH = heightOf(module);
            if (my >= rowY && my < rowY + MODULE_ROW) {
                clickModule(module, mx, x, rowW, button);
                return true;
            }
            if (my >= rowY + MODULE_ROW && my < rowY + rowH) {
                SettingWidget.clickBlock(module, mx, my, x, rowY + MODULE_ROW, rowW,
                    button, this, drag);
                return true;
            }
            rowY += rowH;
        }
        return true;
    }

    private void clickModule(Module module, double mx, int x, int w, int button) {
        if (button != 0 && button != 1) {
            return;
        }
        String name = module.getName();
        if (button == 0 && mx < x + FAV_ZONE) {
            if (!favourites.remove(name)) {
                favourites.add(name);
            }
            return;
        }
        if (button == 1 || mx >= x + w - ARROW_ZONE || !module.isTogglable()) {
            if (!expanded.remove(name)) {
                expanded.add(name);
            }
            return;
        }
        module.toggle();
        OfflineClient.INSTANCE.getConfigManager().saveSoon();
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        scrollBar.release();
        drag.release();
        checkStyle();
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int top = contentTop();
        int h = contentHeight();
        if (SettingWidget.isOver(mouseX, mouseY, listX(), top, listWidth(), h)) {
            refreshListed();
            scrollBar.scroll((int) Math.round(scrollY * 16), totalHeight(), h);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (handleCommonKey(event)) {
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (handleCommonChar((char) event.codepoint())) {
            return true;
        }
        return super.charTyped(event);
    }
}
