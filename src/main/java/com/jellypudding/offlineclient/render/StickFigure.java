package com.jellypudding.offlineclient.render;

import com.jellypudding.offlineclient.OfflineClient;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.UvMapping;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

// The joints of a body built like a player's in world space for the frame being drawn.
// The renderer runs against a collector and hands over the pose it draws the model with.
public record StickFigure(Vec3 crown, Vec3 neck, Vec3 eyes, Vec3 gaze,
                          Vec3 rightShoulder, Vec3 leftShoulder, Vec3 rightHand, Vec3 leftHand,
                          Vec3 rightHip, Vec3 leftHip, Vec3 rightFoot, Vec3 leftFoot) {

    // Model parts are measured in sixteenths of a block.
    private static final float PIXEL = 16;

    // Null for an entity whose model is not built like a player's.
    public static StickFigure of(Entity entity) {
        Minecraft mc = OfflineClient.MC;
        EntityRenderer<Entity, EntityRenderState> renderer = rendererOf(entity);
        if (!(renderer instanceof LivingEntityRenderer<?, ?, ?> living)
            || !(living.getModel() instanceof HumanoidModel<?>)) {
            return null;
        }
        // The same moment in the tick the game draws this entity at.
        float partialTicks = mc.getDeltaTracker()
            .getGameTimeDeltaPartialTick(!mc.level.tickRateManager().isEntityFrozen(entity));
        EntityRenderState state = renderer.createRenderState(entity, partialTicks);
        // An invisible body skips the model and would never hand over its pose.
        state.isInvisible = false;
        Capture capture = new Capture(living);
        renderer.submit(state, new PoseStack(), capture,
            mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState);
        if (capture.joints == null) {
            return null;
        }
        return capture.joints.moved(new Vec3(state.x, state.y, state.z).add(renderer.getRenderOffset(state)));
    }

    @SuppressWarnings("unchecked")
    private static EntityRenderer<Entity, EntityRenderState> rendererOf(Entity entity) {
        return (EntityRenderer<Entity, EntityRenderState>)
            OfflineClient.MC.getEntityRenderDispatcher().getRenderer(entity);
    }

    public void draw(DrawBatch batch, int color) {
        Vec3 chest = rightShoulder.add(leftShoulder).scale(0.5);
        Vec3 pelvis = rightHip.add(leftHip).scale(0.5);
        batch.line(neck, crown, color, true);
        batch.line(eyes, gaze, color, true);
        batch.line(neck, chest, color, true);
        batch.line(chest, pelvis, color, true);
        batch.line(rightShoulder, leftShoulder, color, true);
        batch.line(rightShoulder, rightHand, color, true);
        batch.line(leftShoulder, leftHand, color, true);
        batch.line(rightHip, leftHip, color, true);
        batch.line(rightHip, rightFoot, color, true);
        batch.line(leftHip, leftFoot, color, true);
    }

    private StickFigure moved(Vec3 by) {
        return new StickFigure(crown.add(by), neck.add(by), eyes.add(by), gaze.add(by),
            rightShoulder.add(by), leftShoulder.add(by), rightHand.add(by), leftHand.add(by),
            rightHip.add(by), leftHip.add(by), rightFoot.add(by), leftFoot.add(by));
    }

    // Points on a part's own first box in the pose that part is drawn with.
    private record Limb(Matrix4f pose, ModelPart.Cube box) {

        private Vec3 at(float x, float y, float z) {
            Vector3f point = pose.transformPosition(x / PIXEL, y / PIXEL, z / PIXEL, new Vector3f());
            return new Vec3(point.x, point.y, point.z);
        }

        private float midX() {
            return (box.minX + box.maxX) / 2;
        }

        private float midY() {
            return (box.minY + box.maxY) / 2;
        }

        private float midZ() {
            return (box.minZ + box.maxZ) / 2;
        }

        // Down the middle of an arm or a leg from the joint it swings on.
        private Vec3 joint() {
            return at(midX(), 0, midZ());
        }

        private Vec3 end() {
            return at(midX(), box.maxY, midZ());
        }

        // Null for a part with no box of its own.
        private static Limb of(ModelPart part, PoseStack poseStack) {
            Limb[] found = new Limb[1];
            part.visit(poseStack, (pose, path, index, box) -> {
                if (found[0] == null && path.isEmpty()) {
                    found[0] = new Limb(new Matrix4f(pose.pose()), box);
                }
            });
            return found[0];
        }
    }

    // Takes the main model as it is handed in and lets everything else fall away. A baby
    // and an adult have a model each and the renderer picks one as it draws.
    private static final class Capture extends SubmitNodeStorage {

        private final LivingEntityRenderer<?, ?, ?> renderer;
        private StickFigure joints;

        private Capture(LivingEntityRenderer<?, ?, ?> renderer) {
            this.renderer = renderer;
        }

        @Override
        public <S> void submitModel(Model<? super S> model, S state, PoseStack poseStack, RenderType type,
                                    int light, int overlay, int tint, UvMapping uv, int outline) {
            if (joints != null || model != renderer.getModel() || !(model instanceof HumanoidModel<?> body)) {
                return;
            }
            model.setupAnim(state);
            poseStack.pushPose();
            body.root().translateAndRotate(poseStack);
            joints = measure(body, poseStack);
            poseStack.popPose();
        }

        private static StickFigure measure(HumanoidModel<?> body, PoseStack poseStack) {
            Limb head = Limb.of(body.head, poseStack);
            Limb rightArm = Limb.of(body.rightArm, poseStack);
            Limb leftArm = Limb.of(body.leftArm, poseStack);
            Limb rightLeg = Limb.of(body.rightLeg, poseStack);
            Limb leftLeg = Limb.of(body.leftLeg, poseStack);
            if (head == null || rightArm == null || leftArm == null || rightLeg == null || leftLeg == null) {
                return null;
            }
            // The face is on the low z side of the head. The gaze runs a head's depth out from its middle.
            float depth = head.box().maxZ - head.box().minZ;
            return new StickFigure(
                head.at(head.midX(), head.box().minY, head.midZ()),
                head.at(head.midX(), head.box().maxY, head.midZ()),
                head.at(head.midX(), head.midY(), head.midZ()),
                head.at(head.midX(), head.midY(), head.midZ() - depth),
                rightArm.joint(), leftArm.joint(), rightArm.end(), leftArm.end(),
                rightLeg.joint(), leftLeg.joint(), rightLeg.end(), leftLeg.end());
        }
    }
}
