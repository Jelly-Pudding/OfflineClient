package com.jellypudding.offlineclient.render;

import com.jellypudding.offlineclient.OfflineClient;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

// Gathers the quads of an entity's model so a module can draw them as boxes.
// The renderer runs against a collector and every quad is relative to the feet.
public final class ModelWireframe {

    private ModelWireframe() {
    }

    // The lerped feet position plus the renderer's own offset. Quads sit on top of this.
    public static Vec3 origin(Entity entity, float partialTicks) {
        float delta = OfflineClient.MC.level.tickRateManager().isFrozen() ? 1 : partialTicks;
        Vec3 feet = new Vec3(Mth.lerp(delta, entity.xOld, entity.getX()),
            Mth.lerp(delta, entity.yOld, entity.getY()), Mth.lerp(delta, entity.zOld, entity.getZ()));
        EntityRenderer<Entity, EntityRenderState> renderer = rendererOf(entity);
        return feet.add(renderer.getRenderOffset(renderer.createRenderState(entity, delta)));
    }

    // Four corners per quad in the entity's own space.
    public static List<Vec3[]> capture(Entity entity, float partialTicks) {
        float delta = OfflineClient.MC.level.tickRateManager().isFrozen() ? 1 : partialTicks;
        EntityRenderer<Entity, EntityRenderState> renderer = rendererOf(entity);
        EntityRenderState state = renderer.createRenderState(entity, delta);
        Collector collector = new Collector();
        renderer.submit(state, new PoseStack(), collector,
            OfflineClient.MC.gameRenderer.gameRenderState().levelRenderState.cameraRenderState);
        collector.getSubmitsPerOrder().clear();
        return collector.quads;
    }

    // Draws captured quads at an origin. The scale grows or shrinks them about the feet.
    public static void draw(DrawBatch batch, List<Vec3[]> quads, Vec3 origin, double scale,
                            BoxStyle.Shape shape, int line, int fill, boolean throughWalls) {
        Vec3[] corners = new Vec3[4];
        for (Vec3[] quad : quads) {
            for (int i = 0; i < 4; i++) {
                corners[i] = origin.add(quad[i].scale(scale));
            }
            if (shape != BoxStyle.Shape.SIDES) {
                batch.outlineQuad(corners[0], corners[1], corners[2], corners[3], line, throughWalls);
            }
            if (shape != BoxStyle.Shape.LINES) {
                batch.quad(corners[0], corners[1], corners[2], corners[3], fill, throughWalls);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static EntityRenderer<Entity, EntityRenderState> rendererOf(Entity entity) {
        return (EntityRenderer<Entity, EntityRenderState>)
            OfflineClient.MC.getEntityRenderDispatcher().getRenderer(entity);
    }

    // Takes the model and geometry submissions and turns each into quads.
    private static final class Collector extends SubmitNodeStorage {

        private final List<Vec3[]> quads = new ArrayList<>();
        private final QuadSink sink = new QuadSink(quads);

        @Override
        public <S> void submitModel(Model<? super S> model, S state, PoseStack poseStack, RenderType type,
                                    int light, int overlay, int tint, TextureAtlasSprite sprite,
                                    int outline, ModelFeatureRenderer.CrumblingOverlay crumbling) {
            if (type.isOutline()) {
                return;
            }
            model.setupAnim(state);
            model.renderToBuffer(poseStack, sink, light, overlay, tint);
        }

        @Override
        public void submitCustomGeometry(PoseStack poseStack, RenderType type,
                                         SubmitNodeCollector.CustomGeometryRenderer geometry) {
            if (!type.isOutline()) {
                geometry.render(poseStack.last(), sink);
            }
        }
    }

    // Every fourth vertex closes a quad.
    private static final class QuadSink implements VertexConsumer {

        private final List<Vec3[]> quads;
        private Vec3[] current = new Vec3[4];
        private int filled;

        private QuadSink(List<Vec3[]> quads) {
            this.quads = quads;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            current[filled++] = new Vec3(x, y, z);
            if (filled == 4) {
                quads.add(current);
                current = new Vec3[4];
                filled = 0;
            }
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            return this;
        }

        @Override
        public VertexConsumer setColor(int argb) {
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(float width) {
            return this;
        }
    }
}
