package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.render.Freecam;
import com.jellypudding.offlineclient.modules.render.HandView;
import com.jellypudding.offlineclient.modules.render.NoShieldOverlay;
import com.jellypudding.offlineclient.modules.render.Zoom;
import com.jellypudding.offlineclient.util.Modules;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FirstPersonHandsAndItemsRenderer.class)
public abstract class ItemInHandRendererMixin {

    @Unique
    private static final String SUBMIT_HANDS = "submitHandsWithItems(FLcom/mojang/blaze3d/vertex/PoseStack;"
        + "Lnet/minecraft/client/renderer/SubmitNodeCollector;"
        + "Lnet/minecraft/client/renderer/state/level/PlayerRenderState;"
        + "Lnet/minecraft/client/renderer/state/level/FirstPersonHandsAndItemsRenderState;)V";

    @Unique
    private static final String SUBMIT_ARM =
        "submitArmWithItem(Lnet/minecraft/client/renderer/state/level/PlayerRenderState;"
        + "Lnet/minecraft/client/renderer/state/level/FirstPersonHandsAndItemsRenderState;"
        + "FFLnet/minecraft/world/InteractionHand;FLnet/minecraft/world/item/ItemStack;F"
        + "Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V";

    // The whole first person hand pass is skipped whilst a module hides it.
    @Inject(method = SUBMIT_HANDS, at = @At("HEAD"), cancellable = true)
    private void onSubmitHands(float partialTicks, PoseStack poseStack, SubmitNodeCollector collector,
                               PlayerRenderState player, FirstPersonHandsAndItemsRenderState hands,
                               CallbackInfo ci) {
        Freecam freecam = Modules.get(Freecam.class);
        Zoom zoom = Modules.get(Zoom.class);
        if ((freecam != null && freecam.hidesHand()) || (zoom != null && zoom.hidesHand())) {
            ci.cancel();
        }
    }

    // The swing progress both hands are drawn with.
    @ModifyExpressionValue(method = SUBMIT_HANDS,
        at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;swingAnimation:F"))
    private float onSwingProgress(float original, float partialTicks, PoseStack poseStack,
                                  SubmitNodeCollector collector, PlayerRenderState player,
                                  FirstPersonHandsAndItemsRenderState hands) {
        HandView handView = Modules.get(HandView.class);
        return handView == null
            ? original : handView.adjustSwing(original, hands.mainHandItem, hands.offHandItem);
    }

    // Just before a held item is drawn. The map paths have their own pose and are left alone.
    @Inject(method = SUBMIT_ARM,
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/item/ItemStackRenderState;submit("
                + "Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;III)V"))
    private void beforeItem(PlayerRenderState player, FirstPersonHandsAndItemsRenderState hands,
                            float partialTicks, float pitch, InteractionHand hand, float swing,
                            ItemStack stack, float equip, PoseStack poseStack,
                            SubmitNodeCollector collector, int light, CallbackInfo ci) {
        HandView handView = Modules.get(HandView.class);
        if (handView != null) {
            handView.adjustItem(hand, poseStack);
        }
    }

    // Just before an empty arm is drawn.
    @Inject(method = SUBMIT_ARM,
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/FirstPersonHandsAndItemsRenderer;renderPlayerArm("
                + "Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;"
                + "IFFLnet/minecraft/world/entity/HumanoidArm;"
                + "Lnet/minecraft/client/renderer/state/level/PlayerRenderState;)V"))
    private void beforeArm(PlayerRenderState player, FirstPersonHandsAndItemsRenderState hands,
                           float partialTicks, float pitch, InteractionHand hand, float swing,
                           ItemStack stack, float equip, PoseStack poseStack,
                           SubmitNodeCollector collector, int light, CallbackInfo ci) {
        HandView handView = Modules.get(HandView.class);
        if (handView != null) {
            handView.adjustArm(poseStack);
        }
    }

    // Reached only whilst the item is in use. A shield in use is a raised shield.
    @Inject(method = SUBMIT_ARM,
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/item/ItemStack;getUseAnimation()Lnet/minecraft/world/item/ItemUseAnimation;",
            shift = At.Shift.AFTER))
    private void whileBlocking(PlayerRenderState player, FirstPersonHandsAndItemsRenderState hands,
                               float partialTicks, float pitch, InteractionHand hand, float swing,
                               ItemStack stack, float equip, PoseStack poseStack,
                               SubmitNodeCollector collector, int light, CallbackInfo ci) {
        offlineclient$lowerShield(stack, poseStack, true);
    }

    @Unique
    private static void offlineclient$lowerShield(ItemStack stack, PoseStack poseStack, boolean blocking) {
        if (stack.getUseAnimation() != ItemUseAnimation.BLOCK) {
            return;
        }
        NoShieldOverlay overlay = Modules.get(NoShieldOverlay.class);
        if (overlay != null) {
            overlay.lowerShield(poseStack, blocking);
        }
    }

    @Inject(method = "applyEatTransform", at = @At("HEAD"), cancellable = true)
    private void onEatTransform(PoseStack poseStack, float partialTicks, HumanoidArm arm,
                                float equip, int useTicks, CallbackInfo ci) {
        HandView handView = Modules.get(HandView.class);
        if (handView != null && handView.hidesEating()) {
            ci.cancel();
        }
    }
}
