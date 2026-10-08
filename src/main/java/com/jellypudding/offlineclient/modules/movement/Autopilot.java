package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.config.FindLog;
import com.jellypudding.offlineclient.config.WaypointStore;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.EntityDamageEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.modules.hunting.NewChunks;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.HeldKey;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.Lagback;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.MovementUtil;
import com.jellypudding.offlineclient.util.RotationManager;
import com.jellypudding.offlineclient.util.WorldWatch;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

// Flies a route one stop at a time. Flight or ElytraFly does the moving whilst this turns
// your view towards the next stop and holds the keys.
public final class Autopilot extends Module {

    public enum Route { POINT, WAYPOINTS, GRID, TRAIL }

    public enum Engine { FLIGHT, ELYTRA }

    public enum Ending { HOVER, LAND, LEAVE }

    // What the autopilot does this tick.
    private enum Phase { FLYING, HOLDING, LANDING }

    // How the leg towards one stop is going. It stays ARRIVED or FAILED until the next leg.
    private enum Leg { IDLE, FLYING, ARRIVED, FAILED }

    // A place on the route. Only a waypoint has a name.
    private record Stop(String name, double x, double z) {
    }

    private static final int CHUNK = SectionPos.SECTION_SIZE;

    // The most degrees the view turns in one tick.
    private static final float TURN_STEP = 10;
    // Forward is held only whilst the view points this close to the way.
    private static final float FORWARD_ANGLE = 45;
    // Jump or sneak is held only whilst the height is off by more than this.
    private static final double HEIGHT_BAND = 1;
    // Further below the height than this you climb before going on.
    private static final double CLIMB_FIRST = 8;
    // How far ahead the land is read for hills and the chunks for loading.
    private static final double LOOK_AHEAD = 48;
    private static final double LOOK_STEP = 8;
    // Blocks kept between you and land that rises over the cruise height.
    private static final double CLEARANCE = 6;
    // Ticks of holding forward without a block of headway before you climb over what is in the way.
    private static final int STUCK_TICKS = 60;
    private static final double STUCK_LIFT = 8;
    // The climb is let go once you have come this far past what stopped you.
    private static final double LIFT_KEEP = 64;
    // A leg that makes no headway for this long gives up.
    private static final int GIVE_UP_TICKS = 600;
    // This many pull backs close together end the flight.
    private static final int PULLBACKS = 5;
    private static final int PULLBACK_TICKS = 200;
    // Wings circle a held spot this far out.
    private static final double CIRCLE = 24;
    // ElytraFly's Chest swap gets this many ticks to put the elytra on.
    private static final int WEAR_WAIT = 20;
    // Why wings come down before the elytra gives out.
    private static final String WORN_ELYTRA = "your elytra is nearly worn out";
    // A stop this close below you cannot be steered towards.
    private static final double OVERHEAD = 0.5;
    // The trail is read again every few ticks and may bend this far either way between reads.
    private static final int TRAIL_READ_TICKS = 10;
    private static final float TRAIL_TURN = 60;
    private static final float TRAIL_STEP = 5;
    // Chunks this close are left out of a read. They say nothing about the way ahead.
    private static final int TRAIL_NEAR = 2;
    // A new chunk counts against a way half as much as an old one counts for it.
    private static final double NEW_WEIGHT = 0.5;
    // How quickly the speed behind the time left follows the real speed.
    private static final double PACE_EASE = 0.05;
    // Slower than this in blocks a second the time left is not shown.
    private static final double SLOWEST_PACE = 1;
    private static final long MILLIS_PER_SECOND = 1000;

    private final EnumSetting<Route> route = new EnumSetting<>("Route",
        "Where the flight goes.", Route.POINT)
        .describe(Route.POINT, "Straight to the spot you give the autopilot command.")
        .describe(Route.WAYPOINTS, "Visits the waypoints of this dimension and always flies to the nearest one left.")
        .describe(Route.GRID, "Sweeps a square of chunks row by row.")
        .describe(Route.TRAIL, "Follows the old chunks ahead of you through new land to where they lead.");
    private final EnumSetting<Engine> engine = new EnumSetting<>("Engine",
        "What carries you along.", Engine.FLIGHT)
        .describe(Engine.FLIGHT, "Flight carries you at the cruise height.")
        .describe(Engine.ELYTRA, "ElytraFly carries you on your wings in the mode it is set to.");
    private final NumberSetting cruiseHeight = new NumberSetting("Cruise height",
        "The height held between points. Land that rises higher lifts you over it.", 200, -64, 320, 1);
    private final NumberSetting arriveWithin = new NumberSetting("Arrive within",
        "How close to a point counts as reaching it.", 8, 1, 64, 1, " blocks")
        .under(route, Route.POINT, Route.WAYPOINTS, Route.GRID);
    private final NumberSetting gridSize = new NumberSetting("Grid size",
        "Chunks along each side of the square a grid sweep covers.", 32, 2, 128, 1, " chunks")
        .min(1).under(route, Route.GRID);
    private final NumberSetting rowGap = new NumberSetting("Row gap",
        "Chunks between one row of the sweep and the next.", 8, 1, 32, 1, " chunks")
        .under(route, Route.GRID);
    private final BoolSetting stopOnFinds = new BoolSetting("Stop on finds",
        "Stops when a Hunting module finds something new.", false);
    private final BoolSetting stopNearPlayers = new BoolSetting("Stop near players",
        "Stops when a player who is not your friend comes into view.", true);
    private final BoolSetting stopWhenHurt = new BoolSetting("Stop when hurt",
        "Stops the moment you take damage.", true);
    private final EnumSetting<Ending> atEnd = new EnumSetting<>("At the end",
        "What happens once the route is done or a stop ends it.", Ending.HOVER)
        .describe(Ending.HOVER, "Waits in the air where it stopped. "
            + "On wings it circles the spot until the wings start to fail and then lands or logs you out.")
        .describe(Ending.LAND, "Comes down onto safe ground below. On wings it circles the spot on the way down.")
        .describe(Ending.LEAVE, "Logs you out of the server.");

    private final HeldKey forward = new HeldKey(options -> options.keyUp);
    private final HeldKey jump = new HeldKey(options -> options.keyJump);
    private final HeldKey sneak = new HeldKey(options -> options.keyShift);
    private final Lagback.Watcher lagback = new Lagback.Watcher();
    private final WorldWatch world = new WorldWatch();

    // The stops still to come after the one flown to now.
    private final List<Stop> stops = new ArrayList<>();
    private Stop current;
    private Leg leg = Leg.IDLE;
    private Vec3 legFrom;
    private Phase phase = Phase.FLYING;
    // The spot the autopilot command gave. A plain switch on flies there again.
    private Stop spot;
    // Where holding or landing happens and the height held there.
    private Vec3 heldSpot;
    private double heldHeight;
    private boolean engineWasOn;

    // Where the last block of headway was made and the ticks of pushing since.
    private Vec3 headway;
    private int stuckTicks;
    // The extra height climbed over something in the way and where the climb began.
    private double lift;
    private Vec3 liftedAt;
    // The height a climb last gained a block at and the ticks climbed since. A climb that
    // stalls under a roof is given up for the rest of the leg.
    private double climbedTo;
    private int climbTicks;
    private boolean climbBlocked;
    // Ticks flown on wings without a glider worn.
    private int unwornTicks;

    private float trailYaw;
    private int trailWait;

    private double pace;
    private int findsSeen;
    private boolean hurt;
    private int ticks;
    private final Deque<Integer> pullbacks = new ArrayDeque<>();
    // The players in view on the last tick who are not friends.
    private Set<UUID> strangers = new HashSet<>();
    private boolean trailFound;

    public Autopilot() {
        super("Autopilot", "Flies you to a spot or along a route whilst you do something else.",
            Category.MOVEMENT);
        addSettings(route, engine, cruiseHeight, arriveWithin, gridSize, rowGap, stopOnFinds, stopNearPlayers,
            stopWhenHurt, atEnd);
        searchTags("auto fly", "travel", "route", "waypoints", "trail", "grid", "afk");
    }

    // A flight belongs to the session it started in.
    @Override
    public boolean savesEnabledState() {
        return false;
    }

    @Override
    public String getSuffix() {
        return switch (phase) {
            case FLYING -> route.getValueString();
            case HOLDING -> "circling";
            case LANDING -> "landing";
        };
    }

    // Flies straight to the spot. A flight already going gives way to it.
    public void flyTo(double x, double z, String name) {
        spot = new Stop(name, x, z);
        route.setValue(Route.POINT);
        setEnabled(false);
        setEnabled(true);
    }

    // The distance and the time left for the Info bar. Null whilst nothing is flying.
    public String left() {
        if (!inGame()) {
            return null;
        }
        return switch (phase) {
            case HOLDING -> "circling";
            case LANDING -> "landing";
            case FLYING -> route.is(Route.TRAIL) ? "on a trail" : distanceLeft();
        };
    }

    @Override
    protected void onEnable() {
        if (!inGame()) {
            disable("Join a world before you start the autopilot.");
            return;
        }
        if (engine.is(Engine.ELYTRA) && !MovementUtil.wearsGlider()
            && !Modules.get(ElytraFly.class).swapsGliderOn()) {
            disable("Put an elytra on to fly on your wings.");
            return;
        }
        if (refusesWornElytra()) {
            return;
        }
        if (!plan()) {
            return;
        }
        Module carrier = engineModule();
        engineWasOn = carrier.isEnabled();
        carrier.setEnabled(true);
        phase = Phase.FLYING;
        world.accept();
        lagback.sync();
        pullbacks.clear();
        findsSeen = FindLog.newFinds();
        strangers = new HashSet<>();
        newStranger();
        hurt = false;
        pace = 0;
        unwornTicks = 0;
        if (route.is(Route.TRAIL)) {
            current = null;
            trailYaw = mc.player.getYRot();
            trailWait = 0;
            trailFound = false;
            startLeg();
        } else {
            nextLeg();
        }
        ChatUtil.message("§bAutopilot §7" + startWords());
    }

    @Override
    protected void onDisable() {
        letGo();
        current = null;
        stops.clear();
        leg = Leg.IDLE;
        phase = Phase.FLYING;
    }

    private Module engineModule() {
        return engine.is(Engine.FLIGHT) ? Modules.get(Flight.class) : Modules.get(ElytraFly.class);
    }

    // ElytraFly whilst it is the engine. Null for Flight.
    private ElytraFly wings() {
        return engine.is(Engine.ELYTRA) ? Modules.get(ElytraFly.class) : null;
    }

    // Wings never set off on an elytra that is nearly worn out. True after telling the player.
    private boolean refusesWornElytra() {
        ElytraFly wings = wings();
        if (wings == null || !wings.wearingOut()) {
            return false;
        }
        disable("Your elytra is nearly worn out.");
        return true;
    }

    // Lays out the stops of the route. False after telling the player why it cannot start.
    private boolean plan() {
        stops.clear();
        switch (route.getValue()) {
            case POINT -> {
                if (spot == null) {
                    disable("Give the autopilot a spot with " + prefix() + "autopilot x z.");
                    return false;
                }
                stops.add(spot);
            }
            case WAYPOINTS -> {
                planWaypoints();
                if (stops.isEmpty()) {
                    disable("There are no waypoints in this dimension to visit.");
                    return false;
                }
            }
            case GRID -> planGrid();
            case TRAIL -> {
                if (!Modules.enabled(NewChunks.class)) {
                    disable("Switch NewChunks on first. The trail is read from the chunks it judges.");
                    return false;
                }
            }
        }
        return true;
    }

    private static String prefix() {
        return OfflineClient.INSTANCE.getCommandManager().getPrefix();
    }

    // Each visit goes to the nearest waypoint not yet visited. Hidden waypoints are left out.
    private void planWaypoints() {
        List<WaypointStore.Waypoint> left = new ArrayList<>(WaypointStore.get().here());
        left.removeIf(WaypointStore.Waypoint::hidden);
        double x = mc.player.getX();
        double z = mc.player.getZ();
        while (!left.isEmpty()) {
            WaypointStore.Waypoint nearest = left.getFirst();
            double best = Double.MAX_VALUE;
            for (WaypointStore.Waypoint waypoint : left) {
                Vec3 centre = waypoint.centre();
                double distance = flat(x, z, centre.x, centre.z);
                if (distance < best) {
                    best = distance;
                    nearest = waypoint;
                }
            }
            left.remove(nearest);
            Vec3 centre = nearest.centre();
            x = centre.x;
            z = centre.z;
            stops.add(new Stop(nearest.name(), x, z));
        }
    }

    // A square of chunks round where you are swept west to east and back again row by row.
    private void planGrid() {
        int size = gridSize.getInt();
        int gap = Math.max(1, rowGap.getInt());
        int west = SectionPos.blockToSectionCoord(mc.player.getBlockX()) - size / 2;
        int north = SectionPos.blockToSectionCoord(mc.player.getBlockZ()) - size / 2;
        double westX = SectionPos.sectionToBlockCoord(west, SectionPos.SECTION_HALF_SIZE);
        double eastX = SectionPos.sectionToBlockCoord(west + size - 1, SectionPos.SECTION_HALF_SIZE);
        boolean eastward = true;
        for (int row = north; row < north + size; row += gap) {
            double z = SectionPos.sectionToBlockCoord(row, SectionPos.SECTION_HALF_SIZE);
            stops.add(new Stop(null, eastward ? westX : eastX, z));
            stops.add(new Stop(null, eastward ? eastX : westX, z));
            eastward = !eastward;
        }
    }

    private String startWords() {
        return switch (route.getValue()) {
            case POINT -> "flying to §f" + named(current) + "§7.";
            case WAYPOINTS -> "visiting §f" + (stops.size() + 1) + "§7 waypoints.";
            case GRID -> "sweeping §f" + gridSize.getInt() + "§7 by §f" + gridSize.getInt() + "§7 chunks.";
            case TRAIL -> "following the trail ahead.";
        };
    }

    private static String named(Stop stop) {
        return stop.name() != null ? stop.name() : Math.round(stop.x()) + " " + Math.round(stop.z());
    }

    // Takes the next stop. False once none is left.
    private boolean nextLeg() {
        if (stops.isEmpty()) {
            current = null;
            leg = Leg.IDLE;
            return false;
        }
        current = stops.removeFirst();
        startLeg();
        return true;
    }

    // A fresh leg has made no headway and climbed over nothing yet.
    private void startLeg() {
        leg = Leg.FLYING;
        legFrom = mc.player.position();
        headway = null;
        stuckTicks = 0;
        lift = 0;
        liftedAt = null;
        climbedTo = mc.player.getY();
        climbTicks = 0;
        climbBlocked = false;
    }

    @Subscribe
    private void onDamage(EntityDamageEvent event) {
        if (event.getEntity() == mc.player) {
            hurt = true;
        }
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            return;
        }
        ticks++;
        if (world.changed() || mc.player.isDeadOrDying()) {
            ChatUtil.message("§bAutopilot §7stopped.");
            setEnabled(false);
            return;
        }
        if (!engineModule().isEnabled()) {
            ChatUtil.message("§bAutopilot §7stopped as §f" + engineModule().getName() + "§7 was switched off.");
            setEnabled(false);
            return;
        }
        if (wings() != null && !MovementUtil.wearsGlider()) {
            letGo();
            if (++unwornTicks > WEAR_WAIT) {
                disable("Put an elytra on to fly on your wings.");
            }
            return;
        }
        // An elytra put on after the start is checked as one worn at the start is.
        if (unwornTicks > 0 && refusesWornElytra()) {
            return;
        }
        unwornTicks = 0;
        pace += (EntityUtil.ownSpeed() - pace) * PACE_EASE;
        if (phase == Phase.FLYING) {
            String reason = stopReason();
            if (reason != null) {
                end("stopped as " + reason + ".");
                return;
            }
        }
        switch (phase) {
            case FLYING -> fly();
            case HOLDING -> hold();
            case LANDING -> land();
        }
    }

    // Why the flight should stop now or null to fly on.
    private String stopReason() {
        boolean wasHurt = hurt;
        hurt = false;
        int finds = FindLog.newFinds();
        boolean found = finds != findsSeen;
        findsSeen = finds;
        if (lagback.happened()) {
            pullbacks.addLast(ticks);
        }
        while (!pullbacks.isEmpty() && ticks - pullbacks.peekFirst() > PULLBACK_TICKS) {
            pullbacks.removeFirst();
        }
        if (pullbacks.size() >= PULLBACKS) {
            pullbacks.clear();
            return "the server kept pulling you back";
        }
        ElytraFly wings = wings();
        if (wings != null && wings.wearingOut()) {
            return WORN_ELYTRA;
        }
        if (stopWhenHurt.isOn() && wasHurt) {
            return "you took damage";
        }
        if (stopOnFinds.isOn() && found) {
            return "a Hunting module found something new";
        }
        Player stranger = newStranger();
        if (stopNearPlayers.isOn() && stranger != null) {
            return EntityUtil.displayNameOf(stranger) + " came into view";
        }
        return null;
    }

    // A player who is not your friend and was not in view on the last tick. Anyone in view
    // as the flight starts never counts until they leave and come back.
    private Player newStranger() {
        Set<UUID> now = new HashSet<>();
        Player fresh = null;
        for (Player other : mc.level.players()) {
            if (!EntityUtil.isOtherPlayer(other) || !EntityUtil.isEnemy(other)) {
                continue;
            }
            now.add(other.getUUID());
            if (!strangers.contains(other.getUUID())) {
                fresh = other;
            }
        }
        strangers = now;
        return fresh;
    }

    private void fly() {
        if (route.is(Route.TRAIL) && !readTrail()) {
            end(!Modules.enabled(NewChunks.class) ? "stopped as NewChunks was switched off."
                : trailFound ? "reached the end of the trail." : "found no trail of old chunks ahead.");
            return;
        }
        switch (flyLeg()) {
            case ARRIVED -> {
                if (current.name() != null) {
                    ChatUtil.message("§bAutopilot §7reached §f" + current.name() + "§7.");
                }
                if (!nextLeg()) {
                    end("finished the route.");
                }
            }
            case FAILED -> {
                String stuckAt = "could not get past what blocks the way to §f" + named(current) + "§7";
                if (route.isAny(Route.POINT, Route.TRAIL) || !nextLeg()) {
                    end(stuckAt + ".");
                } else {
                    ChatUtil.message("§bAutopilot §7" + stuckAt + " and flies on to the next stop.");
                }
            }
            case IDLE -> end("finished the route.");
            case FLYING -> {
            }
        }
    }

    // One tick of the leg. Passing the stop counts as reaching it.
    private Leg flyLeg() {
        if (leg != Leg.FLYING) {
            return leg;
        }
        Vec3 here = mc.player.position();
        if (!route.is(Route.TRAIL)) {
            double ahead = (current.x() - here.x) * (current.x() - legFrom.x)
                + (current.z() - here.z) * (current.z() - legFrom.z);
            if (flat(here.x, here.z, current.x(), current.z()) <= arriveWithin.getValue() || ahead < 0) {
                return leg = Leg.ARRIVED;
            }
        }
        boolean pushing = steer(current.x(), current.z(), travelHeight(), true);
        if (stuck(pushing)) {
            leg = Leg.FAILED;
        }
        return leg;
    }

    // Ends the route the way At the end says. The reason goes in chat first.
    private void end(String reason) {
        letGo();
        ChatUtil.message("§bAutopilot §7" + reason);
        heldSpot = mc.player.position();
        heldHeight = mc.player.getY();
        switch (atEnd.getValue()) {
            case HOVER -> hover();
            case LAND -> {
                if (safeGround()) {
                    phase = Phase.LANDING;
                } else {
                    ChatUtil.message("§bAutopilot §7found nowhere safe to land below and waits in the air.");
                    hover();
                }
            }
            case LEAVE -> leave(reason);
        }
    }

    // Flight keeps you where you are once the autopilot lets go. Wings have to circle the spot.
    private void hover() {
        if (wings() == null) {
            setEnabled(false);
        } else {
            phase = Phase.HOLDING;
        }
    }

    private void leave(String reason) {
        boolean stopped = Modules.stopReconnecting();
        setEnabled(false);
        ChatUtil.leaveServer("Autopilot " + reason + (stopped ? "\n§7AutoReconnect was turned off." : ""));
    }

    // Wings circle the spot at the height they stopped at until they start to fail.
    private void hold() {
        if (wingsFail()) {
            return;
        }
        Vec3 aim = circlePoint(heldSpot);
        steer(aim.x, aim.z, heldHeight, true);
    }

    // True once the wings wear thin or sink well under the held height. A hold over safe ground
    // turns into a landing. Otherwise the autopilot logs you out.
    private boolean wingsFail() {
        ElytraFly wings = wings();
        if (wings == null) {
            return false;
        }
        String why = wings.wearingOut() ? WORN_ELYTRA
            : mc.player.getY() < heldHeight - CLIMB_FIRST ? "your wings cannot hold the height" : null;
        if (why == null) {
            return false;
        }
        if (phase == Phase.HOLDING && safeGround()) {
            ChatUtil.message("§bAutopilot §7comes down as " + why + ".");
            heldHeight = mc.player.getY();
            phase = Phase.LANDING;
            return true;
        }
        String reason = "logged you out as " + why + " over unsafe ground.";
        ChatUtil.message("§bAutopilot §7" + reason);
        leave(reason);
        return true;
    }

    // A spot ahead on a ring round the centre. Aiming at it each tick flies you round the ring.
    private Vec3 circlePoint(Vec3 centre) {
        Vec3 out = new Vec3(mc.player.getX() - centre.x, 0, mc.player.getZ() - centre.z);
        Vec3 radial = out.lengthSqr() < OVERHEAD * OVERHEAD ? new Vec3(1, 0, 0) : out.normalize();
        Vec3 along = new Vec3(-radial.z, 0, radial.x);
        return centre.add(radial.scale(CIRCLE)).add(along.scale(CIRCLE));
    }

    // Flight comes straight down. Wings circle the spot and sink only whilst the ground under
    // them is safe. The engine is let go on the ground when the autopilot switched it on.
    private void land() {
        if (mc.player.onGround() || mc.player.isInWater()) {
            letGo();
            if (!engineWasOn) {
                engineModule().setEnabled(false);
            }
            ChatUtil.message("§bAutopilot §7landed.");
            setEnabled(false);
            return;
        }
        if (wings() == null) {
            steer(heldSpot.x, heldSpot.z, mc.level.getMinY(), false);
            return;
        }
        // Over unsafe ground the wings hold the height they last had over safe ground.
        boolean safe = safeGround();
        if (safe) {
            heldHeight = mc.player.getY();
        } else if (wingsFail()) {
            return;
        }
        Vec3 aim = circlePoint(heldSpot);
        steer(aim.x, aim.z, safe ? mc.level.getMinY() : heldHeight, true);
    }

    // True when solid ground with no lava or fire on it lies somewhere below you. Fire and berry
    // bushes have no collision and are found in the block over the ground.
    private boolean safeGround() {
        Vec3 feet = mc.player.position();
        BlockHitResult hit = mc.level.clip(new ClipContext(feet, new Vec3(feet.x, mc.level.getMinY(), feet.z),
            ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, mc.player));
        if (hit.getType() == HitResult.Type.MISS) {
            return false;
        }
        BlockState ground = mc.level.getBlockState(hit.getBlockPos());
        return !ground.getFluidState().is(FluidTags.LAVA) && !BlockUtil.harmful(ground)
            && !BlockUtil.harmful(mc.level.getBlockState(hit.getBlockPos().above()));
    }

    // Turns towards the spot and holds the keys that take you there at the height. True
    // whilst forward is held.
    private boolean steer(double x, double z, double height, boolean push) {
        LocalPlayer player = mc.player;
        boolean overhead = flat(player.getX(), player.getZ(), x, z) < OVERHEAD;
        float wanted = overhead ? player.getYRot() : RotationManager.yawTo(new Vec3(x, player.getEyeY(), z));
        // The view is left alone whilst nothing pulls you along. You can look round as you wait.
        if (push) {
            float turn = Math.clamp(Mth.wrapDegrees(wanted - player.getYRot()), -TURN_STEP, TURN_STEP);
            player.turn(turn / InputUtil.MOUSE_TURN, 0);
        }
        boolean aimed = push && !overhead && Math.abs(Mth.wrapDegrees(wanted - player.getYRot())) < FORWARD_ANGLE;
        ElytraFly wings = wings();
        if (wings != null) {
            wings.steer(player.getYRot(), height);
            // Bounce holds forward itself. A bounce into a wall still counts as stuck.
            if (wings.holdsOwnKeys()) {
                letGo();
                return aimed && aheadLoaded();
            }
        }
        boolean keysHeight = wings == null || !wings.keepsOwnHeight();
        double y = player.getY();
        boolean climbFirst = keysHeight && climbsFirst(height);
        boolean onward = aimed && !climbFirst && aheadLoaded();
        boolean takeOff = wings != null && player.onGround();
        press(forward, onward);
        press(jump, takeOff || keysHeight && y < height - HEIGHT_BAND);
        press(sneak, !takeOff && keysHeight && y > height + HEIGHT_BAND);
        return onward;
    }

    // True whilst you are well under the height and still climbing to it. A climb that gains no
    // block for a while is given up for the rest of the leg and forward is held again.
    private boolean climbsFirst(double height) {
        double y = mc.player.getY();
        if (climbBlocked || y >= height - CLIMB_FIRST) {
            climbedTo = y;
            climbTicks = 0;
            return false;
        }
        if (y >= climbedTo + 1) {
            climbedTo = y;
            climbTicks = 0;
        } else if (++climbTicks >= STUCK_TICKS) {
            climbBlocked = true;
            return false;
        }
        return true;
    }

    private static void press(HeldKey key, boolean down) {
        if (down) {
            key.hold();
        } else {
            key.letGo();
        }
    }

    private void letGo() {
        forward.letGo();
        jump.letGo();
        sneak.letGo();
    }

    // False whilst the chunk you are about to fly into has not loaded. You wait for it.
    private boolean aheadLoaded() {
        Vec3 ahead = mc.player.position().add(Vec3.directionFromRotation(0, mc.player.getYRot()).scale(LOOK_AHEAD));
        return loaded(Mth.floor(ahead.x), Mth.floor(ahead.z));
    }

    private boolean loaded(int x, int z) {
        return mc.level.getChunkSource().hasChunk(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z));
    }

    // The cruise height or higher where the land ahead rises over it. A dimension with a roof
    // is left to the climb over whatever stops you.
    private double travelHeight() {
        double height = cruiseHeight.getValue();
        if (!mc.level.dimensionType().hasCeiling()) {
            height = Math.max(height, landAhead() + CLEARANCE);
        }
        return height + lift;
    }

    // The top of the highest loaded land under the way ahead.
    private double landAhead() {
        Vec3 way = Vec3.directionFromRotation(0, mc.player.getYRot());
        double top = Double.NEGATIVE_INFINITY;
        for (double along = 0; along <= LOOK_AHEAD; along += LOOK_STEP) {
            int x = Mth.floor(mc.player.getX() + way.x * along);
            int z = Mth.floor(mc.player.getZ() + way.z * along);
            if (loaded(x, z)) {
                top = Math.max(top, mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z));
            }
        }
        return top;
    }

    // Counts the ticks forward is held without a block of headway. A wait climbs over what is
    // in the way and a long one gives up. True once it gives up.
    private boolean stuck(boolean pushing) {
        Vec3 here = mc.player.position();
        if (liftedAt != null && flat(here.x, here.z, liftedAt.x, liftedAt.z) > LIFT_KEEP) {
            lift = 0;
            liftedAt = null;
        }
        if (headway == null || flat(here.x, here.z, headway.x, headway.z) >= 1) {
            headway = here;
            stuckTicks = 0;
            return false;
        }
        if (!pushing) {
            return false;
        }
        stuckTicks++;
        if (stuckTicks % STUCK_TICKS == 0) {
            lift += STUCK_LIFT;
            liftedAt = here;
        }
        return stuckTicks >= GIVE_UP_TICKS;
    }

    // Reads which way the trail runs every few ticks and puts the stop far along it.
    // False once no way ahead holds more old chunks than new ones.
    private boolean readTrail() {
        if (!Modules.enabled(NewChunks.class)) {
            return false;
        }
        if (current != null && trailWait-- > 0) {
            return true;
        }
        trailWait = TRAIL_READ_TICKS;
        int far = Math.max(TRAIL_NEAR + 1, mc.options.getEffectiveRenderDistance() - 1);
        float bestYaw = trailYaw;
        double best = 0;
        for (float off = 0; off <= TRAIL_TURN; off += TRAIL_STEP) {
            for (float yaw : new float[] {trailYaw + off, trailYaw - off}) {
                double score = trailScore(yaw, far);
                if (score > best) {
                    best = score;
                    bestYaw = yaw;
                }
            }
        }
        if (best <= 0) {
            return false;
        }
        trailFound = true;
        trailYaw = Mth.wrapDegrees(bestYaw);
        Vec3 way = Vec3.directionFromRotation(0, trailYaw).scale(far * CHUNK);
        current = new Stop(null, mc.player.getX() + way.x, mc.player.getZ() + way.z);
        return true;
    }

    // Old chunks along the way count for it and new ones against it. A chunk with no verdict
    // counts for nothing.
    private double trailScore(float yaw, int far) {
        Vec3 way = Vec3.directionFromRotation(0, yaw);
        double score = 0;
        long last = Long.MIN_VALUE;
        for (double along = TRAIL_NEAR * CHUNK; along <= far * CHUNK; along += CHUNK / 2.0) {
            int chunkX = SectionPos.blockToSectionCoord(Mth.floor(mc.player.getX() + way.x * along));
            int chunkZ = SectionPos.blockToSectionCoord(Mth.floor(mc.player.getZ() + way.z * along));
            long chunk = ChunkPos.pack(chunkX, chunkZ);
            if (chunk == last) {
                continue;
            }
            last = chunk;
            NewChunks.Verdict verdict = Modules.chunkVerdict(chunkX, chunkZ);
            if (verdict != null) {
                score += verdict.old() ? 1 : -NEW_WEIGHT;
            }
        }
        return score;
    }

    // The way still to fly across every stop to come and the time it takes at your pace.
    private String distanceLeft() {
        if (current == null) {
            return null;
        }
        double left = flat(mc.player.getX(), mc.player.getZ(), current.x(), current.z());
        Stop from = current;
        for (Stop next : stops) {
            left += flat(from.x(), from.z(), next.x(), next.z());
            from = next;
        }
        String distance = String.format(Locale.ROOT, "%.0fm", left);
        if (pace < SLOWEST_PACE) {
            return distance;
        }
        return distance + " " + ChatUtil.shortAge(Math.round(left / pace * MILLIS_PER_SECOND));
    }

    private static double flat(double x1, double z1, double x2, double z2) {
        return Math.hypot(x2 - x1, z2 - z1);
    }
}
