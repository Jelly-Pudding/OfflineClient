package com.jellypudding.offlineclient.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

// The world to screen projection applies the same view bobbing the game uses.
@Mixin(GameRenderer.class)
public interface GameRendererAccessor {

    @Invoker("bobHurt")
    void offlineclient$bobHurt(CameraRenderState camera, PoseStack poseStack);

    @Invoker("bobView")
    void offlineclient$bobView(CameraRenderState camera, PoseStack poseStack);
}
