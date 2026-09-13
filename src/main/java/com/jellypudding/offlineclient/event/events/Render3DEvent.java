package com.jellypudding.offlineclient.event.events;

import com.jellypudding.offlineclient.event.Event;
import com.jellypudding.offlineclient.render.DrawBatch;

// Fired after the world has rendered. The shared batch is drawn once after every handler has run.
public final class Render3DEvent extends Event {

    private final DrawBatch batch;
    private final float partialTicks;

    public Render3DEvent(DrawBatch batch, float partialTicks) {
        this.batch = batch;
        this.partialTicks = partialTicks;
    }

    public DrawBatch getBatch() {
        return batch;
    }

    public float getPartialTicks() {
        return partialTicks;
    }
}
