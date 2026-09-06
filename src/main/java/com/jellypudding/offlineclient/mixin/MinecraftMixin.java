package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.event.events.LeftClickEvent;
import com.jellypudding.offlineclient.event.events.RightClickEvent;
import com.jellypudding.offlineclient.modules.player.InventoryTweaks;
import com.jellypudding.offlineclient.modules.player.Multitask;
import com.jellypudding.offlineclient.modules.render.Esp;
import com.jellypudding.offlineclient.modules.render.Freecam;
import com.jellypudding.offlineclient.util.BlockMiner;
import com.jellypudding.offlineclient.util.Modules;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {

    @Shadow
    private void handleKeybinds() {
    }

    @Shadow
    private void continueAttack(boolean holding) {
    }

    // Whether the attack key was down when the tick skipped its mining call.
    @Unique
    private boolean offlineclient$breaking;

    @Unique
    private static boolean offlineclient$frameInput() {
        InventoryTweaks tweaks = Modules.get(InventoryTweaks.class);
        return tweaks != null && tweaks.frameInput();
    }

    // The keys are read once a frame instead so the tick call goes.
    @WrapOperation(method = "tick()V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;handleKeybinds()V"))
    private void tickKeybinds(Minecraft instance, Operation<Void> original) {
        if (offlineclient$frameInput()) {
            continueAttack(offlineclient$breaking);
            offlineclient$breaking = false;
            return;
        }
        original.call(instance);
    }

    // Mining still runs once a tick even whilst the keys are read every frame.
    @WrapOperation(method = "handleKeybinds()V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;continueAttack(Z)V"))
    private void frameAttack(Minecraft instance, boolean holding, Operation<Void> original) {
        if (offlineclient$frameInput()) {
            offlineclient$breaking = holding;
            return;
        }
        original.call(instance, holding);
    }

    @Inject(method = "renderFrame(Z)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;endFrame()V",
            shift = At.Shift.AFTER))
    private void onRenderFrame(boolean advanceTime, CallbackInfo ci) {
        if (OfflineClient.MC.player != null && offlineclient$frameInput()) {
            handleKeybinds();
        }
    }

    // Fires once per game tick even in menus. Modules that need a loaded
    // world use TickEvent instead.
    @Inject(method = "tick()V", at = @At("TAIL"))
    private void onTick(CallbackInfo ci) {
        OfflineClient.INSTANCE.getEventBus().post(ClientTickEvent.INSTANCE);
    }

    // Vanilla aborts any block mining every tick the attack key is up and
    // restarts it on whatever the crosshair hits.
    @Inject(method = "continueAttack(Z)V", at = @At("HEAD"), cancellable = true)
    private void onContinueAttack(boolean holding, CallbackInfo ci) {
        if (BlockMiner.isActive() || offlineclient$freecamBlocks()) {
            ci.cancel();
        }
    }

    @Inject(method = "startAttack()Z", at = @At("HEAD"), cancellable = true)
    private void onStartAttack(CallbackInfoReturnable<Boolean> cir) {
        if (offlineclient$freecamBlocks()
            || OfflineClient.INSTANCE.getEventBus().post(new LeftClickEvent()).isCancelled()) {
            cir.setReturnValue(false);
        }
    }

    @Unique
    private static boolean offlineclient$freecamBlocks() {
        Freecam freecam = Modules.get(Freecam.class);
        return freecam != null && freecam.blocksClicks();
    }

    @Inject(method = "startUseItem()V", at = @At("HEAD"), cancellable = true)
    private void onStartUseItem(CallbackInfo ci) {
        if (OfflineClient.INSTANCE.getEventBus().post(new RightClickEvent()).isCancelled()) {
            ci.cancel();
        }
    }

    @Inject(method = "shouldEntityAppearGlowing(Lnet/minecraft/world/entity/Entity;)Z",
        at = @At("HEAD"),
        cancellable = true)
    private void onShouldEntityAppearGlowing(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        Esp esp = Modules.get(Esp.class);
        if (esp != null && esp.shouldGlow(entity)) {
            cir.setReturnValue(true);
        }
    }

    // Vanilla swallows a right click for as long as a block is being broken.
    @ModifyExpressionValue(method = "startUseItem()V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;isDestroying()Z"))
    private boolean allowUseWhileMining(boolean original) {
        Multitask multitask = offlineclient$module();
        return original && (multitask == null || !multitask.usesWhileMining());
    }

    // Vanilla stops block breaking for as long as an item is in use.
    @ModifyExpressionValue(method = "continueAttack(Z)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;isUsingItem()Z"))
    private boolean allowMineWhileUsing(boolean original) {
        Multitask multitask = offlineclient$module();
        return original && (multitask == null || !multitask.minesWhileUsing());
    }

    // Vanilla takes a whole branch whilst an item is in use where clicks
    // are drained instead of acted on. That branch also releases the item.
    @WrapOperation(method = "handleKeybinds()V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;isUsingItem()Z", ordinal = 0))
    private boolean allowAttackWhileUsing(LocalPlayer player, Operation<Boolean> original) {
        boolean using = original.call(player);
        Multitask multitask = offlineclient$module();
        if (!using || multitask == null || !multitask.attacksWhileUsing()) {
            return using;
        }
        Minecraft mc = OfflineClient.MC;
        if (!mc.options.keyUse.isDown()) {
            mc.gameMode.releaseUsingItem(player);
        }
        while (mc.options.keyUse.consumeClick()) {
            // A queued right click would restart the use this tick.
        }
        return false;
    }

    @Unique
    private static Multitask offlineclient$module() {
        return Modules.get(Multitask.class);
    }
}
