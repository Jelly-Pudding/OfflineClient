package com.jellypudding.offlineclient.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

// A screen of the client drawn at the ClickGUI scale. Everything it draws goes through the scale
// and every pointer position is divided by it to match.
abstract class ScaledScreen extends Screen implements ClientScreen {

    private final LatchedScale latched = new LatchedScale();

    protected ScaledScreen(Component title) {
        super(title);
    }

    // The pointer arrives in view pixels with its fraction kept.
    protected abstract void renderScaled(GuiGraphicsExtractor context, double mouseX, double mouseY,
                                         float partialTicks);

    // What the last frame drew at.
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

    // Each screen holds the scale from the press it takes until the release.
    protected final void holdScale(boolean held) {
        latched.hold(held);
    }

    @Override
    public final void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY,
                                         float partialTicks) {
        latched.refresh();
        context.pose().pushMatrix();
        context.pose().scale(latched.get(), latched.get());
        renderScaled(context, toView(mouseX), toView(mouseY), partialTicks);
        context.pose().popMatrix();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
