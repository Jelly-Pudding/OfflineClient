package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.render.Freecam;
import com.jellypudding.offlineclient.modules.render.HandView;
import com.jellypudding.offlineclient.modules.render.NoShieldOverlay;
import com.jellypudding.offlineclient.util.Modules;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererMixin {

    private static final String SUBMIT_HANDS = "submitHandsWithItems(FLcom/mojang/blaze3d/vertex/PoseStack;"
        + "Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/player/LocalPlayer;I)V";

    private static final String SUBMIT_ARM = "submitArmWithItem(Lnet/minecraft/client/player/AbstractClientPlayer;"
        + "FFLnet/minecraft/world/InteractionHand;FLnet/minecraft/world/item/ItemStack;F"
        + "Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V";

    @Shadow
    private ItemStack mainHandItem;

    @Shadow
    private ItemStack offHandItem;

    // The whole first person hand pass is skipped whilst a module hides it.
    @Inject(method = SUBMIT_HANDS, at = @At("HEAD"), cancellable = true)
    private void onSubmitHands(float partialTicks, PoseStack poseStack, SubmitNodeCollector collector,
                               LocalPlayer player, int light, CallbackInfo ci) {
        Freecam freecam = Modules.get(Freecam.class);
        if (freecam != null && freecam.hidesHand()) {
            ci.cancel();
        }
    }

    // The swing progress both hands are drawn with.
    @ModifyExpressionValue(method = SUBMIT_HANDS,
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;getAttackAnim(F)F"))
    private float onSwingProgress(float original) {
        HandView handView = Modules.get(HandView.class);
        return handView == null ? original : handView.adjustSwing(original, mainHandItem, offHandItem);
    }

    // Just before a held item is drawn. The map paths have their own pose and are left alone.
    @Inject(method = SUBMIT_ARM,
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderItem("
                + "Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;"
                + "Lnet/minecraft/world/item/ItemDisplayContext;Lcom/mojang/blaze3d/vertex/PoseStack;"
                + "Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V"))
    private void beforeItem(AbstractClientPlayer player, float partialTicks, float pitch,
                            InteractionHand hand, float swing, ItemStack stack, float equip,
                            PoseStack poseStack, SubmitNodeCollector collector, int light,
                            CallbackInfo ci) {
        HandView handView = Modules.get(HandView.class);
        if (handView != null) {
            handView.adjustItem(hand, poseStack);
        }
    }

    // Just before an empty arm is drawn.
    @Inject(method = SUBMIT_ARM,
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderPlayerArm("
                + "Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;"
                + "IFFLnet/minecraft/world/entity/HumanoidArm;)V"))
    private void beforeArm(AbstractClientPlayer player, float partialTicks, float pitch,
                           InteractionHand hand, float swing, ItemStack stack, float equip,
                           PoseStack poseStack, SubmitNodeCollector collector, int light,
                           CallbackInfo ci) {
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
    private void whileBlocking(AbstractClientPlayer player, float partialTicks, float pitch,
                               InteractionHand hand, float swing, ItemStack stack, float equip,
                               PoseStack poseStack, SubmitNodeCollector collector, int light,
                               CallbackInfo ci) {
        offlineclient$lowerShield(stack, poseStack, true);
    }

    // Reached for a held item that is not in use.
    @Inject(method = SUBMIT_ARM,
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/item/ItemStack;getSwingAnimation()Lnet/minecraft/world/item/component/SwingAnimation;"))
    private void whileResting(AbstractClientPlayer player, float partialTicks, float pitch,
                              InteractionHand hand, float swing, ItemStack stack, float equip,
                              PoseStack poseStack, SubmitNodeCollector collector, int light,
                              CallbackInfo ci) {
        offlineclient$lowerShield(stack, poseStack, false);
    }

    private static void offlineclient$lowerShield(ItemStack stack, PoseStack poseStack, boolean blocking) {
        if (stack.getUseAnimation() != ItemUseAnimation.BLOCK) {
            return;
        }
        NoShieldOverlay overlay = Modules.get(NoShieldOverlay.class);
        if (overlay != null) {
            overlay.lowerShield(poseStack, blocking);
        }
    }

    // A new item rises into view unless the swap is being skipped.
    @ModifyReturnValue(method = "shouldInstantlyReplaceVisibleItem", at = @At("RETURN"))
    private boolean onShouldReplaceInstantly(boolean original) {
        HandView handView = Modules.get(HandView.class);
        return original || (handView != null && handView.skipsSwap());
    }

    // The swap scale is cubed for the modern ease. One cubed is the old snap.
    @ModifyExpressionValue(method = "tick()V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;getItemSwapScale(F)F"))
    private float onSwapScale(float original) {
        HandView handView = Modules.get(HandView.class);
        return handView != null && handView.usesOldAnimations() ? 1f : original;
    }

    @Inject(method = "applyEatTransform", at = @At("HEAD"), cancellable = true)
    private void onEatTransform(PoseStack poseStack, float partialTicks, HumanoidArm arm,
                                ItemStack stack, Player player, CallbackInfo ci) {
        HandView handView = Modules.get(HandView.class);
        if (handView != null && handView.hidesEating()) {
            ci.cancel();
        }
    }
}
