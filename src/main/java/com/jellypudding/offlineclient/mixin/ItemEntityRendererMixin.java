package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.render.ItemPhysics;
import com.jellypudding.offlineclient.util.Modules;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.ItemEntityRenderer;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Quaternionfc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

// ItemPhysics hooks. The bob and the spin are the only two poses a drop gets.
@Mixin(ItemEntityRenderer.class)
public abstract class ItemEntityRendererMixin {

    @Unique
    private static final String SUBMIT =
        "submit(Lnet/minecraft/client/renderer/entity/state/ItemEntityRenderState;"
            + "Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;"
            + "Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V";

    // The bob that lifts the drop off the ground.
    @WrapOperation(method = SUBMIT,
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V"))
    private void onLift(PoseStack target, float x, float y, float z, Operation<Void> original,
                        ItemEntityRenderState state, PoseStack poseStack,
                        SubmitNodeCollector collector, CameraRenderState camera) {
        ItemPhysics physics = Modules.active(ItemPhysics.class);
        original.call(target, x, physics == null ? y : physics.groundOffset(state), z);
    }

    // The spin the drop turns with.
    @WrapOperation(method = SUBMIT,
        at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vertex/PoseStack;mulPose(Lorg/joml/Quaternionfc;)V"))
    private void onSpin(PoseStack target, Quaternionfc rotation, Operation<Void> original,
                        ItemEntityRenderState state, PoseStack poseStack,
                        SubmitNodeCollector collector, CameraRenderState camera) {
        ItemPhysics physics = Modules.active(ItemPhysics.class);
        if (physics == null) {
            original.call(target, rotation);
            return;
        }
        physics.lay(target, state);
    }
}
