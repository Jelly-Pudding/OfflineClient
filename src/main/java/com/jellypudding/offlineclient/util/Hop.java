package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

// Moves the player in single packet hops. The server checks a move without its
// height and a straight rise or drop can pass through blocks. The landing spot
// has to be clear. The server charges a drop as a fall once a packet claims
// ground. Nothing claims it whilst a fall is owed and the next free tick rises a
// hair. That rise wipes the fall before the player settles onto the ground.
// A trip longer than one hop stops at clear spots along the way.
public enum Hop {
    INSTANCE;

    // Three fillers buy the position packet sqrt(400) blocks of travel. A hair is
    // kept in hand.
    private static final double REACH = 19.9;
    private static final int FILLERS = 3;

    public static final int MAX_TRIP = 1000;

    // Stops are planned a block short of a full hop. Gravity pulls the player off a
    // stop in the air whilst the next hop waits its turn.
    private static final double PLAN_REACH = REACH - 1;
    private static final double PLAN_STEP = 0.25;

    private static final double WIPE_RISE = 0.02;

    // How far under a spot the ground may sit and still count as standing on it.
    private static final double GROUND_PROBE = 0.05;

    public enum Result {
        MOVED(""),
        TOO_FAR("That is more than " + MAX_TRIP + " blocks away."),
        BLOCKED("That spot is inside a block."),
        NO_ROUTE("There is nowhere clear to stop on the way."),
        PULLED_BACK("The server pulled you back on the way."),
        BUSY("Try again in a moment.");

        private final String problem;

        Result(String problem) {
            this.problem = problem;
        }

        public String problem() {
            return problem;
        }
    }

    // Where the last hop down landed until the fall it left is wiped.
    private static Vec3 owed;
    private static boolean rising;

    // The rest of the trip under way and who hears how it ends.
    private static final Deque<Vec3> stops = new ArrayDeque<>();
    private static Consumer<Result> arrival = result -> { };

    // Takes the player there a hop a tick. The first hop goes at once when it can.
    // A new trip replaces one still under way.
    public static void travel(Vec3 destination, Consumer<Result> finished) {
        LocalPlayer player = OfflineClient.MC.player;
        stops.clear();
        if (player == null) {
            finished.accept(Result.BUSY);
            return;
        }
        if (player.position().distanceTo(destination) > MAX_TRIP) {
            finished.accept(Result.TOO_FAR);
            return;
        }
        if (!fits(player, destination)) {
            finished.accept(Result.BLOCKED);
            return;
        }
        List<Vec3> route = route(player, destination);
        if (route == null) {
            finished.accept(Result.NO_ROUTE);
            return;
        }
        stops.addAll(route);
        arrival = finished;
        step();
    }

    public static void travel(Vec3 destination) {
        travel(destination, result -> { });
    }

    public static boolean travelling() {
        return !stops.isEmpty();
    }

    // Stops along the straight line there with the destination itself last. Null
    // when a stretch has nowhere clear to stop within one hop.
    private static List<Vec3> route(LocalPlayer player, Vec3 destination) {
        List<Vec3> route = new ArrayList<>();
        Vec3 from = player.position();
        while (from.distanceTo(destination) > REACH) {
            Vec3 stop = furthestStop(player, from, destination);
            if (stop == null) {
                return null;
            }
            route.add(stop);
            from = stop;
        }
        route.add(destination);
        return route;
    }

    private static Vec3 furthestStop(LocalPlayer player, Vec3 from, Vec3 to) {
        Vec3 way = to.subtract(from).normalize();
        for (double reach = PLAN_REACH; reach > 0; reach -= PLAN_STEP) {
            Vec3 stop = from.add(way.scale(reach));
            if (fits(player, stop)) {
                return stop;
            }
        }
        return null;
    }

    // Takes the next hop once the fall from the last one is wiped.
    private static void step() {
        if (owed != null || stops.isEmpty()) {
            return;
        }
        Result result = to(stops.peekFirst());
        if (result == Result.BUSY) {
            return;
        }
        if (result == Result.MOVED) {
            stops.pollFirst();
            if (!stops.isEmpty()) {
                return;
            }
        }
        stops.clear();
        arrival.accept(result);
    }

    private static Result to(Vec3 spot) {
        LocalPlayer player = OfflineClient.MC.player;
        if (player == null || !MoveGate.free()) {
            return Result.BUSY;
        }
        // The only way a planned hop runs long is the server moving the player.
        if (player.position().distanceTo(spot) > REACH) {
            return Result.PULLED_BACK;
        }
        if (!fits(player, spot)) {
            return Result.BLOCKED;
        }
        boolean up = spot.y > player.getY();
        boolean down = spot.y < player.getY();
        boolean ground = !down && owed == null && standingAt(player, spot);
        MoveGate.fillers(FILLERS, ground);
        if (!MoveGate.send(spot.x, spot.y, spot.z, ground)) {
            return Result.BUSY;
        }
        // A rise wipes whatever fall the server still held.
        if (up) {
            owed = null;
        } else if (down || owed != null) {
            owed = spot;
        }
        rising = false;
        player.setPos(spot);
        // The rest of the tick still runs the physics. Gravity between hops would
        // otherwise gather into a fall.
        player.setDeltaMovement(Vec3.ZERO);
        player.resetFallDistance();
        return Result.MOVED;
    }

    // True when the whole box has room at the spot.
    public static boolean fits(LocalPlayer player, Vec3 spot) {
        return player.level().noCollision(player, boxAt(player, spot));
    }

    private static boolean standingAt(LocalPlayer player, Vec3 spot) {
        return !player.level().noCollision(player, boxAt(player, spot).move(0, -GROUND_PROBE, 0));
    }

    private static AABB boxAt(LocalPlayer player, Vec3 spot) {
        return player.getBoundingBox().move(spot.subtract(player.position()));
    }

    @Subscribe
    private void onTick(TickEvent event) {
        settle();
        step();
    }

    // Waits for a tick with its position packet free. That packet holds the player a
    // hair above the landing and the one after lets them settle.
    private static void settle() {
        LocalPlayer player = OfflineClient.MC.player;
        // A respawn or a new dimension leaves the landing spot behind.
        if (owed == null || player == null || player.position().distanceTo(owed) > REACH) {
            owed = null;
            rising = false;
            return;
        }
        if (rising) {
            owed = null;
            rising = false;
            return;
        }
        if (!MoveGate.free()) {
            return;
        }
        player.setPos(owed.x, owed.y + WIPE_RISE, owed.z);
        player.setDeltaMovement(Vec3.ZERO);
        rising = true;
    }
}
