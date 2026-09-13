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
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.RenderUtil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class Waypoints extends Module {

    // Death markers are named Death followed by the time of day.
    private static final String DEATH_PREFIX = "Death";
    private static final DateTimeFormatter DEATH_TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int DEATH_HUE = 0;

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
    private final BoolSetting acrossDimensions = new BoolSetting("Across dimensions",
        "Shows overworld markers in the nether and the other way round with the coordinates scaled by eight.",
        true);
    private final NumberSetting hideWithin = new NumberSetting("Hide within",
        "Markers this close fade out so they do not fill your screen. Zero keeps them.",
        0, 0, 32, 1, " blocks").min(0);
    private final BoolSetting autoColor = new BoolSetting("Automatic colours",
        "Each new waypoint takes a colour from its name. Off gives it the colour below.", true);
    private final ColorSetting nextColor = new ColorSetting("Next colour",
        "Colour of the next waypoint you add. Recolour an old one with the waypoint colour command.", 200, false)
        .unless(autoColor);
    private final BoolSetting markDeaths = new BoolSetting("Mark deaths",
        "Saves a red waypoint where you die so you can find your things again.", false);
    private final NumberSetting deathsKept = new NumberSetting("Deaths kept",
        "How many death markers are kept in each world. The oldest goes when a new one is saved.",
        5, 1, 20, 1).min(1)
        .under(markDeaths);
    private final BoolSetting deathChat = new BoolSetting("Death message",
        "Tells you in chat where you died.", false)
        .under(markDeaths);

    // Rebuilt once a tick.
    private List<WaypointStore.Waypoint> shown = List.of();

    // The player whose death was last marked. A respawn hands over a new one.
    private LocalPlayer marked;

    public Waypoints() {
        super("Waypoints", "Marks the coordinates you saved in the world.", Category.RENDER);
        addSettings(beam, beamHeight, box, label, distance, coordinates, scale, range,
            acrossDimensions, hideWithin, autoColor, nextColor, markDeaths, deathsKept, deathChat);
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
        shown = inGame() ? visible() : List.of();
        if (inGame() && mc.player.isDeadOrDying()) {
            markDeath(mc.player.position());
        }
    }

    private List<WaypointStore.Waypoint> visible() {
        List<WaypointStore.Waypoint> all = new ArrayList<>(WaypointStore.get().here());
        if (acrossDimensions.isOn()) {
            all.addAll(WaypointStore.get().mirrored());
        }
        all.removeIf(WaypointStore.Waypoint::hidden);
        return all;
    }

    // How solid a marker draws. Close ones fade and do not fill the screen.
    private float strength(double away) {
        double limit = hideWithin.getValue();
        if (limit <= 0) {
            return 1;
        }
        return (float) Math.clamp((away - limit / 2) / (limit / 2), 0, 1);
    }

    // Saves a marker on the spot of a death. AutoRespawn calls this before it
    // respawns and the tick above catches a death without it. Once per death.
    public void markDeath(Vec3 position) {
        if (!markDeaths.isOn() || !inGame() || mc.player == marked) {
            return;
        }
        marked = mc.player;
        BlockPos at = BlockPos.containing(position);
        String name = DEATH_PREFIX + LocalTime.now().format(DEATH_TIME);
        WaypointStore.get().add(new WaypointStore.Waypoint(name, at.getX(), at.getY(), at.getZ(),
            WaypointStore.currentDimension(), WaypointStore.currentServer(), DEATH_HUE));
        dropOldDeaths();
        if (deathChat.isOn()) {
            ChatUtil.message("§cYou died at §f" + BlockUtil.text(at) + "§c.");
        }
    }

    // Keeps only the newest death markers in this world.
    private void dropOldDeaths() {
        List<String> deaths = new ArrayList<>();
        for (WaypointStore.Waypoint waypoint : WaypointStore.get().here()) {
            if (isDeathMarker(waypoint.name())) {
                deaths.add(waypoint.name());
            }
        }
        for (int i = 0; i < deaths.size() - deathsKept.getInt(); i++) {
            WaypointStore.get().remove(deaths.get(i));
        }
    }

    private static boolean isDeathMarker(String name) {
        return name.length() > DEATH_PREFIX.length() && name.startsWith(DEATH_PREFIX)
            && Character.isDigit(name.charAt(DEATH_PREFIX.length()));
    }

    // The hue the next added waypoint gets. Below zero leaves it to the name.
    public int nextHue() {
        return autoColor.isOn() ? WaypointStore.Waypoint.AUTO_HUE : Math.round(nextColor.getHue());
    }

    // A chosen hue wins. Otherwise the same name always gets the same hue.
    public static int colorOf(WaypointStore.Waypoint waypoint) {
        int hue = waypoint.hue() >= 0 ? waypoint.hue()
            : Math.floorMod(waypoint.name().toLowerCase(Locale.ROOT).hashCode(), 360);
        return ColorUtil.hsv(hue, 0.7f, 1f);
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
            float strength = strength(eye.distanceTo(middle));
            if (strength <= 0) {
                continue;
            }
            int color = ColorUtil.fade(colorOf(waypoint), strength);
            if (beam.isOn()) {
                batch.line(middle.subtract(0, reach, 0), middle.add(0, reach, 0), color, true);
            }
            if (box.isOn()) {
                AABB shape = new AABB(waypoint.x(), waypoint.y(), waypoint.z(),
                    waypoint.x() + 1, waypoint.y() + 1, waypoint.z() + 1);
                batch.outlineBox(shape, color, true);
                batch.solidBox(shape, ColorUtil.withAlpha(color, Math.round(50 * strength)), true);
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
            if (screen == null || strength(away) < 1) {
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
                List.of(text.toString()), List.of(colorOf(waypoint)));
        }
    }
}
