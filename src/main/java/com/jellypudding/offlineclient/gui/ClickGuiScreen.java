package com.jellypudding.offlineclient.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.util.RenderUtil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

import java.util.ArrayList;
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
    private static final int FOOTER_HEIGHT = 12;
    private static final int TOOLTIP_WIDTH = 170;

    private final List<Panel> panels = new ArrayList<>();
    private final List<ModuleRow> searchRows = new ArrayList<>();

    private String tooltip;
    private String wrappedFor;
    private final List<String> wrappedLines = new ArrayList<>();

    // Nudged panels mapped to the position they came from.
    private final Map<Panel, int[]> nudged = new HashMap<>();

    // Panels with no saved layout. Tiled once the screen size is known.
    private final List<Panel> freshPanels = new ArrayList<>();

    // The results box is centred so dragging either edge widens it both ways.
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
            if (!restorePanelState(panel)) {
                freshPanels.add(panel);
            }
            panels.add(panel);
            startX += GuiTheme.PANEL_WIDTH + 8;
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
        for (Panel panel : panels) {
            JsonObject state = new JsonObject();
            state.addProperty("x", panel.getX());
            state.addProperty("y", panel.getY());
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
        OfflineClient.INSTANCE.getConfigManager().saveNow();
    }

    @Override
    protected void releaseDrags() {
        resizingSearch = false;
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

    /**
     * Panels saved on a bigger screen can end up outside the window. This
     * runs again on every resize.
     */
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
            entry.getKey().setPosition(entry.getValue()[0], entry.getValue()[1]);
        }
        nudged.clear();
    }

    private void tilePanels() {
        if (freshPanels.isEmpty()) {
            return;
        }
        int x = 10;
        int y = 30;
        for (Panel panel : freshPanels) {
            panel.setCollapsed(true);
            if (x + panel.getWidth() > width - 10) {
                x = 10;
                y += GuiTheme.HEADER_HEIGHT + 10;
            }
            panel.setPosition(x, y);
            x += panel.getWidth() + 8;
        }
        freshPanels.clear();
    }

    private int searchX() {
        return width / 2 - searchWidth / 2;
    }

    private int resultsTop() {
        return SEARCH_TOP + SEARCH_HEIGHT + RESULTS_GAP;
    }

    // Results past the last one that fits are counted in the footer.
    private int shownResults() {
        int room = height - resultsTop() - 8;
        int shown = countThatFit(room);
        if (shown < searchRows.size()) {
            shown = countThatFit(room - FOOTER_HEIGHT);
        }
        return shown;
    }

    private int countThatFit(int room) {
        int used = 4;
        int count = 0;
        for (ModuleRow row : searchRows) {
            int h = row.getHeight();
            if (used + h > room) {
                break;
            }
            used += h;
            count++;
        }
        return count;
    }

    private int resultsHeight(int shown) {
        int total = 4;
        for (int i = 0; i < shown; i++) {
            total += searchRows.get(i).getHeight();
        }
        if (shown < searchRows.size() || searchRows.isEmpty()) {
            total += FOOTER_HEIGHT;
        }
        return total;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float partialTicks) {
        tooltip = null;
        Font font = OfflineClient.MC.font;

        if (resizingSearch) {
            // Centred box so half the pointer offset is the half width.
            searchWidth = Math.clamp(Math.abs(mouseX - width / 2) * 2,
                SEARCH_WIDTH_MIN, SEARCH_WIDTH_MAX);
        }

        int barX = searchX();
        RenderUtil.shadow(context, barX, SEARCH_TOP, barX + searchWidth,
            SEARCH_TOP + SEARCH_HEIGHT, 2);
        context.guiRenderState.up();
        renderSearchBox(context, font, barX, SEARCH_TOP, searchWidth, mouseX, mouseY,
            searchRows.size());
        renderSearchGrips(context, barX, mouseX, mouseY);

        int shown = isSearching() ? shownResults() : 0;
        nudgePanels(isSearching() ? resultsHeight(shown) : 0);
        for (Panel panel : panels) {
            panel.render(context, mouseX, mouseY);
        }
        if (isSearching()) {
            renderSearchResults(context, font, shown, mouseX, mouseY);
        }

        if (tooltip != null && !tooltip.isEmpty()) {
            renderTooltip(context, font, mouseX, mouseY);
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
            int[] origin = nudged.get(panel);
            int homeX = origin != null ? origin[0] : panel.getX();
            int homeY = origin != null ? origin[1] : panel.getY();
            // A nudged panel stays aside until the box loses focus with
            // nothing typed.
            boolean overlaps = (searchActive && origin != null)
                || (resultsHeight > 0
                    && homeX < rx2 && homeX + panel.getWidth() > rx && homeY < ry2);

            if (overlaps) {
                if (origin == null) {
                    nudged.put(panel, new int[] {homeX, homeY});
                }
                boolean left = homeX + panel.getWidth() / 2 < width / 2;
                int target = left ? rx - panel.getWidth() - 6 : rx2 + 6;
                target = Math.clamp(target, 0, Math.max(0, width - panel.getWidth()));
                slideX(panel, target, homeY);
            } else if (origin != null) {
                slideX(panel, origin[0], origin[1]);
                if (panel.getX() == origin[0]) {
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

    // Two dashes on each edge. The only hint that the box can be widened.
    private void renderSearchGrips(GuiGraphicsExtractor context, int barX, int mouseX, int mouseY) {
        boolean lit = resizingSearch || overSearchEdge(mouseX, mouseY);
        int color = lit ? GuiTheme.accentText() : GuiTheme.TEXT_FAINT;
        int midY = SEARCH_TOP + SEARCH_HEIGHT / 2;
        for (int offset = -2; offset <= 2; offset += 4) {
            context.fill(barX + 1, midY + offset, barX + 3, midY + offset + 1, color);
            context.fill(barX + searchWidth - 3, midY + offset,
                barX + searchWidth - 1, midY + offset + 1, color);
        }
        context.guiRenderState.up();
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
        return my >= top && my <= top + resultsHeight(shownResults());
    }

    private void renderSearchResults(GuiGraphicsExtractor context, Font font, int shown,
                                     int mouseX, int mouseY) {
        int x = searchX();
        int y = resultsTop();
        int total = resultsHeight(shown);
        RenderUtil.shadow(context, x, y, x + searchWidth, y + total, 3);
        context.guiRenderState.up();
        RenderUtil.roundedBorderedRect(context, x, y, x + searchWidth, y + total,
            GuiTheme.CORNER + 1, GuiTheme.BG_WINDOW, GuiTheme.accent());
        context.guiRenderState.up();

        int rowY = y + 2;
        int rowX = x + 2;
        int rowW = searchWidth - 4;
        for (int i = 0; i < shown; i++) {
            ModuleRow row = searchRows.get(i);
            row.place(rowX, rowY, rowW, mouseX, mouseY, true);
            row.render(context, mouseX, mouseY);
            rowY += row.getHeight();
        }
        int hidden = searchRows.size() - shown;
        if (hidden > 0) {
            context.text(font, hidden + " more. Keep typing.", rowX + 5,
                GuiTheme.textY(rowY, FOOTER_HEIGHT), GuiTheme.TEXT_FAINT, false);
        } else if (searchRows.isEmpty()) {
            context.text(font, "no matches", rowX + 5, GuiTheme.textY(rowY, FOOTER_HEIGHT),
                GuiTheme.TEXT_DIM, false);
        }
    }

    private void renderTooltip(GuiGraphicsExtractor context, Font font, int mouseX, int mouseY) {
        List<String> lines = wrap(tooltip);
        int w = 0;
        for (String line : lines) {
            w = Math.max(w, font.width(line));
        }
        int h = lines.size() * 10 + 6;
        int tx = Math.max(2, Math.min(mouseX + 10, width - w - 12));
        int ty = Math.max(2, Math.min(mouseY + 10, height - h - 4));

        context.guiRenderState.up();
        RenderUtil.shadow(context, tx, ty, tx + w + 8, ty + h, 2);
        context.guiRenderState.up();
        RenderUtil.roundedBorderedRect(context, tx, ty, tx + w + 8, ty + h, GuiTheme.CORNER,
            GuiTheme.BG_WINDOW, GuiTheme.accent());
        context.guiRenderState.up();
        for (int i = 0; i < lines.size(); i++) {
            context.text(font, lines.get(i), tx + 4, ty + 4 + i * 10, GuiTheme.TEXT, false);
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

        // Only the results drawn this frame can be clicked.
        if (isSearching()) {
            int shown = shownResults();
            for (int i = 0; i < shown; i++) {
                if (searchRows.get(i).mouseClicked(mx, my, button)) {
                    return true;
                }
            }
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

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        resizingSearch = false;
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

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        for (int i = panels.size() - 1; i >= 0; i--) {
            Panel panel = panels.get(i);
            if (panel.isOver(mouseX, mouseY)) {
                panel.scroll((int) Math.round(scrollY * 16));
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    protected void onSearchChanged() {
        searchRows.clear();
        if (!isSearching()) {
            return;
        }
        for (Module module : OfflineClient.INSTANCE.getModuleManager().getAll()) {
            if (module.matchesSearch(search())) {
                searchRows.add(new ModuleRow(module, this));
            }
        }
    }
}
