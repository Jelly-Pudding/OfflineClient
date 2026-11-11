package com.jellypudding.offlineclient.render;

import com.jellypudding.offlineclient.util.PositionHistory;
import com.jellypudding.offlineclient.util.WorldWatch;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

// Tells your own marker on a map you carry from those of other players and works out the land
// the map covers. The server sends neither. It makes your marker from where it last saw you and
// it centres every map it makes on a grid. A player marker that puts the centre on that grid
// from a spot you just stood on is yours and the centre is kept for that map. With the centre
// known the player marker near you is yours even where the server has you ahead of what you see
// as it does in a minecart. On a map that sits off the grid none ever is.
public final class OwnMarker {

    // Where your marker sits in the map's list and the land the map covers in blocks.
    public record Found(int index, int centreX, int centreZ, int blocksAcross) {

        // The map drawn this many pixels wide with its corner on the origin and north up.
        public MapView view(int size) {
            return new MapView(centreX, centreZ, blocksAcross / (double) size, 0, 0, 0, size, size);
        }
    }

    // The middle of a map in blocks.
    private record Centre(int x, int z) {
    }

    // A marker that fits the grid from the spot you stood on this many ticks back. It is exact
    // when it sits just where the server puts a marker for that spot.
    private record Fit(int index, Centre centre, int back, boolean exact) {

        // An exact fit beats a loose one. Your own marker is exact on the spot the server made it
        // from and a marker of somebody beside you fits loosely at best. Between two of a kind
        // the later spot wins. Somebody walking behind you fits a spot you left longer ago.
        private boolean beats(Fit other) {
            if (other == null) {
                return true;
            }
            return exact == other.exact ? back < other.back : exact;
        }
    }

    private static final int NONE = -1;
    // A map is this many of its own pixels across.
    private static final int MAP_PIXELS = 128;
    // Maps of one size tile the world edge to edge from a corner this many blocks north and west
    // of the origin.
    private static final int GRID_SHIFT = 64;
    // The server keeps a player marker for you within this many pixels of the centre.
    private static final int EDGE_PIXELS = 63;
    // A marker counts in halves of a map pixel from the centre.
    private static final double MARKER_STEPS = 2;
    // The server rounds a marker to within three quarters of a pixel of where you stood.
    private static final double SLACK_PIXELS = 1;
    // How far back a marker may have been made. The server sends them every fifth tick and the
    // way there and back takes the ping.
    private static final int TRAIL_TICKS = 40;

    private final PositionHistory trail = new PositionHistory();
    private int followedAt = -1;
    // The centre learnt for each map. Another world may give the id to another map.
    private final Map<MapId, Centre> centres = new HashMap<>();
    private final WorldWatch world = new WorldWatch();

    // Called every frame. It keeps where you stand once a tick.
    public void follow(LocalPlayer player) {
        if (player.tickCount != followedAt) {
            followedAt = player.tickCount;
            trail.record(player.position());
        }
    }

    // Null whilst no marker can be told to be yours. A marker that fits the grid teaches the
    // centre. Whilst none fits the centre learnt before picks the marker near you.
    public Found find(MapId id, MapItemSavedData map) {
        if (world.changed()) {
            centres.clear();
        }
        Vec3 at = trail.ticksAgo(0);
        if (at == null) {
            return null;
        }
        int blocksPerPixel = 1 << map.scale;
        Fit fit = fit(map, at, blocksPerPixel);
        if (fit != null) {
            centres.put(id, fit.centre());
            return found(fit.index(), fit.centre(), blocksPerPixel);
        }
        Centre centre = centres.get(id);
        int index = centre == null ? NONE : near(map, centre, at, blocksPerPixel);
        return index == NONE ? null : found(index, centre, blocksPerPixel);
    }

    private static Found found(int index, Centre centre, int blocksPerPixel) {
        return new Found(index, centre.x(), centre.z(), MAP_PIXELS * blocksPerPixel);
    }

    private static boolean isPlayer(MapDecoration marker) {
        return marker.type().value() == MapDecorationTypes.PLAYER.value();
    }

    // The best fit of any player marker on the map. Null when none fits.
    private Fit fit(MapItemSavedData map, Vec3 at, int blocksPerPixel) {
        Fit best = null;
        int index = 0;
        for (MapDecoration marker : map.getDecorations()) {
            Fit fit = isPlayer(marker) ? fitOf(marker, index, at, blocksPerPixel) : null;
            if (fit != null && fit.beats(best)) {
                best = fit;
            }
            index++;
        }
        return best;
    }

    // How one marker fits where you stood. The latest spot it sits on exactly or failing that
    // the latest it fits at all. Null when it fits none. Only a map you stand on counts. A spot
    // one whole map back fits the map next door just as well.
    private Fit fitOf(MapDecoration marker, int index, Vec3 at, int blocksPerPixel) {
        Fit loose = null;
        for (int back = 0; back < TRAIL_TICKS; back++) {
            Vec3 stood = trail.ticksAgo(back);
            Centre centre = centreFrom(stood, marker, blocksPerPixel);
            if (centre == null || !onMap(at, centre, blocksPerPixel)) {
                continue;
            }
            if (marker.x() == markerByte(stood.x, centre.x(), blocksPerPixel)
                && marker.y() == markerByte(stood.z, centre.z(), blocksPerPixel)) {
                return new Fit(index, centre, back, true);
            }
            if (loose == null) {
                loose = new Fit(index, centre, back, false);
            }
        }
        return loose;
    }

    // The centre of the map if the server made the marker from this spot. Null when that leaves
    // the map off the grid.
    private static Centre centreFrom(Vec3 stood, MapDecoration marker, int blocksPerPixel) {
        int blocksAcross = MAP_PIXELS * blocksPerPixel;
        double impliedX = stood.x - marker.x() / MARKER_STEPS * blocksPerPixel;
        double impliedZ = stood.z - marker.y() / MARKER_STEPS * blocksPerPixel;
        Centre centre = new Centre(onGrid(impliedX, blocksAcross), onGrid(impliedZ, blocksAcross));
        double slack = SLACK_PIXELS * blocksPerPixel;
        boolean fits = Math.abs(impliedX - centre.x()) <= slack && Math.abs(impliedZ - centre.z()) <= slack;
        return fits ? centre : null;
    }

    // Nobody has a player marker whilst they stand off the map.
    private static boolean onMap(Vec3 spot, Centre centre, int blocksPerPixel) {
        double edge = EDGE_PIXELS * blocksPerPixel;
        return Math.abs(spot.x - centre.x()) <= edge && Math.abs(spot.z - centre.z()) <= edge;
    }

    // The nearest spot a map this many blocks wide is ever centred on.
    private static int onGrid(double centre, int blocksAcross) {
        int first = blocksAcross / 2 - GRID_SHIFT;
        return (int) Math.round((centre - first) / blocksAcross) * blocksAcross + first;
    }

    // What the server writes into a marker for a spot this far along one axis. It works in
    // floats and drops the fraction after adding a half.
    private static byte markerByte(double spot, int centre, int blocksPerPixel) {
        float pixels = (float) (spot - centre) / blocksPerPixel;
        if (pixels <= -EDGE_PIXELS) {
            return Byte.MIN_VALUE;
        }
        if (pixels >= EDGE_PIXELS) {
            return Byte.MAX_VALUE;
        }
        return (byte) (int) (pixels * MARKER_STEPS + 0.5);
    }

    // The player marker nearest you on a map whose centre is known. The server may have you
    // where you were of late or as far ahead as you went.
    private int near(MapItemSavedData map, Centre centre, Vec3 at, int blocksPerPixel) {
        if (!onMap(at, centre, blocksPerPixel)) {
            return NONE;
        }
        double least = SLACK_PIXELS * blocksPerPixel + covered(at);
        int nearest = NONE;
        int index = 0;
        for (MapDecoration marker : map.getDecorations()) {
            if (isPlayer(marker)) {
                double x = centre.x() + marker.x() / MARKER_STEPS * blocksPerPixel;
                double z = centre.z() + marker.y() / MARKER_STEPS * blocksPerPixel;
                double away = Math.max(Math.abs(x - at.x), Math.abs(z - at.z));
                if (away <= least) {
                    least = away;
                    nearest = index;
                }
            }
            index++;
        }
        return nearest;
    }

    // How far the spots you stood on of late reach from this one.
    private double covered(Vec3 from) {
        double farthest = 0;
        for (int back = 1; back < TRAIL_TICKS; back++) {
            Vec3 stood = trail.ticksAgo(back);
            farthest = Math.max(farthest, Math.hypot(stood.x - from.x, stood.z - from.z));
        }
        return farthest;
    }
}
