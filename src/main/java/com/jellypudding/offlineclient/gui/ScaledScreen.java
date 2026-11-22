package com.jellypudding.offlineclient.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

abstract class ScaledScreen extends Screen implements ClientScreen {

    private final LatchedScale latched = new LatchedScale();

    protected ScaledScreen(Component title) {
        super(title);
    }

    // The pointer arrives in view pixels with its fraction kept.
    protected abstract void renderScaled(GuiGraphicsExtractor context, double mouseX, double mouseY,
                                         float partialTicks);

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
