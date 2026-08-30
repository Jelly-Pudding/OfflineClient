package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.events.MouseScrollEvent;
import com.jellypudding.offlineclient.modules.player.GUIMove;
import com.jellypudding.offlineclient.modules.player.MiddleClickExtra;
import com.jellypudding.offlineclient.modules.render.FreeLook;
import com.jellypudding.offlineclient.modules.render.Freecam;
import com.jellypudding.offlineclient.util.Modules;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.player.LocalPlayer;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {

    @WrapOperation(method = "turnPlayer(D)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"))
    private void wrapTurn(LocalPlayer player, double deltaYaw, double deltaPitch,
                          Operation<Void> original) {
        Freecam freecam = Modules.get(Freecam.class);
        if (freecam != null && freecam.movesCamera()) {
            freecam.turn(deltaYaw, deltaPitch);
            return;
        }
        FreeLook freeLook = Modules.get(FreeLook.class);
        if (freeLook != null && freeLook.isActive()) {
            freeLook.turn(deltaYaw, deltaPitch);
            return;
        }
        original.call(player, deltaYaw, deltaPitch);
    }

    @Inject(method = "onButton(JLnet/minecraft/client/input/MouseButtonInfo;I)V",
        at = @At("HEAD"),
        cancellable = true)
    private void onButton(long window, MouseButtonInfo info, int action, CallbackInfo ci) {
        // The pointer is hidden and off its last spot.
        if (offlineclient$turning()) {
            ci.cancel();
            return;
        }
        if (info.button() == GLFW.GLFW_MOUSE_BUTTON_MIDDLE && action == GLFW.GLFW_PRESS) {
            MiddleClickExtra extra = Modules.get(MiddleClickExtra.class);
            if (extra != null) {
                extra.onMiddleClick();
            }
        }
    }

    // Modules may take the wheel before it reaches the hotbar.
    @Inject(method = "onScroll(JDD)V", at = @At("HEAD"), cancellable = true)
    private void onScroll(long window, double horizontal, double vertical, CallbackInfo ci) {
        if (OfflineClient.MC.gui.screen() != null || vertical == 0) {
            return;
        }
        if (OfflineClient.INSTANCE.getEventBus().post(new MouseScrollEvent(vertical)).isCancelled()) {
            ci.cancel();
        }
    }

    @Unique
    private static boolean offlineclient$turning() {
        GUIMove guiMove = Modules.get(GUIMove.class);
        return guiMove != null && guiMove.isTurning();
    }

    @ModifyExpressionValue(method = "handleAccumulatedMovement()V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/Gui;screen()Lnet/minecraft/client/gui/screens/Screen;"))
    private Screen hideScreenWhileTurning(Screen original) {
        return offlineclient$turning() ? null : original;
    }

    // Vanilla only turns the player whilst the mouse is grabbed.
    @ModifyExpressionValue(method = "handleAccumulatedMovement()V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/MouseHandler;isMouseGrabbed()Z"))
    private boolean turnWhileScreenOpen(boolean original) {
        return original || offlineclient$turning();
    }
}
