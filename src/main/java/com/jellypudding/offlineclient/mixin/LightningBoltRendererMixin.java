package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.render.Ambience;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.renderer.entity.LightningBoltRenderer;
import net.minecraft.util.ARGB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

// Every bolt quad takes its colour from the same four argument call.
@Mixin(LightningBoltRenderer.class)
public abstract class LightningBoltRendererMixin {

    @ModifyArgs(method = "quad",
        at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vertex/VertexConsumer;setColor(FFFF)"
                + "Lcom/mojang/blaze3d/vertex/VertexConsumer;"))
    private static void boltColour(Args args) {
        int colour = offlineclient$colour();
        if (colour == 0) {
            return;
        }
        args.set(0, ARGB.red(colour) / 255f);
        args.set(1, ARGB.green(colour) / 255f);
        args.set(2, ARGB.blue(colour) / 255f);
    }

    // Zero when nothing is being recoloured.
    @Unique
    private static int offlineclient$colour() {
        Ambience ambience = Modules.get(Ambience.class);
        return ambience == null || !ambience.paintsLightning() ? 0 : ambience.lightningColor();
    }
}
