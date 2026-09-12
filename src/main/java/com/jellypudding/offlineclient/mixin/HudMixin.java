package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.events.Render2DEvent;
import com.jellypudding.offlineclient.modules.render.Blur;
import com.jellypudding.offlineclient.modules.render.ClearView;
import com.jellypudding.offlineclient.modules.render.ItemHighlight;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Predicate;

@Mixin(Hud.class)
public class HudMixin {

    // A blur still fading out after a screen closed goes under the HUD.
    @Inject(
        method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V",
        at = @At("HEAD"))
    private void onExtractHud(GuiGraphicsExtractor context, DeltaTracker tickCounter, CallbackInfo ci) {
        Blur blur = Modules.get(Blur.class);
        if (blur != null && blur.fadingWithoutScreen()) {
            blur.blurHere(context);
        }
    }

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

    // The paint goes down before the item and sits under it.
    @Inject(method = "extractSlot(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IILnet/minecraft/client/DeltaTracker;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/ItemStack;I)V",
        at = @At("HEAD"))
    private void onExtractSlot(GuiGraphicsExtractor context, int x, int y, DeltaTracker tickCounter,
                               Player player, ItemStack stack, int seed, CallbackInfo ci) {
        ItemHighlight highlight = Modules.get(ItemHighlight.class);
        if (highlight == null) {
            return;
        }
        int color = highlight.hotbarColorFor(stack);
        if (color != 0) {
            context.fill(x, y, x + 16, y + 16, color);
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

    @Inject(method = "extractCrosshair", at = @At("HEAD"), cancellable = true)
    private void onCrosshair(CallbackInfo ci) {
        offlineclient$skip(ci, ClearView::blocksCrosshair);
    }

    @Unique
    private static void offlineclient$skip(CallbackInfo ci, Predicate<ClearView> blocked) {
        ClearView clearView = Modules.active(ClearView.class);
        if (clearView != null && blocked.test(clearView)) {
            ci.cancel();
        }
    }
}
