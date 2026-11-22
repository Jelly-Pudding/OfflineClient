package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

// Moves the player or the vehicle they steer by packet. HopPath finds a clear way. The
// server banks a drop as a fall and charges it once a packet claims ground. Nothing
// claims it whilst a fall is owed and a rise of a hair wipes it. A tick carries as many
// hops as the server allows and a long trip stops at clear spots on the way.
public enum Hop {
    INSTANCE;

    public static final int MAX_TRIP = 1000;

    // Stops are planned a block short of a full hop. Gravity pulls the player off a stop
    // in the air whilst the next hop waits its turn.
    private static final double SHORTFALL = 1;

    private static final double WIPE_RISE = 0.02;

    // A vehicle keeps every drop as a fall with no rise to wipe it. Each of its packets
    // claims ground and drops half a block less than the fall that starts to hurt.
    private static final double VEHICLE_DROP = DamageUtil.SAFE_FALL - 0.5;

    // The server kicks anyone floating for eighty ticks. A drop further than it lets
    // pass as floating starts the count again.
    private static final int FLOAT_TICKS = 60;
    private static final double DIP = 0.1;

    private static final int PATIENCE = 40;

    // A pull back this many ticks after a trip that moved up or down or through a block is
    // taken for a late refusal of its last packets.
    private static final int WATCH_TICKS = 20;

    public enum Result {
        MOVED(""),
        TOO_FAR("That is more than " + MAX_TRIP + " blocks away."),
        BLOCKED("That spot is inside a block."),
        NO_ROUTE("There is nowhere clear to stop on the way."),
        PULLED_BACK("The server pulled you back on the way."),
        BUSY("Try again in a moment."),
        NOT_STEERING("You cannot steer what you are riding."),
        ON_FOOT("Only a player on foot goes through a wall."),
        WALLED("This server pulls back every move through a wall."),
        NO_HOLD("Stand on a block or against one to go through a wall."),
        HELD_BACK("Another module is holding your movement back."),
        LEFT("You left the world before the trip ended.");

        private final String problem;

        Result(String problem) {
            this.problem = problem;
        }

        public String problem() {
            return problem;
        }
    }

    // A drop keeps its fall for the action after it. A landing claims the ground and the
    // fall is paid there and then. A wipe rises a hair to clear a fall. An action sends
    // nothing and runs straight after the packet before it. A lean tells the server a spot a
    // hair inside a block and the trip itself stays where it was.
    private enum Kind { MOVE, DROP, LAND, WIPE, ACT, LEAN }

    private record Stop(Kind kind, Vec3 spot, Runnable action) {
    }

    private record Burst(int fillers, int legs) {
    }

    private static final Burst NOTHING = new Burst(0, 0);

    private static final Lagback.Watcher lagback = Lagback.Watcher.withVehicles();
    private static Trip trip;
    // A trip that ended badly may leave the server holding a fall. The wipe waits a tick
    // for a pull back to land on the client.
    private static boolean wipeAfterPull;
    private static int watching;
    // The trip whose hop through a block the server has yet to answer and the ticks left
    // in which a pull back is that answer.
    private static Trip unanswered;
    private static int answerTicks;

    // A trip for whatever the player moves from where it stands. Nothing moves until go.
    public static Plan plan() {
        LocalPlayer player = OfflineClient.MC.player;
        if (player == null) {
            return new Plan(null, Vec3.ZERO, Result.BUSY);
        }
        Entity mover = mover(player);
        Result problem = null;
        if (player.isPassenger() && mover == player) {
            problem = Result.NOT_STEERING;
        } else if (MoveGate.held(mover != player)) {
            problem = Result.HELD_BACK;
        }
        return new Plan(mover, mover.position(), problem);
    }

    // Takes the player there by a clear way. The first hop goes on the next tick.
    public static void travel(Vec3 destination, Consumer<Result> finished) {
        plan().to(destination).go(finished);
    }

    public static boolean travelling() {
        return trip != null;
    }

    public static Entity mover(LocalPlayer player) {
        Entity root = player.getRootVehicle();
        return root != player && root.isLocalInstanceAuthoritative() ? root : player;
    }

    public static boolean fits(Entity mover, Vec3 spot) {
        return HopPath.fits(mover, spot);
    }

    public static Vec3 nearestFit(Entity mover, Vec3 spot) {
        return HopPath.nearestFit(mover, spot);
    }

    public static boolean safeAt(Entity mover, Vec3 spot) {
        return HopPath.landing(mover, spot);
    }

    public static AABB boxAt(Entity mover, Vec3 spot) {
        return HopPath.boxAt(mover, spot);
    }

    // True when the box the server carries from one spot to the next meets nothing. It
    // goes up or down first and then along the longer flat axis and then the other.
    public static boolean clearWay(Entity mover, Vec3 from, Vec3 to) {
        return HopPath.clearWay(mover, from, to);
    }

    public static Vec3 beyond(Entity mover, Vec3 way, double reach) {
        return HopPath.beyond(mover, mover.position(), way, reach);
    }

    public static Consumer<Result> telling(String done) {
        return result -> ClientLines.inChat(() -> {
            if (result == Result.MOVED) {
                ChatUtil.message(done);
            } else {
                ChatUtil.error(result.problem());
            }
        });
    }

    // Wipes the fall the server may still hold after a trip that went wrong. The rise goes
    // on the next tick that allows it. A spot with no room for the rise keeps its fall.
    private static void owe(Vec3 landing) {
        LocalPlayer player = OfflineClient.MC.player;
        if (player == null || !player.isAlive() || player.isPassenger()) {
            return;
        }
        Stop rise = wipeAt(landing);
        if (!HopPath.fits(player, rise.spot())) {
            return;
        }
        List<Stop> wipe = new ArrayList<>(List.of(rise));
        Trip wiping = new Trip(player, wipe, landing, result -> { });
        wiping.owed = true;
        wiping.wipesAfter = false;
        begin(wiping);
    }

    private static Stop wipeAt(Vec3 landing) {
        return new Stop(Kind.WIPE, landing.add(0, WIPE_RISE, 0), null);
    }

    // A trip laid out before it starts. The first problem met is what go reports.
    public static final class Plan {

        private final Entity mover;
        private final boolean vehicle;
        private final Vec3 start;
        private final double hop;
        private final List<Stop> stops = new ArrayList<>();
        private Vec3 end;
        private boolean fallKept;
        // Some hop goes through a block. Whether the server lets it stand is only known a
        // while after the trip.
        private boolean answeredLate;
        private Result problem;

        private Plan(Entity mover, Vec3 start, Result problem) {
            this.mover = mover;
            this.start = start;
            this.problem = problem;
            vehicle = mover != null && mover != OfflineClient.MC.player;
            end = start;
            hop = (vehicle ? MoveGate.vehicleReach() : MoveGate.reach()) - SHORTFALL;
        }

        // An empty plan for the same mover from another spot. It serves to count ticks
        // ahead and never goes.
        Plan fork(Vec3 from) {
            return new Plan(mover, from, problem);
        }

        // Runs the steps and keeps them only when every one went through. False leaves
        // the plan as it was.
        public boolean attempt(Consumer<Plan> steps) {
            if (problem != null) {
                return false;
            }
            int size = stops.size();
            Vec3 before = end;
            boolean kept = fallKept;
            boolean late = answeredLate;
            steps.accept(this);
            if (problem == null) {
                return true;
            }
            stops.subList(size, stops.size()).clear();
            end = before;
            fallKept = kept;
            answeredLate = late;
            problem = null;
            return false;
        }

        // There by a clear way. A wall on the way is climbed over.
        public Plan to(Vec3 spot) {
            if (problem != null) {
                return this;
            }
            wipeKeptFall();
            if (end.distanceTo(spot) > MAX_TRIP) {
                return fail(Result.TOO_FAR);
            }
            if (!HopPath.fits(mover, spot)) {
                return fail(Result.BLOCKED);
            }
            List<Vec3> route = HopPath.route(mover, end, spot, hop, vehicle ? VEHICLE_DROP : hop);
            if (route == null) {
                return fail(Result.NO_ROUTE);
            }
            for (int i = 0; i < route.size(); i++) {
                move(route.get(i), i + 1 < route.size() ? route.get(i + 1) : null);
            }
            return this;
        }

        // Straight there in one hop through whatever lies between. A vanilla server
        // forgives the way of a player it holds inside a block and one only touching a
        // block is leant into it first. Straight up or down every server lets through.
        // The trip only says it moved once the server has had time to pull the hop back.
        public Plan through(Vec3 spot) {
            if (problem != null) {
                return this;
            }
            if (vehicle) {
                return fail(Result.ON_FOOT);
            }
            wipeKeptFall();
            if (!HopPath.landing(mover, spot)) {
                return fail(Result.BLOCKED);
            }
            if (end.distanceTo(spot) > hop || end.y - spot.y > longestDrop()) {
                return fail(Result.NO_ROUTE);
            }
            boolean clear = HopPath.clearWay(mover, end, spot);
            if (!clear && !HopPath.upright(end, spot) && MoveGate.sweepChecked()) {
                if (!MoveGate.forgivesOverlap()) {
                    return fail(Result.WALLED);
                }
                Vec3 lean = HopPath.lean(mover, end);
                if (lean == null) {
                    return fail(Result.NO_HOLD);
                }
                if (!lean.equals(end)) {
                    stops.add(new Stop(Kind.LEAN, lean, null));
                }
            }
            answeredLate |= !clear;
            move(spot, null);
            return this;
        }

        // Straight down onto a spot below. The whole fall is kept for the action that
        // follows. A vehicle keeps no fall and drops the usual way.
        public Plan dropTo(Vec3 spot) {
            if (vehicle) {
                return to(spot);
            }
            if (problem != null) {
                return this;
            }
            if (!HopPath.upright(end, spot) || spot.y >= end.y) {
                return fail(Result.NO_ROUTE);
            }
            if (!HopPath.fits(mover, spot)) {
                return fail(Result.BLOCKED);
            }
            List<Vec3> route = HopPath.route(mover, end, spot, hop, hop);
            if (route == null) {
                return fail(Result.NO_ROUTE);
            }
            for (Vec3 stop : route) {
                stops.add(new Stop(Kind.DROP, stop, null));
            }
            end = spot;
            fallKept = true;
            return this;
        }

        // Straight down onto the ground below with the landing claimed. The server charges
        // the whole fall it holds since the last rise on that packet. A vehicle keeps no
        // fall and drops the usual way.
        public Plan landOn(Vec3 spot) {
            dropTo(spot);
            if (problem == null && !vehicle) {
                stops.add(new Stop(Kind.LAND, stops.removeLast().spot(), null));
                fallKept = false;
            }
            return this;
        }

        // One straight hop the server takes whole with a clear landing out of lava. Straight
        // up or down it may pass through a floor. A drop is kept for the action after it and
        // a shot fired then leaves with the whole hop as its speed.
        public Plan hopTo(Vec3 spot) {
            if (problem != null) {
                return this;
            }
            if (spot.y > end.y) {
                // The rise wipes a kept fall on its own.
                fallKept = false;
            } else {
                wipeKeptFall();
            }
            if (!HopPath.landing(mover, spot)) {
                return fail(Result.BLOCKED);
            }
            boolean straight = HopPath.upright(end, spot) || HopPath.clearWay(mover, end, spot);
            if (!straight || end.distanceTo(spot) > hop || end.y - spot.y > longestDrop()) {
                return fail(Result.NO_ROUTE);
            }
            stops.add(new Stop(Kind.DROP, spot, null));
            fallKept = !vehicle && spot.y < end.y;
            end = spot;
            return this;
        }

        public Result problem() {
            return problem;
        }

        public double longestHop() {
            return hop;
        }

        // The furthest one hop may drop. A vehicle takes every drop as a fall.
        public double longestDrop() {
            return vehicle ? VEHICLE_DROP : hop;
        }

        // Runs straight after the packet for the latest stop. A fall kept for it is wiped
        // once it has run.
        public Plan then(Runnable action) {
            if (problem == null) {
                stops.add(new Stop(Kind.ACT, end, action));
                wipeKeptFall();
            }
            return this;
        }

        // Ticks the trip takes as the server's allowance stands. Max value when a leg is
        // too long for any tick.
        public int ticks() {
            if (problem != null) {
                return 0;
            }
            Vec3 at = start;
            int next = 0;
            int ticks = 0;
            while ((next = pastActions(stops, next)) < stops.size()) {
                Burst burst = pack(at, stops, next, vehicle);
                if (burst.legs() == 0) {
                    return Integer.MAX_VALUE;
                }
                for (int legs = 0; legs < burst.legs(); next++) {
                    Stop stop = stops.get(next);
                    if (stop.kind() != Kind.ACT) {
                        at = stop.kind() == Kind.LEAN ? at : stop.spot();
                        legs++;
                    }
                }
                ticks++;
            }
            return ticks;
        }

        public void go(Consumer<Result> finished) {
            if (problem != null) {
                finished.accept(problem);
                return;
            }
            wipeKeptFall();
            Trip made = new Trip(mover, stops, start, finished);
            made.answeredLate = answeredLate;
            begin(made);
        }

        private Plan fail(Result result) {
            problem = result;
            return this;
        }

        // A drop is wiped straight after unless the next stop rises and wipes it anyway.
        // Coming back down from a wipe's own rise is no drop.
        private void move(Vec3 spot, Vec3 next) {
            boolean drops = spot.y < footing().y;
            stops.add(new Stop(Kind.MOVE, spot, null));
            end = spot;
            if (!vehicle && drops && (next == null || next.y <= spot.y)) {
                addWipe();
            }
        }

        private Vec3 footing() {
            for (int i = stops.size() - 1; i >= 0; i--) {
                Stop stop = stops.get(i);
                if (stop.kind() == Kind.MOVE || stop.kind() == Kind.DROP) {
                    return stop.spot();
                }
            }
            return start;
        }

        private void wipeKeptFall() {
            if (fallKept) {
                fallKept = false;
                addWipe();
            }
        }

        // A spot with no room for the rise keeps its fall.
        private void addWipe() {
            Stop wipe = wipeAt(end);
            if (HopPath.fits(mover, wipe.spot())) {
                stops.add(wipe);
                end = wipe.spot();
            }
        }
    }

    // A trip under way. The server holds the mover at the last stop sent.
    private static final class Trip {

        private final LocalPlayer player;
        private final Entity mover;
        private final boolean vehicle;
        private final List<Stop> stops;
        private final Consumer<Result> finished;
        private Vec3 at;
        private int next;
        private boolean owed;
        private boolean over;
        // Some leg went up or down and its refusal may come after the trip.
        private boolean vertical;
        // Some leg went through a block. The trip says how it went once the server has
        // had time to refuse that.
        private boolean answeredLate;
        // A trip that goes wrong owes a wipe after it. A wipe that goes wrong does not.
        private boolean wipesAfter = true;
        private int idle;
        private int sentTick = -1;

        // A player already falling may leave the server holding that fall.
        private Trip(Entity mover, List<Stop> stops, Vec3 at, Consumer<Result> finished) {
            this.mover = mover;
            this.stops = stops;
            this.at = at;
            this.finished = finished;
            player = OfflineClient.MC.player;
            vehicle = mover != player;
            owed = mover.fallDistance > 0;
        }

        private Stop upcoming() {
            return next < stops.size() ? stops.get(next) : null;
        }
    }

    // A pull back counts against the trip under way as the tick begins and against a hop
    // still waiting for its answer. A trip that begins further down has it behind it.
    @Subscribe
    private void onTick(TickEvent event) {
        boolean pulled = lagback.happened();
        Trip underWay = trip;
        if (trip == null && watching > 0) {
            watching--;
            wipeAfterPull |= pulled;
        }
        if (unanswered != null && (pulled || --answerTicks <= 0)) {
            answer(pulled ? Result.PULLED_BACK : Result.MOVED);
        }
        LocalPlayer player = OfflineClient.MC.player;
        if (wipeAfterPull && trip == null && player != null) {
            wipeAfterPull = false;
            watching = 0;
            owe(player.position());
        }
        if (trip != null) {
            advance(trip, pulled && trip == underWay);
        }
    }

    // A trip ends once its player is gone. Nothing it held back belongs to the next one.
    @Subscribe
    private void onClientTick(ClientTickEvent event) {
        LocalPlayer player = OfflineClient.MC.player;
        if (trip != null && trip.player != player) {
            finish(trip, Result.LEFT);
        }
        if (unanswered != null && unanswered.player != player) {
            answer(Result.LEFT);
        }
        if (player == null) {
            wipeAfterPull = false;
            watching = 0;
        }
    }

    // A new trip replaces one still under way and the old one hears it was cut short. A
    // fall the old one left unwiped is still owed. Hops only go from the tick handler a
    // full tick after the client's own last packet.
    private static void begin(Trip next) {
        // A pull back nobody has read yet is none of the next trip's. It answers a hop
        // that still waits.
        if (lagback.happened()) {
            answer(Result.PULLED_BACK);
        }
        Trip old = trip;
        trip = next;
        if (old != null) {
            next.owed |= old.owed && old.mover == next.mover;
            old.over = true;
            old.finished.accept(Result.BUSY);
        }
    }

    private static void advance(Trip current, boolean pulled) {
        Result problem = problem(current, pulled);
        if (problem != null) {
            finish(current, problem);
            return;
        }
        runActions(current);
        if (current.over) {
            return;
        }
        if (current.upcoming() == null) {
            finish(current, Result.MOVED);
            return;
        }
        // One burst a tick. Fillers only count in full ahead of the tick's first move.
        if (current.sentTick == MoveGate.tick()) {
            return;
        }
        if (!current.vehicle && !MoveGate.free()) {
            idle(current);
            return;
        }
        // A burst straight after the last position would share its server tick.
        if (!MoveGate.settled()) {
            place(current);
            return;
        }
        if (MoveGate.floatingTicks(current.vehicle) >= FLOAT_TICKS && dip(current)) {
            return;
        }
        Burst burst = pack(current.at, current.stops, current.next, current.vehicle);
        if (burst.legs() == 0) {
            idle(current);
            return;
        }
        current.sentTick = MoveGate.tick();
        if (!sendBurst(current, burst)) {
            finish(current, Result.HELD_BACK);
            return;
        }
        if (current.over) {
            return;
        }
        current.idle = 0;
        place(current);
        if (current.upcoming() == null) {
            finish(current, Result.MOVED);
        }
    }

    private static Result problem(Trip current, boolean pulled) {
        LocalPlayer player = OfflineClient.MC.player;
        if (player != current.player) {
            return Result.LEFT;
        }
        if (!player.isAlive() || mover(player) != current.mover) {
            return Result.BUSY;
        }
        if (MoveGate.held(current.vehicle)) {
            return Result.HELD_BACK;
        }
        return pulled ? Result.PULLED_BACK : null;
    }

    // A trip that went wrong may leave the server holding a fall. A pull back and a trip
    // that gave up owe a wipe. One held back would be held back as well. A trip that got
    // there listens a while for a pull back that refused its last packets. One that went
    // through a block keeps its word back until then.
    private static void finish(Trip current, Result result) {
        current.over = true;
        if (trip == current) {
            trip = null;
        }
        if (result == Result.LEFT) {
            wipeAfterPull = false;
            watching = 0;
        } else if (current.wipesAfter) {
            switch (result) {
                case PULLED_BACK -> wipeAfterPull = true;
                case BUSY -> wipeAfterPull |= current.owed;
                case MOVED -> watching = current.vertical && !current.vehicle ? WATCH_TICKS : 0;
                default -> { }
            }
        }
        if (result == Result.MOVED && current.answeredLate) {
            // A hop still waiting from an earlier trip is taken to have stood.
            answer(Result.MOVED);
            unanswered = current;
            answerTicks = WATCH_TICKS;
        } else {
            current.finished.accept(result);
        }
    }

    private static void answer(Result result) {
        Trip waiting = unanswered;
        unanswered = null;
        if (waiting != null) {
            waiting.finished.accept(result);
        }
    }

    private static void runActions(Trip current) {
        Stop stop;
        while (!current.over && (stop = current.upcoming()) != null && stop.kind() == Kind.ACT) {
            current.next++;
            stop.action().run();
        }
    }

    private static boolean sendBurst(Trip current, Burst burst) {
        if (current.vehicle) {
            MoveGate.vehicleFillers(current.mover, burst.fillers());
            return sendLegs(current, burst);
        }
        // The server holds a sneaking player it thinks is on the ground back from any edge
        // a level or falling move crosses. Such a burst goes with the sneak key up.
        boolean stand = MoveGate.crouching() && crossesLevel(current, burst);
        if (stand && !MoveGate.standUp()) {
            return false;
        }
        MoveGate.fillers(burst.fillers(), false);
        boolean sent = sendLegs(current, burst);
        if (stand) {
            MoveGate.resumeInput();
        }
        return sent;
    }

    private static boolean sendLegs(Trip current, Burst burst) {
        for (int leg = 0; leg < burst.legs() && !current.over; leg++) {
            if (!sendLeg(current, current.upcoming(), leg > 0)) {
                return false;
            }
            current.next++;
            runActions(current);
        }
        return true;
    }

    private static boolean crossesLevel(Trip current, Burst burst) {
        Vec3 from = current.at;
        int legs = 0;
        for (int i = current.next; i < current.stops.size() && legs < burst.legs(); i++) {
            Stop stop = current.stops.get(i);
            if (stop.kind() == Kind.ACT) {
                continue;
            }
            if (stop.kind() == Kind.LEAN) {
                legs++;
                continue;
            }
            Vec3 way = stop.spot().subtract(from);
            if (way.y <= 0 && way.horizontalDistanceSqr() > 0) {
                return true;
            }
            from = stop.spot();
            legs++;
        }
        return false;
    }

    private static boolean sendLeg(Trip current, Stop stop, boolean afterAnother) {
        boolean sent;
        if (current.vehicle) {
            sent = MoveGate.sendVehicle(current.mover, stop.spot(), true);
        } else {
            if (afterAnother) {
                MoveGate.endTick();
            }
            sent = MoveGate.send(stop.spot(), claimsGround(current, stop));
        }
        if (sent && stop.kind() != Kind.LEAN) {
            arrive(current, stop.spot());
            // The server holds no fall once a landing has paid it.
            current.owed &= stop.kind() != Kind.LAND;
        }
        return sent;
    }

    // A landing always claims it. Otherwise only a level move onto the ground with no fall
    // owed claims it. The server charges a fall it holds before a rise gets to wipe it. A
    // lean claims the ground the mover stands on already.
    private static boolean claimsGround(Trip current, Stop stop) {
        if (stop.kind() == Kind.LAND) {
            return true;
        }
        boolean lean = stop.kind() == Kind.LEAN;
        boolean level = lean || stop.kind() == Kind.MOVE && stop.spot().y == current.at.y;
        return level && !current.owed && HopPath.standingAt(current.mover, lean ? current.at : stop.spot());
    }

    private static void arrive(Trip current, Vec3 spot) {
        double drop = current.at.y - spot.y;
        if (drop > 0) {
            current.owed = !current.vehicle;
        } else if (drop < 0) {
            current.owed = false;
        }
        current.vertical |= drop != 0;
        current.at = spot;
    }

    // Drops a little on its own to start the server's count of floating ticks again.
    private static boolean dip(Trip current) {
        Vec3 spot = current.at.subtract(0, DIP, 0);
        if (!HopPath.fits(current.mover, spot)) {
            return false;
        }
        current.sentTick = MoveGate.tick();
        boolean sent = current.vehicle ? MoveGate.sendVehicle(current.mover, spot, true)
            : MoveGate.send(spot, false);
        if (!sent) {
            return false;
        }
        arrive(current, spot);
        place(current);
        return true;
    }

    private static void idle(Trip current) {
        if (++current.idle > PATIENCE) {
            finish(current, Result.BUSY);
            return;
        }
        place(current);
    }

    // Keeps the client where the server holds the mover. The rest of the tick still runs
    // the physics and gravity between hops would gather into a fall. A wipe the next tick
    // owes is where the client waits in case its own packet has to carry it.
    private static void place(Trip current) {
        Stop upcoming = current.upcoming();
        Vec3 spot = upcoming != null && upcoming.kind() == Kind.WIPE ? upcoming.spot() : current.at;
        current.mover.setPos(spot);
        current.mover.setDeltaMovement(Vec3.ZERO);
        current.mover.resetFallDistance();
        LocalPlayer player = OfflineClient.MC.player;
        if (current.vehicle && player != null) {
            current.mover.positionRider(player);
            player.resetFallDistance();
        }
    }

    // The fillers and the number of stops one tick carries from a spot. As many stops
    // as the allowance lets through on the fewest fillers.
    private static Burst pack(Vec3 at, List<Stop> stops, int from, boolean vehicle) {
        int most = vehicle ? MoveGate.maxVehicleFillers() : MoveGate.maxFillers();
        Burst best = NOTHING;
        for (int fillers = 0; fillers <= most; fillers++) {
            int legs = legs(at, stops, from, fillers, vehicle);
            if (legs > best.legs()) {
                best = new Burst(fillers, legs);
            }
        }
        return best;
    }

    // A vehicle has no rule of one position a tick. Bursts split the player's positions
    // with tick end packets.
    private static int legs(Vec3 at, List<Stop> stops, int from, int fillers, boolean vehicle) {
        int limit = vehicle || MoveGate.bursts() ? Integer.MAX_VALUE : 1;
        Vec3 last = at;
        int legs = 0;
        for (int i = from; i < stops.size() && legs < limit; i++) {
            Stop stop = stops.get(i);
            if (stop.kind() == Kind.ACT) {
                continue;
            }
            double reach = vehicle ? MoveGate.vehicleReach(fillers, legs + 1)
                : MoveGate.reach(fillers, legs + 1);
            if (stop.spot().distanceTo(at) > reach || stop.spot().distanceTo(last) > reach) {
                break;
            }
            last = stop.spot();
            legs++;
        }
        return legs;
    }

    private static int pastActions(List<Stop> stops, int from) {
        int index = from;
        while (index < stops.size() && stops.get(index).kind() == Kind.ACT) {
            index++;
        }
        return index;
    }
}
