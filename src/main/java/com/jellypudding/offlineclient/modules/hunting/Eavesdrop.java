package com.jellypudding.offlineclient.modules.hunting;

import com.jellypudding.offlineclient.config.FindLog;
import com.jellypudding.offlineclient.config.FindSource;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
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
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.Leak;
import com.jellypudding.offlineclient.util.Notice;
import com.jellypudding.offlineclient.util.PositionHistory;
import com.jellypudding.offlineclient.util.RenderUtil;
import com.jellypudding.offlineclient.util.RepostGate;
import com.jellypudding.offlineclient.util.ServerInfo;
import com.jellypudding.offlineclient.util.ServerWatch;
import com.jellypudding.offlineclient.util.WorldWatch;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

// A global event reaches every player on the server. Vanilla sends one within 32 blocks
// at its true block and one further away 32 blocks from you in its direction. Paper sends
// the true block within the server's view distance and otherwise a point that far away
// across the ground at the event's own height. Vanilla puts an event in another dimension
// on your own block. Paper measures it from where you stand as if it were beside you. The
// lines of events that keep coming from one place cross where that place is.
public final class Eavesdrop extends Module implements FindSource, Leak.Source {

    // The global events worth hearing with the level event id the server sends. A dragon
    // only dies in the End and a survival portal only opens in the Overworld. A kind that
    // repeats keeps coming from one place and its lines are crossed. A kept kind is a place
    // worth going back to and goes in the finds.
    private enum Heard {
        WITHER(LevelEvent.SOUND_WITHER_BOSS_SPAWN, "Wither", "A wither was spawned", null, true, true),
        DRAGON(LevelEvent.SOUND_DRAGON_DEATH, "Dragon death", "The ender dragon died", Level.END, false, false),
        PORTAL(LevelEvent.SOUND_END_PORTAL_SPAWN, "End portal", "An end portal was opened", Level.OVERWORLD,
            false, true),
        // Every other id. Only a plugin sends one.
        OTHER(0, "Event", "A global event happened", null, true, false);

        private final int id;
        private final String label;
        private final String news;
        // Null for an event that can happen in any dimension.
        private final ResourceKey<Level> home;
        private final boolean repeats;
        private final boolean kept;

        Heard(int id, String label, String news, ResourceKey<Level> home, boolean repeats, boolean kept) {
            this.id = id;
            this.label = label;
            this.news = news;
            this.home = home;
            this.repeats = repeats;
            this.kept = kept;
        }

        private static Heard of(int id) {
            for (Heard heard : values()) {
                if (heard != OTHER && heard.id == id) {
                    return heard;
                }
            }
            return OTHER;
        }

        // A plugin event is named by its id.
        private String label(int eventId) {
            return this == OTHER ? "Event " + eventId : label;
        }

        private String news(int eventId) {
            return this == OTHER ? "A global event " + eventId + " happened" : news;
        }
    }

    // One event as it arrived. Home is the dimension its place lies in or null when it could
    // be any. Here is the dimension you heard it in.
    private record Event(Heard kind, int id, String home, String here, BlockPos pos, boolean paper, long now) {
    }

    // One place events of one kind come from. An event of a kind that repeats joins the
    // origin its line or block can point at. Every other event gets an origin of its own.
    private static final class Origin {
        private final Heard kind;
        private final int id;
        // Where the place lies. Null when it could be in any dimension.
        private final String home;
        // The dimension you first heard it in.
        private final String heardIn;
        private final BearingTrail trail = new BearingTrail(SPOT, false);
        // The true block once an event came from close by.
        private BlockPos exact;
        // Where a close event came from when Paper cut its block to nought on some axis.
        private BearingFix near;
        // The height of the place when the server kept it. Paper keeps it on a far event.
        private int height;
        private boolean knowsHeight;
        private long last;
        private final RepostGate told = RepostGate.forChat();
        private final RepostGate saved = RepostGate.forWaypoint();
        // The waypoint and the find this origin keeps. Null until it has one.
        private String waypoint;
        private FindLog.Find logged;

        private Origin(Heard kind, int id, String home, String heardIn) {
            this.kind = kind;
            this.id = id;
            this.home = home;
            this.heardIn = heardIn;
        }

        private String label() {
            return kind.label(id);
        }

        private String news() {
            return kind.news(id);
        }

        // The true block as a fix that cannot be off or the place of a close event or else the
        // crossing of the lines. Null whilst none is known.
        private BearingFix place(double minSpread) {
            if (exact != null) {
                return new BearingFix(exact.getX() + 0.5, exact.getZ() + 0.5, 0);
            }
            return near != null ? near : trail.fix(minSpread);
        }

        private boolean fits(Bearing line, double minSpread) {
            BearingFix place = place(minSpread);
            return place != null ? !place.missedBy(line, SPOT) : trail.isEmpty() || trail.fits(line, minSpread);
        }

        // A true block fits when each line the origin holds could point at it.
        private boolean fits(BlockPos block, double minSpread) {
            BearingFix spot = new BearingFix(block.getX() + 0.5, block.getZ() + 0.5, 0);
            BearingFix place = place(minSpread);
            if (place != null) {
                return Math.hypot(spot.x() - place.x(), spot.z() - place.z()) <= place.radius() + SPOT.lost();
            }
            for (Bearing line : trail.bearings()) {
                if (spot.missedBy(line, SPOT)) {
                    return false;
                }
            }
            return true;
        }
    }

    // Paper sends a far event at the server's view distance across the ground. That is
    // a whole number of chunks and the point lands within a hair of it. The distance is
    // learnt once two such events that landed apart share the same number.
    private static final class RelayRing {
        private double ring = Double.NaN;
        // The last event that could have come from the ring.
        private double candidate = Double.NaN;
        private Vec3 candidateAt = Vec3.ZERO;

        // True when the event came from its own block. A far event from a ring not yet
        // learnt counts as far. The place then lies on its line at the point or past it.
        private boolean close(double across, Vec3 point, double slack) {
            if (across > ring + slack) {
                // Past the ring cannot happen. The server now relays from further out.
                ring = Double.NaN;
            }
            if (!Double.isNaN(ring)) {
                return across < ring - slack;
            }
            double chunks = Math.round(across / SectionPos.SECTION_SIZE) * (double) SectionPos.SECTION_SIZE;
            if (chunks == 0 || Math.abs(across - chunks) > slack) {
                return true;
            }
            if (chunks == candidate && candidateAt.distanceTo(point) > RING_PROOF) {
                ring = chunks;
            }
            candidate = chunks;
            candidateAt = point;
            return false;
        }

        private void forget() {
            ring = Double.NaN;
            candidate = Double.NaN;
        }
    }

    // Vanilla sends a far event this far from you.
    private static final double VANILLA_RELAY = 32;

    // Room for the rounding of a far point on top of how well the server knew where you stood.
    private static final double RELAY_SLACK = 1;

    // A block position stands for any point in its block. Its spread either way is this.
    private static final double ROUNDING = Math.sqrt(1 / 12.0);

    // A coordinate Paper cut to nought could lie in the block on either side of nought. The
    // place is then at most this far from where those blocks meet.
    private static final double CUT_DOUBT = Math.sqrt(2);

    // A true event lands on the same block every time. Two far ones this far apart were
    // heard from different places.
    private static final double RING_PROOF = 3;

    // A wither spawns where its skull went on and a portal never moves. A line that misses
    // by more than a few blocks and its blur came from somewhere else.
    private static final BearingFix.Tolerance SPOT = new BearingFix.Tolerance(1, 8);

    // An event on any block you stood on in the last second landed on your own spot.
    private static final double OWN_SPOT = 1;
    private static final int OWN_TICKS = 20;

    // Nearer than this across the ground there is no telling which way it lies.
    private static final double LEVEL_MIN = 1;

    // Where along a direction line its name sits.
    private static final double LABEL_ALONG = 32;

    private static final double BEAM = 64;

    private static final double LABEL_LIFT = 1.5;

    // The lines of an origin that already has a place draw this faint.
    private static final float SETTLED_LINES = 0.4f;

    private static final int MAX_ORIGINS = 64;

    private final BoolSetting withers = new BoolSetting("Wither spawns",
        "Reports a wither being spawned anywhere on the server. Paper sends one from another"
            + " dimension as if it were in yours.", true);
    private final BoolSetting dragons = new BoolSetting("Dragon deaths",
        "Reports the ender dragon dying.", true);
    private final BoolSetting portals = new BoolSetting("End portals",
        "Reports an end portal being opened which gives away a stronghold.", true);
    private final BoolSetting others = new BoolSetting("Other events",
        "Also reports global events no vanilla source sends. Only plugins send them.", false);
    private final BoolSetting elsewhere = new BoolSetting("Other dimensions",
        "Also reports events in a dimension you are not in. Vanilla gives no place for them. Paper gives"
            + " dragon deaths and portals a place in that dimension's own coordinates.", true);
    private final BoolSetting crossLines = new BoolSetting("Cross lines",
        "Works out where repeated events such as a wither farm come from by crossing lines heard from"
            + " different spots. Walk sideways between events.", true);
    private final NumberSetting spread = new NumberSetting("Minimum spread",
        "How far apart two lines must point before they are crossed. More gives a surer first estimate.",
        3, 0.5, 30, 0.5, " degrees").min(0.1).under(crossLines);
    private final EnumSetting<Notice.Where> messages = Notice.row(
        "Where it posts each event until the place it came from is known. After that only a moved"
            + " place is posted.", Notice.Where.CHAT);
    private final BoolSetting waypoints = new BoolSetting("Waypoints",
        "Saves a waypoint at each wither spot and end portal found and moves it as its estimate"
            + " sharpens.", false);
    private final NumberSetting markTime = new NumberSetting("Mark time",
        "How long the marks of a place stay after the last event from it.", 10, 1, 60, 1, " minutes")
        .min(0.1);
    private final ColorSetting color = new ColorSetting("Colour",
        "Colour of event marks and bearing lines.", 280, false);
    private final NumberSetting scale = new NumberSetting("Scale",
        "Size of the labels.", 1, 0.5, 3, 0.1).min(0.1);
    private final FindLog finds = new FindLog(this);

    // Oldest first.
    private final List<Origin> origins = new ArrayList<>();
    // What the origins give away. Rebuilt every tick.
    private final List<Leak> leaks = new ArrayList<>();
    // Filled from the network thread and handled on the next tick.
    private final Queue<ClientboundLevelEventPacket> heard = new ConcurrentLinkedQueue<>();
    private final PositionHistory history = new PositionHistory();
    private final RelayRing ring = new RelayRing();
    // Lines heard in the Nether and the Overworld share one frame on Paper. Only another
    // server makes them worthless.
    private final ServerWatch server = new ServerWatch();
    private final WorldWatch world = new WorldWatch();

    public Eavesdrop() {
        super("Eavesdrop", "Points to wither spawns and end portals and dragon deaths anywhere on the server.",
            Category.HUNTING);
        addSettings(withers, dragons, portals, others, elsewhere, crossLines, spread, messages, waypoints, markTime,
            color, scale);
        addSettings(FindsScreen.settingsFor(finds));
        searchTags("wither", "stronghold", "sound", "world event", "coords", "triangulate", "wither farm");
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
        return count(origins.size());
    }

    @Override
    protected void onEnable() {
        clear();
    }

    @Override
    protected void onDisable() {
        clear();
        server.forget();
        world.forget();
    }

    private void clear() {
        origins.clear();
        leaks.clear();
        heard.clear();
        history.clear();
        ring.forget();
    }

    private double minSpread() {
        return Math.toRadians(spread.getValue());
    }

    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacket() instanceof ClientboundLevelEventPacket packet && packet.isGlobalEvent()) {
            heard.add(packet);
        }
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            return;
        }
        if (server.changed()) {
            clear();
        }
        if (world.changed()) {
            // Your old spots and the events still queued are in another world's numbers. A
            // proxy may have moved you to a server with another view distance.
            history.clear();
            ring.forget();
            heard.clear();
        }
        history.record(mc.player.position());
        long now = System.currentTimeMillis();
        ClientboundLevelEventPacket packet;
        while ((packet = heard.poll()) != null) {
            hear(packet, now);
        }
        long oldest = now - Math.round(markTime.getValue() * TimeUnit.MINUTES.toMillis(1));
        origins.removeIf(origin -> origin.last < oldest);
        leaks.clear();
        for (Origin origin : origins) {
            Leak leak = leakOf(origin);
            if (leak != null) {
                leaks.add(leak);
                save(origin, leak, now);
            }
        }
    }

    private boolean watching(Heard kind) {
        return switch (kind) {
            case WITHER -> withers.isOn();
            case DRAGON -> dragons.isOn();
            case PORTAL -> portals.isOn();
            case OTHER -> others.isOn();
        };
    }

    private void hear(ClientboundLevelEventPacket packet, long now) {
        Heard kind = Heard.of(packet.getType());
        Vec3 me = history.asServerSaw();
        if (!watching(kind) || me == null) {
            return;
        }
        // Paper relays its own wither and dragon and portal events. A plugin event comes the
        // vanilla way.
        boolean paper = ServerInfo.runsPaper() && kind != Heard.OTHER;
        BlockPos pos = packet.getPos();
        Vec3 point = paper ? truncated(pos) : Vec3.atCenterOf(pos);
        String here = ServerInfo.dimension();
        String home = kind.home == null ? null : kind.home.identifier().toString();
        if (heardElsewhere(kind, packet.getType(), paper, point, home, here)) {
            return;
        }
        // Paper measures a wither from where you stand whatever dimension it is in.
        Event event = new Event(kind, packet.getType(), home != null ? home : paper ? null : here, here, pos, paper,
            now);
        double slack = RELAY_SLACK + history.serverSawSlack();
        double across = Math.hypot(point.x - me.x, point.z - me.z);
        if (paper ? ring.close(across, point, slack) : point.distanceTo(me) < VANILLA_RELAY - slack) {
            heardAt(event);
        } else {
            heardFar(event, me, point, across, slack);
        }
    }

    // True for an event in a dimension you are not in that goes no further. Vanilla puts
    // one on your own spot and only its news is left to tell.
    private boolean heardElsewhere(Heard kind, int id, boolean paper, Vec3 point, String home, String here) {
        boolean away = home != null && !home.equals(here);
        if (paper) {
            return away && !elsewhere.isOn();
        }
        if (!away && (home != null || !history.wasNear(point, OWN_SPOT, OWN_TICKS))) {
            return false;
        }
        if (elsewhere.isOn()) {
            say("§f" + kind.news(id) + " §7"
                + (home != null ? "in the " + ServerInfo.dimensionName(home) : "in another dimension") + ".");
        }
        return true;
    }

    // Paper cuts a far point towards nought rather than down. The spot it stood for sits
    // half a block further out.
    private static Vec3 truncated(BlockPos pos) {
        return new Vec3(outward(pos.getX()), pos.getY() + 0.5, outward(pos.getZ()));
    }

    private static double outward(int coordinate) {
        return coordinate > 0 ? coordinate + 0.5 : coordinate < 0 ? coordinate - 0.5 : coordinate;
    }

    // The block a coordinate Paper cut towards nought lies in. Below nought it is one further
    // down. At nought it could also be the block below.
    private static int uncut(int coordinate) {
        return coordinate < 0 ? coordinate - 1 : coordinate;
    }

    // An event that came from its own block. Paper cuts the block of a dying dragon towards
    // nought and one cut to nought across the ground is only known to within a block.
    private void heardAt(Event event) {
        BlockPos pos = event.pos();
        boolean cut = event.paper() && event.kind() == Heard.DRAGON;
        BlockPos block = cut ? new BlockPos(uncut(pos.getX()), uncut(pos.getY()), uncut(pos.getZ())) : pos;
        Origin origin = originFor(event, true, candidate -> candidate.fits(block, minSpread()));
        if (cut && (pos.getX() == 0 || pos.getZ() == 0)) {
            Vec3 middle = truncated(pos);
            origin.near = new BearingFix(middle.x, middle.z, CUT_DOUBT);
        } else {
            origin.exact = block;
        }
        origin.height = block.getY();
        origin.knowsHeight = true;
        heard(origin, event);
    }

    // An event known only by the line from where the server saw you towards it.
    private void heardFar(Event event, Vec3 me, Vec3 point, double across, double slack) {
        String from = BlockUtil.text(BlockPos.containing(me));
        if (across < LEVEL_MIN) {
            say("§f" + event.kind().news(event.id()) + " §7straight above or below §f" + from + "§7.");
            return;
        }
        // The point stands for a spot in its block and the server saw you a moment ago.
        // Either blurs the line by as much as it moves the point sideways.
        double blur = Math.hypot(ROUNDING, history.serverSawSlack()) / across;
        Bearing line = new Bearing(me.x, me.z, Bearing.between(me, point).yaw(), blur, Math.max(0, across - slack));
        double least = minSpread();
        Origin origin = originFor(event, crossLines.isOn(), candidate -> candidate.fits(line, least));
        origin.trail.add(line, least);
        if (event.paper()) {
            // Paper keeps the event's own height on the far point. That of a wither or a
            // dragon is cut towards nought.
            int height = event.pos().getY();
            origin.height = event.kind() == Heard.PORTAL ? height : uncut(height);
            origin.knowsHeight = true;
        }
        if (origin.place(least) == null) {
            say(lineWords(origin, line, from, event.paper()));
        }
        heard(origin, event);
    }

    private void heard(Origin origin, Event event) {
        origin.last = event.now();
        BearingFix place = origin.place(minSpread());
        if (place == null) {
            return;
        }
        if (origin.told.due(place.x(), place.z(), place.radius(), event.now())) {
            say(placeWords(origin, place));
        }
        log(origin, place, event.now());
    }

    // The origin the event could have come from or a new one. A true block always joins the
    // origin at its spot. A line only joins one whilst lines are crossed.
    private Origin originFor(Event event, boolean gather, Predicate<Origin> fits) {
        if (event.kind().repeats && gather) {
            for (Origin origin : origins) {
                if (origin.id == event.id() && Objects.equals(origin.home, event.home()) && fits.test(origin)) {
                    return origin;
                }
            }
        }
        Origin fresh = new Origin(event.kind(), event.id(), event.home(), event.here());
        origins.add(fresh);
        if (origins.size() > MAX_ORIGINS) {
            origins.removeFirst();
        }
        return fresh;
    }

    private String lineWords(Origin origin, Bearing line, String from, boolean paper) {
        StringBuilder words = new StringBuilder("§f").append(origin.news()).append(" §7at least §f")
            .append(Math.round(line.beyond())).append(" §7blocks §f").append(line.compass()).append(" §7of §f")
            .append(from).append(" §7along yaw §f").append(String.format(Locale.ROOT, "%.1f", line.degrees()));
        if (paper) {
            words.append(" §7at height §f").append(origin.height);
        }
        words.append(dimensionWords(origin)).append("§7.");
        if (origin.kind.repeats && crossLines.isOn() && origin.trail.bearings().size() == 1) {
            words.append(" Walk §f").append(line.sideways()).append(" §7before the next one.");
        }
        return words.toString();
    }

    private String placeWords(Origin origin, BearingFix place) {
        if (origin.exact != null) {
            return "§f" + origin.news() + " §7at §f" + BlockUtil.text(origin.exact) + dimensionWords(origin) + "§7.";
        }
        if (origin.near != null) {
            return "§f" + origin.news() + " §7within a block of §f" + BlockUtil.text(spotOf(origin, place))
                + dimensionWords(origin) + "§7.";
        }
        String height = origin.knowsHeight ? " §7at height §f" + origin.height : "";
        return "§f" + origin.label() + " §7comes from around §f" + Math.round(place.x()) + " " + Math.round(place.z())
            + " §7give or take §f" + Math.round(place.radius()) + " §7blocks" + height + dimensionWords(origin) + "§7.";
    }

    // Names the dimension the place lies in when it is not yours or cannot be known.
    private String dimensionWords(Origin origin) {
        if (origin.home == null) {
            return ServerInfo.runsPaper() ? " §7in whichever dimension it came from" : "";
        }
        return origin.home.equals(ServerInfo.dimension())
            ? "" : " §7in the " + ServerInfo.dimensionName(origin.home);
    }

    // A place worth going back to goes in the finds and follows the estimate as it sharpens.
    private void log(Origin origin, BearingFix place, long now) {
        if (!origin.kind.kept) {
            return;
        }
        String dimension = keptIn(origin);
        BlockPos spot = spotOf(origin, place);
        String kind = origin.kind.label.toLowerCase(Locale.ROOT);
        String detail = origin.exact != null ? "exact"
            : "give or take " + Math.round(place.radius()) + " blocks" + (origin.home == null ? " in any dimension" : "");
        FindLog.Find logged = origin.logged;
        if (logged == null || !logged.pos().equals(spot) || !logged.detail().equals(detail)) {
            if (logged != null && !logged.pos().equals(spot)) {
                finds.remove(logged);
            }
            finds.add(dimension, spot, kind, detail);
            origin.logged = new FindLog.Find(dimension, spot, kind, detail, now);
        }
    }

    // A place worth going back to takes a waypoint that moves as the estimate sharpens.
    private void save(Origin origin, Leak leak, long now) {
        BearingFix place = origin.place(minSpread());
        if (!waypoints.isOn() || !origin.kind.kept || !origin.saved.due(place.x(), place.z(), place.radius(), now)) {
            return;
        }
        if (origin.waypoint == null) {
            origin.waypoint = leak.saveFresh(origin.label().replace(" ", ""), keptIn(origin));
        } else {
            leak.saveAs(origin.waypoint, keptIn(origin));
        }
    }

    // A place that could lie in any dimension is kept in the one it was first heard in.
    private static String keptIn(Origin origin) {
        return origin.home != null ? origin.home : origin.heardIn;
    }

    // The block standing for the place. An estimate without a known height takes yours.
    private BlockPos spotOf(Origin origin, BearingFix place) {
        if (origin.exact != null) {
            return origin.exact;
        }
        int y = origin.knowsHeight ? origin.height : mc.player.getBlockY();
        return BlockPos.containing(place.x(), y, place.z());
    }

    // Null whilst the origin has no place.
    private Leak leakOf(Origin origin) {
        BearingFix place = origin.place(minSpread());
        if (place == null) {
            return null;
        }
        String detail = origin.exact != null ? "exact" : "±" + Math.round(place.radius());
        return new Leak(origin.label(), detail, spotOf(origin, place), origin.knowsHeight, origin.home,
            origin.last, color.getColor());
    }

    private void say(String text) {
        Notice.post(messages, this, Component.literal(text));
    }

    // An origin is drawn in the dimension its place lies in. One that could lie in any is
    // drawn in every one.
    private boolean drawnHere(Origin origin) {
        return origin.home == null || origin.home.equals(ServerInfo.dimension());
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!inGame() || origins.isEmpty()) {
            return;
        }
        DrawBatch batch = event.getBatch();
        double ground = mc.player.getPosition(event.getPartialTicks()).y;
        int shade = color.getColor();
        double least = minSpread();
        for (Origin origin : origins) {
            if (!drawnHere(origin)) {
                continue;
            }
            BearingFix place = origin.place(least);
            int lineShade = place == null ? shade : ColorUtil.fade(shade, SETTLED_LINES);
            for (Bearing line : origin.trail.bearings()) {
                FarShapes.ray(batch, line, ground, lineShade);
            }
            if (origin.exact != null) {
                Vec3 top = Vec3.atCenterOf(origin.exact);
                batch.outlineBox(FarShapes.pullIn(DrawBatch.blockBox(origin.exact)), shade, true);
                FarShapes.line(batch, top, top.add(0, BEAM, 0), shade);
            } else if (place != null) {
                FarShapes.estimate(batch, place.x(), place.z(), place.radius(), shade);
            }
        }
    }

    @Subscribe
    private void onRender2D(Render2DEvent event) {
        if (!inGame() || origins.isEmpty() || !WorldToScreen.update()) {
            return;
        }
        Vec3 camera = WorldToScreen.cameraPos();
        double ground = mc.player.getPosition(event.getPartialTicks()).y;
        double least = minSpread();
        for (Origin origin : origins) {
            if (drawnHere(origin)) {
                label(event, origin, camera, ground, least);
            }
        }
    }

    private void label(Render2DEvent event, Origin origin, Vec3 camera, double ground, double least) {
        BearingFix place = origin.place(least);
        Vec3 spot;
        String detail;
        if (origin.exact != null) {
            spot = Vec3.atCenterOf(origin.exact).add(0, LABEL_LIFT, 0);
            detail = String.format(Locale.ROOT, " %dm", Math.round(camera.distanceTo(spot)));
        } else if (place != null) {
            spot = new Vec3(place.x(), camera.y, place.z());
            detail = String.format(Locale.ROOT, " ±%d %dm", Math.round(place.radius()),
                Math.round(Math.hypot(place.x() - camera.x, place.z() - camera.z)));
        } else {
            Bearing newest = origin.trail.newest();
            if (newest == null) {
                return;
            }
            spot = newest.along(LABEL_ALONG, ground + LABEL_LIFT);
            detail = progress(origin, newest, least);
        }
        Vec3 screen = WorldToScreen.project(spot);
        if (screen != null) {
            RenderUtil.label(event.getContext(), mc.font, screen.x, screen.y, scale.getFloat(),
                List.of(origin.label(), detail), List.of(color.getColor(), RenderUtil.MUTED_TEXT));
        }
    }

    // What an origin without a place still needs. One line needs a second from somewhere to
    // the side and lines too close in angle need more spread.
    private String progress(Origin origin, Bearing newest, double least) {
        if (!origin.kind.repeats || !crossLines.isOn()) {
            return " " + newest.compass();
        }
        List<Bearing> lines = origin.trail.bearings();
        String walk = " walk " + newest.sideways();
        if (lines.size() < 2) {
            return walk;
        }
        return String.format(Locale.ROOT, " spread %.1f of %.1f°", Math.toDegrees(BearingFix.spread(lines)),
            Math.toDegrees(least)) + walk;
    }
}
