package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.modules.player.MiddleClickExtra;
import com.jellypudding.offlineclient.modules.render.Freecam;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.player.LocalPlayer;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {

    /** While Freecam is on the mouse turns the camera and not the player. */
    @WrapOperation(method = "turnPlayer(D)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"))
    private void wrapTurn(LocalPlayer player, double deltaYaw, double deltaPitch,
                          Operation<Void> original) {
        Freecam freecam = OfflineClient.INSTANCE.getModuleManager().get(Freecam.class);
        if (freecam.isEnabled()) {
            freecam.turn(deltaYaw, deltaPitch);
            return;
        }
        original.call(player, deltaYaw, deltaPitch);
    }

    @Inject(method = "onButton(JLnet/minecraft/client/input/MouseButtonInfo;I)V",
        at = @At("HEAD"))
    private void onButton(long window, MouseButtonInfo info, int action, CallbackInfo ci) {
        if (OfflineClient.INSTANCE.getModuleManager() == null) {
            return;
        }
        if (info.button() == GLFW.GLFW_MOUSE_BUTTON_MIDDLE && action == GLFW.GLFW_PRESS) {
            OfflineClient.INSTANCE.getModuleManager().get(MiddleClickExtra.class).onMiddleClick();
        }
    }
}
