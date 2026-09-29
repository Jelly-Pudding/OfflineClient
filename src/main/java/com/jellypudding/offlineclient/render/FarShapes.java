package com.jellypudding.offlineclient.render;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.util.Bearing;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

// Brings far points in along the line of sight. The depth range cuts anything past a
// few render distances and a pulled in point keeps its place on the screen. Only
// shapes drawn through walls may use it.
public final class FarShapes {

    // Inside the nearest far plane the game uses at two chunks of render distance.
    private static final double REACH = 96;

    private static final double HORIZON = 4096;
    private static final double RAY_LIFT = 0.1;
    private static final int RING_POINTS = 48;

    private FarShapes() {
    }

    public static Vec3 pullIn(Vec3 point) {
        Vec3 camera = DrawBatch.cameraPos();
        double away = point.distanceTo(camera);
        return away <= REACH ? point : camera.add(point.subtract(camera).scale(REACH / away));
    }

    // The whole box shrinks towards the camera and looks the size it would far away.
    public static AABB pullIn(AABB box) {
        Vec3 camera = DrawBatch.cameraPos();
        double away = box.getCenter().distanceTo(camera);
        if (away <= REACH) {
            return box;
        }
        double scale = REACH / away;
        Vec3 low = new Vec3(box.minX, box.minY, box.minZ).subtract(camera).scale(scale);
        Vec3 high = new Vec3(box.maxX, box.maxY, box.maxZ).subtract(camera).scale(scale);
        return new AABB(camera.add(low), camera.add(high));
    }

    public static void line(DrawBatch batch, Vec3 from, Vec3 to, int color) {
        batch.line(pullIn(from), pullIn(to), color, true);
    }

    // A bearing drawn out to the horizon a hair above the ground it starts on.
    public static void ray(DrawBatch batch, Bearing bearing, double ground, int color) {
        double y = ground + RAY_LIFT;
        line(batch, bearing.along(0, y), bearing.along(HORIZON, y), color);
    }

    // A spot known only roughly. A line runs through it from the bottom of the world to
    // the top and a ring of its radius sits level with your eyes where it reads best
    // against the horizon.
    public static void estimate(DrawBatch batch, double x, double z, double radius, int color) {
        ClientLevel level = OfflineClient.MC.level;
        if (level == null) {
            return;
        }
        line(batch, new Vec3(x, level.getMinY(), z), new Vec3(x, level.getMaxY(), z), color);
        double y = DrawBatch.cameraPos().y;
        Vec3 last = null;
        for (int i = 0; i <= RING_POINTS; i++) {
            double turn = 2 * Math.PI * i / RING_POINTS;
            Vec3 point = new Vec3(x + Math.cos(turn) * radius, y, z + Math.sin(turn) * radius);
            if (last != null) {
                line(batch, last, point, color);
            }
            last = point;
        }
    }
}
