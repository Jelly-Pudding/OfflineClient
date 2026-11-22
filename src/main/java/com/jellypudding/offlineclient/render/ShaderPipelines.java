package com.jellypudding.offlineclient.render;

import com.jellypudding.offlineclient.OfflineClient;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

// Pipelines that run shaders of the client's own. None goes on the game's list of pipelines. The game
// compiles that list at start and at every resource reload and stops when one fails. These compile
// when first asked for and a shader that fails only turns off the look that needs it.
public final class ShaderPipelines {

    public static final String OUTLINE_CONFIG = "OutlineConfig";
    public static final String MASK_SAMPLER = "MaskSampler";
    public static final String ROWS_SAMPLER = "RowsSampler";
    public static final String PICTURE_SAMPLER = "PictureSampler";

    private static final Identifier SCREEN_QUAD = Identifier.parse("minecraft:core/screenquad");

    // Boxes filled in the exact colour of their corners. Nothing blends and nothing hides them.
    public static final RenderPipeline SILHOUETTE_BOXES = RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
        .withLocation(Identifier.parse("offlineclient:pipeline/silhouette_boxes"))
        .withColorTargetState(ColorTargetState.DEFAULT)
        .withDepthStencilState(Optional.empty())
        .build();

    public static final RenderPipeline OUTLINE_ROWS = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
        .withLocation(Identifier.parse("offlineclient:pipeline/outline_rows"))
        .withVertexShader(SCREEN_QUAD)
        .withFragmentShader(Identifier.parse("offlineclient:post/outline_rows"))
        .withBindGroupLayout(BindGroupLayout.builder()
            .withUniform(OUTLINE_CONFIG, UniformType.UNIFORM_BUFFER)
            .withUniform(MASK_SAMPLER, UniformType.COMBINED_IMAGE_SAMPLER)
            .build())
        .withColorTargetState(ColorTargetState.DEFAULT)
        .build();

    public static final RenderPipeline OUTLINE_EDGES = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
        .withLocation(Identifier.parse("offlineclient:pipeline/outline_edges"))
        .withVertexShader(SCREEN_QUAD)
        .withFragmentShader(Identifier.parse("offlineclient:post/outline_edges"))
        .withBindGroupLayout(BindGroupLayout.builder()
            .withUniform(OUTLINE_CONFIG, UniformType.UNIFORM_BUFFER)
            .withUniform(MASK_SAMPLER, UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform(ROWS_SAMPLER, UniformType.COMBINED_IMAGE_SAMPLER)
            .build())
        .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
        .build();

    private static final RenderPipeline.Snippet PICTURE_SNIPPET = RenderPipeline.builder(RenderPipelines.ENTITY_SNIPPET)
        .withVertexShader(Identifier.parse("offlineclient:core/chams_picture"))
        .withFragmentShader(Identifier.parse("offlineclient:core/chams_picture"))
        .withBindGroupLayout(BindGroupLayout.builder()
            .withUniform(PICTURE_SAMPLER, UniformType.COMBINED_IMAGE_SAMPLER)
            .build())
        .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
        .withCull(false)
        .buildSnippet();

    public static final RenderPipeline CHAMS_PICTURE = RenderPipeline.builder(PICTURE_SNIPPET)
        .withLocation(Identifier.parse("offlineclient:pipeline/chams_picture"))
        .build();

    public static final RenderPipeline CHAMS_PICTURE_THROUGH_WALLS = RenderPipeline.builder(PICTURE_SNIPPET)
        .withLocation(Identifier.parse("offlineclient:pipeline/chams_picture_through_walls"))
        .withDepthStencilState(Optional.empty())
        .build();

    // Pipelines whose shaders could not be made. They are never tried again.
    private static final Set<RenderPipeline> FAILED = new HashSet<>();

    private ShaderPipelines() {
    }

    // Null when its shaders cannot be made. The first ask compiles it. Render thread only.
    public static CompiledRenderPipeline compiled(RenderPipeline pipeline) {
        if (FAILED.contains(pipeline)) {
            return null;
        }
        CompiledRenderPipeline compiled;
        try {
            compiled = RenderSystem.getCompiledPipelineNullable(pipeline);
        } catch (RuntimeException e) {
            OfflineClient.LOG.error("The pipeline {} could not be compiled", pipeline.getLocation(), e);
            compiled = null;
        }
        if (compiled == null) {
            FAILED.add(pipeline);
        }
        return compiled;
    }

    public static boolean ready(RenderPipeline... pipelines) {
        for (RenderPipeline pipeline : pipelines) {
            if (compiled(pipeline) == null) {
                return false;
            }
        }
        return true;
    }
}
