package com.jellypudding.offlineclient.util;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.UnaryOperator;

// Finds the clear stops a trip by packet takes. The server carries a box up or down
// first and then along the longer flat axis and then the other. A leg is only taken
// when that whole path is clear. Straight up or down it ignores and only the landing
// has to be clear. A way a wall blocks climbs over it.
final class HopPath {

    private static final double PLAN_STEP = 0.25;

    // A leg this close to straight up or down counts as one.
    private static final double UPRIGHT = 1.0E-6;

    // A vault tries heights a block apart. It may go a little over the top of the world.
    private static final double VAULT_STEP = 1;
    private static final double VAULT_HEADROOM = 2;

    // How far under a spot the ground may sit and still count as standing on it.
    private static final double GROUND_PROBE = 0.05;

    // The server's own test for a box hanging in the air looks this far round and below.
    private static final double FLOAT_MARGIN = 0.0625;
    private static final double FLOAT_DEPTH = 0.55;

    // Spots round one with no room are tried nearest first out to two blocks.
    private static final double NEAR = 2;
    private static final List<Vec3> NEARBY = nearby();

    private HopPath() {
    }

    // The stops from one spot to another with the destination last. Null when a stretch
    // has nowhere clear to stop. No stop is further than a hop from the last or lower by
    // more than the drop.
    static List<Vec3> route(Entity mover, Vec3 from, Vec3 to, double hop, double drop) {
        if (upright(from, to)) {
            return column(mover, from, to, hop, drop);
        }
        if (clearWay(mover, from, to)) {
            return line(mover, from, to, hop, drop);
        }
        return vault(mover, from, to, hop, drop);
    }

    static boolean upright(Vec3 from, Vec3 to) {
        return to.subtract(from).horizontalDistance() <= UPRIGHT;
    }

    // True when the whole box and anyone riding it has room at the spot.
    static boolean fits(Entity mover, Vec3 spot) {
        return clear(mover, spot, UnaryOperator.identity());
    }

    static AABB boxAt(Entity mover, Vec3 spot) {
        return mover.getBoundingBox().move(spot.subtract(mover.position()));
    }

    static boolean standingAt(Entity mover, Vec3 spot) {
        return !mover.level().noCollision(mover, boxAt(mover, spot).move(0, -GROUND_PROBE, 0));
    }

    // True when the server takes a box at the spot for one hanging in the air.
    static boolean floating(Entity mover, Vec3 spot) {
        AABB around = boxAt(mover, spot).inflate(FLOAT_MARGIN).expandTowards(0, -FLOAT_DEPTH, 0);
        return mover.level().getBlockStates(around).allMatch(BlockState::isAir);
    }

    static Vec3 nearestFit(Entity mover, Vec3 spot) {
        for (Vec3 offset : NEARBY) {
            Vec3 near = spot.add(offset);
            if (landing(mover, near)) {
                return near;
            }
        }
        return null;
    }

    // Somewhere to stop that is clear and out of lava.
    static boolean landing(Entity mover, Vec3 spot) {
        return fits(mover, spot) && !BlockUtil.touchesLava(mover.level(), boxAt(mover, spot));
    }

    // The server carries a box up or down first and then along the longer flat axis.
    // Both servers settle a tie by going along x first.
    static boolean clearWay(Entity mover, Vec3 from, Vec3 to) {
        Vec3 level = new Vec3(from.x, to.y, from.z);
        boolean alongX = Math.abs(to.x - from.x) >= Math.abs(to.z - from.z);
        Vec3 corner = alongX ? new Vec3(to.x, to.y, from.z) : new Vec3(from.x, to.y, to.z);
        return swept(mover, from, level) && swept(mover, level, corner) && swept(mover, corner, to);
    }

    private static boolean swept(Entity mover, Vec3 from, Vec3 to) {
        Vec3 delta = to.subtract(from);
        return delta.lengthSqr() == 0 || clear(mover, from, box -> box.expandTowards(delta));
    }

    // Tests the mover's box and each rider's box moved to the spot and shaped.
    private static boolean clear(Entity mover, Vec3 spot, UnaryOperator<AABB> shape) {
        Vec3 shift = spot.subtract(mover.position());
        if (!mover.level().noCollision(mover, shape.apply(mover.getBoundingBox().move(shift)))) {
            return false;
        }
        for (Entity rider : mover.getPassengers()) {
            if (!rider.level().noCollision(rider, shape.apply(rider.getBoundingBox().move(shift)))) {
                return false;
            }
        }
        return true;
    }

    // Even steps along a way known to be clear. A step of its own may still clip
    // something the whole way missed and the server's own corners are then taken.
    private static List<Vec3> line(Entity mover, Vec3 from, Vec3 to, double hop, double drop) {
        List<Vec3> straight = steps(from, to, hop, drop);
        Vec3 last = from;
        for (Vec3 stop : straight) {
            if (!clearWay(mover, last, stop)) {
                return corners(from, to, hop, drop);
            }
            last = stop;
        }
        return straight;
    }

    // The way the server itself would carry the box with a stop at every turn.
    private static List<Vec3> corners(Vec3 from, Vec3 to, double hop, double drop) {
        Vec3 level = new Vec3(from.x, to.y, from.z);
        boolean alongX = Math.abs(to.x - from.x) >= Math.abs(to.z - from.z);
        Vec3 corner = alongX ? new Vec3(to.x, to.y, from.z) : new Vec3(from.x, to.y, to.z);
        List<Vec3> stops = new ArrayList<>(steps(from, level, hop, drop));
        stops.addAll(steps(level, corner, hop, drop));
        stops.addAll(steps(corner, to, hop, drop));
        return stops;
    }

    private static List<Vec3> steps(Vec3 from, Vec3 to, double hop, double drop) {
        Vec3 way = to.subtract(from);
        int count = (int) Math.ceil(Math.max(way.length() / hop, -way.y / drop));
        List<Vec3> stops = new ArrayList<>();
        for (int step = 1; step <= count; step++) {
            stops.add(from.add(way.scale((double) step / count)));
        }
        return stops;
    }

    // Straight up or down where only each landing has to be clear. A stop on the way
    // keeps out of lava. A floor thicker than one stride has no way through.
    private static List<Vec3> column(Entity mover, Vec3 from, Vec3 to, double hop, double drop) {
        List<Vec3> stops = new ArrayList<>();
        Vec3 at = from;
        while (at.distanceTo(to) > stride(at, to, hop, drop)) {
            Vec3 stop = nextStop(mover, at, to, hop, drop);
            if (stop == null) {
                return null;
            }
            stops.add(stop);
            at = stop;
        }
        if (at.distanceToSqr(to) > 0) {
            stops.add(to);
        }
        return stops;
    }

    private static double stride(Vec3 at, Vec3 to, double hop, double drop) {
        return to.y < at.y ? Math.min(hop, drop) : hop;
    }

    private static Vec3 nextStop(Entity mover, Vec3 at, Vec3 to, double hop, double drop) {
        Vec3 way = to.subtract(at).normalize();
        for (double reach = stride(at, to, hop, drop); reach > 0; reach -= PLAN_STEP) {
            Vec3 stop = at.add(way.scale(reach));
            if (landing(mover, stop)) {
                return stop;
            }
        }
        return null;
    }

    // Up out of the way and across in the open and back down. The lowest height whose
    // way across is clear. It has no limit short of the top of the world.
    private static List<Vec3> vault(Entity mover, Vec3 from, Vec3 to, double hop, double drop) {
        double top = mover.level().getMaxY() + VAULT_HEADROOM;
        for (double height = Math.max(from.y, to.y); height <= top; height += VAULT_STEP) {
            Vec3 up = new Vec3(from.x, height, from.z);
            Vec3 over = new Vec3(to.x, height, to.z);
            if (!fits(mover, up) || !fits(mover, over) || !clearWay(mover, up, over)) {
                continue;
            }
            List<Vec3> climb = column(mover, from, up, hop, drop);
            List<Vec3> descent = column(mover, over, to, hop, drop);
            if (climb != null && descent != null) {
                List<Vec3> stops = new ArrayList<>(climb);
                stops.addAll(line(mover, up, over, hop, drop));
                stops.addAll(descent);
                return stops;
            }
        }
        return null;
    }

    // Ties go to the higher spot. Landing a little high only means a short drop.
    private static List<Vec3> nearby() {
        int reach = (int) Math.round(NEAR / PLAN_STEP);
        List<Vec3> offsets = new ArrayList<>();
        for (int x = -reach; x <= reach; x++) {
            for (int y = -reach; y <= reach; y++) {
                for (int z = -reach; z <= reach; z++) {
                    Vec3 offset = new Vec3(x, y, z).scale(PLAN_STEP);
                    if (offset.length() <= NEAR) {
                        offsets.add(offset);
                    }
                }
            }
        }
        offsets.sort(Comparator.comparingDouble(Vec3::lengthSqr).thenComparingDouble(offset -> -offset.y));
        return List.copyOf(offsets);
    }
}
