package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.misc.NameProtect;
import com.jellypudding.offlineclient.modules.render.AntiBlind;
import com.jellypudding.offlineclient.util.Modules;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractClientPlayer.class)
public abstract class AbstractClientPlayerMixin {

    // NameProtect can hand every player the default skin. Nobody is recognised on a stream.
    @Inject(method = "getSkin", at = @At("HEAD"), cancellable = true)
    private void onGetSkin(CallbackInfoReturnable<PlayerSkin> cir) {
        NameProtect nameProtect = Modules.active(NameProtect.class);
        if (nameProtect != null && nameProtect.hidesSkins()) {
            cir.setReturnValue(DefaultPlayerSkin.get(((AbstractClientPlayer) (Object) this).getUUID()));
        }
    }

    // The view widens with the walking pace. AntiBlind can keep Speed and Slowness out of it.
    @ModifyExpressionValue(method = "getFieldOfViewModifier(ZF)F", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/player/AbstractClientPlayer;getAttributeValue(Lnet/minecraft/core/Holder;)D"))
    private double paceForView(double pace) {
        AntiBlind antiBlind = Modules.active(AntiBlind.class);
        return antiBlind == null ? pace : antiBlind.paceForView((AbstractClientPlayer) (Object) this, pace);
    }
}
