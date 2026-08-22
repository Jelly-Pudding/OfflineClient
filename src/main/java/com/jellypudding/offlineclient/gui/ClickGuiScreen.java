package com.jellypudding.offlineclient.gui;

import com.google.gson.JsonObject;
import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.KeybindSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.setting.Setting;
import com.jellypudding.offlineclient.setting.TextSetting;
import com.jellypudding.offlineclient.util.RenderUtil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ClickGuiScreen extends Screen {

    private final List<Panel> panels = new ArrayList<>();
    private List<ModuleRow> searchRows = new ArrayList<>();
    private static final int SEARCH_WIDTH = 140;
    private static final int SEARCH_TOP = 6;
    private static final int SEARCH_BOTTOM = 20;

    private String search = "";
    private boolean searchFocused;
    private String tooltip;

    /** Panels nudged aside by the search results and where they came from. */
    private final java.util.Map<Panel, int[]> nudged = new java.util.HashMap<>();
    private ModuleRow bindingRow;
    private Setting<?> editingSetting;
    private final StringBuilder editBuffer = new StringBuilder();
    private long suppressCharsUntil;

    public ClickGuiScreen() {
        super(Component.literal("ClickGUI"));

        int startX = 10;
        for (Category category : Category.values()) {
            Panel panel = new Panel(category.getDisplayName(),
                OfflineClient.INSTANCE.getModuleManager().getByCategory(category),
                this, startX, 30);
            // Saved layouts override the default height cap right after.
            panel.setViewHeight(190);
            restorePanelState(panel);
            panels.add(panel);
            startX += GuiTheme.PANEL_WIDTH + 8;
        }
    }

    private void restorePanelState(Panel panel) {
        JsonObject gui = OfflineClient.INSTANCE.getConfigManager().getGuiState();
        if (!gui.has(panel.getTitle())) {
            return;
        }
        JsonObject state = gui.getAsJsonObject(panel.getTitle());
        panel.setPosition(state.get("x").getAsInt(), state.get("y").getAsInt());
        panel.setCollapsed(state.get("collapsed").getAsBoolean());
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
            var expanded = state.getAsJsonArray("expanded");
            for (ModuleRow row : panel.getRows()) {
                for (var name : expanded) {
                    if (row.getModule().getName().equals(name.getAsString())) {
                        row.setExpanded(true);
                    }
                }
            }
        }
    }

    @Override
    public void onClose() {
        commitEditing();
        // Panels moved aside by a search go back where they belong.
        for (var entry : nudged.entrySet()) {
            entry.getKey().setPosition(entry.getValue()[0], entry.getValue()[1]);
        }
        nudged.clear();
        JsonObject gui = OfflineClient.INSTANCE.getConfigManager().getGuiState();
        for (Panel panel : panels) {
            JsonObject state = new JsonObject();
            state.addProperty("x", panel.getX());
            state.addProperty("y", panel.getY());
            state.addProperty("collapsed", panel.isCollapsed());
            state.addProperty("height", panel.getViewHeight());
            state.addProperty("width", panel.getWidth());
            state.addProperty("scroll", panel.getScrollOffset());
            var expanded = new com.google.gson.JsonArray();
            for (ModuleRow row : panel.getRows()) {
                if (row.isExpanded()) {
                    expanded.add(row.getModule().getName());
                }
            }
            state.add("expanded", expanded);
            gui.add(panel.getTitle(), state);
        }
        OfflineClient.INSTANCE.getConfigManager().saveNow();
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
     * Panels saved on a bigger screen or an older layout can end up outside
     * the window where they cannot be reached. Pull them back in.
     */
    @Override
    protected void init() {
        super.init();
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

    @Override
    public void extractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float deltaTicks) {
        // Use a soft dark gradient instead of the vanilla blur.
        context.fillGradient(0, 0, width, height, 0x70101018, 0xA0060610);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float partialTicks) {
        tooltip = null;
        Font font = OfflineClient.MC.font;

        // Watermark in the top right.
        String name = OfflineClient.NAME + " v" + OfflineClient.VERSION;
        float watermarkScale = 0.7f;
        RenderUtil.rainbowText(context, font, name,
            width - font.width(name) * watermarkScale - 4, 4, watermarkScale);

        // Search bar at the top.
        int barX = width / 2 - SEARCH_WIDTH / 2;
        boolean active = searchFocused || !search.isEmpty();
        RenderUtil.borderedRect(context, barX, SEARCH_TOP, barX + SEARCH_WIDTH, SEARCH_BOTTOM,
            GuiTheme.BG_PANEL, active ? GuiTheme.accent() : GuiTheme.OUTLINE);
        context.guiRenderState.up();
        boolean placeholder = search.isEmpty() && !searchFocused;
        String searchText = placeholder ? "click here to search" : search;
        context.text(font, searchText, barX + 5, 9,
            placeholder ? GuiTheme.TEXT_DIM : GuiTheme.TEXT, false);
        // Blinking caret while the bar has focus.
        if (searchFocused && (System.currentTimeMillis() / 500) % 2 == 0) {
            int caretX = barX + 5 + (search.isEmpty() ? 0 : font.width(search) + 1);
            context.fill(caretX, 9, caretX + 1, 17, GuiTheme.TEXT);
        }

        // Panels under the search results slide aside until the search ends.
        int resultsHeight = search.isEmpty() ? 0 : searchResultsHeight();
        nudgePanels(resultsHeight, searchFocused || !search.isEmpty());
        for (Panel panel : panels) {
            panel.render(context, mouseX, mouseY);
        }
        if (!search.isEmpty()) {
            renderSearchResults(context, mouseX, mouseY);
        }

        if (tooltip != null && !tooltip.isEmpty()) {
            renderTooltip(context, font, mouseX, mouseY);
        }
    }

    private int searchResultsHeight() {
        int total = 4;
        for (ModuleRow row : searchRows) {
            total += row.getHeight();
        }
        return total;
    }

    /**
     * Slides any panel under the search results to the side.
     * The overlap test uses the panel's remembered home position.
     */
    private void nudgePanels(int resultsHeight, boolean searchActive) {
        int rx = width / 2 - GuiTheme.PANEL_WIDTH / 2 - 6;
        int rx2 = rx + GuiTheme.PANEL_WIDTH + 12;
        int ry2 = 26 + resultsHeight;

        for (Panel panel : panels) {
            // Dragging a nudged panel hands it back to the player.
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
                // Step aside to whichever side is closer. Only sideways.
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

    private void renderSearchResults(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        int x = width / 2 - GuiTheme.PANEL_WIDTH / 2;
        int y = 26;
        int total = searchResultsHeight();
        context.guiRenderState.up();
        RenderUtil.borderedRect(context, x - 2, y - 2,
            x + GuiTheme.PANEL_WIDTH + 2, y + total, 0xF80C0C14, GuiTheme.accent());
        context.guiRenderState.up();
        y += 2;
        if (searchRows.isEmpty()) {
            context.text(OfflineClient.MC.font, "no matches", x + 5, y + 2, GuiTheme.TEXT_DIM, false);
            return;
        }
        for (ModuleRow row : searchRows) {
            row.render(context, x, y, GuiTheme.PANEL_WIDTH, mouseX, mouseY, true);
            y += row.getHeight();
        }
    }

    private void renderTooltip(GuiGraphicsExtractor context, Font font, int mouseX, int mouseY) {
        List<String> lines = wrap(tooltip, 160);
        int w = 0;
        for (String line : lines) {
            w = Math.max(w, font.width(line));
        }
        int h = lines.size() * 10 + 6;
        int tx = Math.min(mouseX + 10, width - w - 10);
        int ty = Math.min(mouseY + 10, height - h - 4);

        context.guiRenderState.up();
        RenderUtil.borderedRect(context, tx, ty, tx + w + 8, ty + h, 0xF80C0C14, GuiTheme.accent());
        context.guiRenderState.up();
        for (int i = 0; i < lines.size(); i++) {
            context.text(font, lines.get(i), tx + 4, ty + 4 + i * 10, GuiTheme.TEXT, false);
        }
    }

    private List<String> wrap(String text, int maxWidth) {
        Font font = OfflineClient.MC.font;
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = current.isEmpty() ? word : current + " " + word;
            if (font.width(candidate) > maxWidth && !current.isEmpty()) {
                lines.add(current.toString());
                current = new StringBuilder(word);
            } else {
                current = new StringBuilder(candidate);
            }
        }
        if (!current.isEmpty()) {
            lines.add(current.toString());
        }
        return lines;
    }

    public void setTooltip(String tooltip) {
        this.tooltip = tooltip;
    }

    public void startListening(ModuleRow row) {
        if (bindingRow != null) {
            bindingRow.setListeningForBind(false);
        }
        bindingRow = row;
        row.setListeningForBind(true);
    }

    /** Opens the two column picker for a registry list setting. */
    public void openPicker(RegistryListSetting<?> setting) {
        commitEditing();
        openPickerTyped(setting);
    }

    private <T> void openPickerTyped(RegistryListSetting<T> setting) {
        OfflineClient.MC.gui.setScreen(new RegistryPickerScreen<>(this, setting));
    }

    /** Starts typing mode for a number setting. */
    public void startEditing(NumberSetting setting) {
        commitEditing();
        editingSetting = setting;
        editBuffer.setLength(0);
    }

    /** Starts typing mode for a text setting with the current value loaded. */
    public void startEditing(TextSetting setting) {
        commitEditing();
        editingSetting = setting;
        editBuffer.setLength(0);
        editBuffer.append(setting.getValue());
    }

    public boolean isEditing(Setting<?> setting) {
        return editingSetting == setting;
    }

    public String getEditBuffer() {
        return editBuffer.toString();
    }

    private void commitEditing() {
        if (editingSetting instanceof NumberSetting number) {
            if (!editBuffer.isEmpty()) {
                try {
                    number.setValue(Double.parseDouble(editBuffer.toString()));
                    OfflineClient.INSTANCE.getConfigManager().saveSoon();
                } catch (NumberFormatException ignored) {
                }
            }
        } else if (editingSetting instanceof TextSetting text) {
            text.setValue(editBuffer.toString().trim());
            OfflineClient.INSTANCE.getConfigManager().saveSoon();
        }
        cancelEditing();
    }

    private void cancelEditing() {
        editingSetting = null;
        editBuffer.setLength(0);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x();
        double my = event.y();
        int button = event.button();

        // Clicking anywhere else confirms a number that is being typed.
        commitEditing();

        // Search results get clicks first.
        if (!search.isEmpty()) {
            for (ModuleRow row : searchRows) {
                if (row.mouseClicked(mx, my, button)) {
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

        // Clicking the search bar focuses it and shows the caret.
        int barX = width / 2 - SEARCH_WIDTH / 2;
        if (mx >= barX && mx < barX + SEARCH_WIDTH && my >= SEARCH_TOP && my < SEARCH_BOTTOM) {
            searchFocused = true;
            return true;
        }
        searchFocused = false;
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        for (Panel panel : panels) {
            panel.mouseReleased();
        }
        for (ModuleRow row : searchRows) {
            row.mouseReleased();
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();

        if (editingSetting != null) {
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                commitEditing();
            } else if (key == GLFW.GLFW_KEY_ESCAPE) {
                cancelEditing();
            } else if (key == GLFW.GLFW_KEY_BACKSPACE && !editBuffer.isEmpty()) {
                editBuffer.deleteCharAt(editBuffer.length() - 1);
            }
            return true;
        }

        if (bindingRow != null) {
            if (key == GLFW.GLFW_KEY_DELETE || key == GLFW.GLFW_KEY_BACKSPACE) {
                bindingRow.getModule().getKeybind().setValue(KeybindSetting.UNBOUND);
            } else if (key != GLFW.GLFW_KEY_ESCAPE) {
                bindingRow.getModule().getKeybind().setValue(key);
            }
            bindingRow.setListeningForBind(false);
            bindingRow = null;
            OfflineClient.INSTANCE.getConfigManager().saveSoon();
            // The character for this key press arrives right after this callback.
            suppressCharsUntil = System.currentTimeMillis() + 150;
            return true;
        }

        if (searchFocused) {
            if (key == GLFW.GLFW_KEY_BACKSPACE && !search.isEmpty()) {
                search = search.substring(0, search.length() - 1);
                updateSearch();
                return true;
            }
            if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_ENTER) {
                // ESC clears the search and leaves the box. ENTER just leaves.
                if (key == GLFW.GLFW_KEY_ESCAPE) {
                    search = "";
                    updateSearch();
                }
                searchFocused = false;
                return true;
            }
        }
        // Same key that opened the GUI closes it again unless the search
        // box is taking input.
        if (!searchFocused && key == OfflineClient.INSTANCE.getModuleManager()
            .get(com.jellypudding.offlineclient.modules.misc.ClickGuiModule.class)
            .getKeybind().getValue()) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (System.currentTimeMillis() < suppressCharsUntil || bindingRow != null) {
            return true;
        }
        char c = (char) event.codepoint();
        if (editingSetting instanceof NumberSetting) {
            if ((c >= '0' && c <= '9') || c == '.' || c == '-') {
                editBuffer.append(c);
            }
            return true;
        }
        if (editingSetting instanceof TextSetting) {
            if (c >= ' ' && c != 127) {
                editBuffer.append(c);
            }
            return true;
        }
        // Typing only searches once the search box has been clicked.
        if (searchFocused && c >= ' ' && c < 127) {
            search += c;
            updateSearch();
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

    private void updateSearch() {
        searchRows = new ArrayList<>();
        if (search.isEmpty()) {
            return;
        }
        for (Module module : OfflineClient.INSTANCE.getModuleManager().getAll()) {
            if (module.matchesSearch(search)) {
                searchRows.add(new ModuleRow(module, this));
            }
        }
    }
}
