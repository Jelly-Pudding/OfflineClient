package com.jellypudding.offlineclient.event.events;

import com.jellypudding.offlineclient.event.UncancellableEvent;
import net.minecraft.client.gui.GuiGraphicsExtractor;

// Fired whilst the HUD is being drawn.
public final class Render2DEvent extends UncancellableEvent {

    private final GuiGraphicsExtractor context;
    private final float partialTicks;

    public Render2DEvent(GuiGraphicsExtractor context, float partialTicks) {
        this.context = context;
        this.partialTicks = partialTicks;
    }

    public GuiGraphicsExtractor getContext() {
        return context;
    }

    public float getPartialTicks() {
        return partialTicks;
    }
}
