package com.jellypudding.offlineclient.gui;

import com.google.gson.JsonObject;
import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.modules.misc.ClickGuiModule;
import com.jellypudding.offlineclient.modules.render.Blur;
import com.jellypudding.offlineclient.setting.KeybindSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.PickList;
import com.jellypudding.offlineclient.setting.Setting;
import com.jellypudding.offlineclient.setting.TextSetting;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.RenderUtil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

// Shared by both ClickGUI styles. Owns the search box and the typing and
// binding state.
public abstract class GuiScreenBase extends Screen implements SettingWidget.Host {

    public static final int SEARCH_HEIGHT = 14;
    private static final int SEARCH_INDENT = GuiTheme.PAD;
    // A multiplication sign. The closest thing to a cross the font has.
    private static final String CLEAR = "×";
    private static final int CLEAR_ZONE = 12;
    private static final String SEARCH_KEY = "search";

    protected final TextField searchBox = new TextField();
    protected boolean searchFocused;

    // Where the search text last drew. Lets a click land the caret exactly.
    private int searchTextX;
    private int searchRoom;

    private KeybindSetting bindingTarget;
    private Setting<?> editingSetting;
    private final TextField editField = new TextField();
    // The character of the key just bound arrives right after the key
    // event and must be eaten.
    private boolean eatNextChar;

    protected GuiScreenBase() {
        super(Component.literal("ClickGUI"));
    }

    protected abstract void saveState();

    protected abstract void onSearchChanged();

    protected abstract void releaseDrags();

    protected abstract boolean isWindowStyle();

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float deltaTicks) {
        // The vanilla blur washes the panel colours out. Blur adds its own when asked.
        Blur blur = Modules.get(Blur.class);
        if (blur != null && blur.wants(this)) {
            blur.blurHere(context);
        }
        context.fillGradient(0, 0, width, height, 0x70101018, 0xA0060610);
    }

    @Override
    public void onClose() {
        commitEditing();
        bindingTarget = null;
        releaseDrags();
        saveState();
        super.onClose();
    }

    protected final void checkStyle() {
        boolean window = OfflineClient.INSTANCE.getModuleManager()
            .get(ClickGuiModule.class).isWindow();
        if (window == isWindowStyle()) {
            return;
        }
        commitEditing();
        releaseDrags();
        saveState();
        OfflineClient.MC.gui.setScreen(window ? new WindowGuiScreen() : new ClickGuiScreen());
    }

    // The hover text can be switched off in the ClickGUI module.
    protected static boolean hoverHelp() {
        return OfflineClient.INSTANCE.getModuleManager().get(ClickGuiModule.class).showsHoverHelp();
    }

    protected final String search() {
        return searchBox.get();
    }

    protected final boolean isSearching() {
        return !searchBox.isEmpty();
    }

    // A match count below zero leaves the tally off.
    protected final void renderSearchBox(GuiGraphicsExtractor context, Font font, int x, int y,
                                         int w, int mouseX, int mouseY, int matches) {
        boolean active = searchFocused || isSearching();
        boolean hovered = SettingWidget.isOver(mouseX, mouseY, x, y, w, SEARCH_HEIGHT);
        String tally = matches >= 0 && isSearching()
            ? matches + (matches == 1 ? " match" : " matches") : null;
        searchTextX = x + SEARCH_INDENT;
        searchRoom = searchField(context, font, x, y, w, searchBox, "click here to search", active,
            hovered, searchFocused, tally);
    }

    // Returns the width the text had to work with. A box holding text shows
    // a cross at its right end that clears it.
    public static int searchField(GuiGraphicsExtractor context, Font font, int x, int y, int w,
                                  TextField field, String placeholder, boolean active,
                                  boolean hovered, boolean caret, String tally) {
        int h = SEARCH_HEIGHT;
        int border = active ? GuiTheme.accent() : (hovered ? GuiTheme.textFaint() : GuiTheme.edge());
        RenderUtil.roundedBorderedRect(context, x, y, x + w, y + h, GuiTheme.CORNER,
            GuiTheme.bgPanel(), border);
        context.guiRenderState.up();

        int textX = x + SEARCH_INDENT;
        int textY = GuiTheme.textY(y, h);
        int right = x + w - GuiTheme.PAD;
        if (!field.isEmpty()) {
            context.text(font, CLEAR, right - CLEAR_ZONE + (CLEAR_ZONE - font.width(CLEAR)) / 2,
                textY, GuiTheme.textDim(), false);
            right -= CLEAR_ZONE;
        }
        if (tally != null) {
            right -= font.width(tally);
            context.text(font, tally, right, textY, GuiTheme.textDim(), false);
            right -= GuiTheme.PAD;
        }
        int room = right - textX;
        if (field.isEmpty() && !caret) {
            context.text(font, placeholder, textX, textY, GuiTheme.textFaint(), false);
        } else {
            field.render(context, font, textX, textY, room, GuiTheme.text(), caret);
        }
        return room;
    }

    // True over the clearing cross of a box that holds text.
    public static boolean overClear(TextField field, double mx, double my, int x, int y, int w) {
        return !field.isEmpty() && SettingWidget.isOver(mx, my,
            x + w - GuiTheme.PAD - CLEAR_ZONE, y, CLEAR_ZONE, SEARCH_HEIGHT);
    }

    protected final boolean clickSearchBox(double mx, double my, int x, int y, int w) {
        if (!SettingWidget.isOver(mx, my, x, y, w, SEARCH_HEIGHT)) {
            searchFocused = false;
            return false;
        }
        searchFocused = true;
        if (overClear(searchBox, mx, my, x, y, w)) {
            searchBox.clear();
            onSearchChanged();
            return true;
        }
        searchBox.click(OfflineClient.MC.font, searchTextX, searchRoom, mx, false);
        return true;
    }

    // The search survives closing the GUI. Reopening lands on the same
    // results and the cross in the box clears them. Both styles share it.
    protected final void restoreSearch() {
        JsonObject gui = OfflineClient.INSTANCE.getConfigManager().getGuiState();
        if (!gui.has(SEARCH_KEY) || !gui.get(SEARCH_KEY).isJsonPrimitive()) {
            return;
        }
        String text = gui.get(SEARCH_KEY).getAsString();
        if (!text.isEmpty()) {
            searchBox.set(text);
            onSearchChanged();
        }
    }

    protected final void saveSearch() {
        OfflineClient.INSTANCE.getConfigManager().getGuiState().addProperty(SEARCH_KEY, searchBox.get());
    }

    @Override
    public boolean isBinding(KeybindSetting setting) {
        return bindingTarget == setting;
    }

    @Override
    public void startListening(KeybindSetting setting) {
        bindingTarget = setting;
    }

    @Override
    public void openPicker(PickList<?> setting) {
        commitEditing();
        openPickerTyped(setting);
    }

    private <T> void openPickerTyped(PickList<T> setting) {
        OfflineClient.MC.gui.setScreen(new ListPickerScreen<>(this, setting));
    }

    @Override
    public void startEditing(NumberSetting setting) {
        commitEditing();
        editingSetting = setting;
        editField.set(setting.getValueString().replaceAll("[^0-9.-]", ""));
        editField.selectAll();
    }

    @Override
    public void startEditing(TextSetting setting) {
        commitEditing();
        editingSetting = setting;
        editField.set(setting.getValue());
    }

    @Override
    public boolean isEditing(Setting<?> setting) {
        return editingSetting == setting;
    }

    @Override
    public TextField getEditField() {
        return editField;
    }

    protected final void commitEditing() {
        if (editingSetting instanceof NumberSetting number) {
            if (!editField.isEmpty()) {
                try {
                    number.setValue(Double.parseDouble(editField.get()));
                    OfflineClient.INSTANCE.getConfigManager().saveSoon();
                } catch (NumberFormatException e) {
                    ChatUtil.error(editField.get() + " is not a number.");
                }
            }
        } else if (editingSetting instanceof TextSetting text) {
            text.setValue(editField.get().trim());
            OfflineClient.INSTANCE.getConfigManager().saveSoon();
        }
        cancelEditing();
    }

    private void cancelEditing() {
        editingSetting = null;
        editField.clear();
    }

    protected final void beginClick() {
        commitEditing();
        bindingTarget = null;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        return handleCommonKey(event) || super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        return handleCommonChar((char) event.codepoint()) || super.charTyped(event);
    }

    protected final boolean handleCommonKey(KeyEvent event) {
        int key = event.key();
        eatNextChar = false;
        if (editingSetting != null) {
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                commitEditing();
            } else if (key == GLFW.GLFW_KEY_ESCAPE) {
                cancelEditing();
            } else {
                editField.keyPressed(event, editingSetting instanceof NumberSetting
                    ? TextField.NUMBER : TextField.ANY);
            }
            return true;
        }

        if (bindingTarget != null) {
            if (key == GLFW.GLFW_KEY_DELETE || key == GLFW.GLFW_KEY_BACKSPACE) {
                bindingTarget.setValue(KeybindSetting.UNBOUND);
            } else if (key != GLFW.GLFW_KEY_ESCAPE) {
                bindingTarget.setValue(key);
            }
            bindingTarget = null;
            OfflineClient.INSTANCE.getConfigManager().saveSoon();
            // Only a key that types a character has one to swallow.
            eatNextChar = key >= GLFW.GLFW_KEY_SPACE && key <= GLFW.GLFW_KEY_WORLD_2
                || key >= GLFW.GLFW_KEY_KP_0 && key <= GLFW.GLFW_KEY_KP_EQUAL;
            return true;
        }

        if (searchFocused) {
            if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_ENTER
                || key == GLFW.GLFW_KEY_KP_ENTER) {
                if (key == GLFW.GLFW_KEY_ESCAPE && !searchBox.isEmpty()) {
                    searchBox.clear();
                    onSearchChanged();
                }
                searchFocused = false;
                return true;
            }
            String before = searchBox.get();
            if (searchBox.keyPressed(event, TextField.ANY)) {
                if (!searchBox.get().equals(before)) {
                    onSearchChanged();
                }
                return true;
            }
            return false;
        }

        if (key == OfflineClient.INSTANCE.getModuleManager()
            .get(ClickGuiModule.class).getKeybind().getValue()) {
            onClose();
            return true;
        }
        return false;
    }

    // True whilst a key press would type or bind rather than reach the game.
    public boolean isTyping() {
        return searchFocused || editingSetting != null || bindingTarget != null;
    }

    protected final boolean handleCommonChar(char c) {
        if (eatNextChar) {
            eatNextChar = false;
            return true;
        }
        if (bindingTarget != null) {
            return true;
        }
        if (editingSetting != null) {
            editField.charTyped(c, editingSetting instanceof NumberSetting
                ? TextField.NUMBER : TextField.ANY);
            return true;
        }
        if (searchFocused && searchBox.charTyped(c, TextField.ANY)) {
            onSearchChanged();
            return true;
        }
        return false;
    }
}
