package com.jellypudding.offlineclient.modules.hunting;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render2DEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
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
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.Leak;
import com.jellypudding.offlineclient.util.Notice;
import com.jellypudding.offlineclient.util.PositionHistory;
import com.jellypudding.offlineclient.util.RenderUtil;
import com.jellypudding.offlineclient.util.RepostGate;
import com.jellypudding.offlineclient.util.ServerInfo;
import com.jellypudding.offlineclient.util.WorldWatch;
import com.mojang.datafixers.util.Either;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.waypoints.TrackedWaypoint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

// The server links you to every other player in one of three ways. Past 332 blocks it
// sends only the yaw from you to them. Out of your view it sends their chunk. Otherwise it
// sends the block they stand in. A block or chunk link only changes when the player moves
// fast or comes into view and a slow player keeps it at any range. A yaw becomes one of
// those once they come within 332 blocks. It is exact for the moment the server sends it
// and a new one arrives each time it drifts half a degree.
public final class Locator extends Module implements Leak.Source {

    private enum Kind { EXACT, CHUNK, BEARING }

    // One player the locator bar tells you about.
    private static final class Target {
        // Null for a waypoint a plugin named with text.
        private final UUID uuid;
        private String name = "";
        private Kind kind = Kind.BEARING;
        // The block or chunk the server last placed them in. Both are kept once the link
        // turns into a yaw and show where they were last seen.
        private BlockPos block = BlockPos.ZERO;
        private ChunkPos chunk = new ChunkPos(0, 0);
        // Which of the two came last and when it was last true. Null before either.
        private Kind placed;
        private long placedAt;
        // The last yaw the server sent. A change means a fresh reading arrived.
        private float angle = Float.NaN;
        private final BearingTrail trail = new BearingTrail(PLAYER, true);
        // When the newest reading was taken.
        private long readAt;
        private final RepostGate toldPlace = RepostGate.forChat();
        private final RepostGate toldEstimate = RepostGate.forChat();
        private final RepostGate saved = RepostGate.forWaypoint();
        private long lastSeen;

        private Target(UUID uuid) {
            this.uuid = uuid;
        }

        // A player the server places directly needs no bearings.
        private void place(Kind link, long when) {
            if (kind != link) {
                kind = link;
                angle = Float.NaN;
                trail.clear();
            }
            placed = link;
            placedAt = when;
        }
    }

    // Where a label goes and what it says.
    private record Label(Vec3 at, String kind, int color, boolean distance) {
    }

    // A player shuffles a few blocks about their spot. One a fresh line misses by more
    // than 32 blocks has moved on.
    private static final BearingFix.Tolerance PLAYER = new BearingFix.Tolerance(2.5, 32);

    // The server only sends a yaw past 332 blocks. A crossing much nearer than this to where
    // a line was taken is wrong. A player far above or below you can be nearer across the ground.
    private static final double FAR = 128;

    // The server sends a new yaw once it drifts this far from the last one it sent.
    private static final double RESEND = Math.toRadians(0.5);

    private static final double FULL_TURN = 2 * Math.PI;

    // Waypoints are named after the player with this after it.
    private static final String WAYPOINT_SUFFIX = "Located";

    // Where along a bearing line its name sits.
    private static final double LINE_LABEL = 24;

    // How tall the box on an exact mark is. A player stands under two blocks.
    private static final double BODY = 2;

    private static final double LABEL_LIFT = 0.5;

    private static final float FILL_SHARE = 0.2f;

    private static final float LINE_SHARE = 0.6f;

    // A player the server stopped sending and a spot they left draw this faint.
    private static final float GONE_STRENGTH = 0.45f;

    private static final long LIVE_MS = 1000;

    private static final int SHORT_ID = 8;

    private final BoolSetting exact = new BoolSetting("Exact",
        "Marks the block a player stands in when the server sends it exactly. Once the link turns"
            + " into a bearing the last block stays faint with how long ago it was.", true);
    private final BoolSetting chunks = new BoolSetting("Chunks",
        "Marks the chunk column a player stands in when the server sends only that.", true);
    private final BoolSetting estimates = new BoolSetting("Estimates",
        "Marks where far players probably are by crossing bearings taken from different spots."
            + " Travel sideways to a player to gather them. Only a player who stays put gives a true estimate.",
        true);
    private final NumberSetting spread = new NumberSetting("Minimum spread",
        "How far apart two bearings must point before they are crossed. More gives a surer first estimate.",
        5, 1, 30, 0.5, " degrees").min(0.1).under(estimates);
    private final BoolSetting lines = new BoolSetting("Bearing lines",
        "Draws a line along the ground from you towards each far player.", true);
    private final BoolSetting hideSeen = new BoolSetting("Hide seen players",
        "Players you can already see get no mark or message and stay off the leaks list.", true);
    private final NumberSetting markTime = new NumberSetting("Mark time",
        "How long a mark stays once the server stops sending that player.",
        5, 1, 30, 1, " minutes").min(0);
    private final EnumSetting<Notice.Where> messages = Notice.row(
        "Where it posts each player when the server places them and when an estimate appears."
            + " A player on the move is posted at most once a minute.", Notice.Where.CHAT);
    private final BoolSetting waypoints = new BoolSetting("Waypoints",
        "Saves a waypoint named after each player you locate and moves it as they move. A chunk"
            + " or an estimate takes your own height.", false);
    private final ColorSetting exactColor = new ColorSetting("Exact colour",
        "Colour of exact marks.", 120, false).under(exact);
    private final ColorSetting chunkColor = new ColorSetting("Chunk colour",
        "Colour of chunk columns.", 50, false).under(chunks);
    private final ColorSetting farColor = new ColorSetting("Estimate colour",
        "Colour of estimates and bearing lines.", 15, false);
    private final NumberSetting scale = new NumberSetting("Scale",
        "Size of the labels.", 1, 0.5, 3, 0.1).min(0.1);

    // Keyed by the waypoint id. Players are keyed by their account id.
    private final Map<Either<UUID, String>, Target> targets = new HashMap<>();
    // What each target gives away. Rebuilt every tick.
    private final List<Leak> leaks = new ArrayList<>();
    private final PositionHistory history = new PositionHistory();
    private final WorldWatch world = new WorldWatch();

    // False for the first tick after a clear. A yaw the client already holds then may be
    // half a degree stale because the server only resends once it drifts that far.
    private boolean listening;

    public Locator() {
        super("Locator", "Finds other players through the locator bar unless the server turns it off.",
            Category.HUNTING);
        addSettings(exact, chunks, estimates, spread, lines, hideSeen, markTime, messages, waypoints,
            exactColor, chunkColor, farColor, scale);
        searchTags("locator bar", "waypoint", "triangulate", "coords");
    }

    @Override
    public String getSuffix() {
        return count(targets.size());
    }

    @Override
    public List<Leak> leaks() {
        return Collections.unmodifiableList(leaks);
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
        targets.clear();
        leaks.clear();
        history.clear();
        listening = false;
    }

    private double minSpread() {
        return Math.toRadians(spread.getValue());
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            return;
        }
        if (world.changed()) {
            clear();
        }
        history.record(mc.player.position());
        long now = System.currentTimeMillis();
        mc.player.connection.getWaypointManager().forEachWaypoint(mc.player, waypoint -> read(waypoint, now));
        long keep = Math.round(markTime.getValue() * TimeUnit.MINUTES.toMillis(1));
        targets.values().removeIf(target -> now - target.lastSeen > keep);
        leaks.clear();
        for (Target target : targets.values()) {
            BearingFix fix = estimate(target);
            Leak leak = leakOf(target, fix, now);
            if (leak != null) {
                leaks.add(leak);
                tell(target, fix, now);
                save(target, leak, fix, now);
            }
        }
        listening = true;
    }

    private void read(TrackedWaypoint waypoint, long now) {
        Either<UUID, String> id = waypoint.id();
        Target target = targets.computeIfAbsent(id, key -> new Target(key.left().orElse(null)));
        target.name = nameOf(id);
        target.lastSeen = now;
        if (waypoint instanceof TrackedWaypoint.Vec3iWaypoint spot) {
            Vec3i at = spot.vector;
            target.place(Kind.EXACT, now);
            target.block = new BlockPos(at.getX(), at.getY(), at.getZ());
        } else if (waypoint instanceof TrackedWaypoint.ChunkWaypoint column) {
            target.place(Kind.CHUNK, now);
            target.chunk = column.chunkPos;
        } else if (waypoint instanceof TrackedWaypoint.AzimuthWaypoint azimuth) {
            heard(target, azimuth.angle, now);
        }
    }

    // The tab list names a player by the account id the waypoint carries.
    private String nameOf(Either<UUID, String> id) {
        return id.map(uuid -> {
            PlayerInfo info = mc.player.connection.getPlayerInfo(uuid);
            if (info != null) {
                return info.getProfile().name();
            }
            Entity entity = mc.level.getEntity(uuid);
            return entity != null ? entity.getName().getString() : uuid.toString().substring(0, SHORT_ID);
        }, text -> text);
    }

    private void heard(Target target, float angle, long now) {
        target.kind = Kind.BEARING;
        if (angle == target.angle) {
            return;
        }
        boolean held = Float.isNaN(target.angle) && !listening;
        boolean flipped = flipped(target.angle, angle);
        target.angle = angle;
        Vec3 origin = history.asServerSaw();
        if (!held && !flipped && origin != null) {
            target.trail.add(new Bearing(origin.x, origin.z, angle, 0, FAR), minSpread());
            target.readAt = now;
        }
    }

    // The server compares raw yaws. Crossing the line due north of a player swaps the sign and
    // resends a line that barely turned. Taken as a reading it would look as if they moved.
    private static boolean flipped(float last, float angle) {
        double turn = Math.IEEEremainder(angle - last, FULL_TURN);
        return Math.abs(angle - last) > Math.PI && Math.abs(turn) < 2 * RESEND;
    }

    // Null whilst the target is placed directly or its bearings do not cross yet.
    private BearingFix estimate(Target target) {
        return target.kind == Kind.BEARING && estimates.isOn() ? target.trail.fix(minSpread()) : null;
    }

    // Null when the target gives nothing away worth listing.
    private Leak leakOf(Target target, BearingFix fix, long now) {
        if (seen(target)) {
            return null;
        }
        String here = ServerInfo.dimension();
        int y = mc.player.getBlockY();
        float strength = strength(target, now);
        return switch (target.kind) {
            case EXACT -> exact.isOn() ? new Leak(target.name, "exact", target.block, true, here, target.placedAt,
                ColorUtil.fade(exactColor.getColor(), strength)) : null;
            case CHUNK -> chunks.isOn() ? new Leak(target.name, "chunk", middle(target.chunk, y), false, here,
                target.placedAt, ColorUtil.fade(chunkColor.getColor(), strength)) : null;
            case BEARING -> {
                if (fix != null) {
                    yield new Leak(target.name, "±" + Math.round(fix.radius()), BlockPos.containing(fix.x(), y, fix.z()),
                        false, here, target.readAt, ColorUtil.fade(farColor.getColor(), strength));
                }
                yield lastSpot(target, y, here);
            }
        };
    }

    // Where a player now known only by yaw was last placed. Null when never or not shown.
    private Leak lastSpot(Target target, int y, String here) {
        if (target.placed == Kind.EXACT && exact.isOn()) {
            return new Leak(target.name, "last seen", target.block, true, here, target.placedAt,
                ColorUtil.fade(exactColor.getColor(), GONE_STRENGTH));
        }
        if (target.placed == Kind.CHUNK && chunks.isOn()) {
            return new Leak(target.name, "last chunk", middle(target.chunk, y), false, here, target.placedAt,
                ColorUtil.fade(chunkColor.getColor(), GONE_STRENGTH));
        }
        return null;
    }

    private static BlockPos middle(ChunkPos chunk, int y) {
        return new BlockPos(chunk.getMiddleBlockX(), y, chunk.getMiddleBlockZ());
    }

    // A placed player is posted when first placed and a new estimate when it appears.
    // Either one on the move is posted once a minute at most.
    private void tell(Target target, BearingFix fix, long now) {
        if (messages.is(Notice.Where.OFF)) {
            return;
        }
        switch (target.kind) {
            case EXACT -> {
                BlockPos block = target.block;
                if (target.toldPlace.due(block.getX(), block.getZ(), 0, now)) {
                    Notice.post(messages, this, Component.literal("§f" + target.name + " §7stands at ")
                        .append(ChatUtil.walkLink(block)).append("§7."));
                }
            }
            case CHUNK -> {
                ChunkPos chunk = target.chunk;
                if (target.toldPlace.due(chunk.getMiddleBlockX(), chunk.getMiddleBlockZ(), 0, now)) {
                    Notice.post(messages, this, Component.literal("§f" + target.name + " §7is in the chunk around §f"
                        + chunk.getMiddleBlockX() + " " + chunk.getMiddleBlockZ() + "§7."));
                }
            }
            case BEARING -> {
                if (fix != null && target.toldEstimate.due(fix.x(), fix.z(), fix.radius(), now)) {
                    Notice.post(messages, this, Component.literal("§f" + target.name + " §7is around §f"
                        + Math.round(fix.x()) + " " + Math.round(fix.z()) + " §7give or take §f"
                        + Math.round(fix.radius()) + " §7blocks if they stayed put."));
                }
            }
        }
    }

    private void save(Target target, Leak leak, BearingFix fix, long now) {
        double radius = fix == null ? 0 : fix.radius();
        if (waypoints.isOn() && target.saved.due(leak.pos().getX(), leak.pos().getZ(), radius, now)) {
            leak.saveAs(target.name.replace(" ", "") + WAYPOINT_SUFFIX, ServerInfo.dimension());
        }
    }

    private boolean seen(Target target) {
        return hideSeen.isOn() && target.uuid != null && mc.level.getEntity(target.uuid) != null;
    }

    private static float strength(Target target, long now) {
        return now - target.lastSeen < LIVE_MS ? 1 : GONE_STRENGTH;
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!inGame() || targets.isEmpty()) {
            return;
        }
        DrawBatch batch = event.getBatch();
        Vec3 feet = mc.player.getPosition(event.getPartialTicks());
        long now = System.currentTimeMillis();
        for (Target target : targets.values()) {
            if (seen(target)) {
                continue;
            }
            float strength = strength(target, now);
            switch (target.kind) {
                case EXACT -> {
                    if (exact.isOn()) {
                        drawExact(batch, target.block, ColorUtil.fade(exactColor.getColor(), strength));
                    }
                }
                case CHUNK -> {
                    if (chunks.isOn()) {
                        drawChunk(batch, target.chunk, ColorUtil.fade(chunkColor.getColor(), strength));
                    }
                }
                case BEARING -> {
                    drawLast(batch, target);
                    drawFar(batch, target, feet, ColorUtil.fade(farColor.getColor(), strength));
                }
            }
        }
    }

    private void drawLast(DrawBatch batch, Target target) {
        if (target.placed == Kind.EXACT && exact.isOn()) {
            drawExact(batch, target.block, ColorUtil.fade(exactColor.getColor(), GONE_STRENGTH));
        } else if (target.placed == Kind.CHUNK && chunks.isOn()) {
            drawChunk(batch, target.chunk, ColorUtil.fade(chunkColor.getColor(), GONE_STRENGTH));
        }
    }

    private void drawExact(DrawBatch batch, BlockPos block, int color) {
        AABB body = FarShapes.pullIn(new AABB(block.getX(), block.getY(), block.getZ(),
            block.getX() + 1, block.getY() + BODY, block.getZ() + 1));
        batch.outlineBox(body, color, true);
        batch.solidBox(body, ColorUtil.fade(color, FILL_SHARE), true);
        Vec3 head = new Vec3(block.getX() + 0.5, block.getY() + BODY, block.getZ() + 0.5);
        FarShapes.line(batch, head, new Vec3(head.x, mc.level.getMaxY(), head.z), color);
    }

    private void drawChunk(DrawBatch batch, ChunkPos chunk, int color) {
        AABB column = new AABB(chunk.getMinBlockX(), mc.level.getMinY(), chunk.getMinBlockZ(),
            chunk.getMaxBlockX() + 1, mc.level.getMaxY(), chunk.getMaxBlockZ() + 1);
        batch.outlineBox(FarShapes.pullIn(column), color, true);
    }

    private void drawFar(DrawBatch batch, Target target, Vec3 feet, int color) {
        if (lines.isOn() && !Float.isNaN(target.angle)) {
            FarShapes.ray(batch, new Bearing(feet.x, feet.z, target.angle), feet.y,
                ColorUtil.fade(color, LINE_SHARE));
        }
        BearingFix fix = estimate(target);
        if (fix != null) {
            FarShapes.estimate(batch, fix.x(), fix.z(), fix.radius(), color);
        }
    }

    // Every label the target has. A player known by yaw can have one where they were last
    // placed and one for the estimate or their line.
    private void labels(Target target, Vec3 camera, float partial, long now, List<Label> into) {
        if (seen(target)) {
            return;
        }
        switch (target.kind) {
            case EXACT -> {
                if (exact.isOn()) {
                    into.add(new Label(top(target.block), "exact", exactColor.getColor(), true));
                }
            }
            case CHUNK -> {
                if (chunks.isOn()) {
                    ChunkPos chunk = target.chunk;
                    into.add(new Label(new Vec3(chunk.getMiddleBlockX(), camera.y, chunk.getMiddleBlockZ()),
                        "chunk", chunkColor.getColor(), true));
                }
            }
            case BEARING -> {
                Label last = lastLabel(target, camera, now);
                if (last != null) {
                    into.add(last);
                }
                Label far = farLabel(target, camera, partial);
                if (far != null) {
                    into.add(far);
                }
            }
        }
    }

    private static Vec3 top(BlockPos block) {
        return new Vec3(block.getX() + 0.5, block.getY() + BODY + LABEL_LIFT, block.getZ() + 0.5);
    }

    // Null when the spot they were last placed in is not shown.
    private Label lastLabel(Target target, Vec3 camera, long now) {
        String ago = " " + ChatUtil.ago(now - target.placedAt);
        if (target.placed == Kind.EXACT && exact.isOn()) {
            return new Label(top(target.block), "last seen" + ago,
                ColorUtil.fade(exactColor.getColor(), GONE_STRENGTH), true);
        }
        if (target.placed == Kind.CHUNK && chunks.isOn()) {
            ChunkPos chunk = target.chunk;
            return new Label(new Vec3(chunk.getMiddleBlockX(), camera.y, chunk.getMiddleBlockZ()),
                "last chunk" + ago, ColorUtil.fade(chunkColor.getColor(), GONE_STRENGTH), true);
        }
        return null;
    }

    // The estimate or else the line towards them. Null when neither is drawn.
    private Label farLabel(Target target, Vec3 camera, float partial) {
        BearingFix fix = estimate(target);
        if (fix != null) {
            return new Label(new Vec3(fix.x(), camera.y, fix.z()),
                "estimate ±" + Math.round(fix.radius()), farColor.getColor(), true);
        }
        if (!lines.isOn() || Float.isNaN(target.angle)) {
            return null;
        }
        Vec3 feet = mc.player.getPosition(partial);
        Bearing now = new Bearing(feet.x, feet.z, target.angle);
        return new Label(now.along(LINE_LABEL, feet.y), "to the " + now.compass(), farColor.getColor(), false);
    }

    @Subscribe
    private void onRender2D(Render2DEvent event) {
        if (!inGame() || targets.isEmpty() || !WorldToScreen.update()) {
            return;
        }
        Vec3 camera = WorldToScreen.cameraPos();
        long now = System.currentTimeMillis();
        List<Label> found = new ArrayList<>();
        for (Target target : targets.values()) {
            found.clear();
            labels(target, camera, event.getPartialTicks(), now, found);
            float strength = strength(target, now);
            for (Label label : found) {
                draw(event, target.name, label, camera, strength);
            }
        }
    }

    private void draw(Render2DEvent event, String name, Label label, Vec3 camera, float strength) {
        Vec3 screen = WorldToScreen.project(label.at());
        if (screen == null) {
            return;
        }
        List<String> parts = new ArrayList<>(List.of(name, " " + label.kind()));
        if (label.distance()) {
            double away = Math.hypot(label.at().x - camera.x, label.at().z - camera.z);
            parts.add(String.format(Locale.ROOT, " %dm", Math.round(away)));
        }
        int muted = ColorUtil.fade(RenderUtil.MUTED_TEXT, strength);
        List<Integer> colors = new ArrayList<>(List.of(ColorUtil.fade(label.color(), strength), muted));
        if (label.distance()) {
            colors.add(muted);
        }
        RenderUtil.label(event.getContext(), mc.font, screen.x, screen.y, scale.getFloat(), parts, colors);
    }
}
