package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.event.events.RightClickEvent;
import com.jellypudding.offlineclient.modules.render.Esp;
import com.jellypudding.offlineclient.modules.render.Freecam;
import com.jellypudding.offlineclient.util.BlockMiner;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {

    /**
     * Fires our client tick event at the end of every game tick. This runs
     * even in menus. Modules that need a loaded world use TickEvent instead.
     */
    @Inject(method = "tick()V", at = @At("TAIL"))
    private void onTick(CallbackInfo ci) {
        OfflineClient.INSTANCE.getEventBus().post(ClientTickEvent.INSTANCE);
    }

    /**
     * Vanilla aborts any block mining every tick the attack key is up and
     * restarts it on whatever the crosshair hits. Suppressed while a
     * module drives mining through BlockMiner.
     */
    @Inject(method = "continueAttack(Z)V", at = @At("HEAD"), cancellable = true)
    private void onContinueAttack(boolean holding, CallbackInfo ci) {
        if (BlockMiner.isActive()) {
            ci.cancel();
        }
        if (OfflineClient.INSTANCE.getModuleManager() != null && OfflineClient.INSTANCE
            .getModuleManager().get(Freecam.class).blocksClicks()) {
            ci.cancel();
        }
    }

    /** Attacks go nowhere while Freecam has the camera detached. */
    @Inject(method = "startAttack()Z", at = @At("HEAD"), cancellable = true)
    private void onStartAttack(CallbackInfoReturnable<Boolean> cir) {
        if (OfflineClient.INSTANCE.getModuleManager() != null && OfflineClient.INSTANCE
            .getModuleManager().get(Freecam.class).blocksClicks()) {
            cir.setReturnValue(false);
        }
    }

    /** Lets modules take over a right click before the game uses the item. */
    @Inject(method = "startUseItem()V", at = @At("HEAD"), cancellable = true)
    private void onStartUseItem(CallbackInfo ci) {
        if (OfflineClient.INSTANCE.getEventBus().post(new RightClickEvent()).isCancelled()) {
            ci.cancel();
        }
    }

    /** Makes the vanilla outline effect pick up ESP glow targets. */
    @Inject(method = "shouldEntityAppearGlowing(Lnet/minecraft/world/entity/Entity;)Z",
        at = @At("HEAD"),
        cancellable = true)
    private void onShouldEntityAppearGlowing(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (OfflineClient.INSTANCE.getModuleManager() == null) {
            return;
        }
        if (OfflineClient.INSTANCE.getModuleManager().get(Esp.class).shouldGlow(entity)) {
            cir.setReturnValue(true);
        }
    }
}
