package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.config.WaypointStore;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

// A place the server gave away without you seeing it. Locator and Eavesdrop and EyeTracker and
// ItemCoords hand theirs to the leaks list on the HUD and save them as waypoints the same way. The
// height is only real when the server sent it. The dimension is null when the place could
// lie in any of them. The time is when the place was last known to be right.
public record Leak(String name, String detail, BlockPos pos, boolean knowsHeight, String dimension,
                   long time, int color) {

    // A module that learns places this way. The module answers isEnabled itself.
    public interface Source {

        List<Leak> leaks();

        boolean isEnabled();
    }

    // The leaks of every source that is switched on.
    public static List<Leak> all() {
        List<Leak> all = new ArrayList<>();
        for (Source source : Modules.all(Source.class)) {
            if (source.isEnabled()) {
                all.addAll(source.leaks());
            }
        }
        return all;
    }

    // True whilst any source is switched on even if it has found nothing yet.
    public static boolean anySourceOn() {
        return Modules.all(Source.class).stream().anyMatch(Source::isEnabled);
    }

    // X Y Z or only X Z when the height is not known.
    public String coordinates() {
        return knowsHeight ? BlockUtil.text(pos) : pos.getX() + " " + pos.getZ();
    }

    // Saves the place as a waypoint of that name or moves the one saved before. A place
    // that could lie in any dimension goes in the given one.
    public void saveAs(String waypoint, String anyDimension) {
        WaypointStore.get().markFind(waypoint, pos, savedIn(anyDimension), Modules.nextWaypointColour());
    }

    // Saves the place as a new waypoint named after the stem with the first number free
    // where it goes such as Wither2. Returns the name it took.
    public String saveFresh(String stem, String anyDimension) {
        return WaypointStore.get().markFreshFind(stem, pos, savedIn(anyDimension), Modules.nextWaypointColour());
    }

    // The nearest waypoint named after the stem and a number that stands within reach of the
    // place across the ground where it would go. Null when none does.
    public String numberedNear(String stem, String anyDimension, double reach) {
        Pattern numbered = Pattern.compile(Pattern.quote(stem) + "\\d+", Pattern.CASE_INSENSITIVE);
        String nearest = null;
        double best = reach;
        for (WaypointStore.Waypoint waypoint : WaypointStore.get().in(savedIn(anyDimension))) {
            double away = Math.hypot(waypoint.x() - pos.getX(), waypoint.z() - pos.getZ());
            if (away <= best && numbered.matcher(waypoint.name()).matches()) {
                nearest = waypoint.name();
                best = away;
            }
        }
        return nearest;
    }

    private String savedIn(String anyDimension) {
        return dimension != null ? dimension : anyDimension;
    }
}
