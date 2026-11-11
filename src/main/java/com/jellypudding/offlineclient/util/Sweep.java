package com.jellypudding.offlineclient.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

// An area flown line by line with the lines a set number of chunks apart. Rows run back and
// forth across a frame of chunks along its longer side and a spiral winds out from its centre
// chunk for as long as the world goes on. The line flown and the place reached on it travel
// with the sweep. Leg n of a sweep runs from turn n to turn n plus one.
public record Sweep(Shape shape, int west, int north, int east, int south, int gap, int leg, double x, double z) {

    public enum Shape { SQUARE, AREA, SPIRAL }

    // No chunk of a sweep lies past the farthest the world border can reach.
    private static final int EDGE = SectionPos.blockToSectionCoord(WorldBorder.MAX_SIZE / 2);
    // A place read back from a file has to lie this close to the line it names.
    private static final double ON_LINE = 1;

    // A square of chunks round the given one. A size that would cross the world border is cut
    // at it.
    public static Sweep square(int chunkX, int chunkZ, int size, int gap) {
        int west = chunkX - size / 2;
        int north = chunkZ - size / 2;
        return begun(Shape.SQUARE, west, north, (long) west + size - 1, (long) north + size - 1, gap);
    }

    // Every chunk between two corners given in blocks.
    public static Sweep area(int x1, int z1, int x2, int z2, int gap) {
        int west = SectionPos.blockToSectionCoord(Math.min(x1, x2));
        int north = SectionPos.blockToSectionCoord(Math.min(z1, z2));
        return begun(Shape.AREA, west, north, SectionPos.blockToSectionCoord(Math.max(x1, x2)),
            SectionPos.blockToSectionCoord(Math.max(z1, z2)), gap);
    }

    // A spiral is framed by its centre chunk alone.
    public static Sweep spiral(int chunkX, int chunkZ, int gap) {
        return begun(Shape.SPIRAL, chunkX, chunkZ, chunkX, chunkZ, gap);
    }

    private static Sweep begun(Shape shape, long west, long north, long east, long south, int gap) {
        Sweep framed = new Sweep(shape, edge(west), edge(north), edge(east), edge(south), Math.max(1, gap), 0, 0, 0);
        Vec3 start = framed.turn(0);
        return framed.at(0, start.x, start.z);
    }

    private static int edge(long chunk) {
        return Math.clamp(chunk, -EDGE, EDGE);
    }

    // The same sweep with the line flown and the place reached on it.
    public Sweep at(int leg, double x, double z) {
        return new Sweep(shape, west, north, east, south, gap, leg, x, z);
    }

    public Vec3 place() {
        return new Vec3(x, 0, z);
    }

    // True whilst the sweep has a line of this number. A spiral goes on as far as any world can.
    public boolean hasLeg(int leg) {
        if (leg < 0) {
            return false;
        }
        if (shape == Shape.SPIRAL) {
            return withinBorder(spiralOffset(leg + 1L));
        }
        return leg + 1L < 2L * rows();
    }

    // True when the line ends inside the border the world has. A line that runs out of it can
    // never be flown to its end.
    public boolean endsInside(int leg, WorldBorder border) {
        Vec3 end = turn(leg + 1);
        return border.isWithinBounds(end.x, end.z);
    }

    // Where turn n of the sweep lies. The middle of its chunk at height nought.
    public Vec3 turn(int n) {
        if (shape == Shape.SPIRAL) {
            long[] offset = spiralOffset(n);
            return centreOf(west + offset[0], north + offset[1]);
        }
        int row = n / 2;
        boolean forward = row % 2 == 0;
        boolean end = n % 2 == 1;
        int across = (alongX() ? north : west) + row * gap;
        int along = forward == end ? (alongX() ? east : south) : (alongX() ? west : north);
        return alongX() ? centreOf(along, across) : centreOf(across, along);
    }

    // The point of a line nearest a place.
    public Vec3 nearestOn(int leg, double fromX, double fromZ) {
        Vec3 start = turn(leg);
        Vec3 end = turn(leg + 1);
        double dx = end.x - start.x;
        double dz = end.z - start.z;
        double length = dx * dx + dz * dz;
        if (length == 0) {
            return start;
        }
        double along = Math.clamp(((fromX - start.x) * dx + (fromZ - start.z) * dz) / length, 0, 1);
        return new Vec3(start.x + dx * along, 0, start.z + dz * along);
    }

    // Blocks still to fly from a place on a line to the end of the last one. The rows are flown on
    // the even lines and are all as long as the first. The odd lines step the gap to the next row.
    // A spiral never ends.
    public double blocksLeft(int leg, double fromX, double fromZ) {
        if (shape == Shape.SPIRAL) {
            return Double.POSITIVE_INFINITY;
        }
        Vec3 next = turn(leg + 1);
        Vec3 first = turn(0);
        Vec3 firstEnd = turn(1);
        long lastLeg = 2L * rows() - 2;
        long rowsLeft = Math.max(0, lastLeg / 2 - leg / 2);
        long stepsLeft = Math.max(0, lastLeg - leg - rowsLeft);
        return Math.hypot(next.x - fromX, next.z - fromZ)
            + rowsLeft * Math.hypot(firstEnd.x - first.x, firstEnd.z - first.z)
            + stepsLeft * (double) gap * SectionPos.SECTION_SIZE;
    }

    // How many times the spiral has gone round by this line. Nought for rows.
    public int ring(int leg) {
        return shape == Shape.SPIRAL ? leg / 4 + 1 : 0;
    }

    // Rows run along the longer side. A square runs west to east.
    private boolean alongX() {
        return east - west >= south - north;
    }

    private int rows() {
        int across = alongX() ? south - north : east - west;
        return across / gap + 1;
    }

    // The turns of a square spiral in steps of the gap. The legs run east then south then west
    // then north and every second leg is a step longer. Each full round ends a step further north
    // west than the last.
    private long[] spiralOffset(long n) {
        long round = n / 4;
        long out = round + 1;
        long[] steps = switch ((int) (n % 4)) {
            case 0 -> new long[] {-round, -round};
            case 1 -> new long[] {out, -round};
            case 2 -> new long[] {out, out};
            default -> new long[] {-out, out};
        };
        return new long[] {steps[0] * gap, steps[1] * gap};
    }

    private boolean withinBorder(long[] offset) {
        return Math.abs(west + offset[0]) <= EDGE && Math.abs(north + offset[1]) <= EDGE;
    }

    private static Vec3 centreOf(long chunkX, long chunkZ) {
        return new Vec3(chunkX * SectionPos.SECTION_SIZE + SectionPos.SECTION_HALF_SIZE, 0,
            chunkZ * SectionPos.SECTION_SIZE + SectionPos.SECTION_HALF_SIZE);
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("shape", shape.name().toLowerCase(Locale.ROOT));
        json.addProperty("west", west);
        json.addProperty("north", north);
        json.addProperty("east", east);
        json.addProperty("south", south);
        json.addProperty("gap", gap);
        json.addProperty("leg", leg);
        json.addProperty("x", x);
        json.addProperty("z", z);
        return json;
    }

    // Null for anything missing or out of bounds. A sweep taken from a file has to describe one
    // the autopilot could have flown and a place on its own line.
    public static Sweep fromJson(JsonElement element) {
        try {
            JsonObject json = element.getAsJsonObject();
            Shape shape = Shape.valueOf(json.get("shape").getAsString().toUpperCase(Locale.ROOT));
            Sweep sweep = new Sweep(shape, whole(json, "west"), whole(json, "north"), whole(json, "east"),
                whole(json, "south"), whole(json, "gap"), whole(json, "leg"), json.get("x").getAsDouble(),
                json.get("z").getAsDouble());
            return sweep.sound() ? sweep : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    // A number that is not whole or does not fit is as broken as a missing one.
    private static int whole(JsonObject json, String name) {
        double value = json.get(name).getAsDouble();
        if (value != Math.rint(value) || Math.abs(value) > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(name + " is not a whole number");
        }
        return (int) value;
    }

    private boolean sound() {
        boolean framed = Math.abs(west) <= EDGE && Math.abs(east) <= EDGE && Math.abs(north) <= EDGE
            && Math.abs(south) <= EDGE && west <= east && north <= south;
        boolean centred = shape != Shape.SPIRAL || west == east && north == south;
        if (!framed || !centred || gap < 1 || gap > 2 * EDGE || !hasLeg(leg) || !Double.isFinite(x)
            || !Double.isFinite(z)) {
            return false;
        }
        Vec3 nearest = nearestOn(leg, x, z);
        return Math.hypot(nearest.x - x, nearest.z - z) <= ON_LINE;
    }
}
