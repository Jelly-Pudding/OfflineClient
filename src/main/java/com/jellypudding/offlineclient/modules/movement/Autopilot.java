package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.config.FindLog;
import com.jellypudding.offlineclient.config.SweepLedger;
import com.jellypudding.offlineclient.config.WaypointStore;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.event.events.EntityDamageEvent;
import com.jellypudding.offlineclient.event.events.PostMotionEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.modules.hunting.NewChunks;
import com.jellypudding.offlineclient.modules.misc.AutoReconnect;
import com.jellypudding.offlineclient.modules.misc.Linger;
import com.jellypudding.offlineclient.setting.ActionSetting;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.TextSetting;
import com.jellypudding.offlineclient.util.Armed;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.Hands;
import com.jellypudding.offlineclient.util.HeldKey;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.Lagback;
import com.jellypudding.offlineclient.util.Leave;
import com.jellypudding.offlineclient.util.LoadedChunks;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.MovementUtil;
import com.jellypudding.offlineclient.util.PitStop;
import com.jellypudding.offlineclient.util.RestockErrand;
import com.jellypudding.offlineclient.util.RotationManager;
import com.jellypudding.offlineclient.util.ServerInfo;
import com.jellypudding.offlineclient.util.Sweep;
import com.jellypudding.offlineclient.util.WorldWatch;
import net.minecraft.SharedConstants;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
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
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

// Flies a route one stop at a time. Flight or ElytraFly does the moving whilst this turns
// your view towards the next stop and holds the keys.
public final class Autopilot extends Module {

    public enum Route { POINT, WAYPOINTS, GRID, AREA, SPIRAL, TRAIL }

    public enum Engine { FLIGHT, ELYTRA }

    public enum Ending { HOVER, LAND, LEAVE }

    // What the autopilot does this tick. Standing is a stop on the way that flies on after it.
    private enum Phase { FLYING, HOLDING, LANDING, STANDING }

    // How the leg towards one stop is going. It stays ARRIVED or FAILED until the next leg.
    private enum Leg { IDLE, FLYING, ARRIVED, FAILED }

    // A place on the route. Only a waypoint has a name.
    record Stop(String name, double x, double z) {

        static Stop at(Vec3 spot) {
            return new Stop(null, spot.x, spot.z);
        }
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
    // How quickly the speed behind the time left follows the real speed.
    private static final double PACE_EASE = 0.05;
    // Slower than this in blocks a second the time left is not shown.
    private static final double SLOWEST_PACE = 1;
    private static final long MILLIS_PER_SECOND = 1000;
    // Where a sweep has got to is written down this often as well as at every turn and when it stops.
    private static final int SAVE_TICKS = 2 * 60 * SharedConstants.TICKS_PER_SECOND;
    // A stop on the way that takes longer than this is given up. The next one waits at least the gap.
    private static final int STOP_TICKS = 4 * 60 * SharedConstants.TICKS_PER_SECOND;
    private static final int STOP_GAP = 60 * SharedConstants.TICKS_PER_SECOND;
    // You stand still for a stop once you move slower than this.
    private static final double STILL = 0.01;
    // Back in the flight's dimension after a drop the flight gives the server this long to place
    // you. It carries on only this near where it was cut off and waits this long elsewhere for a
    // lobby to send you on before it stops.
    private static final int SETTLE_TICKS = 2 * SharedConstants.TICKS_PER_SECOND;
    private static final double BACK_WITHIN = 8 * CHUNK;
    private static final int BACK_TICKS = 60 * SharedConstants.TICKS_PER_SECOND;
    // This many dropped connections within the window end the flight once you are back.
    private static final int MOST_DROPS = 3;
    private static final int DROP_MINUTES = 10;

    private final EnumSetting<Route> route = new EnumSetting<>("Route",
        "Where the flight goes. A flight the connection cut short carries on once AutoReconnect brings you back to"
            + " where it was.", Route.POINT)
        .describe(Route.POINT, "Straight to the spot you give the autopilot command.")
        .describe(Route.WAYPOINTS, "Visits the waypoints of this dimension and always flies to the nearest one left.")
        .describe(Route.GRID, "Sweeps a square of chunks round where you start row by row.")
        .describe(Route.AREA, "Sweeps the area between two corners row by row along its longer side.")
        .describe(Route.SPIRAL, "Sweeps outwards from where you start in a square spiral that never ends.")
        .describe(Route.TRAIL, "Follows the old chunks ahead of you through new land to where they lead.");
    private final EnumSetting<Engine> engine = new EnumSetting<>("Engine",
        "What carries you along.", Engine.FLIGHT)
        .describe(Engine.FLIGHT, "Flight carries you at the cruise height.")
        .describe(Engine.ELYTRA, "ElytraFly carries you on your wings in the mode it is set to.");
    private final NumberSetting cruiseHeight = new NumberSetting("Cruise height",
        "The height held between points. Land that rises higher lifts you over it.", 200, -64, 320, 1);
    private final NumberSetting arriveWithin = new NumberSetting("Arrive within",
        "How close to a point counts as reaching it.", 8, 1, 64, 1, " blocks")
        .under(route, Route.POINT, Route.WAYPOINTS, Route.GRID, Route.AREA, Route.SPIRAL);
    private final NumberSetting gridSize = new NumberSetting("Grid size",
        "Chunks along each side of the square. A sweep keeps the size it started with.", 32, 2, 128, 1, " chunks")
        .min(1).under(route, Route.GRID);
    private final TextSetting cornerOne = new TextSetting("Corner one",
        "One corner of the area as x and z. A sweep keeps the area it started with.", "")
        .under(route, Route.AREA);
    private final TextSetting cornerTwo = new TextSetting("Corner two",
        "The opposite corner of the area as x and z.", "")
        .under(route, Route.AREA);
    private final NumberSetting rowGap = new NumberSetting("Row gap",
        "Chunks between one line of a sweep and the next. A sweep keeps the gap it started with.",
        8, 1, 32, 1, " chunks")
        .under(route, Route.GRID, Route.AREA, Route.SPIRAL);
    private final ActionSetting resume = new ActionSetting("Resume",
        "Flies back to where the sweep saved for this server and dimension stopped and carries on with it.",
        this::resume)
        .under(route, Route.GRID, Route.AREA, Route.SPIRAL);
    private final TrailFollower trail = new TrailFollower(this::tell).under(route, Route.TRAIL);
    private final BoolSetting mendElytra = new BoolSetting("Mend elytra",
        "Stands somewhere safe to mend the elytra with experience bottles through AutoMend once it wears down to"
            + " Elytra line and then flies on.", false)
        .under(engine, Engine.ELYTRA);
    private final BoolSetting restockFlight = new BoolSetting("Restock",
        "Stands somewhere safe to take rockets and a fresh elytra and bottles to mend with out of the shulker boxes"
            + " you carry or your ender chest when they run low and then flies on. The ender chest needs a Silk Touch"
            + " pickaxe in the hotbar.", false)
        .under(engine, Engine.ELYTRA);
    private final NumberSetting keepRockets = new NumberSetting("Keep rockets",
        "Restocks once you carry fewer rockets than this. Nought never stops for rockets.", 64, 0, 256, 16)
        .under(restockFlight);
    private final NumberSetting elytraLine = new NumberSetting("Elytra line",
        "How much durability the worn elytra has left when it stops to mend it or change it.", 25, 15, 90, 5, "%")
        .min(11).max(99)
        .under(engine, () -> engine.is(Engine.ELYTRA) && (mendElytra.isOn() || restockFlight.isOn()));
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
    private final SweepLedger sweeps = new SweepLedger();
    private final PitStop pitStop = new PitStop();
    private final InventoryUtil.SlotSwap slots = new InventoryUtil.SlotSwap();

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

    // The sweep flown whilst the route is one. It carries the line flown and the place reached.
    private Sweep sweep;
    // True whilst the flight heads for the place the sweep starts or carries on from.
    private boolean joining;
    // False until the sweep has flown part of a line. Until then a sweep saved before is kept.
    private boolean swept;
    private int sinceSave;
    // A saved sweep the next start flies in place of a fresh one.
    private Sweep resumed;
    // A plain switch on that would sweep over the sweep saved here only tells you. A second one
    // before this time starts afresh. The area command starts afresh at once.
    private long freshUntil;
    private boolean freshAsked;
    // True once a sweep stopped short of a line that runs out of the world border.
    private boolean atBorder;

    // True from the moment a stop on the way is under way until the flight takes off again. What
    // the stop is for and how long it has taken.
    private boolean stopping;
    private boolean standingWork;
    private int stopTicks;
    private int sinceStop = STOP_GAP;
    // Said once whilst a stop waits for ground to stand on.
    private boolean toldNoGround;
    // Said once a flight when no stop could help the worn elytra.
    private boolean toldNoHelp;
    // A stop that left the elytra worn means no more stops for it on this flight.
    private boolean elytraLeft;

    // The server and dimension the flight belongs to.
    private String flightServer;
    private String flightDimension;
    // True once the connection dropped mid flight. The joins AutoReconnect had made by then and
    // where you were. The ticks back in the flight's dimension since or minus one whilst elsewhere.
    private boolean cutOff;
    private int joinsAtCut;
    private Vec3 cutAt;
    private int backTicks = -1;
    // Where you were on the last tick flown.
    private Vec3 lastAt;
    // When the connection dropped mid flight within the last few minutes.
    private final Deque<Long> drops = new ArrayDeque<>();

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
    // True from the ground until the wings are open. Jump is held all that time. ElytraFly
    // reads the keys a tick late and a jump let go in the air never opens them.
    private boolean takingOff;

    private double pace;
    private int findsSeen;
    private boolean hurt;
    private int ticks;
    private final Deque<Integer> pullbacks = new ArrayDeque<>();
    // The players in view on the last tick who are not friends.
    private Set<UUID> strangers = new HashSet<>();

    public Autopilot() {
        super("Autopilot", "Flies you to a spot or along a route whilst you do something else.",
            Category.MOVEMENT);
        addSettings(route, engine, cruiseHeight, arriveWithin, gridSize, cornerOne, cornerTwo, rowGap, resume);
        addSettings(trail.settings());
        addSettings(mendElytra, restockFlight, keepRockets, elytraLine, stopOnFinds, stopNearPlayers, stopWhenHurt,
            atEnd);
        searchTags("auto fly", "travel", "route", "waypoints", "trail", "grid", "spiral", "search area", "sweep",
            "resume", "afk");
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
            case STANDING -> "standing";
        };
    }

    // Flies straight to the spot. A flight already going gives way to it.
    public void flyTo(double x, double z, String name) {
        spot = new Stop(name, x, z);
        route.setValue(Route.POINT);
        setEnabled(false);
        setEnabled(true);
    }

    // Sweeps the area between two corners. A flight already going gives way to it and the sweep
    // saved here is replaced once this one reaches its first line.
    public void sweepArea(int x1, int z1, int x2, int z2) {
        cornerOne.setValue(x1 + " " + z1);
        cornerTwo.setValue(x2 + " " + z2);
        route.setValue(Route.AREA);
        setEnabled(false);
        freshAsked = true;
        setEnabled(true);
        freshAsked = false;
    }

    // Carries on with the sweep saved for this server and dimension from where it stopped. A
    // flight already going gives way to it. Null once the autopilot has said what it does and
    // otherwise why it cannot.
    public String resume() {
        if (!inGame()) {
            return "Join a world first";
        }
        if (!sweeps.ready()) {
            return "The saved sweeps are still being read";
        }
        if (sweeps.get(ServerInfo.dimension()) == null) {
            return "No sweep is saved for this server and dimension";
        }
        // A sweep flying now writes down the place it reached as it stops.
        setEnabled(false);
        Sweep saved = sweeps.get(ServerInfo.dimension());
        route.setValue(switch (saved.shape()) {
            case SQUARE -> Route.GRID;
            case AREA -> Route.AREA;
            case SPIRAL -> Route.SPIRAL;
        });
        resumed = saved;
        setEnabled(true);
        resumed = null;
        return null;
    }

    // The distance and the time left for the Info bar. Null whilst nothing is flying.
    public String left() {
        if (!inGame() || cutOff) {
            return null;
        }
        return switch (phase) {
            case HOLDING -> "circling";
            case LANDING -> "landing";
            case STANDING -> "standing";
            case FLYING -> route.is(Route.TRAIL) ? trail.words() : endless() ? "ring " + sweep.ring(sweep.leg())
                : distanceLeft();
        };
    }

    // A spiral on its lines never ends. On its way to them it still has a distance to go.
    private boolean endless() {
        return sweep != null && sweep.shape() == Sweep.Shape.SPIRAL && !joining;
    }

    @Override
    protected void onEnable() {
        Sweep picked = resumed;
        resumed = null;
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
        if (!plan(picked)) {
            return;
        }
        Module carrier = engineModule();
        engineWasOn = carrier.isEnabled();
        carrier.setEnabled(true);
        phase = Phase.FLYING;
        world.accept();
        flightServer = ServerInfo.key();
        flightDimension = ServerInfo.dimension();
        cutOff = false;
        backTicks = -1;
        drops.clear();
        lastAt = mc.player.position();
        sinceStop = STOP_GAP;
        elytraLeft = false;
        toldNoGround = false;
        toldNoHelp = false;
        pitStop.reset();
        watchAfresh();
        if (route.is(Route.TRAIL)) {
            current = null;
            trail.start(mc.player.getYRot());
            startLeg();
        } else if (sweep != null) {
            joinSweep();
        } else {
            nextLeg();
        }
        tell(startWords(picked != null));
    }

    @Override
    protected void onDisable() {
        letGo();
        endStop();
        if (sweep != null) {
            saveSweep();
        }
        sweep = null;
        current = null;
        stops.clear();
        leg = Leg.IDLE;
        phase = Phase.FLYING;
        cutOff = false;
    }

    // What a flight keeps an eye on starts from nothing. Anyone already in view never counts.
    private void watchAfresh() {
        lagback.sync();
        pullbacks.clear();
        findsSeen = FindLog.newFinds();
        strangers = new HashSet<>();
        newStranger();
        hurt = false;
        pace = 0;
        unwornTicks = 0;
        takingOff = false;
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

    // Lays out the stops of the route. A saved sweep picked up again takes the place of a fresh
    // one. False after telling the player why it cannot start.
    private boolean plan(Sweep picked) {
        stops.clear();
        sweep = null;
        // A start that is turned down writes nothing over the sweep saved here.
        swept = false;
        // The file of saved sweeps is read the first time it is asked after a join.
        sweeps.ready();
        if (picked != null) {
            sweep = picked;
            swept = true;
            return true;
        }
        int chunkX = SectionPos.blockToSectionCoord(mc.player.getBlockX());
        int chunkZ = SectionPos.blockToSectionCoord(mc.player.getBlockZ());
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
            case GRID -> sweep = Sweep.square(chunkX, chunkZ, gridSize.getInt(), rowGap.getInt());
            case AREA -> {
                int[] one = corner(cornerOne);
                int[] two = corner(cornerTwo);
                if (one == null || two == null) {
                    disable("Type both corners of the area as x and z under Corner one and Corner two or use "
                        + prefix() + "autopilot area x z x z.");
                    return false;
                }
                sweep = Sweep.area(one[0], one[1], two[0], two[1], rowGap.getInt());
            }
            case SPIRAL -> sweep = Sweep.spiral(chunkX, chunkZ, rowGap.getInt());
            case TRAIL -> {
                if (!Modules.enabled(NewChunks.class)) {
                    disable("Switch NewChunks on first. The trail is read from the chunks it judges.");
                    return false;
                }
            }
        }
        return sweep == null || mayReplaceSaved();
    }

    // A plain switch on never sweeps over the sweep saved here unasked. It tells you and a second
    // switch on within a few seconds starts afresh. False once it has said why it does not start.
    private boolean mayReplaceSaved() {
        if (!sweeps.ready()) {
            disable("Autopilot is still reading the sweeps saved for this server. Switch it on again in a moment.");
            return false;
        }
        long now = System.currentTimeMillis();
        if (freshAsked || now <= freshUntil || sweeps.get(ServerInfo.dimension()) == null) {
            freshUntil = 0;
            return true;
        }
        freshUntil = now + Armed.CONFIRM_MS;
        tell("would replace the sweep saved here. Press Resume to carry on with it or"
            + " switch Autopilot on again to start afresh.");
        setEnabled(false);
        return false;
    }

    // Two whole numbers for x and z. Null for anything else.
    private static int[] corner(TextSetting text) {
        String[] parts = text.getValue().trim().split("[\\s,]+");
        if (parts.length != 2) {
            return null;
        }
        try {
            return new int[] {Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
        } catch (NumberFormatException e) {
            return null;
        }
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

    private String startWords(boolean picked) {
        String words = switch (route.getValue()) {
            case POINT -> "flying to §f" + named(current) + "§7.";
            case WAYPOINTS -> "visiting §f" + (stops.size() + 1) + "§7 waypoints.";
            case GRID, AREA, SPIRAL -> (picked ? "carries on with " : "sweeping ") + sweepWords() + "§7.";
            case TRAIL -> trail.startWords();
        };
        if (sweep == null) {
            return words;
        }
        words += wayToSweep(picked);
        if (!picked && sweeps.get(flightDimension) != null) {
            words += " It replaces the sweep saved here once it reaches its first line.";
        }
        return words;
    }

    // Where the flight to the place a sweep starts or carries on from goes and how far it is.
    // Nothing whilst you are there already.
    private String wayToSweep(boolean back) {
        double away = flat(mc.player.getX(), mc.player.getZ(), current.x(), current.z());
        if (away <= CHUNK) {
            return "";
        }
        return " It first flies §f" + Math.round(away) + "§7 blocks" + (back ? " back" : "") + " to §f"
            + named(current) + "§7.";
    }

    private String sweepWords() {
        return switch (sweep.shape()) {
            case SQUARE -> "§f" + (sweep.east() - sweep.west() + 1) + "§7 by §f"
                + (sweep.south() - sweep.north() + 1) + "§7 chunks";
            case AREA -> "the area from §f" + SectionPos.sectionToBlockCoord(sweep.west()) + " "
                + SectionPos.sectionToBlockCoord(sweep.north()) + "§7 to §f"
                + SectionPos.sectionToBlockCoord(sweep.east(), CHUNK - 1) + " "
                + SectionPos.sectionToBlockCoord(sweep.south(), CHUNK - 1);
            case SPIRAL -> "a spiral out from §f" + named(Stop.at(sweep.turn(0)));
        };
    }

    private static String named(Stop stop) {
        return stop.name() != null ? stop.name() : Math.round(stop.x()) + " " + Math.round(stop.z());
    }

    // Takes the next stop. False once none is left.
    private boolean nextLeg() {
        if (sweep != null) {
            return nextLine();
        }
        if (stops.isEmpty()) {
            current = null;
            leg = Leg.IDLE;
            return false;
        }
        current = stops.removeFirst();
        startLeg();
        return true;
    }

    // Heads for the place the sweep starts or carries on from before any line is flown.
    private void joinSweep() {
        joining = true;
        current = Stop.at(sweep.place());
        sinceSave = 0;
        startLeg();
    }

    // The place the sweep was joined at sits on the line still to finish. After that each line
    // runs from the end of the last one. The sweep ends at a line that runs out of the world border.
    private boolean nextLine() {
        int line = joining ? sweep.leg() : sweep.leg() + 1;
        atBorder = sweep.hasLeg(line) && !sweep.endsInside(line, mc.level.getWorldBorder());
        if (!sweep.hasLeg(line) || atBorder) {
            current = null;
            leg = Leg.IDLE;
            return false;
        }
        if (!joining) {
            Vec3 start = sweep.turn(line);
            sweep = sweep.at(line, start.x, start.z);
        }
        joining = false;
        swept = true;
        current = Stop.at(sweep.turn(line + 1));
        startLeg();
        saveSweep();
        return true;
    }

    // Writes down where the sweep has got to. A fresh sweep still on its way to its start has
    // done nothing worth keeping and the sweep saved before stays as it was.
    private void saveSweep() {
        sinceSave = 0;
        if (swept) {
            sweeps.put(flightDimension, sweep);
        }
    }

    // A finished sweep leaves nothing to pick up again. One that never reached a line of its own
    // leaves the sweep saved before as it was.
    private void finishSweep() {
        if (swept) {
            sweeps.forget(flightDimension);
        }
        sweep = null;
    }

    // The place reached is the point of the line nearest you. A climb over something in the way
    // never moves it on.
    private void trackSweep() {
        Vec3 on = sweep.nearestOn(sweep.leg(), mc.player.getX(), mc.player.getZ());
        sweep = sweep.at(sweep.leg(), on.x, on.z);
        if (++sinceSave >= SAVE_TICKS) {
            saveSweep();
        }
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

    // A dropped connection leaves no world or the frozen one Linger keeps. The flight waits with
    // what it has done written down. A stop half done starts again once you are back.
    @Subscribe
    private void onClientTick(ClientTickEvent event) {
        if (cutOff || inGame() && !Linger.holding()) {
            return;
        }
        cutOff = true;
        joinsAtCut = AutoReconnect.joinsMade();
        cutAt = lastAt;
        backTicks = -1;
        long now = System.currentTimeMillis();
        drops.addLast(now);
        while (now - drops.peekFirst() > TimeUnit.MINUTES.toMillis(DROP_MINUTES)) {
            drops.removeFirst();
        }
        letGo();
        if (stopping) {
            endStop();
            phase = Phase.FLYING;
            sinceStop = STOP_GAP;
        }
        if (sweep != null) {
            saveSweep();
        }
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (Linger.holding()) {
            return;
        }
        ticks++;
        if (world.changed()) {
            if (!rejoined()) {
                tell("stopped.");
                setEnabled(false);
            }
            return;
        }
        if (cutOff) {
            comeBack();
            return;
        }
        lastAt = mc.player.position();
        if (mc.player.isDeadOrDying()) {
            tell("stopped.");
            setEnabled(false);
            return;
        }
        if (!engineModule().isEnabled()) {
            tell("stopped as §f" + engineModule().getName() + "§7 was switched off.");
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
        // A stop on the way is for the wings. A switch to Flight ends it.
        if (stopping && wings() == null) {
            flyOnAfterStop();
        }
        if (phase == Phase.FLYING || stopping) {
            String reason = stopReason();
            if (reason != null) {
                end("stopped as " + reason + ".");
                return;
            }
        }
        if (phase == Phase.FLYING && wings() != null && startStop()) {
            return;
        }
        switch (phase) {
            case FLYING -> fly();
            case HOLDING -> hold();
            case LANDING -> land();
            case STANDING -> stand();
        }
    }

    // The speed behind the time left. A tick has made its move by the time its position goes out.
    @Subscribe
    private void onPostMotion(PostMotionEvent event) {
        pace += (EntityUtil.ownSpeed() - pace) * PACE_EASE;
    }

    // What a stop on the way looks after. Once a stop has left the elytra worn a line of nought
    // never calls for another.
    private PitStop.Needs needs() {
        return new PitStop.Needs(mendElytra.isOn(), restockFlight.isOn(), elytraLeft ? 0 : elytraLine.getValue() / 100,
            keepRockets.getInt());
    }

    // A stop for the elytra or for rockets. It is looked for once a second. Bounce comes to a halt
    // over ground it can stand on and Packet stands on a block put under you. The gliding modes come
    // down onto ground they can stand on once some lies below.
    private boolean startStop() {
        if (++sinceStop < STOP_GAP || ticks % SharedConstants.TICKS_PER_SECOND != 0) {
            return false;
        }
        PitStop.Needs asked = needs();
        String why = pitStop.due(asked);
        if (why == null) {
            toldNoGround = false;
            tellNoHelp(asked);
            return false;
        }
        ElytraFly wings = wings();
        boolean safe = safeGround(true);
        boolean stands = wings.holdsOwnKeys() ? safe : wings.hovers() && PitStop.platformSlot() != -1;
        if (!stands && !safe) {
            if (!toldNoGround) {
                toldNoGround = true;
                tell("needs to stop " + why + " and flies on until there is ground to stand on.");
            }
            return false;
        }
        letGo();
        stopping = true;
        standingWork = false;
        stopTicks = 0;
        toldNoGround = false;
        heldSpot = mc.player.position();
        heldHeight = mc.player.getY();
        phase = stands ? Phase.STANDING : Phase.LANDING;
        tell((stands ? "stops " : "comes down ") + why + ".");
        return true;
    }

    // Says once a flight why no stop can help the elytra once it is worn down to Elytra line.
    private void tellNoHelp(PitStop.Needs asked) {
        if (toldNoHelp) {
            return;
        }
        String cannot = pitStop.noHelp(asked);
        if (cannot != null) {
            toldNoHelp = true;
            tell("cannot stop for the elytra as " + cannot + ".");
        }
    }

    // Stands still for the stop and does its work. A stop that takes too long ends the flight.
    private void stand() {
        letGo();
        ElytraFly wings = wings();
        if (wings.holdsOwnKeys()) {
            wings.holdStill();
        }
        // A stop given up leaves you standing where you are unless At the end logs you out.
        if (++stopTicks > STOP_TICKS) {
            end("gave up the stop as it took too long.", atEnd.is(Ending.LEAVE) ? Ending.LEAVE : Ending.LAND);
            return;
        }
        if (!standingWork) {
            if (!settled(wings)) {
                return;
            }
            pitStop.begin(needs());
            standingWork = true;
        }
        if (!pitStop.tick()) {
            flyOnAfterStop();
        }
    }

    // True once you stand still on something. Packet sinks onto a block it puts under your feet.
    // A restock puts one beside it too whilst no spot beside you will take a container.
    private boolean settled(ElytraFly wings) {
        if (mc.player.onGround() && !mc.player.isFallFlying()) {
            return mc.player.getDeltaMovement().horizontalDistanceSqr() < STILL * STILL;
        }
        if (!wings.hovers()) {
            return false;
        }
        BlockPos feet = mc.player.blockPosition();
        BlockPos beside = feet.relative(mc.player.getDirection().getOpposite()).below();
        BlockPos floor = BlockUtil.isReplaceable(feet.below()) ? feet.below()
            : restockFlight.isOn() && RestockErrand.errandSpot() == null && BlockUtil.isReplaceable(beside)
            && PitStop.platformSlot() != -1 ? beside : null;
        if (floor == null) {
            sneak.hold();
        } else if (!Hands.taken()) {
            BlockUtil.placeFrom(slots, PitStop.platformSlot(), floor, true);
        }
        return false;
    }

    private void flyOnAfterStop() {
        String worn = pitStop.stillWorn();
        if (worn != null) {
            elytraLeft = true;
            tell(worn + " It flies on without stopping for the elytra again.");
        }
        endStop();
        sinceStop = 0;
        phase = Phase.FLYING;
        if (current != null) {
            startLeg();
        }
        tell("flies on.");
    }

    // Lets go of whatever the stop holds.
    private void endStop() {
        if (stopping) {
            pitStop.end();
        }
        stopping = false;
        standingWork = false;
    }

    // Each world the client enters after the connection dropped. The flight waits on through a
    // join AutoReconnect made by itself to the server it flew on. A queue or a lobby in another
    // dimension is waited out. False once the flight is over.
    private boolean rejoined() {
        if (!cutOff || AutoReconnect.joinsMade() == joinsAtCut || !ServerInfo.key().equals(flightServer)) {
            return false;
        }
        backTicks = ServerInfo.dimension().equals(flightDimension) ? 0 : -1;
        return true;
    }

    // Back in the flight's dimension the server is given a moment to place you. The flight then
    // carries on only near where it was cut off. Elsewhere it waits a while for a lobby to send
    // you on before it stops. A connection that keeps dropping ends it.
    private void comeBack() {
        if (backTicks < 0 || ++backTicks < SETTLE_TICKS) {
            return;
        }
        if (drops.size() >= MOST_DROPS) {
            tell("stopped as the connection dropped §f" + drops.size() + "§7 times within §f"
                + DROP_MINUTES + "§7 minutes.");
            setEnabled(false);
            return;
        }
        if (flat(mc.player.getX(), mc.player.getZ(), cutAt.x, cutAt.z) <= BACK_WITHIN) {
            carryOn();
        } else if (backTicks >= BACK_TICKS) {
            tell("stopped as you came back far from where the flight was cut off.");
            setEnabled(false);
        }
    }

    // Takes the flight up again where the connection dropped. Nobody watched the rejoin and
    // everyone in view then counts as someone who just came into view.
    private void carryOn() {
        cutOff = false;
        backTicks = -1;
        watchAfresh();
        strangers.clear();
        String back = "";
        if (sweep != null && phase == Phase.FLYING) {
            joinSweep();
            back = wayToSweep(true);
        } else if (current != null) {
            startLeg();
        }
        trail.readAgain();
        tell("carries on now that you are back." + back);
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
        // A stop on the way may be fetching the elytra that takes over.
        if (wings != null && wings.wearingOut() && !stopping) {
            return WORN_ELYTRA;
        }
        String blocked = wings == null ? null : wings.bounceStoppedFor();
        if (blocked != null) {
            return blocked;
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
        if (route.is(Route.TRAIL)) {
            Stop next = trail.next(current);
            if (next == null) {
                end(trail.endWords());
                return;
            }
            current = next;
        }
        if (sweep != null && !joining) {
            trackSweep();
        }
        switch (flyLeg()) {
            case ARRIVED -> {
                if (current.name() != null) {
                    tell("reached §f" + current.name() + "§7.");
                }
                if (!nextLeg()) {
                    String done = sweep != null && atBorder ? "reached the world border." : "finished the route.";
                    if (sweep != null) {
                        finishSweep();
                    }
                    end(done);
                }
            }
            case FAILED -> {
                String stuckAt = "could not get past what blocks the way to §f" + named(current) + "§7";
                if (route.isAny(Route.POINT, Route.TRAIL) || !nextLeg()) {
                    end(stuckAt + ".");
                } else {
                    tell(stuckAt + " and flies on to the next stop.");
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
        end(reason, atEnd.getValue());
    }

    private void end(String reason, Ending ending) {
        letGo();
        // Wings that held still for a stop take off and circle at the cruise height.
        boolean grounded = stopping;
        endStop();
        if (sweep != null) {
            saveSweep();
        }
        tell(reason);
        heldSpot = mc.player.position();
        heldHeight = grounded ? Math.max(mc.player.getY(), cruiseHeight.getValue()) : mc.player.getY();
        switch (ending) {
            case HOVER -> hover();
            case LAND -> {
                if (safeGround(false)) {
                    phase = Phase.LANDING;
                } else {
                    tell("found nowhere safe to land below and waits in the air.");
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
        Leave.QUIT.go("Autopilot " + reason + (stopped ? "\n§7AutoReconnect was turned off." : ""));
    }

    // Wings circle the spot at the height they stopped at until they start to fail.
    private void hold() {
        if (wingsFail()) {
            return;
        }
        Vec3 aim = circlePoint(heldSpot, CIRCLE);
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
        if (phase == Phase.HOLDING && safeGround(false)) {
            tell("comes down as " + why + ".");
            heldHeight = mc.player.getY();
            phase = Phase.LANDING;
            return true;
        }
        String reason = "logged you out as " + why + " over unsafe ground.";
        tell(reason);
        leave(reason);
        return true;
    }

    // A spot ahead on a ring round the centre. Aiming at it each tick flies you round the ring.
    static Vec3 circlePoint(Vec3 centre, double radius) {
        Vec3 out = new Vec3(mc.player.getX() - centre.x, 0, mc.player.getZ() - centre.z);
        Vec3 radial = out.lengthSqr() < OVERHEAD * OVERHEAD ? new Vec3(1, 0, 0) : out.normalize();
        Vec3 along = new Vec3(-radial.z, 0, radial.x);
        return centre.add(radial.scale(radius)).add(along.scale(radius));
    }

    // Flight comes straight down. Wings circle the spot and sink only whilst the ground under
    // them is safe. The engine is let go on the ground when the autopilot switched it on.
    private void land() {
        if (stopping && mc.player.isInWater()) {
            end("found no ground to stand on for the stop.");
            return;
        }
        if (stopping && mc.player.onGround()) {
            phase = Phase.STANDING;
            return;
        }
        if (mc.player.onGround() || mc.player.isInWater()) {
            letGo();
            if (!engineWasOn) {
                engineModule().setEnabled(false);
            }
            tell("landed.");
            setEnabled(false);
            return;
        }
        if (wings() == null) {
            steer(heldSpot.x, heldSpot.z, mc.level.getMinY(), false);
            return;
        }
        // Over unsafe ground the wings hold the height they last had over safe ground.
        boolean safe = safeGround(stopping);
        if (safe) {
            heldHeight = mc.player.getY();
        } else if (wingsFail()) {
            return;
        }
        Vec3 aim = circlePoint(heldSpot, CIRCLE);
        steer(aim.x, aim.z, safe ? mc.level.getMinY() : heldHeight, true);
    }

    // True when solid ground with no lava or fire on it lies somewhere below you. Fire and berry
    // bushes have no collision and are found in the block over the ground. Water is safe to come
    // down into but nothing to stand on.
    private boolean safeGround(boolean toStand) {
        Vec3 feet = mc.player.position();
        BlockHitResult hit = mc.level.clip(new ClipContext(feet, new Vec3(feet.x, mc.level.getMinY(), feet.z),
            ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, mc.player));
        if (hit.getType() == HitResult.Type.MISS) {
            return false;
        }
        BlockState ground = mc.level.getBlockState(hit.getBlockPos());
        if (toStand && !ground.getFluidState().isEmpty()) {
            return false;
        }
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
        takingOff = wings != null && !player.isFallFlying() && (takingOff || player.onGround());
        forward.set(onward);
        jump.set(takingOff || keysHeight && y < height - HEIGHT_BAND);
        sneak.set(!takingOff && keysHeight && y > height + HEIGHT_BAND);
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

    private void letGo() {
        forward.letGo();
        jump.letGo();
        sneak.letGo();
    }

    // False whilst the chunk you are about to fly into has not loaded. You wait for it.
    private boolean aheadLoaded() {
        Vec3 ahead = mc.player.position().add(Vec3.directionFromRotation(0, mc.player.getYRot()).scale(LOOK_AHEAD));
        return LoadedChunks.has(ahead.x, ahead.z);
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
            if (LoadedChunks.has(x, z)) {
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

    // The way still to fly across every stop to come and the time it takes at your pace.
    private String distanceLeft() {
        if (current == null) {
            return null;
        }
        double left = blocksLeft();
        String distance = String.format(Locale.ROOT, "%.0fm", left);
        if (pace < SLOWEST_PACE) {
            return distance;
        }
        return distance + " " + ChatUtil.shortAge(Math.round(left / pace * MILLIS_PER_SECOND));
    }

    // A sweep still on its way to the place it carries on from has that place and the rest of
    // its lines still to fly. A spiral counts only the way to that place.
    private double blocksLeft() {
        double left = flat(mc.player.getX(), mc.player.getZ(), current.x(), current.z());
        if (sweep != null && !joining) {
            return sweep.blocksLeft(sweep.leg(), mc.player.getX(), mc.player.getZ());
        }
        if (sweep != null) {
            return sweep.shape() == Sweep.Shape.SPIRAL ? left : left + sweep.blocksLeft(sweep.leg(), current.x(),
                current.z());
        }
        Stop from = current;
        for (Stop next : stops) {
            left += flat(from.x(), from.z(), next.x(), next.z());
            from = next;
        }
        return left;
    }

    // The seconds the flight still takes at your pace. Empty whilst it flies to no stop or too
    // slowly to say.
    public OptionalDouble secondsLeft() {
        if (!inGame() || cutOff || phase != Phase.FLYING || route.is(Route.TRAIL) || current == null || endless()
            || pace < SLOWEST_PACE) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(blocksLeft() / pace);
    }

    private static double flat(double x1, double z1, double x2, double z2) {
        return Math.hypot(x2 - x1, z2 - z1);
    }
}
