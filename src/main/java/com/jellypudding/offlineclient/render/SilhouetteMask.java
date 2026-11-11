package com.jellypudding.offlineclient.render;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.commands.RenderPass;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.UvMapping;
import net.minecraft.client.resources.model.geometry.ItemQuads;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector4f;
import org.joml.Vector4fc;

import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

// Draws the picture the outline shader works from. Every silhouette is drawn in its number as a
// colour with nothing blended and nothing hidden by the world. Entities go through the game's own
// outline drawing and come out with their true shape. Boxes are filled by hand.
final class SilhouetteMask {

    private static final Vector4fc CLEAR = new Vector4f(0, 0, 0, 0);
    // Alpha marks a covered pixel. The low three bytes carry the number.
    private static final int COVERED = 0xFF000000;
    // An entity whose hitbox grown by this many blocks is out of view is left out. Some models reach
    // past the hitbox and the glow reaches past the model.
    private static final double CULL_MARGIN = 1;

    private static final RenderType BOXES = RenderType.create("offlineclient:silhouette_boxes",
        RenderSetup.builder(ShaderPipelines.SILHOUETTE_BOXES).createRenderSetup());

    // Reused every frame. Preparing a frame drains it.
    private static final OutlineOnlyStorage STORAGE = new OutlineOnlyStorage();

    private SilhouetteMask() {
    }

    // Fills the mask with this frame's silhouettes. Each entity and box carries the number its colour
    // is kept under. An entity that leaves nothing in the mask joins the boxes as its hitbox. The box
    // vertices go up through the buffer the caller keeps from frame to frame.
    static void draw(RenderTarget mask, StagedVertexBuffer boxVertices, List<Entity> entities, IntList entityIds,
                     List<AABB> boxes, IntList boxIds, float partialTicks) {
        CameraRenderState camera = OfflineClient.MC.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
        // The game turns the view in the shader. Here the turn goes into the pose as DrawBatch does it.
        PoseStack pose = new PoseStack();
        pose.mulPose(camera.viewRotationMatrix);

        FeatureRenderDispatcher.PreparedFrame prepared = null;
        StagedVertexBuffer.Draw boxDraw = null;
        try {
            if (!entities.isEmpty()) {
                prepared = prepareEntities(entities, entityIds, boxes, boxIds, camera, pose, partialTicks);
            }
            if (!boxes.isEmpty()) {
                boxDraw = boxVertices.appendDraw(BOXES.format(), BOXES.primitiveTopology());
                fillBoxes(boxVertices.getVertexBuilder(boxDraw), pose.last(), camera.pos, boxes, boxIds);
                boxVertices.requestIndexCount(boxDraw);
                boxVertices.upload();
            }
            try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "OfflineClient silhouettes", mask.getColorTextureView(), Optional.of(CLEAR), null,
                OptionalDouble.empty())) {
                RenderSystem.bindDefaultUniforms(pass);
                if (prepared != null) {
                    prepared.executeOutline(pass);
                }
                StagedVertexBuffer.ExecuteInfo info = boxDraw == null ? null : boxVertices.getExecuteInfo(boxDraw);
                if (info != null) {
                    BOXES.prepare().drawFromBuffer(info, pass);
                }
            }
        } finally {
            if (prepared != null) {
                prepared.close();
            }
            // The GPU buffer of this frame's boxes goes back to the pool once the GPU is done with it.
            if (boxDraw != null) {
                boxVertices.endFrame();
            }
        }
    }

    // The game's own feature frame prepared with the entities. The game shares one frame between the
    // world and the pictures of the menus and it is always free once the world is drawn. A frame left
    // half prepared would stop the game's next frame.
    private static FeatureRenderDispatcher.PreparedFrame prepareEntities(List<Entity> entities, IntList ids,
                                                                         List<AABB> boxes, IntList boxIds,
                                                                         CameraRenderState camera, PoseStack pose,
                                                                         float partialTicks) {
        FeatureRenderDispatcher features = OfflineClient.MC.gameRenderer.featureRenderDispatcher();
        FeatureRenderDispatcher.PreparedFrame shared = features.preparedFrame;
        if (shared.context != null) {
            throw new IllegalStateException("The feature frame is still in use");
        }
        submitEntities(entities, ids, boxes, boxIds, camera, pose, partialTicks);
        try {
            return features.prepareFrame(STORAGE);
        } catch (RuntimeException e) {
            if (shared.context != null) {
                try {
                    shared.close();
                } catch (RuntimeException closing) {
                    e.addSuppressed(closing);
                }
            }
            throw e;
        }
    }

    // A fresh render state for each entity drawn in its number. The game clears its own states for
    // the frame once they are submitted and their outline colour belongs to the vanilla glow. Some
    // entities are drawn without a model or not at all and only their hitbox can stand in for them.
    private static void submitEntities(List<Entity> entities, IntList ids, List<AABB> boxes, IntList boxIds,
                                       CameraRenderState camera, PoseStack pose, float partialTicks) {
        EntityRenderDispatcher dispatcher = OfflineClient.MC.getEntityRenderDispatcher();
        Frustum view = camera.cullFrustum;
        Vec3 eye = camera.pos;
        for (int i = 0; i < entities.size(); i++) {
            Entity entity = entities.get(i);
            if (view != null && !view.isVisible(entity.getBoundingBox().inflate(CULL_MARGIN))) {
                continue;
            }
            int id = ids.getInt(i);
            EntityRenderState state = dispatcher.extractEntity(entity, partialTicks);
            state.outlineColor = COVERED | id;
            STORAGE.kept = 0;
            dispatcher.submit(state, camera, state.x - eye.x, state.y - eye.y, state.z - eye.z, pose, STORAGE);
            if (STORAGE.kept == 0) {
                boxes.add(EntityUtil.lerpedBox(entity, partialTicks));
                boxIds.add(id);
            }
        }
    }

    private static void fillBoxes(VertexConsumer consumer, PoseStack.Pose pose, Vec3 eye, List<AABB> boxes,
                                  IntList ids) {
        for (int i = 0; i < boxes.size(); i++) {
            DrawBatch.boxFaces(consumer, pose, boxes.get(i), eye, COVERED | ids.getInt(i));
        }
    }

    // Takes in only what the outline pass draws. The rest of an entity is never prepared.
    private static final class OutlineOnlyStorage extends SubmitNodeStorage {

        // How many outline submits were taken in since it was last set to nought.
        private int kept;

        @Override
        public SubmitNodeCollection order(int order) {
            return getSubmitsPerOrder().computeIfAbsent(order, key -> new OutlineOnlyCollection(this));
        }
    }

    private static final class OutlineOnlyCollection extends SubmitNodeCollection {

        private final OutlineOnlyStorage storage;

        private OutlineOnlyCollection(OutlineOnlyStorage storage) {
            super(false, storage.seeThrough());
            this.storage = storage;
        }

        // An outline render type goes to the outline phase alone.
        private static RenderType outlineOf(RenderType type, int outline) {
            if (outline == 0) {
                return null;
            }
            return type.isOutline() ? type : type.outline().orElse(null);
        }

        @Override
        public <S> void submitModel(Model<? super S> model, S state, PoseStack poseStack, RenderType type,
                                    int light, int overlay, int tint, UvMapping uv, int outline) {
            RenderType outlineType = outlineOf(type, outline);
            if (outlineType != null) {
                storage.kept++;
                super.submitModel(model, state, poseStack, outlineType, light, overlay, tint, uv, outline);
            }
        }

        @Override
        public void submitBlockModel(PoseStack poseStack, RenderType type, List<BlockStateModelPart> parts, int[] tints,
                                     int light, int overlay, int outline) {
            RenderType outlineType = outlineOf(type, outline);
            if (outlineType != null) {
                storage.kept++;
                super.submitBlockModel(poseStack, outlineType, parts, tints, light, overlay, outline);
            }
        }

        @Override
        public void submitItem(PoseStack poseStack, ItemDisplayContext context, int light, int overlay, int outline,
                               int[] tints, ItemQuads quads, ItemStackRenderState.FoilType foil) {
            if (outline != 0) {
                storage.kept++;
                super.submitItem(poseStack, context, light, overlay, outline, tints, quads, foil);
            }
        }

        @Override
        public void submitMovingBlock(PoseStack poseStack, MovingBlockRenderState state, int outline) {
            if (outline != 0) {
                storage.kept++;
                super.submitMovingBlock(poseStack, state, outline);
            }
        }

        @Override
        public void submitShadow(PoseStack poseStack, float radius, List<EntityRenderState.ShadowPiece> pieces) {
        }

        @Override
        public void submitNameTag(PoseStack poseStack, Vec3 offset, int y, Component name, boolean seeThrough,
                                  int light, CameraRenderState camera) {
        }

        @Override
        public void submitText(PoseStack poseStack, float x, float y, FormattedCharSequence text, boolean shadow,
                               Font.DisplayMode mode, int light, int color, int background, int outline) {
        }

        @Override
        public void submitTextBackground(PoseStack poseStack, float x1, float y1, float x2, float y2, int z,
                                         Font.DisplayMode mode, int color) {
        }

        @Override
        public void submitFlame(PoseStack poseStack, EntityRenderState state, Quaternionf rotation) {
        }

        @Override
        public void submitLeash(PoseStack poseStack, EntityRenderState.LeashState leash) {
        }

        @Override
        public void submitCustomGeometry(PoseStack poseStack, RenderType type,
                                         SubmitNodeCollector.CustomGeometryRenderer geometry) {
        }
    }
}
