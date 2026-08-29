package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.events.Render2DEvent;
import com.jellypudding.offlineclient.modules.render.ClearView;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Predicate;

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
        ClearView clearView = Modules.active(ClearView.class);
        if (clearView == null) {
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
        ClearView clearView = Modules.active(ClearView.class);
        if (clearView != null && clearView.blocksVignette()) {
            ci.cancel();
        }
    }
    // One HUD element each. Every one is skipped at the head of its own extract call.
    @Inject(method = "extractSpyglassOverlay", at = @At("HEAD"), cancellable = true)
    private void onSpyglass(CallbackInfo ci) {
        offlineclient$skip(ci, ClearView::blocksSpyglass);
    }

    @Inject(method = "extractBossOverlay", at = @At("HEAD"), cancellable = true)
    private void onBossBars(CallbackInfo ci) {
        offlineclient$skip(ci, ClearView::blocksBossBars);
    }

    @Inject(method = "extractScoreboardSidebar", at = @At("HEAD"), cancellable = true)
    private void onScoreboard(CallbackInfo ci) {
        offlineclient$skip(ci, ClearView::blocksScoreboard);
    }

    @Inject(method = "extractTitle", at = @At("HEAD"), cancellable = true)
    private void onTitle(CallbackInfo ci) {
        offlineclient$skip(ci, ClearView::blocksTitles);
    }

    @Inject(method = "extractSelectedItemName", at = @At("HEAD"), cancellable = true)
    private void onItemName(CallbackInfo ci) {
        offlineclient$skip(ci, ClearView::blocksItemNames);
    }

    @Inject(method = "extractEffects", at = @At("HEAD"), cancellable = true)
    private void onEffects(CallbackInfo ci) {
        offlineclient$skip(ci, ClearView::blocksEffectIcons);
    }

    @Unique
    private static void offlineclient$skip(CallbackInfo ci, Predicate<ClearView> blocked) {
        ClearView clearView = Modules.active(ClearView.class);
        if (clearView != null && blocked.test(clearView)) {
            ci.cancel();
        }
    }
}
