package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.modules.misc.Conceal;
import com.jellypudding.offlineclient.modules.render.BlockSelection;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.util.Modules;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public class LevelRendererMixin {

    @Inject(
        method = "render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lorg/joml/Vector4f;ZZ)V",
        at = @At("RETURN"))
    private void onRender(GraphicsResourceAllocator allocator, boolean renderBlockOutline,
                          CameraRenderState cameraState, GpuBufferSlice gpuBufferSlice,
                          Vector4f vector4f, boolean shouldRenderSky, boolean flag,
                          CallbackInfo ci) {
        PoseStack poseStack = new PoseStack();
        poseStack.mulPose(cameraState.viewRotationMatrix);
        float partialTicks = OfflineClient.MC.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        DrawBatch batch = new DrawBatch(poseStack);
        try {
            OfflineClient.INSTANCE.getEventBus().post(new Render3DEvent(batch, partialTicks));
        } finally {
            // The native buffer is freed even when a handler throws. Conceal lets the handlers run
            // for those that do more than draw and throws away what they drew.
            if (Conceal.hides(Conceal.Part.WORLD)) {
                batch.discard();
            } else {
                batch.draw();
            }
        }
    }

    // BlockSelection draws its own outline in place of the vanilla one. The vanilla one stays
    // whilst Conceal throws the world drawing away.
    @Inject(
        method = "submitBlockOutline(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/LevelRenderState;)V",
        at = @At("HEAD"), cancellable = true)
    private void onSubmitBlockOutline(CallbackInfo ci) {
        if (Modules.enabled(BlockSelection.class) && !Conceal.hides(Conceal.Part.WORLD)) {
            ci.cancel();
        }
    }
}
