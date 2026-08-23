package com.jellypudding.offlineclient.render;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

// Matches the vanilla translucent entity pipeline but skips the depth test.
public final class EntityPipelines {

    private static final RenderPipeline CHAMS = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.ENTITY_SNIPPET)
            .withLocation(Identifier.parse("offlineclient:pipeline/chams"))
            .withShaderDefine("ALPHA_CUTOUT", 0.1f)
            .withShaderDefine("PER_FACE_LIGHTING")
            .withBindGroupLayout(BindGroupLayouts.SAMPLER1)
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .withCull(false)
            .withDepthStencilState(Optional.empty())
            .build());

    // One render type per skin. Only touched from the render thread.
    private static final Map<Identifier, RenderType> TYPES = new HashMap<>();

    private EntityPipelines() {
    }

    public static RenderType chams(Identifier texture) {
        return TYPES.computeIfAbsent(texture, id -> RenderType.create("offlineclient:chams",
            RenderSetup.builder(CHAMS)
                .withTexture("Sampler0", id)
                .useLightmap()
                .useOverlay()
                .sortOnUpload()
                .createRenderSetup()));
    }
}
