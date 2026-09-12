package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.mixinterface.IRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(EntityRenderState.class)
public class EntityRenderStateMixin implements IRenderState {

    @Unique
    private int offlineclient$tint;
    @Unique
    private boolean offlineclient$chams;
    @Unique
    private boolean offlineclient$flat;
    @Unique
    private boolean offlineclient$forceVisible;

    @Override
    public void offlineclient$clear() {
        offlineclient$tint = 0;
        offlineclient$chams = false;
        offlineclient$flat = false;
        offlineclient$forceVisible = false;
    }

    @Override
    public int offlineclient$getTint() {
        return offlineclient$tint;
    }

    @Override
    public void offlineclient$setTint(int tint) {
        offlineclient$tint = tint;
    }

    @Override
    public boolean offlineclient$isChams() {
        return offlineclient$chams;
    }

    @Override
    public void offlineclient$setChams(boolean chams) {
        offlineclient$chams = chams;
    }

    @Override
    public boolean offlineclient$isFlat() {
        return offlineclient$flat;
    }

    @Override
    public void offlineclient$setFlat(boolean flat) {
        offlineclient$flat = flat;
    }

    @Override
    public boolean offlineclient$isForceVisible() {
        return offlineclient$forceVisible;
    }

    @Override
    public void offlineclient$setForceVisible(boolean forceVisible) {
        offlineclient$forceVisible = forceVisible;
    }
}
