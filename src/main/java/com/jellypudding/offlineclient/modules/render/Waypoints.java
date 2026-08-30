package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.config.WaypointStore;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render2DEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.render.WorldToScreen;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.RenderUtil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;

public final class Waypoints extends Module {

    private final BoolSetting beam = new BoolSetting("Beam",
        "Draws a tall line that shows a marker over hills.", true);
    private final NumberSetting beamHeight = new NumberSetting("Beam height",
        "How far the line reaches up and down.", 64, 8, 320, 8, " blocks").min(1);
    private final BoolSetting box = new BoolSetting("Box",
        "Draw a box on the marked block.", true);
    private final BoolSetting label = new BoolSetting("Label",
        "Write the name above the marker.", true);
    private final BoolSetting distance = new BoolSetting("Distance",
        "Add how far away the marker is to the label.", true);
    private final BoolSetting coordinates = new BoolSetting("Coordinates",
        "Add the block position to the label.", false);
    private final NumberSetting scale = new NumberSetting("Scale",
        "Size of the labels.", 1, 0.5, 3, 0.1).min(0.1);
    private final NumberSetting range = new NumberSetting("Range",
        "Furthest marker to draw or zero for every one.", 0, 0, 2000, 100, " blocks").min(0);

    // Rebuilt once a tick.
    private List<WaypointStore.Waypoint> shown = List.of();

    public Waypoints() {
        super("Waypoints", "Marks the coordinates you saved in the world.", Category.RENDER);
        addSettings(beam, beamHeight, box, label, distance, coordinates, scale, range);
        searchTags("waypoint", "marker", "coords");
    }

    @Override
    public String getSuffix() {
        return count(shown.size());
    }

    @Override
    protected void onEnable() {
        shown = List.of();
    }

    @Override
    protected void onDisable() {
        shown = List.of();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        shown = inGame() ? WaypointStore.get().here() : List.of();
    }

    // The same name always gets the same hue.
    private static int colorOf(String name) {
        return ColorUtil.hsv(Math.floorMod(name.toLowerCase(Locale.ROOT).hashCode(), 360), 0.7f, 1f);
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!inGame()) {
            return;
        }
        DrawBatch batch = event.getBatch();
        double limit = range.getValue();
        double reach = beamHeight.getValue();
        Vec3 eye = mc.player.getEyePosition();

        for (WaypointStore.Waypoint waypoint : shown) {
            Vec3 middle = new Vec3(waypoint.x() + 0.5, waypoint.y() + 0.5, waypoint.z() + 0.5);
            if (limit > 0 && eye.distanceTo(middle) > limit) {
                continue;
            }
            int color = colorOf(waypoint.name());
            if (beam.isOn()) {
                batch.line(middle.subtract(0, reach, 0), middle.add(0, reach, 0), color, true);
            }
            if (box.isOn()) {
                AABB shape = new AABB(waypoint.x(), waypoint.y(), waypoint.z(),
                    waypoint.x() + 1, waypoint.y() + 1, waypoint.z() + 1);
                batch.outlineBox(shape, color, true);
                batch.solidBox(shape, ColorUtil.withAlpha(color, 50), true);
            }
        }
    }

    @Subscribe
    private void onRender2D(Render2DEvent event) {
        if (!label.isOn() || !inGame() || !WorldToScreen.update()) {
            return;
        }
        List<WaypointStore.Waypoint> waypoints = shown;
        if (waypoints.isEmpty()) {
            return;
        }
        GuiGraphicsExtractor context = event.getContext();
        Font font = mc.font;
        Vec3 camera = WorldToScreen.cameraPos();
        double limit = range.getValue();
        float factor = scale.getFloat();

        for (WaypointStore.Waypoint waypoint : waypoints) {
            Vec3 middle = new Vec3(waypoint.x() + 0.5, waypoint.y() + 1.5, waypoint.z() + 0.5);
            double away = camera.distanceTo(middle);
            if (limit > 0 && away > limit) {
                continue;
            }
            Vec3 screen = WorldToScreen.project(middle);
            if (screen == null) {
                continue;
            }

            StringBuilder text = new StringBuilder(waypoint.name());
            if (coordinates.isOn()) {
                text.append(" ").append(waypoint.x()).append(" ")
                    .append(waypoint.y()).append(" ").append(waypoint.z());
            }
            if (distance.isOn()) {
                text.append(" ").append(Math.round(away)).append("m");
            }
            RenderUtil.label(context, font, screen.x, screen.y, factor,
                List.of(text.toString()), List.of(colorOf(waypoint.name())));
        }
    }
}
