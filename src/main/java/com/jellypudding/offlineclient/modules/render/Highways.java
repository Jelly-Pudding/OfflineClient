package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.render.FarShapes;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.TextSetting;
import com.jellypudding.offlineclient.util.Bearing;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// The roads out of 0 0 are drawn at your height out to the horizon either way from the
// point on each road nearest you. Coordinates read the same in every dimension.
public final class Highways extends Module {

    // Yaws of the roads through 0 0. Each one is also drawn the opposite way.
    private static final double[] AXES = {0, Math.PI / 2};
    private static final double[] DIAGONALS = {Math.PI / 4, -Math.PI / 4};

    private static final double THOUSAND = 1_000;
    private static final double MILLION = 1_000_000;

    private final BoolSetting axes = new BoolSetting("Axes",
        "Draws the highways running north and south and east and west from 0 0.", true);
    private final ColorSetting axisColor = new ColorSetting("Axis colour",
        "Colour of the axis highways.", 190, false).under(axes);
    private final BoolSetting diagonals = new BoolSetting("Diagonals",
        "Draws the highways running out from 0 0 halfway between the axes.", true);
    private final ColorSetting diagonalColor = new ColorSetting("Diagonal colour",
        "Colour of the diagonal highways.", 30, false).under(diagonals);
    private final BoolSetting rings = new BoolSetting("Ring roads",
        "Draws the square roads round 0 0 at the distances below.", false);
    private final TextSetting ringDistances = new TextSetting("Ring distances",
        "How far each ring road lies from 0 0 in blocks with spaces between them. Write 5k for five"
            + " thousand and 1m for a million.",
        "1k 5k 10k 25k 50k 100k").under(rings);
    private final ColorSetting ringColor = new ColorSetting("Ring colour",
        "Colour of the ring roads.", 120, false).under(rings);

    // The distances read from the text the last time it changed.
    private String parsedText;
    private List<Double> ringList = List.of();

    public Highways() {
        super("Highways", "Draws the highways and ring roads round 0 0 out to the horizon.", Category.RENDER);
        addSettings(axes, axisColor, diagonals, diagonalColor, rings, ringDistances, ringColor);
        searchTags("axis", "diagonal", "ring road", "nether highway", "travel");
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!inGame()) {
            return;
        }
        DrawBatch batch = event.getBatch();
        Vec3 feet = mc.player.getPosition(event.getPartialTicks());
        if (axes.isOn()) {
            drawRoads(batch, AXES, feet, axisColor.getColor());
        }
        if (diagonals.isOn()) {
            drawRoads(batch, DIAGONALS, feet, diagonalColor.getColor());
        }
        if (rings.isOn()) {
            int color = ringColor.getColor();
            for (double distance : ringDistances()) {
                drawRing(batch, distance, feet.y, color);
            }
        }
    }

    private static void drawRoads(DrawBatch batch, double[] yaws, Vec3 feet, int color) {
        for (double yaw : yaws) {
            Bearing road = new Bearing(0, 0, yaw);
            Vec3 nearest = road.along(road.ahead(feet.x, feet.z), feet.y);
            FarShapes.ray(batch, new Bearing(nearest.x, nearest.z, yaw), feet.y, color);
            FarShapes.ray(batch, new Bearing(nearest.x, nearest.z, yaw + Math.PI), feet.y, color);
        }
    }

    private static void drawRing(DrawBatch batch, double distance, double y, int color) {
        double[] xs = {-distance, distance, distance, -distance};
        double[] zs = {-distance, -distance, distance, distance};
        for (int i = 0; i < xs.length; i++) {
            int next = (i + 1) % xs.length;
            FarShapes.line(batch, new Vec3(xs[i], y, zs[i]), new Vec3(xs[next], y, zs[next]), color);
        }
    }

    private List<Double> ringDistances() {
        String text = ringDistances.getValue();
        if (!text.equals(parsedText)) {
            parsedText = text;
            ringList = parse(text);
        }
        return ringList;
    }

    // Plain numbers with an optional k for thousands or m for millions. Anything else is skipped.
    private static List<Double> parse(String text) {
        List<Double> distances = new ArrayList<>();
        for (String word : text.trim().toLowerCase(Locale.ROOT).split("\\s+")) {
            if (word.isEmpty()) {
                continue;
            }
            double scale = 1;
            char last = word.charAt(word.length() - 1);
            if (last == 'k' || last == 'm') {
                scale = last == 'k' ? THOUSAND : MILLION;
                word = word.substring(0, word.length() - 1);
            }
            try {
                double distance = Double.parseDouble(word) * scale;
                if (distance > 0 && Double.isFinite(distance)) {
                    distances.add(distance);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return List.copyOf(distances);
    }
}
