package com.jellypudding.offlineclient.gui;

import com.google.gson.JsonObject;
import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.modules.misc.ClickGuiModule;
import com.jellypudding.offlineclient.setting.KeybindSetting;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.Tally;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

// Shared by both ClickGUI styles. Owns the search box and the typing and
// binding state.
public abstract class GuiScreenBase extends ScaledScreen {

    private static final String SEARCH_KEY = "search";

    protected final SearchField searchBar = new SearchField("click here to search", this::onSearchChanged);

    private final TextInput textInput = new TextInput(this);
    protected final SettingHost settings = new SettingHost(this, this::setTooltip);

    // The text input and the setting host only keep the reference. Neither calls
    // back into the subclass before it has finished building.
    @SuppressWarnings("this-escape")
    protected GuiScreenBase() {
        super(Component.literal("ClickGUI"));
    }

    protected abstract void setTooltip(String text);

    protected abstract void saveState();

    protected abstract void onSearchChanged();

    protected abstract void releaseDrags();

    protected abstract boolean isWindowStyle();

    protected abstract void renderGui(GuiGraphicsExtractor context, int mouseX, int mouseY,
                                      float partialTicks);

    protected abstract boolean clickGui(double mx, double my, int button);

    protected abstract boolean releaseGui(double mx, double my, int button);

    protected abstract boolean scrollGui(double mx, double my, double amount);

    @Override
    protected final void renderScaled(GuiGraphicsExtractor context, double mouseX, double mouseY,
                                      float partialTicks) {
        syncTextInput();
        renderGui(context, (int) mouseX, (int) mouseY, partialTicks);
    }

    // Asked for whenever a field is taking characters. Clicking one is the
    // only way in and the frame before the first keystroke covers the rest.
    private void syncTextInput() {
        textInput.set(searchBar.isFocused() || settings.isEditing() || extraTyping());
    }

    // A screen may own another field that takes characters.
    protected boolean extraTyping() {
        return false;
    }

    @Override
    public void removed() {
        textInput.set(false);
        super.removed();
    }

    @Override
    public final boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (closesGui(event)) {
            onClose();
            return true;
        }
        holdScale(true);
        boolean handled = clickGui(toView(event.x()), toView(event.y()), event.button());
        syncTextInput();
        return handled || super.mouseClicked(event, doubleClick);
    }

    @Override
    public final boolean mouseReleased(MouseButtonEvent event) {
        boolean handled = releaseGui(toView(event.x()), toView(event.y()), event.button());
        searchBar.release();
        holdScale(false);
        checkStyle();
        return handled || super.mouseReleased(event);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        searchBar.drag(toView(event.x()));
        return super.mouseDragged(event, dragX, dragY);
    }

    // Nothing scrolls whilst the left button is held. The rows would slide out from
    // under whatever it holds.
    @Override
    public final boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (OfflineClient.MC.mouseHandler.isLeftPressed()) {
            return true;
        }
        return scrollGui(toView(mouseX), toView(mouseY), scrollY)
            || super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float deltaTicks) {
        GuiTheme.dimBackground(this, context);
    }

    @Override
    public void onClose() {
        settings.beginClick();
        releaseDrags();
        ResetFoot.disarm();
        saveState();
        super.onClose();
    }

    protected final void checkStyle() {
        boolean window = Modules.get(ClickGuiModule.class).isWindow();
        if (window == isWindowStyle()) {
            return;
        }
        settings.commitEditing();
        releaseDrags();
        saveState();
        OfflineClient.MC.gui.setScreen(window ? new WindowGuiScreen() : new ClickGuiScreen());
    }

    public final String search() {
        return searchBar.get();
    }

    public final boolean isSearching() {
        return !searchBar.isEmpty();
    }

    // A match count below zero leaves the tally off.
    public final void renderSearchBox(GuiGraphicsExtractor context, Font font, int x, int y,
                                      int w, int mouseX, int mouseY, int matches) {
        String tally = matches >= 0 && isSearching() ? Tally.counted(matches, "match") : null;
        searchBar.render(context, font, x, y, w, mouseX, mouseY, tally);
    }

    protected final SearchField.Click clickSearchBox(double mx, double my) {
        return searchBar.click(mx, my);
    }

    // The search survives closing the GUI. Reopening lands on the same
    // results and the cross in the box clears them. Both styles share it.
    protected final void restoreSearch() {
        JsonObject gui = OfflineClient.INSTANCE.getConfigManager().getGuiState();
        if (!gui.has(SEARCH_KEY) || !gui.get(SEARCH_KEY).isJsonPrimitive()) {
            return;
        }
        String text = gui.get(SEARCH_KEY).getAsString();
        searchBar.set(text);
    }

    protected final void saveSearch() {
        OfflineClient.INSTANCE.getConfigManager().getGuiState().addProperty(SEARCH_KEY, searchBar.get());
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
        if (settings.keyPressed(event)) {
            return true;
        }

        if (searchBar.isFocused()) {
            if (key == InputConstants.KEY_ESCAPE || key == InputConstants.KEY_RETURN
                || key == InputConstants.KEY_NUMPADENTER) {
                if (key == InputConstants.KEY_ESCAPE) {
                    searchBar.clear();
                }
                searchBar.setFocused(false);
                return true;
            }
            return searchBar.keyPressed(event);
        }

        // An undo can bring the style back and no mouse release follows a key.
        if (ResetFoot.undoKey(event)) {
            checkStyle();
            return true;
        }

        if (guiKey().answers(event)) {
            onClose();
            return true;
        }
        return false;
    }

    private static KeybindSetting guiKey() {
        return Modules.get(ClickGuiModule.class).getKeybind();
    }

    // The mouse button the GUI is bound to closes it like its key does. Never whilst a row
    // listens for a bind.
    private boolean closesGui(MouseButtonEvent event) {
        return guiKey().answers(event) && !settings.isListening() && !extraTyping();
    }

    // A modifier key let go may be the bind a row is listening for.
    @Override
    public boolean keyReleased(KeyEvent event) {
        return settings.keyReleased(event) || super.keyReleased(event);
    }

    @Override
    public boolean isTyping() {
        return searchBar.isFocused() || settings.isEditing() || settings.isListening() || extraTyping();
    }

    protected final boolean handleCommonChar(char c) {
        if (settings.charTyped(c)) {
            return true;
        }
        return searchBar.charTyped(c);
    }
}
