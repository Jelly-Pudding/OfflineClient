package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.render.DebugDrawings;
import com.jellypudding.offlineclient.util.Modules;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.client.multiplayer.ClientDebugSubscriber;
import net.minecraft.util.debug.DebugSubscription;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

@Mixin(ClientDebugSubscriber.class)
public abstract class ClientDebugSubscriberMixin {

    // The game asks for this data only in its development builds. DebugDrawings asks for it here
    // in single player.
    @Inject(method = "requestedSubscriptions()Ljava/util/Set;", at = @At("RETURN"), cancellable = true)
    private void onRequestedSubscriptions(CallbackInfoReturnable<Set<DebugSubscription<?>>> cir) {
        DebugDrawings drawings = Modules.active(DebugDrawings.class);
        Set<DebugSubscription<?>> extra = drawings == null ? Set.of() : drawings.subscriptions();
        if (!extra.isEmpty()) {
            Set<DebugSubscription<?>> wanted = new ReferenceOpenHashSet<>(cir.getReturnValue());
            wanted.addAll(extra);
            cir.setReturnValue(wanted);
        }
    }
}
