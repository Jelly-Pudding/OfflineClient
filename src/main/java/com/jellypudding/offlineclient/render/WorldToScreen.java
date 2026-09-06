package com.jellypudding.offlineclient.render;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.mixin.GameRendererAccessor;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;

// Projects world positions onto the HUD using the same projection and view
// bobbing the level is drawn with.
public final class WorldToScreen {

    private static final Minecraft MC = OfflineClient.MC;
    private static final Matrix4f MATRIX = new Matrix4f();
    private static Vec3 cameraPos = Vec3.ZERO;
    private static int width;
    private static int height;

    private WorldToScreen() {
    }

    // Rebuilds the projection for this frame. False if there is no camera yet.
    public static boolean update() {
        GameRenderer renderer = MC.gameRenderer;
        CameraRenderState camera = renderer.gameRenderState().levelRenderState.cameraRenderState;
        if (camera == null || !camera.initialized || camera.projectionMatrix == null
            || camera.viewRotationMatrix == null || camera.pos == null) {
            return false;
        }
        PoseStack bob = new PoseStack();
        GameRendererAccessor accessor = (GameRendererAccessor) renderer;
        accessor.offlineclient$bobHurt(camera, bob);
        if (renderer.gameRenderState().optionsRenderState.bobView) {
            accessor.offlineclient$bobView(camera, bob);
        }
        MATRIX.set(camera.projectionMatrix).mul(bob.last().pose()).mul(camera.viewRotationMatrix);
        cameraPos = camera.pos;
        width = MC.getWindow().getGuiScaledWidth();
        height = MC.getWindow().getGuiScaledHeight();
        return true;
    }

    public static Vec3 cameraPos() {
        return cameraPos;
    }

    // True when the projected point lands inside the window.
    public static boolean onScreen(Vec3 world) {
        Vec3 screen = project(world);
        return screen != null && screen.x >= 0 && screen.x <= width && screen.y >= 0 && screen.y <= height;
    }

    // A unit direction across the screen from its centre toward a world point.
    // Points behind the camera still give the way round to them.
    public static Vec3 directionTo(Vec3 world) {
        Vector4f v = new Vector4f((float) (world.x - cameraPos.x), (float) (world.y - cameraPos.y),
            (float) (world.z - cameraPos.z), 1f);
        MATRIX.transform(v);
        double x = v.x * width;
        double y = -v.y * height;
        if (v.w < 0) {
            x = -x;
            y = -y;
        }
        double length = Math.hypot(x, y);
        if (length < 1.0E-6 || Double.isNaN(length)) {
            return new Vec3(0, -1, 0);
        }
        return new Vec3(x / length, y / length, 0);
    }

    // Null when the point is behind the camera. The z value is the depth
    // and grows with distance.
    public static Vec3 project(Vec3 world) {
        Vector4f v = new Vector4f((float) (world.x - cameraPos.x), (float) (world.y - cameraPos.y),
            (float) (world.z - cameraPos.z), 1f);
        MATRIX.transform(v);
        if (v.w <= 0.001f) {
            return null;
        }
        double x = (v.x / v.w + 1) / 2 * width;
        double y = (1 - v.y / v.w) / 2 * height;
        if (Double.isNaN(x) || Double.isNaN(y)) {
            return null;
        }
        return new Vec3(x, y, v.w);
    }
}
