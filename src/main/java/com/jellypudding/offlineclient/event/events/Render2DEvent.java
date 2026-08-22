package com.jellypudding.offlineclient.event.events;

import com.jellypudding.offlineclient.event.Event;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Fired while the HUD is being drawn.
 */
public final class Render2DEvent extends Event {

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
