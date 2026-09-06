package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.render.Ambience;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.renderer.entity.LightningBoltRenderer;
import net.minecraft.util.ARGB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

// Every bolt quad is drawn from the same three colour parts.
@Mixin(LightningBoltRenderer.class)
public abstract class LightningBoltRendererMixin {

    @ModifyVariable(method = "quad", at = @At("HEAD"), argsOnly = true, index = 7)
    private static float boltRed(float vanilla) {
        int colour = offlineclient$colour();
        return colour == 0 ? vanilla : ARGB.red(colour) / 255f;
    }

    @ModifyVariable(method = "quad", at = @At("HEAD"), argsOnly = true, index = 8)
    private static float boltGreen(float vanilla) {
        int colour = offlineclient$colour();
        return colour == 0 ? vanilla : ARGB.green(colour) / 255f;
    }

    @ModifyVariable(method = "quad", at = @At("HEAD"), argsOnly = true, index = 9)
    private static float boltBlue(float vanilla) {
        int colour = offlineclient$colour();
        return colour == 0 ? vanilla : ARGB.blue(colour) / 255f;
    }

    // Zero when nothing is being recoloured.
    private static int offlineclient$colour() {
        Ambience ambience = Modules.get(Ambience.class);
        return ambience == null || !ambience.paintsLightning() ? 0 : ambience.lightningColor();
    }
}
