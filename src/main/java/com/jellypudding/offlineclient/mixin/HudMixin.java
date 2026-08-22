package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.events.Render2DEvent;
import com.jellypudding.offlineclient.modules.render.ClearView;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Hud.class)
public class HudMixin {

    @Inject(
        method = "extractTabList(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V",
        at = @At("HEAD"))
    private void onRenderHud(GuiGraphicsExtractor context, DeltaTracker tickCounter, CallbackInfo ci) {
        if (OfflineClient.MC.debugEntries.isOverlayVisible()) {
            return;
        }
        float tickDelta = tickCounter.getGameTimeDeltaPartialTick(true);
        OfflineClient.INSTANCE.getEventBus().post(new Render2DEvent(context, tickDelta));
    }

    @Inject(
        method = "extractTextureOverlay(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/resources/Identifier;F)V",
        at = @At("HEAD"),
        cancellable = true)
    private void onRenderOverlay(GuiGraphicsExtractor context, Identifier texture, float opacity, CallbackInfo ci) {
        if (texture == null) {
            return;
        }
        ClearView clearView = OfflineClient.INSTANCE.getModuleManager().get(ClearView.class);
        if (!clearView.isEnabled()) {
            return;
        }
        String path = texture.getPath();
        if (("textures/misc/pumpkinblur.png".equals(path) && clearView.blocksPumpkin())
            || ("textures/misc/powder_snow_outline.png".equals(path) && clearView.blocksPowderSnow())) {
            ci.cancel();
        }
    }

    @Inject(method = "extractVignette", at = @At("HEAD"), cancellable = true)
    private void onRenderVignette(GuiGraphicsExtractor context, Entity entity, CallbackInfo ci) {
        ClearView clearView = OfflineClient.INSTANCE.getModuleManager().get(ClearView.class);
        if (clearView.isEnabled() && clearView.blocksVignette()) {
            ci.cancel();
        }
    }
}
