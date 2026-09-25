package com.jellypudding.offlineclient.gui;

import com.google.gson.JsonObject;
import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.modules.misc.ClickGuiModule;
import com.jellypudding.offlineclient.modules.render.Blur;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.Modules;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

// Shared by both ClickGUI styles. Owns the search box and the typing and
// binding state.
public abstract class GuiScreenBase extends Screen {

    private static final String SEARCH_KEY = "search";
    private static final int DIM_TOP = 0x70101018;
    private static final int DIM_BOTTOM = 0xA0060610;

    protected final SearchField searchBar = new SearchField("click here to search", this::onSearchChanged);

    private final TextInput textInput = new TextInput(this);
    protected final SettingHost settings = new SettingHost(this, this::setTooltip);

    private final LatchedScale latched = new LatchedScale();

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

    // Everything in the GUI is drawn through this and every pointer position
    // is divided by it to match. The font is a bitmap and goes soft between
    // whole pixels. The chosen size therefore lands on the nearest step the
    // game's own scale allows. It never outgrows the largest scale the game
    // would pick for the window and the GUI always fits.
    public static float guiScale() {
        ClickGuiModule gui = Modules.get(ClickGuiModule.class);
        float wanted = gui == null ? 1f : gui.scale();
        Window window = OfflineClient.MC.getWindow();
        int vanilla = window.getGuiScale();
        if (vanilla <= 0) {
            return wanted;
        }
        int largest = Math.max(1, window.calculateScale(0, false));
        return Math.clamp(Math.round(vanilla * wanted), 1, largest) / (float) vanilla;
    }

    // What the last frame drew at. Held still whilst the pointer is down
    // because dragging the scale slider would otherwise move the slider out
    // from under the pointer that is dragging it.
    protected final float scale() {
        return latched.get();
    }

    protected final int viewWidth() {
        return (int) latched.toView(width);
    }

    protected final int viewHeight() {
        return (int) latched.toView(height);
    }

    protected final double toView(double screen) {
        return latched.toView(screen);
    }

    @Override
    public final void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY,
                                         float partialTicks) {
        syncTextInput();
        latched.refresh();
        context.pose().pushMatrix();
        context.pose().scale(latched.get(), latched.get());
        renderGui(context, (int) toView(mouseX), (int) toView(mouseY), partialTicks);
        context.pose().popMatrix();
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
        latched.hold(true);
        boolean handled = clickGui(toView(event.x()), toView(event.y()), event.button());
        syncTextInput();
        return handled || super.mouseClicked(event, doubleClick);
    }

    @Override
    public final boolean mouseReleased(MouseButtonEvent event) {
        boolean handled = releaseGui(toView(event.x()), toView(event.y()), event.button());
        searchBar.release();
        latched.hold(false);
        checkStyle();
        return handled || super.mouseReleased(event);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        searchBar.drag(toView(event.x()));
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public final boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return scrollGui(toView(mouseX), toView(mouseY), scrollY)
            || super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float deltaTicks) {
        dimBackground(this, context);
    }

    // The vanilla blur washes the panel colours out. Blur adds its own when
    // asked. The wash over the world follows the opacity setting because a
    // see through panel is worth nothing over a blacked out world.
    public static void dimBackground(Screen screen, GuiGraphicsExtractor context) {
        Blur blur = Modules.get(Blur.class);
        if (blur != null && blur.wants(screen)) {
            blur.blurHere(context);
        }
        ClickGuiModule gui = Modules.get(ClickGuiModule.class);
        float share = gui == null ? 1f : gui.opacity();
        context.fillGradient(0, 0, screen.width, screen.height,
            ColorUtil.fade(DIM_TOP, share), ColorUtil.fade(DIM_BOTTOM, share));
    }

    @Override
    public void onClose() {
        settings.beginClick();
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
        settings.commitEditing();
        releaseDrags();
        saveState();
        OfflineClient.MC.gui.setScreen(window ? new WindowGuiScreen() : new ClickGuiScreen());
    }

    // The hover text can be switched off in the ClickGUI module.
    protected static boolean hoverHelp() {
        return OfflineClient.INSTANCE.getModuleManager().get(ClickGuiModule.class).showsHoverHelp();
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
        String tally = matches >= 0 && isSearching()
            ? matches + (matches == 1 ? " match" : " matches") : null;
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

        if (key == OfflineClient.INSTANCE.getModuleManager()
            .get(ClickGuiModule.class).getKeybind().getValue()) {
            onClose();
            return true;
        }
        return false;
    }

    // True whilst a key press would type or bind rather than reach the game.
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
