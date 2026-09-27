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
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.Bearing;
import com.jellypudding.offlineclient.util.BearingFix;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.PositionHistory;
import com.jellypudding.offlineclient.util.RenderUtil;
import com.jellypudding.offlineclient.util.WorldWatch;
import com.mojang.datafixers.util.Either;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.waypoints.TrackedWaypoint;

import java.util.ArrayList;
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
public final class Locator extends Module {

    private enum Kind { EXACT, CHUNK, BEARING }

    // One player the locator bar tells you about.
    private static final class Target {
        // Null for a waypoint a plugin named with text.
        private final UUID uuid;
        private String name = "";
        private Kind kind = Kind.BEARING;
        private BlockPos block = BlockPos.ZERO;
        private ChunkPos chunk = new ChunkPos(0, 0);
        // The last yaw the server sent. A change means a fresh reading arrived.
        private float angle = Float.NaN;
        // Oldest first with one reading for each spot you stood on.
        private final List<Bearing> bearings = new ArrayList<>();
        private BearingFix fix;
        // The estimate last posted in chat and when.
        private BearingFix posted;
        private long postedAt;
        private long lastSeen;

        private Target(UUID uuid) {
            this.uuid = uuid;
        }

        // A player the server places directly needs no bearings.
        private void placed(Kind now) {
            kind = now;
            angle = Float.NaN;
            bearings.clear();
            fix = null;
            posted = null;
        }
    }

    // Where a label goes and what it says.
    private record Label(Vec3 at, String kind, int color, boolean distance) {
    }

    // A reading taken this close to the last one replaces it.
    private static final double SAME_SPOT = 4;

    // A new yaw whilst you stand this still can only come from the player moving. Past 332
    // blocks your own step turns the line less than the half degree the server waits for.
    private static final double STILL = 2;

    // Enough for a long walk sideways to a far player.
    private static final int MAX_BEARINGS = 64;

    // An estimate that moves less than this is a refinement and stays out of chat.
    private static final double REPOST = 64;

    // A player on the move is posted at most this often.
    private static final long REPOST_MS = TimeUnit.MINUTES.toMillis(1);

    // Where along a bearing line its name sits.
    private static final double LINE_LABEL = 24;

    // How tall the box on an exact mark is. A player stands under two blocks.
    private static final double BODY = 2;

    private static final double LABEL_LIFT = 0.5;

    private static final int CIRCLE_POINTS = 48;

    private static final float FILL_SHARE = 0.2f;

    private static final float LINE_SHARE = 0.6f;

    // A player the server stopped sending draws this faint until forgotten.
    private static final float GONE_STRENGTH = 0.45f;

    private static final long LIVE_MS = 1000;

    private static final int SHORT_ID = 8;

    private final BoolSetting exact = new BoolSetting("Exact",
        "Marks the block a player stands in when the server sends it exactly.", true);
    private final BoolSetting chunks = new BoolSetting("Chunks",
        "Marks the chunk column a player stands in when the server sends only that.", true);
    private final BoolSetting estimates = new BoolSetting("Estimates",
        "Marks where far players probably are by crossing bearings taken from different spots."
            + " Travel sideways to a player to gather them. Only a player who stays put gives a true estimate.",
        true);
    private final NumberSetting spread = new NumberSetting("Minimum spread",
        "How far apart two bearings must point before they are crossed. More gives a surer first estimate.",
        5, 1, 30, 0.5, " degrees").min(0.1).under(estimates);
    private final BoolSetting announce = new BoolSetting("Chat",
        "Posts the coordinates in chat when an estimate appears and at most once a minute as it moves.",
        true).under(estimates);
    private final BoolSetting lines = new BoolSetting("Bearing lines",
        "Draws a line along the ground from you towards each far player.", true);
    private final BoolSetting hideSeen = new BoolSetting("Hide seen players",
        "Players you can already see get no exact or chunk mark.", true);
    private final NumberSetting forget = new NumberSetting("Forget after",
        "How long a mark stays once the server stops sending that player.",
        5, 1, 30, 1, " minutes").min(0);
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
    private final PositionHistory history = new PositionHistory();
    private final WorldWatch world = new WorldWatch();

    // False for the first tick after a clear. A yaw the client already holds then may be
    // half a degree stale because the server only resends once it drifts that far.
    private boolean listening;

    public Locator() {
        super("Locator", "Finds other players through the locator bar unless the server turns it off.",
            Category.HUNTING);
        addSettings(exact, chunks, estimates, spread, announce, lines, hideSeen, forget,
            exactColor, chunkColor, farColor, scale);
        searchTags("locator bar", "waypoint", "triangulate", "coords");
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
        targets.clear();
        history.clear();
        listening = false;
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
        long keep = Math.round(forget.getValue() * TimeUnit.MINUTES.toMillis(1));
        targets.values().removeIf(target -> now - target.lastSeen > keep);
        double least = Math.toRadians(spread.getValue());
        for (Target target : targets.values()) {
            if (target.kind == Kind.BEARING) {
                target.fix = BearingFix.cross(target.bearings, least);
                post(target, now);
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
            if (target.kind != Kind.EXACT) {
                target.placed(Kind.EXACT);
            }
            target.block = new BlockPos(at.getX(), at.getY(), at.getZ());
        } else if (waypoint instanceof TrackedWaypoint.ChunkWaypoint column) {
            if (target.kind != Kind.CHUNK) {
                target.placed(Kind.CHUNK);
            }
            target.chunk = column.chunkPos;
        } else if (waypoint instanceof TrackedWaypoint.AzimuthWaypoint azimuth) {
            heard(target, azimuth.angle);
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

    private void heard(Target target, float angle) {
        target.kind = Kind.BEARING;
        if (angle == target.angle) {
            return;
        }
        boolean held = Float.isNaN(target.angle) && !listening;
        target.angle = angle;
        Vec3 origin = history.asServerSaw();
        if (!held && origin != null) {
            take(target, new Bearing(origin.x, origin.z, angle));
        }
    }

    private void take(Target target, Bearing bearing) {
        List<Bearing> bearings = target.bearings;
        // A reading that cannot point at a well founded estimate means the player moved on.
        // Any new reading whilst you stand still means the same.
        if ((!bearings.isEmpty() && bearings.getLast().originDistance(bearing) < STILL)
            || (target.fix != null && bearings.size() > 2 && target.fix.missedBy(bearing))) {
            bearings.clear();
        }
        if (!bearings.isEmpty() && bearings.getLast().originDistance(bearing) < SAME_SPOT) {
            bearings.removeLast();
        }
        bearings.add(bearing);
        if (bearings.size() > MAX_BEARINGS) {
            bearings.removeFirst();
        }
        double least = Math.toRadians(spread.getValue());
        while (bearings.size() > 1 && !BearingFix.agree(bearings, least)) {
            bearings.removeFirst();
        }
    }

    // Once for each new estimate. A refinement inside the posted radius stays quiet and a
    // player on the move is posted once a minute at most.
    private void post(Target target, long now) {
        BearingFix fix = target.fix;
        if (fix == null || !estimates.isOn() || !announce.isOn()) {
            return;
        }
        BearingFix last = target.posted;
        if (last != null && (now - target.postedAt < REPOST_MS
            || Math.hypot(fix.x() - last.x(), fix.z() - last.z()) < Math.max(last.radius(), REPOST))) {
            return;
        }
        target.posted = fix;
        target.postedAt = now;
        ChatUtil.message("§bLocator §f" + target.name + " §7is around §f" + Math.round(fix.x())
            + " " + Math.round(fix.z()) + " §7give or take §f" + Math.round(fix.radius())
            + " §7blocks if they stayed put.");
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
            float strength = strength(target, now);
            switch (target.kind) {
                case EXACT -> {
                    if (exact.isOn() && !seen(target)) {
                        drawExact(batch, target.block, ColorUtil.fade(exactColor.getColor(), strength));
                    }
                }
                case CHUNK -> {
                    if (chunks.isOn() && !seen(target)) {
                        drawChunk(batch, target.chunk, ColorUtil.fade(chunkColor.getColor(), strength));
                    }
                }
                case BEARING -> drawFar(batch, target, feet, ColorUtil.fade(farColor.getColor(), strength));
            }
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
        BearingFix fix = target.fix;
        if (!estimates.isOn() || fix == null) {
            return;
        }
        FarShapes.line(batch, new Vec3(fix.x(), mc.level.getMinY(), fix.z()),
            new Vec3(fix.x(), mc.level.getMaxY(), fix.z()), color);
        // The circle sits level with your eyes where it reads best against the horizon.
        double y = DrawBatch.cameraPos().y;
        Vec3 last = null;
        for (int i = 0; i <= CIRCLE_POINTS; i++) {
            double turn = 2 * Math.PI * i / CIRCLE_POINTS;
            Vec3 point = new Vec3(fix.x() + Math.cos(turn) * fix.radius(), y,
                fix.z() + Math.sin(turn) * fix.radius());
            if (last != null) {
                FarShapes.line(batch, last, point, color);
            }
            last = point;
        }
    }

    // Null when the target has nothing drawn.
    private Label labelOf(Target target, Vec3 camera, float partial) {
        switch (target.kind) {
            case EXACT -> {
                if (!exact.isOn() || seen(target)) {
                    return null;
                }
                BlockPos block = target.block;
                return new Label(new Vec3(block.getX() + 0.5, block.getY() + BODY + LABEL_LIFT, block.getZ() + 0.5),
                    "exact", exactColor.getColor(), true);
            }
            case CHUNK -> {
                if (!chunks.isOn() || seen(target)) {
                    return null;
                }
                ChunkPos chunk = target.chunk;
                return new Label(new Vec3(chunk.getMiddleBlockX(), camera.y, chunk.getMiddleBlockZ()),
                    "chunk", chunkColor.getColor(), true);
            }
            default -> {
                BearingFix fix = target.fix;
                if (estimates.isOn() && fix != null) {
                    return new Label(new Vec3(fix.x(), camera.y, fix.z()),
                        "estimate ±" + Math.round(fix.radius()), farColor.getColor(), true);
                }
                if (!lines.isOn() || Float.isNaN(target.angle)) {
                    return null;
                }
                Vec3 feet = mc.player.getPosition(partial);
                Bearing now = new Bearing(feet.x, feet.z, target.angle);
                return new Label(now.along(LINE_LABEL, feet.y), "to the " + now.compass(),
                    farColor.getColor(), false);
            }
        }
    }

    @Subscribe
    private void onRender2D(Render2DEvent event) {
        if (!inGame() || targets.isEmpty() || !WorldToScreen.update()) {
            return;
        }
        Vec3 camera = WorldToScreen.cameraPos();
        long now = System.currentTimeMillis();
        for (Target target : targets.values()) {
            Label label = labelOf(target, camera, event.getPartialTicks());
            if (label == null) {
                continue;
            }
            Vec3 screen = WorldToScreen.project(label.at());
            if (screen == null) {
                continue;
            }
            float strength = strength(target, now);
            List<String> parts = new ArrayList<>(List.of(target.name, " " + label.kind()));
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
}
