package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.events.Render2DEvent;
import com.jellypudding.offlineclient.gui.HudEditorScreen;
import com.jellypudding.offlineclient.hud.GamePart;
import com.jellypudding.offlineclient.modules.misc.HudModule;
import com.jellypudding.offlineclient.modules.render.Blur;
import com.jellypudding.offlineclient.modules.render.ClearView;
import com.jellypudding.offlineclient.modules.render.ItemHighlight;
import com.jellypudding.offlineclient.util.Modules;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.contextualbar.ContextualBar;
import net.minecraft.resources.Identifier;
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
    private void onRenderVignette(CallbackInfo ci) {
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

    // The HUD editor draws the hotbar group itself above its shade.
    @WrapOperation(
        method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/Hud;extractHotbarAndDecorations(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V"))
    private void onHotbarGroup(Hud hud, GuiGraphicsExtractor context, DeltaTracker delta,
                               Operation<Void> original) {
        if (!(OfflineClient.MC.gui.screen() instanceof HudEditorScreen)) {
            original.call(hud, context, delta);
        }
    }

    // Each part of the game's own HUD is drawn where its HUD element sits.
    @WrapOperation(
        method = "extractHotbarAndDecorations(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/Hud;extractItemHotbar(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V"))
    private void placeHotbar(Hud hud, GuiGraphicsExtractor context, DeltaTracker delta,
                             Operation<Void> original) {
        offlineclient$place(GamePart.HOTBAR, context, () -> original.call(hud, context, delta));
    }

    // A mount's hearts take the place of the hunger bar.
    @WrapOperation(
        method = "extractHotbarAndDecorations(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/Hud;extractVehicleHealth(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V"))
    private void placeMountHealth(Hud hud, GuiGraphicsExtractor context, Operation<Void> original) {
        offlineclient$place(GamePart.HUNGER, context, () -> original.call(hud, context));
    }

    @WrapOperation(
        method = "extractHotbarAndDecorations(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/contextualbar/ContextualBar;extractBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V"))
    private void placeBarBackground(ContextualBar bar, GuiGraphicsExtractor context, DeltaTracker delta,
                                    Operation<Void> original) {
        offlineclient$place(GamePart.EXPERIENCE, context, () -> original.call(bar, context, delta));
    }

    @WrapOperation(
        method = "extractHotbarAndDecorations(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/contextualbar/ContextualBar;extractExperienceLevel(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/gui/Font;I)V"))
    private void placeLevel(GuiGraphicsExtractor context, Font font, int level, Operation<Void> original) {
        offlineclient$place(GamePart.EXPERIENCE, context, () -> original.call(context, font, level));
    }

    @WrapOperation(
        method = "extractHotbarAndDecorations(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/contextualbar/ContextualBar;extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V"))
    private void placeBar(ContextualBar bar, GuiGraphicsExtractor context, DeltaTracker delta,
                          Operation<Void> original) {
        offlineclient$place(GamePart.EXPERIENCE, context, () -> original.call(bar, context, delta));
    }

    @WrapOperation(
        method = "extractHotbarAndDecorations(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/Hud;extractSelectedItemName(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V"))
    private void placeItemName(Hud hud, GuiGraphicsExtractor context, Operation<Void> original) {
        offlineclient$place(GamePart.ITEM_NAME, context, () -> original.call(hud, context));
    }

    @WrapOperation(
        method = "extractPlayerHealth(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/Hud;extractArmor(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/world/entity/player/Player;IIII)V"))
    private void placeArmour(GuiGraphicsExtractor context, Player player, int top, int rows, int rowHeight,
                             int left, Operation<Void> original) {
        offlineclient$place(GamePart.ARMOUR, context,
            () -> original.call(context, player, top, rows, rowHeight, left));
    }

    @WrapOperation(
        method = "extractPlayerHealth(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/Hud;extractHearts(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/world/entity/player/Player;IIIIFIIIZ)V"))
    private void placeHearts(Hud hud, GuiGraphicsExtractor context, Player player, int left, int top,
                             int rowHeight, int regenerating, float maxHealth, int health, int shownHealth,
                             int absorption, boolean blinking, Operation<Void> original) {
        offlineclient$place(GamePart.HEARTS, context, () -> original.call(hud, context, player, left, top,
            rowHeight, regenerating, maxHealth, health, shownHealth, absorption, blinking));
    }

    @WrapOperation(
        method = "extractPlayerHealth(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/Hud;extractFood(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/world/entity/player/Player;II)V"))
    private void placeHunger(Hud hud, GuiGraphicsExtractor context, Player player, int top, int right,
                             Operation<Void> original) {
        offlineclient$place(GamePart.HUNGER, context, () -> original.call(hud, context, player, top, right));
    }

    @WrapOperation(
        method = "extractPlayerHealth(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/Hud;extractAirBubbles(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/world/entity/player/Player;III)V"))
    private void placeAir(Hud hud, GuiGraphicsExtractor context, Player player, int mountHearts, int top,
                          int right, Operation<Void> original) {
        offlineclient$place(GamePart.AIR, context,
            () -> original.call(hud, context, player, mountHearts, top, right));
    }

    @Unique
    private static void offlineclient$place(GamePart part, GuiGraphicsExtractor context, Runnable draw) {
        HudModule hud = Modules.get(HudModule.class);
        if (hud != null && hud.placesGameParts()) {
            hud.getManager().drawGamePart(part, context, OfflineClient.MC.font, draw);
        } else {
            draw.run();
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
