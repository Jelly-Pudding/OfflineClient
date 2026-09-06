package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.render.Ambience;
import com.jellypudding.offlineclient.modules.render.ClearView;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.Camera;
import net.minecraft.world.level.material.FogType;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.util.ARGB;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FogRenderer.class)
public class FogRendererMixin {

    // Far past any render distance. Nothing inside the world ever fades.
    @Unique
    private static final float OFFLINECLIENT_FAR = 1.0E7f;

    // The colour every fog band is drawn in.
    @ModifyVariable(
        method = "updateBuffer(Ljava/nio/ByteBuffer;ILorg/joml/Vector4f;FFFFFF)V",
        at = @At("HEAD"), argsOnly = true)
    private Vector4f recolourFog(Vector4f vanilla) {
        Ambience ambience = Modules.get(Ambience.class);
        if (ambience == null || !ambience.paintsFog()) {
            return vanilla;
        }
        int colour = ambience.fogColor();
        return new Vector4f(ARGB.red(colour) / 255f, ARGB.green(colour) / 255f,
            ARGB.blue(colour) / 255f, 1f);
    }

    @Inject(method = "setupFog", at = @At("RETURN"))
    private void onSetupFog(Camera camera, int renderDistance, DeltaTracker deltaTracker,
                            float skyDarken, ClientLevel level,
                            CallbackInfoReturnable<FogData> cir) {
        ClearView clearView = Modules.active(ClearView.class);
        if (clearView == null) {
            return;
        }
        // The blue underwater is mostly the short water fog. Pushing it away clears the tint.
        boolean underwater = clearView.blocksWater() && camera.getFluidInCamera() == FogType.WATER;
        if (!clearView.blocksFog() && !underwater) {
            return;
        }
        FogData data = cir.getReturnValue();
        if (data == null) {
            return;
        }
        data.environmentalStart = OFFLINECLIENT_FAR;
        data.environmentalEnd = OFFLINECLIENT_FAR;
        data.renderDistanceStart = OFFLINECLIENT_FAR;
        data.renderDistanceEnd = OFFLINECLIENT_FAR;
        data.skyEnd = OFFLINECLIENT_FAR;
        data.cloudEnd = OFFLINECLIENT_FAR;
    }
}
