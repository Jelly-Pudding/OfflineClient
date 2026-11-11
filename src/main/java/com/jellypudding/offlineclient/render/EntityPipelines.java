package com.jellypudding.offlineclient.render;

import com.jellypudding.offlineclient.OfflineClient;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
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

    private record Key(Identifier texture, boolean solid) {
    }

    // One render type per skin for each phase it can go in. Only touched from the render thread.
    private static final Map<Key, RenderType> TYPES = new HashMap<>();

    private EntityPipelines() {
    }

    public static RenderType chams(Identifier texture) {
        return TYPES.computeIfAbsent(new Key(texture, solidPhase()), EntityPipelines::create);
    }

    // Improved transparency draws translucent models with pipelines of their own and the model types
    // of the client have none. Whilst it is on they go in with the solid models.
    static boolean solidPhase() {
        return OfflineClient.MC.gameRenderer.useImprovedTransparency();
    }

    private static RenderType create(Key key) {
        RenderSetup.RenderSetupBuilder setup = RenderSetup.builder(CHAMS)
            .withTexture("Sampler0", key.texture())
            .useLightmap()
            .useOverlay()
            .setOutline(RenderSetup.OutlineProperty.AFFECTS_OUTLINE)
            .sortOnUpload();
        if (key.solid()) {
            setup.withForcedSolidModelPhase();
        }
        return RenderType.create("offlineclient:chams", setup.createRenderSetup());
    }
}
