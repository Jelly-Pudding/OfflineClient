package com.jellypudding.offlineclient.gui;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.path.Travel;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.function.Function;

abstract class ListWindow extends ScaledScreen {

    protected static final int MARGIN = WindowParts.MARGIN;
    private static final int GAP = 5;

    protected final Screen parent;
    protected final SearchField search = SearchField.sticky("type to search", this::changed);
    protected final Footer footer = new Footer();
    private final TextInput textInput = new TextInput(this);

    protected ListWindow(String title, Screen parent) {
        super(Component.literal(title));
        this.parent = parent;
    }

    // It waits a frame because a chat line that opened it would otherwise close it again.
    static void openOver(Function<Screen, ListWindow> window) {
        Minecraft mc = OfflineClient.MC;
        mc.schedule(() -> mc.gui.setScreen(window.apply(mc.gui.screen())));
    }

    protected abstract void changed();

    protected abstract void renderView(GuiGraphicsExtractor context, int mouseX, int mouseY);

    protected abstract boolean click(double mx, double my);

    protected abstract void releaseLists();

    protected final int windowWidth() {
        return WindowParts.width(viewWidth());
    }

    protected final int windowHeight() {
        return WindowParts.height(viewHeight());
    }

    protected final int windowX() {
        return (viewWidth() - windowWidth()) / 2;
    }

    protected final int windowY() {
        return (viewHeight() - windowHeight()) / 2;
    }

    protected final int toolbarY() {
        return windowY() + WindowParts.TITLE_HEIGHT + GAP;
    }

    protected final int contentTop() {
        return toolbarY() + SearchField.HEIGHT + GAP;
    }

    protected final int contentHeight() {
        int bottom = windowY() + windowHeight() - WindowParts.FOOT_HEIGHT - GAP;
        return Math.max(RowList.ROW_HEIGHT, bottom - contentTop());
    }

    protected final int listX() {
        return windowX() + WindowParts.SIDEBAR_WIDTH;
    }

    protected final int listWidth() {
        return windowWidth() - WindowParts.SIDEBAR_WIDTH - MARGIN;
    }

    protected final void walkTo(BlockPos pos) {
        if (Travel.to(pos)) {
            closeAll();
        } else {
            footer.say("Could not start the walk there", GuiTheme.RED_TEXT);
        }
    }

    protected final void copySpot(BlockPos pos) {
        String spot = BlockUtil.text(pos);
        OfflineClient.MC.keyboardHandler.setClipboard(spot);
        footer.say("Copied " + spot);
    }

    @Override
    public boolean isTyping() {
        return true;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float deltaTicks) {
        GuiTheme.dimBackground(this, context);
    }

    @Override
    protected final void renderScaled(GuiGraphicsExtractor context, double mouseX, double mouseY,
                                      float partialTicks) {
        renderView(context, (int) mouseX, (int) mouseY);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        holdScale(true);
        double mx = toView(event.x());
        double my = toView(event.y());
        return click(mx, my) || super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        releaseLists();
        search.release();
        holdScale(false);
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        search.drag(toView(event.x()));
        return super.mouseDragged(event, dragX, dragY);
    }

    // Escape closes the window before the search box can take it.
    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == InputConstants.KEY_ESCAPE) {
            onClose();
            return true;
        }
        return search.keyPressed(event) || super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        return search.charTyped((char) event.codepoint()) || super.charTyped(event);
    }

    @Override
    public void added() {
        super.added();
        textInput.set(true);
    }

    @Override
    public void removed() {
        textInput.set(false);
        super.removed();
    }

    @Override
    public void onClose() {
        OfflineClient.MC.gui.setScreen(parent);
    }

    // A walk cannot run behind a screen. The screen this one covers closes its own way and
    // keeps what it saves on the way out.
    private void closeAll() {
        if (parent == null) {
            OfflineClient.MC.gui.setScreen(null);
        } else {
            parent.onClose();
        }
    }
}
