package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.misc.NameProtect;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// NameProtect can hand every player the default skin. Nobody is recognised on a stream.
@Mixin(AbstractClientPlayer.class)
public abstract class AbstractClientPlayerMixin {

    @Inject(method = "getSkin", at = @At("HEAD"), cancellable = true)
    private void onGetSkin(CallbackInfoReturnable<PlayerSkin> cir) {
        NameProtect nameProtect = Modules.active(NameProtect.class);
        if (nameProtect != null && nameProtect.hidesSkins()) {
            cir.setReturnValue(DefaultPlayerSkin.get(((AbstractClientPlayer) (Object) this).getUUID()));
        }
    }
}
