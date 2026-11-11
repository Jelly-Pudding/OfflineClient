package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.events.KeyPressEvent;
import com.jellypudding.offlineclient.event.events.MouseScrollEvent;
import com.jellypudding.offlineclient.modules.combat.AimAssist;
import com.jellypudding.offlineclient.modules.misc.Panic;
import com.jellypudding.offlineclient.modules.player.GUIMove;
import com.jellypudding.offlineclient.modules.player.MiddleClickExtra;
import com.jellypudding.offlineclient.modules.render.FreeLook;
import com.jellypudding.offlineclient.modules.render.Freecam;
import com.jellypudding.offlineclient.modules.render.Zoom;
import com.jellypudding.offlineclient.setting.Bind;
import com.jellypudding.offlineclient.util.ClickRate;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.Modules;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {

    // The first option turnPlayer reads is the mouse sensitivity. The two after it are the invert switches.
    @ModifyExpressionValue(method = "turnPlayer(D)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/OptionInstance;get()Ljava/lang/Object;",
            ordinal = 0))
    private Object zoomSensitivity(Object sensitivity) {
        Zoom zoom = Modules.active(Zoom.class);
        return zoom == null ? sensitivity : zoom.scaleSensitivity((Double) sensitivity);
    }

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
        if (freeLook != null && freeLook.stealsMouse()) {
            freeLook.turn(deltaYaw, deltaPitch);
            return;
        }
        AimAssist aim = Modules.get(AimAssist.class);
        double scale = aim == null ? 1 : aim.mouseScale();
        original.call(player, deltaYaw * scale, deltaPitch * scale);
    }

    @Inject(method = "onButton(JLnet/minecraft/client/input/MouseButtonInfo;I)V",
        at = @At("HEAD"),
        cancellable = true)
    private void onButton(long window, MouseButtonInfo info, int action, CallbackInfo ci) {
        boolean press = action == InputConstants.PRESS;
        InputUtil.trackButton(info.button(), press);
        if (press) {
            ClickRate.press(info.button());
        }
        // The pointer is hidden and off its last spot.
        if (offlineclient$turning()) {
            ci.cancel();
            return;
        }
        Bind input = Bind.of(info);
        if (!input.isBound() || Panic.sleepsThrough(input, action)) {
            return;
        }
        KeyPressEvent event = OfflineClient.INSTANCE.getEventBus().post(new KeyPressEvent(input, action));
        if (event.isCancelled()) {
            ci.cancel();
            return;
        }
        if (!press || !InputUtil.isMiddle(info.button())) {
            return;
        }
        // A middle click a bind answered in play is that bind's alone. Nothing picks a block or
        // throws a pearl. Under a screen binds stand down and the click stays the screen's.
        if (event.wasTaken() && OfflineClient.MC.gui.screen() == null) {
            ci.cancel();
            return;
        }
        MiddleClickExtra extra = Modules.get(MiddleClickExtra.class);
        if (extra != null) {
            extra.onMiddleClick();
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

    // The game asks here every frame whether the window has the focus.
    @Inject(method = "handleAccumulatedMovement()V", at = @At("HEAD"))
    private void onHandleMovement(CallbackInfo ci) {
        if (!OfflineClient.MC.isWindowActive()) {
            InputUtil.releaseButtons();
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
