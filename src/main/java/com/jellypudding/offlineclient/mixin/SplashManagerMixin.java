package com.jellypudding.offlineclient.mixin;

import net.minecraft.client.gui.components.SplashRenderer;
import net.minecraft.client.resources.SplashManager;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Replaces the vanilla yellow splash with our own lines. Each one shows up in
 * a random vivid color.
 */
@Mixin(SplashManager.class)
public class SplashManagerMixin {

    private static final List<String> OFFLINE_SPLASHES = List.of(
        "Don't leave me! Don't leave me! I'll die without you!",
        "Your mind makes it real!",
        "Keep the lights out!",
        "How do you know she can't get in here?",
        "DON'T you swear at me you little shit!",
        "The marquis of snakes!",
        "The rest is confetti.",
        "It's you. It's me. It's us."
    );

    @Inject(
        method = "getSplash()Lnet/minecraft/client/gui/components/SplashRenderer;",
        at = @At("HEAD"),
        cancellable = true)
    private void onGetSplash(CallbackInfoReturnable<SplashRenderer> cir) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        String line = OFFLINE_SPLASHES.get(random.nextInt(OFFLINE_SPLASHES.size()));
        int color = com.jellypudding.offlineclient.util.ColorUtil.hsv(
            random.nextFloat(360f), 0.85f, 1f) & 0xFFFFFF;
        cir.setReturnValue(new SplashRenderer(Component.literal(line).withColor(color)));
    }
}
