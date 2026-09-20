package com.jellypudding.offlineclient.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.modules.misc.ClickGuiModule;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.RenderUtil;
import com.jellypudding.offlineclient.util.SearchRank;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// The draggable panel style of the ClickGUI. One panel per category with the
// search bar floating over them and the tabs along the top.
public final class ClickGuiScreen extends GuiScreenBase {

    private static final int SEARCH_WIDTH = 156;
    private static final int SEARCH_WIDTH_MIN = 120;
    private static final int SEARCH_WIDTH_MAX = 460;

    // How close to an edge the pointer has to be to grab it.
    private static final int GRIP = 4;
    private static final int RESULTS_GAP = 6;
    // Room between the tabs and whatever a tab opens.
    private static final int TAB_GAP = 4;
    // How far the pointer travels before a press on the bar becomes a move.
    private static final int DRAG_SLACK = 3;

    // Layout of the panels on a first run or after a reset.
    private static final int TILE_MARGIN = 10;
    private static final int TILE_GAP = 8;
    private static final int FOOTER_HEIGHT = 12;
    private static final int PADDING = 4;
    private static final int TOOLTIP_WIDTH = 170;

    private static final String SEARCH_X = "searchX";
    private static final String SEARCH_Y = "searchY";
    private static final String SEARCH_W = "searchWidth";
    private static final String TAB_STATE = "tabs";

    private final List<Panel> panels = new ArrayList<>();
    private final List<ModuleRow> searchRows = new ArrayList<>();
    private final ScrollBar resultsScroll = new ScrollBar();

    private final TabStrip tabs = new TabStrip();
    private final Map<TabStrip.Tab, TabView> views = new EnumMap<>(TabStrip.Tab.class);
    private TabStrip.Tab openTab;

    private String tooltip;
    private String wrappedFor;
    private final List<String> wrappedLines = new ArrayList<>();

    // Panels with no saved layout. Tiled once the screen size is known.
    private final List<Panel> freshPanels = new ArrayList<>();

    // The saved stacking order. Higher draws on top. Only used whilst the
    // panels are built.
    private final Map<Panel, Integer> layers = new HashMap<>();

    private int searchWidth = SEARCH_WIDTH;
    private int searchX = -1;
    private int searchY = -1;
    private boolean resizingLeft;
    private boolean resizingRight;
    private boolean movingSearch;
    private boolean pressedBar;
    private int pressedX;
    private int pressedY;
    private int grabX;
    private int grabY;

    // The scale the layout was last checked against.
    private float drawnAt;

    public ClickGuiScreen() {
        JsonObject gui = OfflineClient.INSTANCE.getConfigManager().getGuiState();
        if (gui.has(SEARCH_W)) {
            searchWidth = Math.clamp(gui.get(SEARCH_W).getAsInt(),
                SEARCH_WIDTH_MIN, SEARCH_WIDTH_MAX);
        }
        if (gui.has(SEARCH_X) && gui.has(SEARCH_Y)) {
            searchX = gui.get(SEARCH_X).getAsInt();
            searchY = gui.get(SEARCH_Y).getAsInt();
        }
        int startX = TILE_MARGIN;
        for (Category category : Category.values()) {
            Panel panel = new Panel(category.getDisplayName(),
                OfflineClient.INSTANCE.getModuleManager().getByCategory(category),
                this, startX, TabStrip.bottom() + TILE_GAP);
            // Saved layouts override the default height cap right after.
            panel.setViewHeight(190);
            if (!restorePanelStateSafely(panel)) {
                freshPanels.add(panel);
            }
            panels.add(panel);
            startX += GuiTheme.PANEL_WIDTH + TILE_GAP;
        }
        // The panel that was on top last time comes back on top.
        panels.sort(Comparator.comparingInt(panel -> layers.getOrDefault(panel, 0)));
        layers.clear();

        views.put(TabStrip.Tab.FRIENDS, new TabView(GuiSources.friends(), this));
        views.put(TabStrip.Tab.MACROS, new TabView(GuiSources.macros(), this));
        views.put(TabStrip.Tab.PROFILES, new TabView(GuiSources.profiles(), this));
        restoreTabState(gui);
        restoreSearch();
    }

    private void restoreTabState(JsonObject gui) {
        if (!gui.has(TAB_STATE) || !gui.get(TAB_STATE).isJsonObject()) {
            return;
        }
        JsonObject state = gui.getAsJsonObject(TAB_STATE);
        for (var entry : views.entrySet()) {
            String key = entry.getKey().name();
            if (!state.has(key) || !state.get(key).isJsonObject()) {
                continue;
            }
            JsonObject box = state.getAsJsonObject(key);
            if (box.has("width")) {
                entry.getValue().setWidth(box.get("width").getAsInt());
            }
            if (box.has("height")) {
                entry.getValue().setHeight(box.get("height").getAsInt());
            }
        }
    }

    // A hand edited or corrupt layout must never stop the GUI opening.
    private boolean restorePanelStateSafely(Panel panel) {
        try {
            return restorePanelState(panel);
        } catch (RuntimeException e) {
            OfflineClient.LOG.warn("Ignoring a broken saved layout for {}", panel.getTitle(), e);
            return false;
        }
    }

    private boolean restorePanelState(Panel panel) {
        JsonObject gui = OfflineClient.INSTANCE.getConfigManager().getGuiState();
        if (!gui.has(panel.getTitle()) || !gui.get(panel.getTitle()).isJsonObject()) {
            return false;
        }
        JsonObject state = gui.getAsJsonObject(panel.getTitle());
        if (state.has("x") && state.has("y")) {
            panel.setPosition(state.get("x").getAsInt(), state.get("y").getAsInt());
        }
        if (state.has("collapsed")) {
            panel.setCollapsed(state.get("collapsed").getAsBoolean());
        }
        if (state.has("height")) {
            panel.setViewHeight(state.get("height").getAsInt());
        }
        if (state.has("width")) {
            panel.setWidth(state.get("width").getAsInt());
        }
        if (state.has("scroll")) {
            panel.setScrollOffset(state.get("scroll").getAsInt());
        }
        if (state.has("layer")) {
            layers.put(panel, state.get("layer").getAsInt());
        }
        if (state.has("expanded")) {
            JsonArray expanded = state.getAsJsonArray("expanded");
            for (ModuleRow row : panel.getRows()) {
                for (var name : expanded) {
                    if (row.getModule().getName().equals(name.getAsString())) {
                        row.setExpanded(true);
                    }
                }
            }
        }
        return true;
    }

    @Override
    protected void saveState() {
        JsonObject gui = OfflineClient.INSTANCE.getConfigManager().getGuiState();
        for (int layer = 0; layer < panels.size(); layer++) {
            Panel panel = panels.get(layer);
            JsonObject state = new JsonObject();
            state.addProperty("x", panel.getX());
            state.addProperty("y", panel.getY());
            state.addProperty("layer", layer);
            state.addProperty("collapsed", panel.isCollapsed());
            state.addProperty("height", panel.getViewHeight());
            state.addProperty("width", panel.getWidth());
            state.addProperty("scroll", panel.getScrollOffset());
            JsonArray expanded = new JsonArray();
            for (ModuleRow row : panel.getRows()) {
                if (row.isExpanded()) {
                    expanded.add(row.getModule().getName());
                }
            }
            state.add("expanded", expanded);
            gui.add(panel.getTitle(), state);
        }
        gui.addProperty(SEARCH_W, searchWidth);
        gui.addProperty(SEARCH_X, searchX);
        gui.addProperty(SEARCH_Y, searchY);

        JsonObject boxes = new JsonObject();
        for (var entry : views.entrySet()) {
            JsonObject box = new JsonObject();
            box.addProperty("width", entry.getValue().getWidth());
            box.addProperty("height", entry.getValue().getHeight());
            boxes.add(entry.getKey().name(), box);
        }
        gui.add(TAB_STATE, boxes);
        saveSearch();
        OfflineClient.INSTANCE.getConfigManager().saveNow();
    }

    @Override
    protected void releaseDrags() {
        resizingLeft = false;
        resizingRight = false;
        movingSearch = false;
        pressedBar = false;
        resultsScroll.release();
        for (Panel panel : panels) {
            panel.releaseDrags();
        }
        for (ModuleRow row : searchRows) {
            row.mouseReleased();
        }
        for (TabView view : views.values()) {
            view.release();
        }
    }

    @Override
    protected boolean isWindowStyle() {
        return false;
    }

    @Override
    protected void init() {
        super.init();
        tilePanels();
        rescuePanels();
    }

    // Panels saved on a bigger screen can end up outside the window. Every
    // resize and every change of scale gives them a place to come back to.
    private void rescuePanels() {
        drawnAt = scale();
        int rescued = 0;
        for (Panel panel : panels) {
            boolean offscreen = panel.getX() > viewWidth() - 24
                || panel.getX() < 24 - panel.getWidth()
                || panel.getY() > viewHeight() - 12
                || panel.getY() < 0;
            if (offscreen) {
                panel.setPosition(TILE_MARGIN + (rescued % 3) * (GuiTheme.PANEL_WIDTH + 12),
                    TabStrip.bottom() + TILE_GAP + (rescued / 3) * 60);
                rescued++;
            }
        }
        searchWidth = Math.clamp(searchWidth, SEARCH_WIDTH_MIN,
            Math.max(SEARCH_WIDTH_MIN, Math.min(SEARCH_WIDTH_MAX, viewWidth())));
        int floor = searchFloor();
        searchX = Math.clamp(searchX, 0, Math.max(0, viewWidth() - searchWidth));
        searchY = Math.clamp(searchY, floor, Math.max(floor, viewHeight() - SEARCH_HEIGHT));
    }

    // Lays untouched panels out in centred rows. Starting hard against the
    // left edge leaves slack on one side and looks lopsided on a first run.
    private void tilePanels() {
        if (searchX < 0 || searchY < 0) {
            searchX = (viewWidth() - searchWidth) / 2;
            searchY = TabStrip.bottom() + RESULTS_GAP;
        }
        if (freshPanels.isEmpty()) {
            return;
        }
        for (Panel panel : freshPanels) {
            panel.setCollapsed(true);
        }
        int usable = Math.max(GuiTheme.PANEL_WIDTH, viewWidth() - TILE_MARGIN * 2);
        int y = searchY + SEARCH_HEIGHT + RESULTS_GAP;
        int index = 0;
        while (index < freshPanels.size()) {
            int count = rowCount(index, usable);
            int x = (viewWidth() - rowWidth(index, count)) / 2;
            for (int i = 0; i < count; i++) {
                Panel panel = freshPanels.get(index + i);
                panel.setPosition(x, y);
                x += panel.getWidth() + TILE_GAP;
            }
            index += count;
            y += GuiTheme.HEADER_HEIGHT + TILE_GAP;
        }
        freshPanels.clear();
    }

    // How many panels from this one onwards fit a single row.
    private int rowCount(int from, int usable) {
        int count = 0;
        int used = 0;
        for (int i = from; i < freshPanels.size(); i++) {
            int next = used == 0 ? freshPanels.get(i).getWidth()
                : used + TILE_GAP + freshPanels.get(i).getWidth();
            if (count > 0 && next > usable) {
                break;
            }
            used = next;
            count++;
        }
        return count;
    }

    private int rowWidth(int from, int count) {
        int total = 0;
        for (int i = from; i < from + count; i++) {
            total += freshPanels.get(i).getWidth();
            if (i > from) {
                total += TILE_GAP;
            }
        }
        return total;
    }

    // The tabs are tested before the bar. A bar parked under them could
    // never be clicked again.
    private int searchFloor() {
        return showsTabs() ? TabStrip.bottom() : 0;
    }

    private int resultsTop() {
        return searchY + SEARCH_HEIGHT + RESULTS_GAP;
    }

    // Everything the rows need. Taller than the view means the box scrolls.
    private int resultsContentHeight() {
        int total = 0;
        for (ModuleRow row : searchRows) {
            total += row.getHeight();
        }
        return total;
    }

    // The screen below the box less a small margin.
    private int resultsRoom() {
        return Math.max(GuiTheme.ROW_HEIGHT + PADDING, viewHeight() - resultsTop() - 8);
    }

    // The clipped strip the rows are drawn into.
    private int resultsViewHeight() {
        if (searchRows.isEmpty()) {
            return 0;
        }
        return Math.min(resultsContentHeight(), resultsRoom() - PADDING);
    }

    private int resultsBoxHeight() {
        if (searchRows.isEmpty()) {
            return PADDING + FOOTER_HEIGHT;
        }
        return PADDING + resultsViewHeight();
    }

    private int resultsTrackX() {
        return ScrollBar.trackX(searchX + 2, searchWidth - 4);
    }

    // An expanded row can be taller than the view. Its top edge is pinned
    // into sight and the rest is reached by scrolling.
    private void keepVisible(ModuleRow target) {
        int view = resultsViewHeight();
        int top = 0;
        for (ModuleRow row : searchRows) {
            if (row == target) {
                break;
            }
            top += row.getHeight();
        }
        int offset = resultsScroll.getOffset();
        int bottom = top + target.getHeight();
        boolean outOfSight = bottom <= offset || top >= offset + view;
        if (outOfSight) {
            offset = top;
        } else if (target.getHeight() <= view) {
            // Only the part that is cut off is scrolled into view.
            if (bottom > offset + view) {
                offset = bottom - view;
            }
            if (top < offset) {
                offset = top;
            }
        }
        // A row taller than the view that is already partly in sight stays where it is.
        resultsScroll.setOffset(ScrollBar.clamp(offset, resultsContentHeight(), view));
    }

    @Override
    protected void renderGui(GuiGraphicsExtractor context, int mouseX, int mouseY, float partialTicks) {
        tooltip = null;
        Font font = OfflineClient.MC.font;
        if (drawnAt != scale()) {
            rescuePanels();
        }

        if (openTab != null && (!showsTabs() || !openTab.isShown())) {
            openTab = null;
        }
        if (openTab == null) {
            for (Panel panel : panels) {
                panel.render(context, mouseX, mouseY, viewWidth(), viewHeight());
            }
            renderSearch(context, font, mouseX, mouseY);
        } else {
            views.get(openTab).render(context, viewWidth(), viewHeight(),
                TabStrip.bottom() + TAB_GAP, mouseX, mouseY);
        }

        if (showsTabs()) {
            tabs.render(context, viewWidth(), mouseX, mouseY, openTab);
        }

        if (tooltip != null && !tooltip.isEmpty() && hoverHelp()) {
            RenderUtil.tooltip(context, font, wrap(tooltip), mouseX, mouseY,
                viewWidth(), viewHeight(), GuiTheme.bgTooltip(), GuiTheme.text());
        }
    }

    private static boolean showsTabs() {
        return OfflineClient.INSTANCE.getModuleManager().get(ClickGuiModule.class).showsTabs();
    }

    private void renderSearch(GuiGraphicsExtractor context, Font font, int mouseX, int mouseY) {
        if (movingSearch) {
            int floor = searchFloor();
            searchX = Math.clamp(mouseX - grabX, 0, Math.max(0, viewWidth() - searchWidth));
            searchY = Math.clamp(mouseY - grabY, floor,
                Math.max(floor, viewHeight() - SEARCH_HEIGHT));
        }
        if (resizingRight) {
            int room = Math.max(SEARCH_WIDTH_MIN, viewWidth() - searchX);
            searchWidth = Math.clamp(mouseX - searchX, SEARCH_WIDTH_MIN,
                Math.min(SEARCH_WIDTH_MAX, room));
        }
        if (resizingLeft) {
            int right = searchX + searchWidth;
            int wanted = Math.clamp(mouseX, Math.max(0, right - SEARCH_WIDTH_MAX),
                right - SEARCH_WIDTH_MIN);
            searchX = wanted;
            searchWidth = right - wanted;
        }
        RenderUtil.shadow(context, searchX, searchY, searchX + searchWidth,
            searchY + SEARCH_HEIGHT, 2);
        context.guiRenderState.up();
        renderSearchBox(context, font, searchX, searchY, searchWidth, mouseX, mouseY,
            searchRows.size());
        if (isSearching()) {
            renderSearchResults(context, font, mouseX, mouseY);
        }
    }

    // Either upright edge of the search bar or of the results box below it.
    private boolean overSearchEdge(double mx, double my, boolean left) {
        double edge = left ? searchX : searchX + searchWidth;
        if (Math.abs(mx - edge) > GRIP) {
            return false;
        }
        if (my >= searchY && my <= searchY + SEARCH_HEIGHT) {
            return true;
        }
        if (!isSearching()) {
            return false;
        }
        int top = resultsTop();
        return my >= top && my <= top + resultsBoxHeight();
    }

    private void renderSearchResults(GuiGraphicsExtractor context, Font font,
                                     int mouseX, int mouseY) {
        int x = searchX;
        int y = resultsTop();
        int box = resultsBoxHeight();
        RenderUtil.shadow(context, x, y, x + searchWidth, y + box, 3);
        context.guiRenderState.up();
        RenderUtil.roundedBorderedRect(context, x, y, x + searchWidth, y + box,
            GuiTheme.CORNER + 1, GuiTheme.bgWindow(), GuiTheme.accent());
        context.guiRenderState.up();

        int rowX = x + 2;
        int viewTop = y + 2;
        if (searchRows.isEmpty()) {
            context.text(font, "no matches", rowX + 5, GuiTheme.textY(viewTop, FOOTER_HEIGHT),
                GuiTheme.textDim(), false);
            return;
        }

        int view = resultsViewHeight();
        int total = resultsContentHeight();
        boolean scrollable = total > view;
        int rowW = ScrollBar.rowWidth(searchWidth - 4, total, view);
        resultsScroll.update(mouseY, total, view);

        // A slider being dragged still gets the real pointer position.
        boolean mouseInView = SettingWidget.isOver(mouseX, mouseY, rowX, viewTop, rowW, view);

        context.enableScissor(rowX, viewTop, rowX + rowW, viewTop + view);
        int rowY = viewTop - resultsScroll.getOffset();
        for (ModuleRow row : searchRows) {
            int rowH = row.getHeight();
            row.place(rowX, rowY, rowW, mouseX, mouseY, mouseInView);
            if (rowY + rowH > viewTop && rowY < viewTop + view) {
                row.render(context, mouseX, mouseY);
            }
            rowY += rowH;
        }
        context.disableScissor();

        if (scrollable) {
            int trackX = resultsTrackX();
            resultsScroll.render(context, trackX, viewTop, view, total,
                ScrollBar.isOverTrack(mouseX, mouseY, trackX, viewTop, view));
        }
    }

    private List<String> wrap(String text) {
        if (text.equals(wrappedFor)) {
            return wrappedLines;
        }
        Font font = OfflineClient.MC.font;
        wrappedFor = text;
        wrappedLines.clear();
        StringBuilder current = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = current.isEmpty() ? word : current + " " + word;
            if (font.width(candidate) > TOOLTIP_WIDTH && !current.isEmpty()) {
                wrappedLines.add(current.toString());
                current = new StringBuilder(word);
            } else {
                current = new StringBuilder(candidate);
            }
        }
        if (!current.isEmpty()) {
            wrappedLines.add(current.toString());
        }
        return wrappedLines;
    }

    @Override
    public void setTooltip(String tooltip) {
        this.tooltip = tooltip;
    }

    @Override
    protected boolean clickGui(double mx, double my, int button) {
        beginClick();
        pressedBar = false;

        if (showsTabs() && tabs.mouseClicked(mx, my, button, this::chooseTab)) {
            return true;
        }
        if (openTab != null) {
            return views.get(openTab).mouseClicked(mx, my, button);
        }

        // The search sits over the panels. It takes the click first.
        if (isSearching() && clickResults(mx, my, button)) {
            return true;
        }
        if (InputUtil.isLeft(button)
            && (overSearchEdge(mx, my, true) || overSearchEdge(mx, my, false))) {
            resizingLeft = overSearchEdge(mx, my, true);
            resizingRight = !resizingLeft;
            return true;
        }
        if (clickSearchBox(mx, my, searchX, searchY, searchWidth)) {
            pressedBar = InputUtil.isLeft(button);
            pressedX = (int) mx;
            pressedY = (int) my;
            grabX = (int) mx - searchX;
            grabY = (int) my - searchY;
            return true;
        }

        // The panel drawn on top gets the click first.
        for (int i = panels.size() - 1; i >= 0; i--) {
            Panel panel = panels.get(i);
            if (panel.mouseClicked(mx, my, button)) {
                panels.remove(i);
                panels.add(panel);
                searchFocused = false;
                return true;
            }
        }
        return false;
    }

    // Clicking the open tab goes back to the modules.
    private void chooseTab(TabStrip.Tab tab) {
        if (tab == TabStrip.Tab.HUD) {
            HudEditorScreen editor = HudEditorScreen.open();
            if (editor != null) {
                saveState();
                OfflineClient.MC.gui.setScreen(editor);
            }
            return;
        }
        openTab = openTab == tab ? null : tab;
        searchFocused = false;
    }

    // The border of the box is left alone. A click there falls through to
    // the edge resize.
    private boolean clickResults(double mx, double my, int button) {
        int rowX = searchX + 2;
        int rowsW = searchWidth - 4;
        int y = resultsTop();
        if (!SettingWidget.isOver(mx, my, rowX, y, rowsW, resultsBoxHeight())) {
            return false;
        }
        if (searchRows.isEmpty()) {
            return true;
        }

        int viewTop = y + 2;
        int view = resultsViewHeight();
        int trackX = resultsTrackX();
        if (resultsContentHeight() > view
            && ScrollBar.isOverTrack(mx, my, trackX, viewTop, view)) {
            resultsScroll.beginDrag((int) my);
            return true;
        }
        // Rows scrolled out of the strip are placed but not clickable.
        if (my < viewTop || my >= viewTop + view) {
            return true;
        }
        for (ModuleRow row : searchRows) {
            if (row.mouseClicked(mx, my, button)) {
                // Keys go to whatever the row is editing and not the search box.
                searchFocused = false;
                keepVisible(row);
                return true;
            }
        }
        return true;
    }

    @Override
    protected boolean releaseGui(double mx, double my, int button) {
        resizingLeft = false;
        resizingRight = false;
        movingSearch = false;
        pressedBar = false;
        resultsScroll.release();
        for (Panel panel : panels) {
            panel.mouseReleased();
        }
        for (ModuleRow row : searchRows) {
            row.mouseReleased();
        }
        for (TabView view : views.values()) {
            view.release();
        }
        return false;
    }

    // A press in the bar places the caret. Pulling away from that press
    // carries the bar with it instead.
    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (pressedBar && openTab == null && InputUtil.isLeft(event.button())) {
            double mx = toView(event.x());
            double my = toView(event.y());
            if (Math.abs(mx - pressedX) > DRAG_SLACK || Math.abs(my - pressedY) > DRAG_SLACK) {
                movingSearch = true;
            }
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    protected boolean scrollGui(double mx, double my, double amount) {
        if (openTab != null) {
            TabView view = views.get(openTab);
            if (view.isOver(mx, my)) {
                view.wheel(amount);
            }
            return true;
        }
        if (isSearching() && SettingWidget.isOver(mx, my, searchX, resultsTop(),
            searchWidth, resultsBoxHeight())) {
            int total = resultsContentHeight();
            int view = resultsViewHeight();
            resultsScroll.scroll(ScrollBar.wheelDelta(amount, total, view), total, view);
            return true;
        }
        for (int i = panels.size() - 1; i >= 0; i--) {
            Panel panel = panels.get(i);
            if (panel.isOver(mx, my)) {
                panel.wheel(amount);
                return true;
            }
        }
        return false;
    }

    // Escape steps back to the modules before it closes the GUI.
    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == InputConstants.KEY_ESCAPE && openTab != null && !isTyping()) {
            openTab = null;
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    protected void onSearchChanged() {
        // A row or a thumb held whilst its list is dropped would never let go.
        for (ModuleRow row : searchRows) {
            row.mouseReleased();
        }
        searchRows.clear();
        resultsScroll.release();
        resultsScroll.setOffset(0);
        if (!isSearching()) {
            return;
        }
        for (Module module : SearchRank.rank(OfflineClient.INSTANCE.getModuleManager().getAll(),
            module -> module.searchScore(search()))) {
            searchRows.add(new ModuleRow(module, this));
        }
    }
}
