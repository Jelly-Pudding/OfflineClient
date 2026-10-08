package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.config.DataFiles;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BoundedMap;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.ServerInfo;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class Breadcrumbs extends Module {

    // Ticks between disk writes.
    private static final int SAVE_INTERVAL = 600;
    // Points sit a little above the feet and clear of the floor.
    private static final double LIFT = 0.1;
    // The other players whose trails are kept. The one seen longest ago drops first.
    private static final int MAX_OTHERS = 64;
    // Further than anyone moves in one tick. A glide reaches about 17.3 blocks.
    private static final double JUMP = 20;
    // A gap in a trail. Nothing is drawn to it or from it and the point after it always goes in.
    private static final Vec3 BREAK = new Vec3(Double.NaN, Double.NaN, Double.NaN);

    private final BoolSetting throughWalls = new BoolSetting("Through walls",
        "Show the trail through blocks.", true);
    private final ColorSetting color = new ColorSetting("Colour",
        "Trail colour.", 190, false);
    private final BoolSetting keepTrail = new BoolSetting("Keep trail",
        "The trail survives toggling the module off and on.", true);
    private final BoolSetting persist = new BoolSetting("Save trail",
        "The trail is written to disk and comes back next session.", false);
    private final NumberSetting spacing = new NumberSetting("Spacing",
        "How far you move before a new point is added.", 0.5, 0.1, 4, 0.1, " blocks").min(0.05);
    private final BoolSetting fade = new BoolSetting("Fade",
        "Older parts of the trail draw fainter.", false);
    private final NumberSetting maxPoints = new NumberSetting("Max points",
        "How many points the trail keeps before the oldest drop off.", 10000, 100, 10000, 100, " points")
        .min(10);
    private final BoolSetting others = new BoolSetting("Other players",
        "Also draws a trail behind every other player in view.", false);
    private final ColorSetting playerColor = new ColorSetting("Player colour",
        "Colour of the trails behind other players.", 0, false).under(others);
    private final BoolSetting keepLeavers = new BoolSetting("Keep after leaving",
        "Keeps a player's trail once they leave your view.", false).under(others);

    private final Deque<Vec3> trail = new ArrayDeque<>();
    private final Map<UUID, Deque<Vec3>> otherTrails = new BoundedMap<>(MAX_OTHERS, true);
    // The other players followed on the last tick.
    private Set<UUID> wasInView = Set.of();
    // The server and dimension the trail belongs to. Null until the first tick in a world.
    private String world;
    private int sinceSave;

    public Breadcrumbs() {
        super("Breadcrumbs", "Draws a trail behind you to lead you back.", Category.RENDER);
        addSettings(throughWalls, color, keepTrail, persist, spacing, fade, maxPoints, others, playerColor,
            keepLeavers);
        searchTags("trail", "path", "waypoint", "player trails");
    }

    @Override
    public String getSuffix() {
        return count(trail.size());
    }

    @Override
    protected void onEnable() {
        if (!keepTrail.isOn()) {
            trail.clear();
            otherTrails.clear();
        }
        wasInView = Set.of();
        sinceSave = 0;
        if (persist.isOn() && inGame()) {
            // A trail kept from another world would be drawn at the wrong coordinates
            // and then saved over this world.
            String here = ServerInfo.worldKey();
            if (!here.equals(world)) {
                trail.clear();
            }
            world = here;
            if (trail.isEmpty()) {
                load();
            }
        }
    }

    @Override
    protected void onDisable() {
        if (persist.isOn()) {
            save();
        }
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            return;
        }
        String here = ServerInfo.worldKey();
        if (!here.equals(world)) {
            if (persist.isOn() && world != null) {
                save();
            }
            world = here;
            trail.clear();
            otherTrails.clear();
            if (persist.isOn()) {
                load();
            }
        }

        if (persist.isOn() && ++sinceSave >= SAVE_INTERVAL) {
            sinceSave = 0;
            save();
        }

        extend(trail, mc.player.position());
        if (others.isOn()) {
            followOthers();
        } else {
            otherTrails.clear();
        }
    }

    // A kept trail breaks where its player comes back into view.
    private void followOthers() {
        Set<UUID> inView = new HashSet<>();
        for (Player player : mc.level.players()) {
            if (!EntityUtil.isOtherPlayer(player)) {
                continue;
            }
            UUID id = player.getUUID();
            inView.add(id);
            Deque<Vec3> points = otherTrails.computeIfAbsent(id, key -> new ArrayDeque<>());
            if (!wasInView.contains(id)) {
                breakTrail(points);
            }
            extend(points, player.position());
        }
        if (!keepLeavers.isOn()) {
            otherTrails.keySet().retainAll(inView);
        }
        wasInView = inView;
    }

    // A new point once the walker is a spacing away from the last one. A jump further than one
    // tick of movement breaks the trail.
    private void extend(Deque<Vec3> points, Vec3 feet) {
        Vec3 pos = feet.add(0, LIFT, 0);
        if (!points.isEmpty()) {
            double step = spacing.getValue();
            double apart = points.peekLast().distanceToSqr(pos);
            if (apart < step * step) {
                return;
            }
            // The last point lies less than a spacing from where the walker stood a tick ago.
            double reach = step + JUMP;
            if (apart > reach * reach) {
                breakTrail(points);
            }
        }
        points.addLast(pos);
        trim(points);
    }

    // Never at the start of a trail or twice in a row.
    private static void breakTrail(Deque<Vec3> points) {
        if (!points.isEmpty() && !isBreak(points.peekLast())) {
            points.addLast(BREAK);
        }
    }

    // A saved trail reads its breaks back as fresh points with no position.
    private static boolean isBreak(Vec3 point) {
        return Double.isNaN(point.x);
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        DrawBatch batch = event.getBatch();
        draw(batch, trail, color.getColor());
        for (Deque<Vec3> points : otherTrails.values()) {
            draw(batch, points, playerColor.getColor());
        }
    }

    private void draw(DrawBatch batch, Deque<Vec3> points, int argb) {
        int total = points.size();
        if (total < 2) {
            return;
        }
        boolean through = throughWalls.isOn();
        boolean fading = fade.isOn();
        Vec3 previous = BREAK;
        int i = 0;
        for (Vec3 point : points) {
            if (!isBreak(previous) && !isBreak(point)) {
                batch.line(previous, point, fading ? ColorUtil.fade(argb, i / (float) total) : argb, through);
            }
            previous = point;
            i++;
        }
    }

    private void trim(Deque<Vec3> points) {
        while (points.size() > maxPoints.getInt()) {
            points.removeFirst();
        }
    }

    // One point per line as three plain numbers.
    private void save() {
        if (world == null) {
            return;
        }
        StringBuilder text = new StringBuilder();
        for (Vec3 point : trail) {
            text.append(point.x).append(' ').append(point.y).append(' ').append(point.z).append('\n');
        }
        DataFiles.writeSafely(fileFor(world), temp -> Files.writeString(temp, text.toString()));
    }

    private void load() {
        if (world == null) {
            return;
        }
        Path file = fileFor(world);
        if (!Files.exists(file)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(file)) {
                String[] parts = line.split(" ");
                if (parts.length != 3) {
                    continue;
                }
                trail.addLast(new Vec3(Double.parseDouble(parts[0]),
                    Double.parseDouble(parts[1]), Double.parseDouble(parts[2])));
                trim(trail);
            }
        } catch (IOException | NumberFormatException e) {
            OfflineClient.LOG.error("Failed to read trail", e);
        }
    }

    private static Path fileFor(String world) {
        return DataFiles.path("trails", DataFiles.keyName(world) + ".txt");
    }
}
