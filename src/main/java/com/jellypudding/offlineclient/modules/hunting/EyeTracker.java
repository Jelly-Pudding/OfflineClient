package com.jellypudding.offlineclient.modules.hunting;

import com.jellypudding.offlineclient.config.FindLog;
import com.jellypudding.offlineclient.config.FindSource;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render2DEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.gui.FindsScreen;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.render.FarShapes;
import com.jellypudding.offlineclient.render.WorldToScreen;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.Bearing;
import com.jellypudding.offlineclient.util.BearingFix;
import com.jellypudding.offlineclient.util.BearingTrail;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.Leak;
import com.jellypudding.offlineclient.util.Notice;
import com.jellypudding.offlineclient.util.RenderUtil;
import com.jellypudding.offlineclient.util.RepostGate;
import com.jellypudding.offlineclient.util.ServerInfo;
import com.jellypudding.offlineclient.util.TrackedPlace;
import com.jellypudding.offlineclient.util.WorldWatch;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.EyeOfEnder;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

// An eye of ender flies straight from where it was thrown towards the stronghold nearest the
// thrower. Its target is twelve blocks along that line eight blocks up or the stronghold itself
// when that is nearer. The line runs through the north west corner of the chunk the stronghold
// starts in and its stairs down stand two to six blocks in from that corner. Lines from eyes
// thrown in different places cross at the corner.
public final class EyeTracker extends Module implements FindSource, Leak.Source {

    // The thrower is null when nobody stood where the eye appeared.
    private static final class Flight {
        private final Vec3 first;
        private final Player thrower;
        private Vec3 last;

        private Flight(Vec3 first, Player thrower) {
            this.first = first;
            this.thrower = thrower;
            last = first;
        }
    }

    private static final class Target {
        private final BearingTrail trail = new BearingTrail(EYE, false);
        // The corner itself once an eye came down onto it or the lines met on one.
        private BlockPos corner;
        private long last;
        private final RepostGate told = RepostGate.forChat();
        private final TrackedPlace tracked = new TrackedPlace();

        // The corner as a fix that cannot be off or else the crossing of the lines. Null whilst
        // the lines do not cross yet.
        private BearingFix place() {
            if (corner != null) {
                return new BearingFix(corner.getX(), corner.getZ(), 0);
            }
            return trail.fix(MIN_SPREAD);
        }

        // Lines towards two strongholds cross as well but seldom on a chunk corner.
        private boolean fits(Bearing line) {
            BearingFix known = place();
            if (known != null ? known.missedBy(line, EYE) : !trail.isEmpty() && !trail.fits(line, MIN_SPREAD)) {
                return false;
            }
            BearingFix crossing = corner == null ? trail.fixWith(line, MIN_SPREAD) : null;
            return crossing == null || onCorner(crossing);
        }

        private boolean fits(BlockPos landing) {
            return corner != null ? corner.equals(landing) : trail.pointsAt(landing.getX(), landing.getZ());
        }
    }

    // The line runs exactly through the corner and the server places an eye to a 4096th of a
    // block. The rounding blurs the line by two steps over its length. A line that misses by
    // more than a few blocks points at another stronghold.
    private static final BearingFix.Tolerance EYE = new BearingFix.Tolerance(0.1, 4);
    private static final double PLACE_STEP = 1.0 / 4096;

    // Lines this close in angle are not crossed. The radius of the crossing says how sure it is.
    private static final double MIN_SPREAD = Math.toRadians(1);

    // An eye that moved less than this across the ground gives no line worth having.
    private static final double MIN_FLIGHT = 2;

    // An eye aimed past twelve blocks climbs to eight above where it started. One seen from its
    // throw that sank by more than this was aimed at the stronghold corner at height nought.
    private static final double SANK = 1;

    // A crossing or a landing this close to a chunk corner is that corner. Two corners lie a
    // chunk apart and only a fix surer than half of that is held to one.
    private static final double CORNER_REACH = 1.5;
    private static final double CORNER_DOUBT = SectionPos.SECTION_HALF_SIZE;
    // Lines aimed at one stronghold cross no further from its corner than the radius of their
    // crossing. A block more allows for rounding.
    private static final double CORNER_SLACK = 1;

    // Lines that still cross nowhere are forgotten this long after the last throw. A player who
    // travelled far before the next one may have thrown it towards another stronghold.
    private static final long LONE_MS = TimeUnit.MINUTES.toMillis(5);

    // The stairs down run from two to six blocks in from the corner. Their middle is marked.
    private static final int STAIRS_IN = 4;
    // Nothing gives away how deep the stairs lie. Sea level stands in above them and every
    // sighting of one stronghold lands on the same block.
    private static final int STAIRS_HEIGHT = 63;

    // An eye appears where its thrower stands half way up their body.
    private static final double THROWER_REACH = 1.5;

    private static final int MAX_TARGETS = 8;

    private static final String KIND = "stronghold";
    private static final String WAYPOINT = "Stronghold";

    private static final double LABEL_ALONG = 24;
    private static final double LABEL_LIFT = 1.5;

    private final EnumSetting<Notice.Where> messages = Notice.row(
        "Where it posts each eye that flies and the stronghold the lines meet at.", Notice.Where.CHAT);
    private final BoolSetting waypoints = new BoolSetting("Waypoints",
        "Saves a waypoint at each stronghold found and moves it as more eyes sharpen it.", true);
    private final ColorSetting colour = new ColorSetting("Colour",
        "Colour of the flight lines and the stronghold mark.", 160, false);
    private final NumberSetting scale = new NumberSetting("Scale",
        "Size of the labels.", 1, 0.5, 3, 0.1).min(0.1);
    private final FindLog finds = new FindLog(this);

    // Keyed by entity id. Ids belong to one world.
    private final Map<Integer, Flight> flights = new HashMap<>();
    // Oldest first.
    private final List<Target> targets = new ArrayList<>();
    private final List<Leak> leaks = new ArrayList<>();
    private final WorldWatch world = new WorldWatch();

    public EyeTracker() {
        super("EyeTracker", "Finds the stronghold where the flights of thrown eyes of ender cross.",
            Category.HUNTING);
        addSettings(messages, waypoints, colour, scale);
        addSettings(FindsScreen.settingsFor(finds));
        searchTags("eye of ender", "ender eye", "stronghold", "end portal", "triangulate");
    }

    @Override
    public FindLog findLog() {
        return finds;
    }

    @Override
    public List<Leak> leaks() {
        return Collections.unmodifiableList(leaks);
    }

    @Override
    public String getSuffix() {
        return count(targets.size());
    }

    @Override
    protected void onEnable() {
        clear();
        if (inGame()) {
            world.accept();
        }
    }

    @Override
    protected void onDisable() {
        clear();
        world.forget();
    }

    private void clear() {
        flights.clear();
        targets.clear();
        leaks.clear();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (world.changed()) {
            clear();
        }
        Set<Integer> flying = new HashSet<>();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity instanceof EyeOfEnder eye) {
                flying.add(eye.getId());
                // The base is where the server last placed it. The client moves its own copy ahead.
                Vec3 at = eye.getPositionCodec().getBase();
                flights.computeIfAbsent(eye.getId(), id -> new Flight(at, throwerAt(at))).last = at;
            }
        }
        flights.entrySet().removeIf(entry -> {
            if (flying.contains(entry.getKey())) {
                return false;
            }
            landed(entry.getValue());
            return true;
        });
        long now = System.currentTimeMillis();
        targets.removeIf(target -> target.place() == null && now - target.last > LONE_MS);
        leaks.clear();
        for (Target target : targets) {
            BearingFix place = target.place();
            if (place != null) {
                Leak leak = leakOf(target, place);
                leaks.add(leak);
                save(target, leak, place, now);
            }
        }
    }

    private Player throwerAt(Vec3 at) {
        Player best = null;
        double bestDistance = THROWER_REACH;
        for (Player player : mc.level.players()) {
            double across = Math.hypot(player.getX() - at.x, player.getZ() - at.z);
            if (across <= bestDistance && Math.abs(player.getY(0.5) - at.y) <= THROWER_REACH) {
                best = player;
                bestDistance = across;
            }
        }
        return best;
    }

    // A flight gives a line and one that sank came down onto the corner itself.
    private void landed(Flight flight) {
        double across = Math.hypot(flight.last.x - flight.first.x, flight.last.z - flight.first.z);
        Bearing line = across < MIN_FLIGHT ? null : new Bearing(flight.first.x, flight.first.z,
            Bearing.between(flight.first, flight.last).yaw(), 2 * PLACE_STEP / across, 0);
        BlockPos landing = flight.thrower != null && flight.last.y < flight.first.y - SANK ? landingOf(flight) : null;
        if (line == null && landing == null) {
            return;
        }
        long now = System.currentTimeMillis();
        Target target = targetFor(line, landing);
        target.last = now;
        boolean wasExact = target.corner != null;
        if (line != null) {
            target.trail.add(line, MIN_SPREAD);
        }
        if (landing != null) {
            target.corner = landing;
        }
        BearingFix place = target.place();
        if (place == null) {
            say(lineWords(flight, line, target));
            return;
        }
        if (target.corner == null && place.radius() < CORNER_DOUBT) {
            target.corner = nearCorner(place.x(), place.z());
        }
        if (!wasExact && target.corner != null) {
            // The stairs themselves are news and the waypoint moves onto them at once.
            target.told.reset();
            target.tracked.resave();
        }
        found(target, now);
    }

    // A server that moved the corner off the chunk grid has it where the eye stopped.
    private static BlockPos landingOf(Flight flight) {
        BlockPos corner = nearCorner(flight.last.x, flight.last.z);
        return corner != null ? corner : BlockPos.containing(flight.last.x, 0, flight.last.z);
    }

    private Target targetFor(Bearing line, BlockPos landing) {
        for (Target target : targets) {
            if ((line == null || target.fits(line)) && (landing == null || target.fits(landing))) {
                return target;
            }
        }
        Target fresh = new Target();
        targets.add(fresh);
        if (targets.size() > MAX_TARGETS) {
            targets.removeFirst();
        }
        return fresh;
    }

    private static BlockPos nearCorner(double x, double z) {
        BlockPos corner = cornerNearest(x, z);
        return Math.hypot(corner.getX() - x, corner.getZ() - z) <= CORNER_REACH ? corner : null;
    }

    private static boolean onCorner(BearingFix crossing) {
        BlockPos corner = cornerNearest(crossing.x(), crossing.z());
        return Math.hypot(corner.getX() - crossing.x(), corner.getZ() - crossing.z())
            <= crossing.radius() + CORNER_SLACK;
    }

    private static BlockPos cornerNearest(double x, double z) {
        return new BlockPos((int) Math.round(x / SectionPos.SECTION_SIZE) * SectionPos.SECTION_SIZE, 0,
            (int) Math.round(z / SectionPos.SECTION_SIZE) * SectionPos.SECTION_SIZE);
    }

    private void found(Target target, long now) {
        BearingFix place = target.place();
        if (target.told.due(place.x(), place.z(), place.radius(), now)) {
            say(placeWords(target, place));
        }
        log(target, place, now);
    }

    private Component lineWords(Flight flight, Bearing line, Target target) {
        String who = flight.thrower == null ? "§7An eye of ender flew"
            : flight.thrower == mc.player ? "§7Your eye of ender flew"
            : "§f" + EntityUtil.displayNameOf(flight.thrower) + " §7threw an eye of ender";
        String words = who + " §f" + line.compass() + " §7along yaw §f"
            + String.format(Locale.ROOT, "%.1f", line.degrees()) + " §7from §f" + Math.round(flight.first.x) + " "
            + Math.round(flight.first.z) + "§7.";
        if (target.trail.bearings().size() == 1) {
            words += " Throw the next one a hundred blocks or more §f" + line.sideways() + " §7of there.";
        }
        return Component.literal(words);
    }

    private Component placeWords(Target target, BearingFix place) {
        BlockPos spot = spotOf(target, place);
        if (target.corner != null) {
            return Component.literal("§fThe stronghold §7starts below ").append(ChatUtil.walkLink(spot))
                .append("§7. Its stairs go down there.");
        }
        return Component.literal("§fThe stronghold §7lies below ").append(ChatUtil.walkLink(spot))
            .append("§7 give or take §f" + Math.round(place.radius()) + " §7blocks.");
    }

    private static BlockPos spotOf(Target target, BearingFix place) {
        if (target.corner != null) {
            return new BlockPos(target.corner.getX() + STAIRS_IN, STAIRS_HEIGHT, target.corner.getZ() + STAIRS_IN);
        }
        return BlockPos.containing(place.x(), STAIRS_HEIGHT, place.z());
    }

    private static String detailOf(Target target, BearingFix place) {
        return target.corner != null ? "stairs down" : "give or take " + Math.round(place.radius()) + " blocks";
    }

    private void log(Target target, BearingFix place, long now) {
        target.tracked.log(finds, ServerInfo.dimension(), spotOf(target, place), KIND, detailOf(target, place), now);
    }

    private void save(Target target, Leak leak, BearingFix place, long now) {
        if (waypoints.isOn()) {
            target.tracked.save(leak, WAYPOINT, ServerInfo.dimension(), place.radius(), now);
        }
    }

    private Leak leakOf(Target target, BearingFix place) {
        String detail = target.corner != null ? "stairs" : "±" + Math.round(place.radius());
        return new Leak(WAYPOINT, detail, spotOf(target, place), false, ServerInfo.dimension(), target.last,
            colour.getColor());
    }

    private void say(Component words) {
        Notice.post(messages, this, words);
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!inGame() || targets.isEmpty()) {
            return;
        }
        DrawBatch batch = event.getBatch();
        double ground = mc.player.getPosition(event.getPartialTicks()).y;
        int shade = colour.getColor();
        for (Target target : targets) {
            BearingFix place = target.place();
            BlockPos stairs = target.corner != null ? spotOf(target, place) : null;
            FarShapes.crossing(batch, target.trail.bearings(), place, stairs, ground, shade);
        }
    }

    @Subscribe
    private void onRender2D(Render2DEvent event) {
        if (!inGame() || targets.isEmpty() || !WorldToScreen.update()) {
            return;
        }
        Vec3 camera = WorldToScreen.cameraPos();
        double ground = mc.player.getPosition(event.getPartialTicks()).y;
        for (Target target : targets) {
            label(event, target, camera, ground);
        }
    }

    private void label(Render2DEvent event, Target target, Vec3 camera, double ground) {
        BearingFix place = target.place();
        Vec3 spot;
        String detail;
        if (place != null) {
            spot = target.corner != null ? Vec3.atCenterOf(spotOf(target, place)).add(0, LABEL_LIFT, 0)
                : new Vec3(place.x(), camera.y, place.z());
            String sure = target.corner != null ? "" : String.format(Locale.ROOT, " ±%d", Math.round(place.radius()));
            detail = sure + String.format(Locale.ROOT, " %dm", Math.round(Math.hypot(spot.x - camera.x,
                spot.z - camera.z)));
        } else {
            Bearing newest = target.trail.newest();
            if (newest == null) {
                return;
            }
            spot = newest.along(LABEL_ALONG, ground + LABEL_LIFT);
            detail = progress(target, newest);
        }
        Vec3 screen = WorldToScreen.project(spot);
        if (screen != null) {
            RenderUtil.label(event.getContext(), mc.font, screen.x, screen.y, scale.getFloat(),
                List.of(WAYPOINT, detail), List.of(colour.getColor(), RenderUtil.MUTED_TEXT));
        }
    }

    private static String progress(Target target, Bearing newest) {
        List<Bearing> lines = target.trail.bearings();
        String walk = " throw again " + newest.sideways();
        if (lines.size() < 2) {
            return walk;
        }
        return String.format(Locale.ROOT, " spread %.1f of %.1f°", Math.toDegrees(BearingFix.spread(lines)),
            Math.toDegrees(MIN_SPREAD)) + walk;
    }
}
