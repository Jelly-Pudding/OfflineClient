package com.jellypudding.offlineclient.event.events;

import com.jellypudding.offlineclient.event.Event;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.mojang.blaze3d.vertex.PoseStack;

/**
 * Fired after the world has rendered. The shared batch is drawn once after
 * every handler has run.
 */
public final class Render3DEvent extends Event {

    private final PoseStack poseStack;
    private final DrawBatch batch;
    private final float partialTicks;

    public Render3DEvent(PoseStack poseStack, DrawBatch batch, float partialTicks) {
        this.poseStack = poseStack;
        this.batch = batch;
        this.partialTicks = partialTicks;
    }

    public PoseStack getPoseStack() {
        return poseStack;
    }

    public DrawBatch getBatch() {
        return batch;
    }

    public float getPartialTicks() {
        return partialTicks;
    }
}
