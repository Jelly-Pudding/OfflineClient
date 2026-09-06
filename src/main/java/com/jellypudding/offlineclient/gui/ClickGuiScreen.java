package com.jellypudding.offlineclient.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.util.RenderUtil;
import com.jellypudding.offlineclient.util.SearchRank;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// The draggable panel style of the ClickGUI. One panel per category.
public final class ClickGuiScreen extends GuiScreenBase {

    private static final int SEARCH_WIDTH = 156;
    private static final int SEARCH_WIDTH_MIN = 120;
    private static final int SEARCH_WIDTH_MAX = 460;

    // How close to an edge the pointer has to be to grab it.
    private static final int GRIP = 4;
    private static final int SEARCH_TOP = 6;
    private static final int RESULTS_GAP = 6;

    // Layout of the panels on a first run or after a reset.
    private static final int TILE_MARGIN = 10;
    private static final int TILE_GAP = 8;
    private static final int TILE_TOP = 30;
    private static final int FOOTER_HEIGHT = 12;
    private static final int PADDING = 4;
    private static final int TOOLTIP_WIDTH = 170;

    private final List<Panel> panels = new ArrayList<>();
    private final List<ModuleRow> searchRows = new ArrayList<>();
    private final ScrollBar resultsScroll = new ScrollBar();

    private String tooltip;
    private String wrappedFor;
    private final List<String> wrappedLines = new ArrayList<>();

    // Where a panel sat before the search results pushed it aside.
    private record Home(int x, int y) {
    }

    private final Map<Panel, Home> nudged = new HashMap<>();

    // Panels with no saved layout. Tiled once the screen size is known.
    private final List<Panel> freshPanels = new ArrayList<>();

    // The saved stacking order. Higher draws on top. Only used whilst the
    // panels are built.
    private final Map<Panel, Integer> layers = new HashMap<>();

    // The results box is centred. Dragging either edge widens it both ways.
    private int searchWidth = SEARCH_WIDTH;
    private boolean resizingSearch;

    public ClickGuiScreen() {
        JsonObject gui = OfflineClient.INSTANCE.getConfigManager().getGuiState();
        if (gui.has("searchWidth")) {
            searchWidth = Math.clamp(gui.get("searchWidth").getAsInt(),
                SEARCH_WIDTH_MIN, SEARCH_WIDTH_MAX);
        }
        int startX = 10;
        for (Category category : Category.values()) {
            Panel panel = new Panel(category.getDisplayName(),
                OfflineClient.INSTANCE.getModuleManager().getByCategory(category),
                this, startX, 30);
            // Saved layouts override the default height cap right after.
            panel.setViewHeight(190);
            if (!restorePanelStateSafely(panel)) {
                freshPanels.add(panel);
            }
            panels.add(panel);
            startX += GuiTheme.PANEL_WIDTH + 8;
        }
        // The panel that was on top last time comes back on top.
        panels.sort(Comparator.comparingInt(panel -> layers.getOrDefault(panel, 0)));
        layers.clear();
        restoreSearch();
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
        restoreNudged();
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
        gui.addProperty("searchWidth", searchWidth);
        saveSearch();
        OfflineClient.INSTANCE.getConfigManager().saveNow();
    }

    @Override
    protected void releaseDrags() {
        resizingSearch = false;
        resultsScroll.release();
        for (Panel panel : panels) {
            panel.releaseDrags();
        }
        for (ModuleRow row : searchRows) {
            row.mouseReleased();
        }
    }

    @Override
    protected boolean isWindowStyle() {
        return false;
    }

    // Panels saved on a bigger screen can end up outside the window. This
    // runs again on every resize.
    @Override
    protected void init() {
        super.init();
        // A search running across a resize would leave panels pinned aside.
        restoreNudged();
        tilePanels();
        int rescued = 0;
        for (Panel panel : panels) {
            boolean offscreen = panel.getX() > width - 24
                || panel.getX() < 24 - panel.getWidth()
                || panel.getY() > height - 12
                || panel.getY() < 0;
            if (offscreen) {
                panel.setPosition(10 + (rescued % 3) * (GuiTheme.PANEL_WIDTH + 12),
                    30 + (rescued / 3) * 60);
                rescued++;
            }
        }
    }

    private void restoreNudged() {
        for (var entry : nudged.entrySet()) {
            entry.getKey().setPosition(entry.getValue().x(), entry.getValue().y());
        }
        nudged.clear();
    }

    // Lays untouched panels out in centred rows. Starting hard against the
    // left edge leaves slack on one side and looks lopsided on a first run.
    private void tilePanels() {
        if (freshPanels.isEmpty()) {
            return;
        }
        for (Panel panel : freshPanels) {
            panel.setCollapsed(true);
        }
        int usable = Math.max(GuiTheme.PANEL_WIDTH, width - TILE_MARGIN * 2);
        int y = TILE_TOP;
        int index = 0;
        while (index < freshPanels.size()) {
            int count = rowCount(index, usable);
            int x = (width - rowWidth(index, count)) / 2;
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

    private int searchX() {
        return width / 2 - searchWidth / 2;
    }

    private int resultsTop() {
        return SEARCH_TOP + SEARCH_HEIGHT + RESULTS_GAP;
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
        return Math.max(GuiTheme.ROW_HEIGHT + PADDING, height - resultsTop() - 8);
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
        return ScrollBar.trackX(searchX() + 2, searchWidth - 4);
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
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float partialTicks) {
        tooltip = null;
        Font font = OfflineClient.MC.font;

        if (resizingSearch) {
            // Centred box. Half the pointer offset gives the half width.
            searchWidth = Math.clamp(Math.abs(mouseX - width / 2) * 2,
                SEARCH_WIDTH_MIN, SEARCH_WIDTH_MAX);
        }

        int barX = searchX();
        RenderUtil.shadow(context, barX, SEARCH_TOP, barX + searchWidth,
            SEARCH_TOP + SEARCH_HEIGHT, 2);
        context.guiRenderState.up();
        renderSearchBox(context, font, barX, SEARCH_TOP, searchWidth, mouseX, mouseY,
            searchRows.size());

        nudgePanels(isSearching() ? resultsBoxHeight() : 0);
        for (Panel panel : panels) {
            panel.render(context, mouseX, mouseY);
        }
        if (isSearching()) {
            renderSearchResults(context, font, mouseX, mouseY);
        }

        if (tooltip != null && !tooltip.isEmpty() && hoverHelp()) {
            RenderUtil.tooltip(context, font, wrap(tooltip), mouseX, mouseY, width, height);
        }
    }

    // The overlap test uses the panel's remembered home position.
    private void nudgePanels(int resultsHeight) {
        int rx = searchX() - 4;
        int rx2 = rx + searchWidth + 8;
        int ry2 = resultsTop() + resultsHeight;
        boolean searchActive = searchFocused || isSearching();

        for (Panel panel : panels) {
            if (panel.isDragging()) {
                nudged.remove(panel);
                continue;
            }
            Home origin = nudged.get(panel);
            int homeX = origin != null ? origin.x() : panel.getX();
            int homeY = origin != null ? origin.y() : panel.getY();
            // A nudged panel stays aside until the box loses focus with
            // nothing typed.
            boolean overlaps = (searchActive && origin != null)
                || (resultsHeight > 0
                    && homeX < rx2 && homeX + panel.getWidth() > rx && homeY < ry2);

            if (overlaps) {
                if (origin == null) {
                    nudged.put(panel, new Home(homeX, homeY));
                }
                boolean left = homeX + panel.getWidth() / 2 < width / 2;
                int target = left ? rx - panel.getWidth() - 6 : rx2 + 6;
                target = Math.clamp(target, 0, Math.max(0, width - panel.getWidth()));
                slideX(panel, target, homeY);
            } else if (origin != null) {
                slideX(panel, origin.x(), origin.y());
                if (panel.getX() == origin.x()) {
                    nudged.remove(panel);
                }
            }
        }
    }

    private void slideX(Panel panel, int targetX, int y) {
        int current = panel.getX();
        if (current == targetX) {
            panel.setPosition(targetX, y);
            return;
        }
        int step = Math.max(2, Math.abs(targetX - current) / 4);
        int next = current < targetX
            ? Math.min(targetX, current + step)
            : Math.max(targetX, current - step);
        panel.setPosition(next, y);
    }

    // Either upright edge of the search bar or of the results box below it.
    private boolean overSearchEdge(double mx, double my) {
        int left = searchX();
        int right = left + searchWidth;
        boolean nearEdge = Math.abs(mx - left) <= GRIP || Math.abs(mx - right) <= GRIP;
        if (!nearEdge) {
            return false;
        }
        if (my >= SEARCH_TOP && my <= SEARCH_TOP + SEARCH_HEIGHT) {
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
        int x = searchX();
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
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x();
        double my = event.y();
        int button = event.button();

        beginClick();

        // The results box sits over the panels. It takes the click first.
        if (isSearching() && clickResults(mx, my, button)) {
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

        if (button == 0 && overSearchEdge(mx, my)) {
            resizingSearch = true;
            return true;
        }
        if (clickSearchBox(mx, my, searchX(), SEARCH_TOP, searchWidth)) {
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    // The border of the box is left alone. A click there falls through to
    // the edge resize.
    private boolean clickResults(double mx, double my, int button) {
        int rowX = searchX() + 2;
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
    public boolean mouseReleased(MouseButtonEvent event) {
        resizingSearch = false;
        resultsScroll.release();
        for (Panel panel : panels) {
            panel.mouseReleased();
        }
        for (ModuleRow row : searchRows) {
            row.mouseReleased();
        }
        checkStyle();
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (isSearching() && !searchRows.isEmpty()
            && SettingWidget.isOver(mouseX, mouseY, searchX(), resultsTop(),
                searchWidth, resultsBoxHeight())) {
            resultsScroll.scroll(ScrollBar.wheelDelta(scrollY, resultsContentHeight(), resultsViewHeight()),
                resultsContentHeight(), resultsViewHeight());
            return true;
        }
        for (int i = panels.size() - 1; i >= 0; i--) {
            Panel panel = panels.get(i);
            if (panel.isOver(mouseX, mouseY)) {
                panel.wheel(scrollY);
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    protected void onSearchChanged() {
        // A row dropped whilst its slider is held would never release it.
        for (ModuleRow row : searchRows) {
            row.mouseReleased();
        }
        searchRows.clear();
        resultsScroll.setOffset(0);
        if (!isSearching()) {
            return;
        }
        String query = search();
        List<Module> matches = SearchRank.rank(
            OfflineClient.INSTANCE.getModuleManager().getAll(),
            module -> module.searchScore(query));
        for (Module module : matches) {
            searchRows.add(new ModuleRow(module, this));
        }
    }
}
