package com.jellypudding.offlineclient.mixinterface;

// Extra flags modules hang on an entity render state whilst it is extracted.
// LivingEntityRendererMixin reads them back when the model is submitted.
public interface IRenderState {

    // Render states are pooled and reused every frame.
    void offlineclient$clear();

    // ARGB multiplied into the model colour. Zero leaves the model alone.
    int offlineclient$getTint();

    void offlineclient$setTint(int tint);

    // Draws the model with the depth test off.
    boolean offlineclient$isChams();

    void offlineclient$setChams(boolean chams);

    // Swaps the skin for a plain white texture so only the tint shows.
    boolean offlineclient$isFlat();

    void offlineclient$setFlat(boolean flat);

    boolean offlineclient$isForceVisible();

    void offlineclient$setForceVisible(boolean forceVisible);
}
