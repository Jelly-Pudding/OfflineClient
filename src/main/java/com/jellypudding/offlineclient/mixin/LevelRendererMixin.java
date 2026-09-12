package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.modules.render.BlockSelection;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.util.Modules;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public class LevelRendererMixin {

    @Inject(
        method = "render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V",
        at = @At("RETURN"))
    private void onRender(GraphicsResourceAllocator allocator, DeltaTracker tickCounter,
                          boolean renderBlockOutline, CameraRenderState cameraState,
                          Matrix4fc positionMatrix, GpuBufferSlice gpuBufferSlice,
                          Vector4f vector4f, boolean shouldRenderSky, CallbackInfo ci) {
        PoseStack poseStack = new PoseStack();
        poseStack.mulPose(positionMatrix);
        float partialTicks = tickCounter.getGameTimeDeltaPartialTick(false);
        DrawBatch batch = new DrawBatch(poseStack);
        try {
            OfflineClient.INSTANCE.getEventBus().post(new Render3DEvent(poseStack, batch, partialTicks));
        } finally {
            // The native buffer is freed even when a handler throws.
            batch.draw();
        }
    }

    // BlockSelection draws its own outline in place of the vanilla one.
    @Inject(
        method = "submitBlockOutline(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/LevelRenderState;)V",
        at = @At("HEAD"), cancellable = true)
    private void onSubmitBlockOutline(PoseStack poseStack, SubmitNodeCollector collector,
                                      LevelRenderState state, CallbackInfo ci) {
        if (Modules.enabled(BlockSelection.class)) {
            ci.cancel();
        }
    }
}
