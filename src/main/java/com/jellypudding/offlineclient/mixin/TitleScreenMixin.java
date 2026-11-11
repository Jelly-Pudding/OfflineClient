package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.gui.NamesTab;
import com.jellypudding.offlineclient.gui.ProxiesTab;
import com.jellypudding.offlineclient.gui.RecoveryScreen;
import com.jellypudding.offlineclient.gui.TabScreen;
import com.jellypudding.offlineclient.gui.TabView;
import com.jellypudding.offlineclient.modules.misc.Conceal;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.RenderUtil;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.ThreadLocalRandom;

@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {

    @Shadow
    @Final
    private static Component COPYRIGHT_TEXT;

    @Shadow
    private boolean fading;

    @Shadow
    private long fadeInStart;

    // The client's buttons sit in a row in the top right corner above the logo.
    @Unique
    private static final int RECOVERY_WIDTH = 58;

    @Unique
    private static final int LIST_WIDTH = 46;

    @Unique
    private static final int CORNER_HEIGHT = 16;

    @Unique
    private static final int CORNER_GAP = 4;

    @Unique
    private int offlineclient$creditColorFrom;

    @Unique
    private int offlineclient$creditColorTo;

    @Unique
    private int offlineclient$versionColor;

    @Unique
    private int offlineclient$creditY = -1;

    private TitleScreenMixin(Component title) {
        super(title);
    }

    // Swaps the vanilla copyright line in the bottom right for the developer credit.
    @Inject(method = "init()V", at = @At("TAIL"))
    private void onInit(CallbackInfo ci) {
        float hue = ThreadLocalRandom.current().nextFloat(360f);
        offlineclient$creditColorFrom = ColorUtil.hsv(hue, 0.8f, 1f);
        offlineclient$creditColorTo = ColorUtil.hsv(hue + 80f, 0.8f, 1f);
        offlineclient$versionColor =
            ColorUtil.hsv(ThreadLocalRandom.current().nextFloat(360f), 0.8f, 1f);

        AbstractWidget copyright = null;
        for (GuiEventListener child : children()) {
            if (child instanceof AbstractWidget widget
                && COPYRIGHT_TEXT.equals(widget.getMessage())) {
                copyright = widget;
                break;
            }
        }
        if (copyright != null) {
            offlineclient$creditY = copyright.getY();
            removeWidget(copyright);
        }

        // A way back to defaults when a saved setting breaks the game.
        addRenderableWidget(Button.builder(
                Component.literal("Recovery"),
                button -> minecraft.gui.setScreen(
                    new RecoveryScreen(this)))
            .bounds(width - RECOVERY_WIDTH - CORNER_GAP, CORNER_GAP, RECOVERY_WIDTH, CORNER_HEIGHT)
            .build());
        if (!Conceal.hides(Conceal.Part.SCREENS)) {
            offlineclient$listButton("Names", 1, new NamesTab());
            offlineclient$listButton("Proxies", 2, new ProxiesTab());
        }
    }

    // A button left of Recovery that opens one list of the GUI before there is a world to open
    // the GUI in. Place one is the nearest.
    @Unique
    private void offlineclient$listButton(String label, int place, TabView.Source source) {
        int right = width - RECOVERY_WIDTH - CORNER_GAP - (place - 1) * (LIST_WIDTH + CORNER_GAP);
        addRenderableWidget(Button.builder(Component.literal(label),
                button -> minecraft.gui.setScreen(new TabScreen(this, label, source)))
            .bounds(right - LIST_WIDTH - CORNER_GAP, CORNER_GAP, LIST_WIDTH, CORNER_HEIGHT)
            .build());
    }

    @Inject(
        method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
        at = @At("TAIL"))
    private void onRender(GuiGraphicsExtractor context, int mouseX, int mouseY,
                          float partialTicks, CallbackInfo ci) {
        Font font = minecraft.font;
        float alpha = offlineclient$fadeAlpha();

        String name = OfflineClient.NAME + " v" + OfflineClient.VERSION;
        RenderUtil.rainbowText(context, font, name, 4, 4, 1f, alpha);

        String credit = "Developed by AlphaAlex115";
        int y = offlineclient$creditY != -1 ? offlineclient$creditY : height - 10;
        RenderUtil.gradientText(context, font, credit, width - font.width(credit) - 2, y,
            offlineclient$creditColorFrom, offlineclient$creditColorTo, alpha);
    }

    @Unique
    private float offlineclient$fadeAlpha() {
        if (!fading) {
            return 1f;
        }
        if (fadeInStart == 0L) {
            return 0f;
        }
        float t = (Util.getMillis() - fadeInStart) / 2000f;
        return Mth.clampedMap(Mth.clamp(t, 0f, 1f), 0.5f, 1f, 0f, 1f);
    }

    // The top byte carries the fade alpha.
    @WrapOperation(
        method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)V"))
    private void wrapVersionText(GuiGraphicsExtractor context, Font font, String text,
                                 int x, int y, int color, Operation<Void> original) {
        int recolored = (color & 0xFF000000) | (offlineclient$versionColor & 0xFFFFFF);
        original.call(context, font, text, x, y, recolored);
    }
}
