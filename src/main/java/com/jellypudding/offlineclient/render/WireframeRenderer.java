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
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

// Runs an entity through its own renderer into a catcher that turns every
// model face into a box style face. The result is the model as a wireframe.
public final class WireframeRenderer {

    private static final PoseStack POSE = new PoseStack();
    private static final Catcher CATCHER = new Catcher();

    private static DrawBatch batch;
    private static BoxStyle style;
    private static int lineColor;
    private static int fillColor;
    private static boolean throughWalls;
    private static Vec3 origin = Vec3.ZERO;

    private WireframeRenderer() {
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void draw(DrawBatch target, Entity entity, float partialTicks, BoxStyle shape,
                            int line, int fill, boolean through) {
        batch = target;
        style = shape;
        lineColor = line;
        fillColor = fill;
        throughWalls = through;

        EntityRenderer renderer = OfflineClient.MC.getEntityRenderDispatcher().getRenderer(entity);
        EntityRenderState state = renderer.createRenderState(entity, partialTicks);
        origin = entity.getPosition(partialTicks).add(renderer.getRenderOffset(state));
        CameraRenderState camera = OfflineClient.MC.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;

        POSE.pushPose();
        renderer.submit(state, POSE, CATCHER, camera);
        POSE.popPose();
        CATCHER.getSubmitsPerOrder().clear();
    }

    // Takes the model submissions and feeds their faces straight to the batch.
    private static final class Catcher extends SubmitNodeStorage {

        private final FaceConsumer faces = new FaceConsumer();

        @Override
        public <S> void submitModel(Model<? super S> model, S state, PoseStack poseStack, RenderType type,
                                    int light, int overlay, int tint, TextureAtlasSprite sprite, int outline,
                                    ModelFeatureRenderer.CrumblingOverlay crumbling) {
            if (type.isOutline()) {
                return;
            }
            model.setupAnim(state);
            model.renderToBuffer(poseStack, faces, light, overlay, tint);
        }

        @Override
        public void submitCustomGeometry(PoseStack poseStack, RenderType type,
                                         SubmitNodeCollector.CustomGeometryRenderer geometry) {
            if (!type.isOutline()) {
                geometry.render(poseStack.last(), faces);
            }
        }
    }

    // Every four vertices make one face of the model.
    private static final class FaceConsumer implements VertexConsumer {

        private final Vec3[] corners = new Vec3[4];
        private int count;

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            corners[count++] = origin.add(x, y, z);
            if (count == 4) {
                count = 0;
                if (style.drawsSides()) {
                    batch.quad(corners[0], corners[1], corners[2], corners[3], fillColor, throughWalls);
                }
                if (style.drawsLines()) {
                    batch.outlineQuad(corners[0], corners[1], corners[2], corners[3], lineColor, throughWalls);
                }
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
