package com.jellypudding.offlineclient.render;

import com.jellypudding.offlineclient.OfflineClient;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Collects lines and boxes from every Render3DEvent handler and draws
 * them in a single upload. All coordinates are world coordinates.
 */
public final class DrawBatch {

    // A batch is built every frame and most have nothing to draw.
    private StagedVertexBuffer buffer;
    private final List<StagedVertexBuffer.Draw> draws = new ArrayList<>();
    private final List<RenderType> types = new ArrayList<>();

    private final PoseStack.Pose pose;
    private final Vec3 camera;

    public DrawBatch(PoseStack poseStack) {
        this.pose = poseStack.last();
        this.camera = cameraPos();
    }

    public static Vec3 cameraPos() {
        Camera camera = OfflineClient.MC.gameRenderer.mainCamera();
        return camera == null ? Vec3.ZERO : camera.position();
    }

    // A point in front of the camera that tracer lines start from.
    public static Vec3 tracerOrigin() {
        Camera camera = OfflineClient.MC.gameRenderer.mainCamera();
        if (camera == null) {
            return Vec3.ZERO;
        }
        double yaw = Math.toRadians(camera.yRot());
        double pitch = Math.toRadians(camera.xRot());
        return new Vec3(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch),
            Math.cos(yaw) * Math.cos(pitch)).scale(10);
    }

    public void line(Vec3 from, Vec3 to, int color, boolean throughWalls) {
        lineRelative(from.subtract(camera), to.subtract(camera), color, throughWalls);
    }

    public void tracer(Vec3 to, int color, boolean throughWalls) {
        lineRelative(tracerOrigin(), to.subtract(camera), color, throughWalls);
    }

    public void outlineBox(AABB box, int color, boolean throughWalls) {
        AABB b = box.move(camera.reverse());
        float x1 = (float) b.minX;
        float y1 = (float) b.minY;
        float z1 = (float) b.minZ;
        float x2 = (float) b.maxX;
        float y2 = (float) b.maxY;
        float z2 = (float) b.maxZ;

        // bottom
        edge(x1, y1, z1, x2, y1, z1, color, throughWalls);
        edge(x2, y1, z1, x2, y1, z2, color, throughWalls);
        edge(x2, y1, z2, x1, y1, z2, color, throughWalls);
        edge(x1, y1, z2, x1, y1, z1, color, throughWalls);
        // top
        edge(x1, y2, z1, x2, y2, z1, color, throughWalls);
        edge(x2, y2, z1, x2, y2, z2, color, throughWalls);
        edge(x2, y2, z2, x1, y2, z2, color, throughWalls);
        edge(x1, y2, z2, x1, y2, z1, color, throughWalls);
        // pillars
        edge(x1, y1, z1, x1, y2, z1, color, throughWalls);
        edge(x2, y1, z1, x2, y2, z1, color, throughWalls);
        edge(x2, y1, z2, x2, y2, z2, color, throughWalls);
        edge(x1, y1, z2, x1, y2, z2, color, throughWalls);
    }

    public void solidBox(AABB box, int color, boolean throughWalls) {
        VertexConsumer vc = buffer(Pipelines.fill(throughWalls));
        AABB b = box.move(camera.reverse());
        float x1 = (float) b.minX;
        float y1 = (float) b.minY;
        float z1 = (float) b.minZ;
        float x2 = (float) b.maxX;
        float y2 = (float) b.maxY;
        float z2 = (float) b.maxZ;

        quad(vc, color, x1, y1, z1, x2, y1, z1, x2, y1, z2, x1, y1, z2);
        quad(vc, color, x1, y2, z1, x1, y2, z2, x2, y2, z2, x2, y2, z1);
        quad(vc, color, x1, y1, z1, x1, y2, z1, x2, y2, z1, x2, y1, z1);
        quad(vc, color, x2, y1, z1, x2, y2, z1, x2, y2, z2, x2, y1, z2);
        quad(vc, color, x1, y1, z2, x2, y1, z2, x2, y2, z2, x1, y2, z2);
        quad(vc, color, x1, y1, z1, x1, y1, z2, x1, y2, z2, x1, y2, z1);
    }

    public void draw() {
        if (buffer == null) {
            return;
        }
        try {
            if (draws.isEmpty()) {
                return;
            }
            buffer.upload();
            for (int i = 0; i < draws.size(); i++) {
                StagedVertexBuffer.ExecuteInfo info = buffer.getExecuteInfo(draws.get(i));
                if (info != null) {
                    types.get(i).prepare().drawFromBuffer(info);
                }
            }
            buffer.endDraw();
        } finally {
            draws.clear();
            types.clear();
            buffer.close();
            buffer = null;
        }
    }

    private void lineRelative(Vec3 from, Vec3 to, int color, boolean throughWalls) {
        VertexConsumer vc = buffer(Pipelines.lines(throughWalls));
        float x1 = (float) from.x;
        float y1 = (float) from.y;
        float z1 = (float) from.z;
        float x2 = (float) to.x;
        float y2 = (float) to.y;
        float z2 = (float) to.z;

        Vector3f normal = new Vector3f(x2, y2, z2).sub(x1, y1, z1).normalize();
        vc.addVertex(pose, x1, y1, z1).setColor(color).setNormal(pose, normal).setLineWidth(2);

        // The vanilla line shader glitches when a line crosses the near plane.
        float t = new Vector3f(x1, y1, z1).negate().dot(normal);
        float length = new Vector3f(x2, y2, z2).sub(x1, y1, z1).length();
        if (t > 0 && t < length) {
            Vector3f mid = new Vector3f(normal).mul(t).add(x1, y1, z1);
            vc.addVertex(pose, mid).setColor(color).setNormal(pose, normal).setLineWidth(2);
            vc.addVertex(pose, mid).setColor(color).setNormal(pose, normal).setLineWidth(2);
        }

        vc.addVertex(pose, x2, y2, z2).setColor(color).setNormal(pose, normal).setLineWidth(2);
    }

    private void edge(float x1, float y1, float z1, float x2, float y2, float z2,
                      int color, boolean throughWalls) {
        VertexConsumer vc = buffer(Pipelines.lines(throughWalls));
        Vector3f normal = new Vector3f(x2, y2, z2).sub(x1, y1, z1).normalize();
        vc.addVertex(pose, x1, y1, z1).setColor(color).setNormal(pose, normal).setLineWidth(2);
        vc.addVertex(pose, x2, y2, z2).setColor(color).setNormal(pose, normal).setLineWidth(2);
    }

    private void quad(VertexConsumer vc, int color,
                      float ax, float ay, float az, float bx, float by, float bz,
                      float cx, float cy, float cz, float dx, float dy, float dz) {
        vc.addVertex(pose, ax, ay, az).setColor(color);
        vc.addVertex(pose, bx, by, bz).setColor(color);
        vc.addVertex(pose, cx, cy, cz).setColor(color);
        vc.addVertex(pose, dx, dy, dz).setColor(color);
    }

    private VertexConsumer buffer(RenderType type) {
        if (buffer == null) {
            buffer = new StagedVertexBuffer(() -> "OfflineClient DrawBatch", RenderType.BIG_BUFFER_SIZE);
        }
        if (!types.isEmpty() && types.getLast() == type
            && type.canConsolidateConsecutiveGeometry()) {
            return buffer.getVertexBuilder(draws.getLast());
        }
        StagedVertexBuffer.Draw draw = buffer.appendDraw(type.format(),
            type.primitiveTopology(), type.sortOnUpload()
                ? RenderSystem.getProjectionType().vertexSorting() : null);
        draws.add(draw);
        types.add(type);
        return buffer.getVertexBuilder(draw);
    }
}
