package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.movement.Sprint;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.player.ClientInput;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientInput.class)
public abstract class ClientInputMixin {

    @Shadow
    protected Vec2 moveVector;

    // Every sprint check in the game asks this. A sideways step counts as forward whilst Sprint allows it.
    @Inject(method = "hasForwardImpulse()Z", at = @At("HEAD"), cancellable = true)
    private void onHasForwardImpulse(CallbackInfoReturnable<Boolean> cir) {
        Sprint sprint = Modules.get(Sprint.class);
        if (sprint != null && sprint.sprintsAnyDirection() && moveVector.lengthSquared() > 1e-5f) {
            cir.setReturnValue(true);
        }
    }
}
