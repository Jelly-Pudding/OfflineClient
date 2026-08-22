package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.render.XRay;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(SectionCompiler.class)
public abstract class SectionCompilerMixin {

    /**
     * See through blocks need the translucent layer or their alpha is
     * ignored. Only the blocks XRay is fading are moved.
     */
    @ModifyVariable(
        method = "getOrBeginLayer(Ljava/util/Map;Lnet/minecraft/client/renderer/SectionBufferBuilderPack;Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;)Lcom/mojang/blaze3d/vertex/BufferBuilder;",
        at = @At("HEAD"),
        argsOnly = true)
    private ChunkSectionLayer onGetOrBeginLayer(ChunkSectionLayer layer) {
        return XRay.meshingTranslucent() ? ChunkSectionLayer.TRANSLUCENT : layer;
    }
}
