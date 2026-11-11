package com.jellypudding.offlineclient.gui;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.render.ClientFonts;
import com.jellypudding.offlineclient.util.RenderUtil;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

// One tab of the GUI on a screen of its own. It opens from the game's menus where there is no
// world for the GUI itself and goes back to the screen it was opened over.
public final class TabScreen extends ScaledScreen {

    private static final int TITLE_TOP = 10;
    // Where the box of rows starts under the title.
    private static final int BOX_TOP = 26;
    private static final int BACK_WIDTH = 44;
    private static final int BACK_HEIGHT = 14;
    private static final int BACK_INSET = 6;
    private static final int TOOLTIP_WIDTH = 200;

    private final Screen parent;
    private final TextInput textInput = new TextInput(this);
    private final TabView view;
    private String tooltip;

    public TabScreen(Screen parent, String title, TabView.Source source) {
        super(Component.literal(title));
        this.parent = parent;
        view = new TabView(source, tip -> tooltip = tip);
    }

    private static boolean overBack(double mx, double my) {
        return SettingWidget.isOver(mx, my, BACK_INSET, BACK_INSET, BACK_WIDTH, BACK_HEIGHT);
    }

    @Override
    public boolean isTyping() {
        return view.isTyping();
    }

    @Override
    protected void renderScaled(GuiGraphicsExtractor context, double pointerX, double pointerY, float partialTicks) {
        textInput.set(view.isTyping());
        int mouseX = (int) pointerX;
        int mouseY = (int) pointerY;
        Font font = ClientFonts.gui();
        tooltip = null;
        context.centeredText(font, getTitle(), viewWidth() / 2, TITLE_TOP, GuiTheme.accentText());
        GuiTheme.button(context, font, BACK_INSET, BACK_INSET, BACK_WIDTH, BACK_HEIGHT, "back",
            overBack(mouseX, mouseY), true);
        view.render(context, viewWidth(), viewHeight(), BOX_TOP, mouseX, mouseY);
        if (tooltip != null && !tooltip.isEmpty()) {
            RenderUtil.tooltip(context, font, RenderUtil.wrap(font, tooltip, TOOLTIP_WIDTH), mouseX, mouseY,
                viewWidth(), viewHeight(), GuiTheme.bgTooltip(), GuiTheme.text());
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        holdScale(true);
        double mx = toView(event.x());
        double my = toView(event.y());
        if (overBack(mx, my)) {
            onClose();
            return true;
        }
        return view.mouseClicked(mx, my, event.button()) || super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        view.release();
        holdScale(false);
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        view.drag(toView(event.x()));
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (view.isOver(toView(mouseX), toView(mouseY))) {
            view.wheel(scrollY);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    // The field takes escape first to clear itself. A second one leaves the screen.
    @Override
    public boolean keyPressed(KeyEvent event) {
        if (view.keyPressed(event)) {
            return true;
        }
        if (event.key() == InputConstants.KEY_ESCAPE) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        return view.keyReleased(event) || super.keyReleased(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        return view.charTyped((char) event.codepoint()) || super.charTyped(event);
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
}
