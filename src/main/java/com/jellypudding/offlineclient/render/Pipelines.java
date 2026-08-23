package com.jellypudding.offlineclient.render;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.LayeringTransform;
import net.minecraft.client.renderer.rendertype.OutputTarget;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

import java.util.Optional;

/**
 * Render types for drawing lines and boxes in the world. Each one comes in a
 * normal variant and a through walls variant that skips the depth test.
 */
public final class Pipelines {

    // Vanilla lines with custom shaders that skip fog.
    private static final RenderPipeline.Snippet LINE_SNIPPET = RenderPipeline
        .builder(RenderPipelines.LINES_SNIPPET)
        .withVertexShader(Identifier.parse("offlineclient:core/lines"))
        .withFragmentShader(Identifier.parse("offlineclient:core/lines"))
        .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
        .withCull(false)
        .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR_NORMAL_LINE_WIDTH)
        .withPrimitiveTopology(PrimitiveTopology.LINES)
        .buildSnippet();

    private static final RenderPipeline LINE_PIPELINE = RenderPipelines.register(
        RenderPipeline.builder(LINE_SNIPPET)
            .withLocation(Identifier.parse("offlineclient:pipeline/lines"))
            .withDepthStencilState(DepthStencilState.DEFAULT)
            .build());

    private static final RenderPipeline LINE_PIPELINE_THROUGH_WALLS = RenderPipelines.register(
        RenderPipeline.builder(LINE_SNIPPET)
            .withLocation(Identifier.parse("offlineclient:pipeline/lines_through_walls"))
            .withDepthStencilState(Optional.empty())
            .build());

    private static final RenderPipeline FILL_PIPELINE = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
            .withLocation(Identifier.parse("offlineclient:pipeline/fill"))
            .withDepthStencilState(DepthStencilState.DEFAULT)
            .withCull(true)
            .build());

    private static final RenderPipeline FILL_PIPELINE_THROUGH_WALLS = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
            .withLocation(Identifier.parse("offlineclient:pipeline/fill_through_walls"))
            .withDepthStencilState(Optional.empty())
            .withCull(true)
            .build());

    public static final RenderType LINES = RenderType.create("offlineclient:lines",
        RenderSetup.builder(LINE_PIPELINE)
            .setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING)
            .setOutputTarget(OutputTarget.ITEM_ENTITY_TARGET)
            .createRenderSetup());

    public static final RenderType LINES_THROUGH_WALLS = RenderType.create(
        "offlineclient:lines_through_walls",
        RenderSetup.builder(LINE_PIPELINE_THROUGH_WALLS)
            .setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING)
            .setOutputTarget(OutputTarget.ITEM_ENTITY_TARGET)
            .createRenderSetup());

    public static final RenderType FILL = RenderType.create("offlineclient:fill",
        RenderSetup.builder(FILL_PIPELINE).sortOnUpload().createRenderSetup());

    public static final RenderType FILL_THROUGH_WALLS = RenderType.create(
        "offlineclient:fill_through_walls",
        RenderSetup.builder(FILL_PIPELINE_THROUGH_WALLS).sortOnUpload().createRenderSetup());

    private Pipelines() {
    }

    public static RenderType lines(boolean throughWalls) {
        return throughWalls ? LINES_THROUGH_WALLS : LINES;
    }

    public static RenderType fill(boolean throughWalls) {
        return throughWalls ? FILL_THROUGH_WALLS : FILL;
    }
}
