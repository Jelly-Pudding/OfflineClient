package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public final class Breadcrumbs extends Module {

    private static final int MAX_POINTS = 10000;

    private final BoolSetting throughWalls = new BoolSetting("Through walls",
        "Show the trail through blocks.", true);
    private final ColorSetting color = new ColorSetting("Color",
        "Trail color.", 190, false);
    private final BoolSetting keepTrail = new BoolSetting("Keep trail",
        "The trail survives toggling the module off and on.", true);

    private final Deque<Vec3> trail = new ArrayDeque<>();
    private ResourceKey<Level> dimension;

    public Breadcrumbs() {
        super("Breadcrumbs", "Draws a trail behind you so you can find your way back.", Category.RENDER);
        addSettings(throughWalls, color, keepTrail);
    }

    @Override
    protected void onEnable() {
        if (!keepTrail.isOn()) {
            trail.clear();
        }
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            return;
        }
        // A nether portal or rejoin means the old points are meaningless.
        if (mc.level.dimension() != dimension) {
            dimension = mc.level.dimension();
            trail.clear();
        }
        Vec3 pos = mc.player.position().add(0, 0.1, 0);
        if (!trail.isEmpty() && trail.peekLast().distanceToSqr(pos) < 0.25) {
            return;
        }
        trail.addLast(pos);
        if (trail.size() > MAX_POINTS) {
            trail.removeFirst();
        }
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (trail.size() < 2) {
            return;
        }
        DrawBatch batch = event.getBatch();
        int argb = color.getColor();
        List<Vec3> points = new ArrayList<>(trail);
        for (int i = 1; i < points.size(); i++) {
            batch.line(points.get(i - 1), points.get(i), argb, throughWalls.isOn());
        }
    }
}
