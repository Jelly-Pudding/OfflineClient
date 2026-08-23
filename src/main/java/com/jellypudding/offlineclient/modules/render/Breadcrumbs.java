package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.ColorUtil;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public final class Breadcrumbs extends Module {

    private static final int MAX_POINTS = 10000;
    // Ticks between disk writes.
    private static final int SAVE_INTERVAL = 600;

    private final BoolSetting throughWalls = new BoolSetting("Through walls",
        "Show the trail through blocks.", true);
    private final ColorSetting color = new ColorSetting("Color",
        "Trail colour.", 190, false);
    private final BoolSetting keepTrail = new BoolSetting("Keep trail",
        "The trail survives toggling the module off and on.", true);
    private final BoolSetting persist = new BoolSetting("Save trail",
        "The trail is written to disk and comes back next session.", false);
    private final NumberSetting spacing = new NumberSetting("Spacing",
        "How far you move before a new point is added.", 0.5, 0.1, 4, 0.1, " blocks").min(0.05);
    private final BoolSetting fade = new BoolSetting("Fade",
        "Older parts of the trail draw fainter.", false);

    private final Deque<Vec3> trail = new ArrayDeque<>();
    private ResourceKey<Level> dimension;
    private int sinceSave;

    public Breadcrumbs() {
        super("Breadcrumbs", "Draws a trail behind you so you can find your way back.", Category.RENDER);
        addSettings(throughWalls, color, keepTrail, persist, spacing, fade);
        searchTags("trail", "path", "waypoint");
    }

    @Override
    public String getSuffix() {
        return trail.isEmpty() ? null : String.valueOf(trail.size());
    }

    @Override
    protected void onEnable() {
        if (!keepTrail.isOn()) {
            trail.clear();
        }
        sinceSave = 0;
        if (persist.isOn() && inGame()) {
            dimension = mc.level.dimension();
            // A kept trail already holds what the file holds.
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
        // A portal or a rejoin swaps the dimension key.
        if (mc.level.dimension() != dimension) {
            if (persist.isOn() && dimension != null) {
                save();
            }
            dimension = mc.level.dimension();
            trail.clear();
            if (persist.isOn()) {
                load();
            }
        }

        if (persist.isOn() && ++sinceSave >= SAVE_INTERVAL) {
            sinceSave = 0;
            save();
        }

        Vec3 pos = mc.player.position().add(0, 0.1, 0);
        double step = spacing.getValue();
        if (!trail.isEmpty() && trail.peekLast().distanceToSqr(pos) < step * step) {
            return;
        }
        trail.addLast(pos);
        if (trail.size() > MAX_POINTS) {
            trail.removeFirst();
        }
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        int total = trail.size();
        if (total < 2) {
            return;
        }
        DrawBatch batch = event.getBatch();
        int argb = color.getColor();
        boolean through = throughWalls.isOn();
        boolean fading = fade.isOn();
        Vec3 previous = null;
        int i = 0;
        for (Vec3 point : trail) {
            if (previous != null) {
                batch.line(previous, point, fading ? ColorUtil.fade(argb, i / (float) total) : argb, through);
            }
            previous = point;
            i++;
        }
    }

    // One point per line as three plain numbers.
    private void save() {
        if (dimension == null) {
            return;
        }
        List<Vec3> points = new ArrayList<>(trail);
        StringBuilder text = new StringBuilder();
        for (Vec3 point : points) {
            text.append(point.x).append(' ').append(point.y).append(' ').append(point.z).append('\n');
        }
        try {
            Path file = fileFor(dimension);
            Files.createDirectories(file.getParent());
            Files.writeString(file, text.toString());
        } catch (IOException e) {
            OfflineClient.LOG.error("Failed to save trail", e);
        }
    }

    private void load() {
        if (dimension == null) {
            return;
        }
        Path file = fileFor(dimension);
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
                if (trail.size() > MAX_POINTS) {
                    trail.removeFirst();
                }
            }
        } catch (IOException | NumberFormatException e) {
            OfflineClient.LOG.error("Failed to read trail", e);
        }
    }

    private Path fileFor(ResourceKey<Level> key) {
        String name = key.identifier().toString().replace(':', '_').replace('/', '_');
        return mc.gameDirectory.toPath().resolve("offlineclient").resolve("trails").resolve(name + ".txt");
    }
}
