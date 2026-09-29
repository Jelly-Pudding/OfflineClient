package com.jellypudding.offlineclient.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.modules.misc.ClickGuiModule;
import com.jellypudding.offlineclient.util.RenderUtil;
import com.jellypudding.offlineclient.util.SearchRank;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

// A single centred window with a category sidebar and a module list. The
// other half of the ClickGUI style setting.
public final class WindowGuiScreen extends GuiScreenBase {

    private static final int MARGIN = WindowParts.MARGIN;
    private static final int MODULE_ROW = 18;
    // Click zones on the left and right of a module row.
    private static final int FAV_ZONE = 20;
    private static final int ARROW_ZONE = 18;
    private static final int PILL_WIDTH = 22;
    private static final int PILL_HEIGHT = 10;

    private static final String FAVOURITES = "Favourites";
    private static final String ENABLED = "Enabled";
    private static final String ALL = "All";
    private static final String HINT =
        "Click a row to toggle. The arrow opens settings. The star favourites.";

    private static final String STATE_KEY = "window";

    private final List<String> tabs = new ArrayList<>();
    private final int[] tabCounts;
    private final Set<String> expanded = new LinkedHashSet<>();
    private final SettingWidget.Drag drag = new SettingWidget.Drag();
    private final ScrollBar scrollBar = new ScrollBar();

    // The modules on show. Refilled in place to avoid a per frame allocation.
    private final List<Module> listed = new ArrayList<>();
    // Everything the text in the box finds with the closest answer first.
    private final List<Module> ranked = new ArrayList<>();
    private String rankedFor = "";
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
        try {
            restoreState();
        } catch (RuntimeException e) {
            OfflineClient.LOG.warn("Ignoring a broken saved window layout", e);
        }
        restoreSearch();
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
        state.add("expanded", names(expanded));
        OfflineClient.INSTANCE.getConfigManager().getGuiState().add(STATE_KEY, state);
        saveSearch();
        OfflineClient.INSTANCE.getConfigManager().saveNow();
    }

    @Override
    protected void releaseDrags() {
        drag.cancel();
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
        return WindowParts.width(viewWidth());
    }

    private int windowHeight() {
        return WindowParts.height(viewHeight());
    }

    private int windowX() {
        return (viewWidth() - windowWidth()) / 2;
    }

    private int windowY() {
        return (viewHeight() - windowHeight()) / 2;
    }

    private int searchY() {
        return windowY() + WindowParts.TITLE_HEIGHT + 5;
    }

    private int contentTop() {
        return searchY() + SearchField.HEIGHT + 5;
    }

    private int contentBottom() {
        return windowY() + windowHeight() - descHeight() - 5;
    }

    // The bar at the foot of the window goes away with the hover help.
    private int descHeight() {
        return hoverHelp() ? WindowParts.FOOT_HEIGHT : 0;
    }

    private int contentHeight() {
        return Math.max(MODULE_ROW, contentBottom() - contentTop());
    }

    private int listX() {
        return windowX() + WindowParts.SIDEBAR_WIDTH;
    }

    private int listWidth() {
        return windowWidth() - WindowParts.SIDEBAR_WIDTH - MARGIN;
    }

    // A query that has not changed keeps the order it already has.
    private void refreshRanked() {
        String query = search();
        if (query.equals(rankedFor)) {
            return;
        }
        rankedFor = query;
        ranked.clear();
        if (!query.isEmpty()) {
            ranked.addAll(SearchRank.rank(allModules, module -> module.searchScore(query)));
        }
    }

    // A search covers every category and not just the chosen sidebar row.
    // The closest answer goes to the top. Favourites come first either way.
    private void refreshListed() {
        refreshRanked();
        listed.clear();
        boolean searching = isSearching();
        List<Module> source = searching ? ranked : allModules;
        for (int pass = 0; pass < 2; pass++) {
            boolean wantFavourite = pass == 0;
            for (int i = 0; i < source.size(); i++) {
                Module module = source.get(i);
                if (Favourites.has(module) != wantFavourite) {
                    continue;
                }
                if (searching || inTab(module, tab)) {
                    listed.add(module);
                }
            }
        }
    }

    private boolean inTab(Module module, String name) {
        return switch (name) {
            case FAVOURITES -> Favourites.has(module);
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
            if (Favourites.has(module)) {
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
    protected void renderGui(GuiGraphicsExtractor context, int mouseX, int mouseY, float partialTicks) {
        description = null;
        Font font = OfflineClient.MC.font;
        int wx = windowX();
        int wy = windowY();
        int ww = windowWidth();
        int wh = windowHeight();

        refreshListed();
        refreshCounts();

        WindowParts.frame(context, wx, wy, ww, wh);
        renderTitle(context, font, wx, wy, ww);
        renderSearchBox(context, font, wx + MARGIN, searchY(), ww - 2 * MARGIN,
            mouseX, mouseY, isSearching() ? listed.size() : -1);
        WindowParts.tabs(context, font, wx + MARGIN, contentTop(), WindowParts.tabWidth(), contentHeight(),
            tabs, i -> String.valueOf(tabCounts[i]), tabs.indexOf(tab), mouseX, mouseY);
        renderList(context, font, mouseX, mouseY);
        if (hoverHelp()) {
            boolean empty = description == null || description.isEmpty();
            WindowParts.footer(context, font, wx, wy, ww, wh, empty ? HINT : description,
                empty ? GuiTheme.textFaint() : GuiTheme.text());
        }
    }

    private void renderTitle(GuiGraphicsExtractor context, Font font, int wx, int wy, int ww) {
        int titleY = WindowParts.titleY(wy);
        ClickGuiModule gui = OfflineClient.INSTANCE.getModuleManager().get(ClickGuiModule.class);
        context.text(font, OfflineClient.NAME, wx + 10, titleY, gui.titleColor(), false);
        context.text(font, "v" + OfflineClient.VERSION,
            wx + 14 + font.width(OfflineClient.NAME), titleY, gui.versionColor(), false);
        int on = tabCounts[Category.values().length + 1];
        String tally = on + " enabled";
        context.text(font, tally, wx + ww - 10 - font.width(tally), titleY,
            on > 0 ? GuiTheme.accentText() : GuiTheme.textFaint(), false);
    }

    private void renderList(GuiGraphicsExtractor context, Font font, int mouseX, int mouseY) {
        int x = listX();
        int top = contentTop();
        int h = contentHeight();
        int w = listWidth();
        int total = totalHeight();
        boolean overflow = total > h;
        int rowW = ScrollBar.rowWidth(w, total, h);

        scrollBar.update(mouseY, total, h);

        RenderUtil.roundedRect(context, x, top, x + w, top + h, GuiTheme.CORNER, GuiTheme.bgPanel());
        context.guiRenderState.up();

        boolean mouseInView = SettingWidget.isOver(mouseX, mouseY, x, top, rowW, h);
        drag.follow(SettingWidget.blockContentX(x), SettingWidget.blockContentWidth(rowW), mouseX, mouseY);

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
                GuiTheme.textFaint(), false);
        }
        context.disableScissor();

        if (overflow) {
            int trackX = ScrollBar.trackX(x, w);
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
            ? GuiTheme.accentOn(GuiTheme.bgRow(), hovered ? 0.42f : 0.26f)
            : (hovered ? GuiTheme.bgRowHover() : GuiTheme.bgRow());
        RenderUtil.roundedRect(context, x + 2, rowTop, x + w - 2, rowTop + rowH,
            GuiTheme.CORNER, bg);
        context.guiRenderState.up();
        if (on) {
            RenderUtil.roundedRect(context, x + 2, rowTop, x + 4, rowTop + rowH, 1,
                GuiTheme.accent());
        }

        boolean favourite = Favourites.has(module);
        RenderUtil.star(context, x + 8, y + (MODULE_ROW - RenderUtil.STAR_SIZE) / 2,
            favourite ? GuiTheme.STAR : (hovered ? GuiTheme.textDim() : GuiTheme.textFaint()),
            favourite);

        int ty = GuiTheme.textY(y, MODULE_ROW);
        int pillRight = x + w - ARROW_ZONE - 2;
        int pillLeft = pillRight - PILL_WIDTH;
        context.text(font, module.getName(), x + FAV_ZONE + 2, ty,
            on ? GuiTheme.text() : GuiTheme.textDim(), false);

        String suffix = on ? module.getSuffix() : null;
        int suffixX = x + FAV_ZONE + 5 + font.width(module.getName());
        int suffixRoom = pillLeft - 4 - suffixX;
        if (suffix != null && suffixRoom > 12) {
            context.text(font, SettingWidget.trimEnd(font, suffix, suffixRoom), suffixX, ty,
                GuiTheme.textFaint(), false);
        }

        if (module.isTogglable()) {
            int pillTop = y + (MODULE_ROW - PILL_HEIGHT) / 2;
            RenderUtil.toggle(context, pillLeft, pillTop, PILL_WIDTH, PILL_HEIGHT, on,
                GuiTheme.GREEN, GuiTheme.bgSetting(),
                on ? 0xFF0B2415 : (hovered ? GuiTheme.text() : GuiTheme.textDim()));
        }
        RenderUtil.chevron(context, x + w - 14, y + (MODULE_ROW - RenderUtil.CHEVRON_HEIGHT) / 2, !open,
            hovered ? GuiTheme.text() : GuiTheme.textDim());

        if (hovered) {
            description = module.getDescription();
        }
        if (open) {
            SettingWidget.renderBlock(context, font, module, x, y + MODULE_ROW, w,
                mouseX, mouseY, hoverAllowed, settings);
        }
    }

    @Override
    public void setTooltip(String tooltip) {
        description = tooltip;
    }

    @Override
    protected boolean clickGui(double mx, double my, int button) {
        settings.beginClick();
        return clickSearchBox(mx, my) != SearchField.Click.MISSED
            || clickSidebar(mx, my)
            || clickList(mx, my, button);
    }

    private boolean clickSidebar(double mx, double my) {
        int index = WindowParts.tabAt(mx, my, windowX() + MARGIN, contentTop(), WindowParts.tabWidth(),
            contentHeight(), tabs.size());
        if (index < 0) {
            return false;
        }
        tab = tabs.get(index);
        if (isSearching()) {
            searchBar.clear();
        }
        searchBar.setFocused(false);
        scrollBar.setOffset(0);
        return true;
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
        int rowW = ScrollBar.rowWidth(w, total, h);

        int trackX = ScrollBar.trackX(x, w);
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
                    button, settings, drag);
                return true;
            }
            rowY += rowH;
        }
        return true;
    }

    private void clickModule(Module module, double mx, int x, int w, int button) {
        String name = module.getName();
        ModuleRow.clickModule(module, button, mx < x + FAV_ZONE, mx >= x + w - ARROW_ZONE, () -> {
            if (!expanded.remove(name)) {
                expanded.add(name);
            }
        });
    }

    @Override
    protected boolean releaseGui(double mx, double my, int button) {
        scrollBar.release();
        drag.release();
        return false;
    }

    @Override
    protected boolean scrollGui(double mx, double my, double amount) {
        int top = contentTop();
        int h = contentHeight();
        if (SettingWidget.isOver(mx, my, listX(), top, listWidth(), h)) {
            refreshListed();
            scrollBar.scroll(ScrollBar.wheelDelta(amount, totalHeight(), h), totalHeight(), h);
            return true;
        }
        return false;
    }
}
