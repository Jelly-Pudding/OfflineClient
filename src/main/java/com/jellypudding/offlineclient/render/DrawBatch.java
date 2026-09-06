package com.jellypudding.offlineclient.render;

import com.jellypudding.offlineclient.OfflineClient;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

// Collects lines and boxes from every Render3DEvent handler and draws
// them in a single upload. All coordinates are world coordinates.
public final class DrawBatch {

    // Thinner lines vanish against terrain at any distance.
    private static final float LINE_WIDTH = 2;

    // Direction.values allocates a fresh array on every call.
    private static final Direction[] SIDES = Direction.values();

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

    // Pulled in off the faces. A box flush with a block fights it for depth.
    public static final double BLOCK_INSET = 0.002;

    // The bounds of a block pulled in off its faces.
    public static AABB blockBox(BlockPos pos) {
        return new AABB(pos).deflate(BLOCK_INSET);
    }

    // Outlines a single block.
    public void outlineBlock(BlockPos pos, int color, boolean throughWalls) {
        outlineBox(blockBox(pos), color, throughWalls);
    }

    // A bit per side for the joined box calls.
    public static int sideBit(Direction side) {
        return 1 << side.ordinal();
    }

    // The faces of a box that the mask does not hide.
    public void solidBoxPart(AABB box, int hidden, int color, boolean throughWalls) {
        if (hidden == 0) {
            solidBox(box, color, throughWalls);
            return;
        }
        for (Direction side : SIDES) {
            if ((hidden & sideBit(side)) == 0) {
                solidFace(box, side, color, throughWalls);
            }
        }
    }

    // The edges of a box leaving out every edge that lies on a hidden face.
    public void outlineBoxPart(AABB box, int hidden, int color, boolean throughWalls) {
        if (hidden == 0) {
            outlineBox(box, color, throughWalls);
            return;
        }
        AABB b = box.move(camera.reverse());
        float x1 = (float) b.minX;
        float y1 = (float) b.minY;
        float z1 = (float) b.minZ;
        float x2 = (float) b.maxX;
        float y2 = (float) b.maxY;
        float z2 = (float) b.maxZ;

        if (shown(hidden, Direction.DOWN, Direction.NORTH)) {
            edge(x1, y1, z1, x2, y1, z1, color, throughWalls);
        }
        if (shown(hidden, Direction.DOWN, Direction.EAST)) {
            edge(x2, y1, z1, x2, y1, z2, color, throughWalls);
        }
        if (shown(hidden, Direction.DOWN, Direction.SOUTH)) {
            edge(x2, y1, z2, x1, y1, z2, color, throughWalls);
        }
        if (shown(hidden, Direction.DOWN, Direction.WEST)) {
            edge(x1, y1, z2, x1, y1, z1, color, throughWalls);
        }
        if (shown(hidden, Direction.UP, Direction.NORTH)) {
            edge(x1, y2, z1, x2, y2, z1, color, throughWalls);
        }
        if (shown(hidden, Direction.UP, Direction.EAST)) {
            edge(x2, y2, z1, x2, y2, z2, color, throughWalls);
        }
        if (shown(hidden, Direction.UP, Direction.SOUTH)) {
            edge(x2, y2, z2, x1, y2, z2, color, throughWalls);
        }
        if (shown(hidden, Direction.UP, Direction.WEST)) {
            edge(x1, y2, z2, x1, y2, z1, color, throughWalls);
        }
        if (shown(hidden, Direction.NORTH, Direction.WEST)) {
            edge(x1, y1, z1, x1, y2, z1, color, throughWalls);
        }
        if (shown(hidden, Direction.NORTH, Direction.EAST)) {
            edge(x2, y1, z1, x2, y2, z1, color, throughWalls);
        }
        if (shown(hidden, Direction.SOUTH, Direction.EAST)) {
            edge(x2, y1, z2, x2, y2, z2, color, throughWalls);
        }
        if (shown(hidden, Direction.SOUTH, Direction.WEST)) {
            edge(x1, y1, z2, x1, y2, z2, color, throughWalls);
        }
    }

    // An edge shows only whilst both of the faces it lies on do.
    private static boolean shown(int hidden, Direction a, Direction b) {
        return (hidden & (sideBit(a) | sideBit(b))) == 0;
    }

    public void outlineBox(AABB box, int color, boolean throughWalls) {
        AABB b = box.move(camera.reverse());
        float x1 = (float) b.minX;
        float y1 = (float) b.minY;
        float z1 = (float) b.minZ;
        float x2 = (float) b.maxX;
        float y2 = (float) b.maxY;
        float z2 = (float) b.maxZ;

        edge(x1, y1, z1, x2, y1, z1, color, throughWalls);
        edge(x2, y1, z1, x2, y1, z2, color, throughWalls);
        edge(x2, y1, z2, x1, y1, z2, color, throughWalls);
        edge(x1, y1, z2, x1, y1, z1, color, throughWalls);
        edge(x1, y2, z1, x2, y2, z1, color, throughWalls);
        edge(x2, y2, z1, x2, y2, z2, color, throughWalls);
        edge(x2, y2, z2, x1, y2, z2, color, throughWalls);
        edge(x1, y2, z2, x1, y2, z1, color, throughWalls);
        edge(x1, y1, z1, x1, y2, z1, color, throughWalls);
        edge(x2, y1, z1, x2, y2, z1, color, throughWalls);
        edge(x2, y1, z2, x2, y2, z2, color, throughWalls);
        edge(x1, y1, z2, x1, y2, z2, color, throughWalls);
    }

    // The four edges of a rectangle lying flat at one height.
    public void flatRect(double x1, double z1, double x2, double z2, double y,
                         int color, boolean throughWalls) {
        line(new Vec3(x1, y, z1), new Vec3(x2, y, z1), color, throughWalls);
        line(new Vec3(x2, y, z1), new Vec3(x2, y, z2), color, throughWalls);
        line(new Vec3(x2, y, z2), new Vec3(x1, y, z2), color, throughWalls);
        line(new Vec3(x1, y, z2), new Vec3(x1, y, z1), color, throughWalls);
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

    // One face of a box as a tinted quad.
    public void solidFace(AABB box, Direction side, int color, boolean throughWalls) {
        float[] c = faceCorners(box, side);
        quad(buffer(Pipelines.fill(throughWalls)), color,
            c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], c[8], c[9], c[10], c[11]);
    }

    // The four edges of one face of a box.
    public void outlineFace(AABB box, Direction side, int color, boolean throughWalls) {
        float[] c = faceCorners(box, side);
        edge(c[0], c[1], c[2], c[3], c[4], c[5], color, throughWalls);
        edge(c[3], c[4], c[5], c[6], c[7], c[8], color, throughWalls);
        edge(c[6], c[7], c[8], c[9], c[10], c[11], color, throughWalls);
        edge(c[9], c[10], c[11], c[0], c[1], c[2], color, throughWalls);
    }

    // The corners of one face in order round its edge relative to the camera.
    private float[] faceCorners(AABB box, Direction side) {
        AABB b = box.move(camera.reverse());
        float x1 = (float) b.minX;
        float y1 = (float) b.minY;
        float z1 = (float) b.minZ;
        float x2 = (float) b.maxX;
        float y2 = (float) b.maxY;
        float z2 = (float) b.maxZ;
        return switch (side) {
            case DOWN -> new float[] {x1, y1, z1, x2, y1, z1, x2, y1, z2, x1, y1, z2};
            case UP -> new float[] {x1, y2, z1, x1, y2, z2, x2, y2, z2, x2, y2, z1};
            case NORTH -> new float[] {x1, y1, z1, x1, y2, z1, x2, y2, z1, x2, y1, z1};
            case SOUTH -> new float[] {x1, y1, z2, x2, y1, z2, x2, y2, z2, x1, y2, z2};
            case WEST -> new float[] {x1, y1, z1, x1, y1, z2, x1, y2, z2, x1, y2, z1};
            case EAST -> new float[] {x2, y1, z1, x2, y2, z1, x2, y2, z2, x2, y1, z2};
        };
    }

    // One filled face between four world points in order around its edge.
    public void quad(Vec3 a, Vec3 b, Vec3 c, Vec3 d, int color, boolean throughWalls) {
        VertexConsumer vc = buffer(Pipelines.fill(throughWalls));
        Vec3 ra = a.subtract(camera);
        Vec3 rb = b.subtract(camera);
        Vec3 rc = c.subtract(camera);
        Vec3 rd = d.subtract(camera);
        quad(vc, color, (float) ra.x, (float) ra.y, (float) ra.z, (float) rb.x, (float) rb.y, (float) rb.z,
            (float) rc.x, (float) rc.y, (float) rc.z, (float) rd.x, (float) rd.y, (float) rd.z);
    }

    // The four edges of a face between four world points.
    public void outlineQuad(Vec3 a, Vec3 b, Vec3 c, Vec3 d, int color, boolean throughWalls) {
        line(a, b, color, throughWalls);
        line(b, c, color, throughWalls);
        line(c, d, color, throughWalls);
        line(d, a, color, throughWalls);
    }

    // A box whose sides blend from one colour at the bottom to another at the top.
    public void gradientBox(AABB box, int bottom, int top, boolean throughWalls) {
        solidFace(box, Direction.DOWN, bottom, throughWalls);
        solidFace(box, Direction.UP, top, throughWalls);
        gradientSides(box, bottom, top, throughWalls);
    }

    // The four upright faces of a box blending from bottom colour to top colour.
    public void gradientSides(AABB box, int bottom, int top, boolean throughWalls) {
        VertexConsumer vc = buffer(Pipelines.fill(throughWalls));
        AABB b = box.move(camera.reverse());
        float x1 = (float) b.minX;
        float y1 = (float) b.minY;
        float z1 = (float) b.minZ;
        float x2 = (float) b.maxX;
        float y2 = (float) b.maxY;
        float z2 = (float) b.maxZ;

        wall(vc, bottom, top, x1, z1, x2, z1, y1, y2);
        wall(vc, bottom, top, x2, z1, x2, z2, y1, y2);
        wall(vc, bottom, top, x2, z2, x1, z2, y1, y2);
        wall(vc, bottom, top, x1, z2, x1, z1, y1, y2);
    }

    // One upright face between two corners with its own colour at each height.
    private void wall(VertexConsumer vc, int bottom, int top,
                      float ax, float az, float bx, float bz, float y1, float y2) {
        vc.addVertex(pose, ax, y1, az).setColor(bottom);
        vc.addVertex(pose, ax, y2, az).setColor(top);
        vc.addVertex(pose, bx, y2, bz).setColor(top);
        vc.addVertex(pose, bx, y1, bz).setColor(bottom);
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
        edge((float) from.x, (float) from.y, (float) from.z,
            (float) to.x, (float) to.y, (float) to.z, color, throughWalls);
    }

    private void edge(float x1, float y1, float z1, float x2, float y2, float z2,
                      int color, boolean throughWalls) {
        VertexConsumer vc = buffer(Pipelines.lines(throughWalls));
        Vector3f normal = new Vector3f(x2, y2, z2).sub(x1, y1, z1).normalize();
        vc.addVertex(pose, x1, y1, z1).setColor(color).setNormal(pose, normal).setLineWidth(LINE_WIDTH);

        // The vanilla line shader glitches when a line crosses the near plane.
        float t = new Vector3f(x1, y1, z1).negate().dot(normal);
        float length = new Vector3f(x2, y2, z2).sub(x1, y1, z1).length();
        if (t > 0 && t < length) {
            Vector3f mid = new Vector3f(normal).mul(t).add(x1, y1, z1);
            vc.addVertex(pose, mid).setColor(color).setNormal(pose, normal).setLineWidth(LINE_WIDTH);
            vc.addVertex(pose, mid).setColor(color).setNormal(pose, normal).setLineWidth(LINE_WIDTH);
        }

        vc.addVertex(pose, x2, y2, z2).setColor(color).setNormal(pose, normal).setLineWidth(LINE_WIDTH);
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
