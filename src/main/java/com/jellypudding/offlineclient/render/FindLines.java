package com.jellypudding.offlineclient.render;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.config.FindLog;
import com.jellypudding.offlineclient.config.FindLog.Find;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.Setting;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.ToIntFunction;

// How a module points at its finds. Lines run from your view to them and a module that
// marks chunks can raise a tall column through each one. The module draws its own boxes
// with a BoxStyle and asks this which finds lie close enough to draw.
public final class FindLines {

    // Which lines a module starts with.
    public enum Tracers { OFF, EVERY, NEAREST }

    // The four upright edges of a column one block across.
    private static final int CORNERS = 4;

    private final FindLog log;
    private final BoolSetting tracers;
    private final BoolSetting nearestOnly;
    private final NumberSetting tracerRange;
    private final NumberSetting hideWithin;
    private final NumberSetting drawRange;
    // Null for a log of single blocks. A column adds nothing to a box there.
    private final BoolSetting columns;
    private final BoolSetting fullHeight;
    private final NumberSetting columnBottom;
    private final NumberSetting columnTop;

    // Distances follow the log. A log of chunks measures across the ground and gets columns.
    public FindLines(FindLog log, boolean tracersOn) {
        this(log, tracersOn ? Tracers.EVERY : Tracers.OFF, false);
    }

    // The same with the lines and columns a module starts with.
    public FindLines(FindLog log, Tracers startLines, boolean columnsOn) {
        this.log = log;
        tracers = new BoolSetting("Tracers", "Draws a line from your view to each find.", startLines != Tracers.OFF);
        nearestOnly = new BoolSetting("Nearest only", "Draws a line to the nearest find only.",
            startLines == Tracers.NEAREST).under(tracers);
        tracerRange = new NumberSetting("Tracer range", "Finds further away than this get no line.",
            2000, 50, 10000, 50, " blocks").min(1).under(tracers);
        hideWithin = new NumberSetting("Hide within",
            "Drops the line to a find once you are this close to it.", 16, 0, 64, 1, " blocks")
            .min(0).under(tracers);
        drawRange = new NumberSetting("Draw range",
            "Finds further away than this get no box or column. They are still kept.",
            512, 16, 4096, 16, " blocks").min(1);
        if (log.marksChunks()) {
            columns = new BoolSetting("Columns", "Draws a tall line through each find to spot it from afar.",
                columnsOn);
            fullHeight = new BoolSetting("Full height",
                "Runs each column from the bottom of the world to the top.", true).under(columns);
            columnBottom = new NumberSetting("Column bottom", "The height each column starts at.",
                64, -64, 320, 1).unless(fullHeight);
            columnTop = new NumberSetting("Column top", "The height each column ends at.",
                256, -64, 320, 1).unless(fullHeight);
        } else {
            columns = null;
            fullHeight = null;
            columnBottom = null;
            columnTop = null;
        }
    }

    public Setting<?>[] settings() {
        List<Setting<?>> all = new ArrayList<>(List.of(tracers, nearestOnly, tracerRange, hideWithin, drawRange));
        if (columns != null) {
            all.addAll(List.of(columns, fullHeight, columnBottom, columnTop));
        }
        return all.toArray(new Setting<?>[0]);
    }

    // True for a find close enough to draw its box.
    public boolean inDrawRange(Find find) {
        LocalPlayer player = OfflineClient.MC.player;
        double range = drawRange.getValue();
        return player != null && log.distanceSqr(find, player.getEyePosition()) <= range * range;
    }

    // The tracers and columns of every find in the dimension you are in.
    public void draw(DrawBatch batch, ToIntFunction<Find> color) {
        draw(batch, log.here(), color);
    }

    // The same for a module that shows only some of its finds.
    public void draw(DrawBatch batch, Collection<Find> finds, ToIntFunction<Find> color) {
        LocalPlayer player = OfflineClient.MC.player;
        if (player == null || finds.isEmpty()) {
            return;
        }
        Vec3 eye = player.getEyePosition();
        if (tracers.isOn()) {
            drawTracers(batch, finds, color, eye);
        }
        if (columns != null && columns.isOn()) {
            drawColumns(batch, finds, color, eye);
        }
    }

    private void drawTracers(DrawBatch batch, Collection<Find> finds, ToIntFunction<Find> color, Vec3 eye) {
        double far = tracerRange.getValue() * tracerRange.getValue();
        double near = hideWithin.getValue() * hideWithin.getValue();
        Find nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (Find find : finds) {
            double distance = log.distanceSqr(find, eye);
            if (distance > far || distance < near) {
                continue;
            }
            if (!nearestOnly.isOn()) {
                tracer(batch, find, color);
            } else if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = find;
            }
        }
        if (nearest != null) {
            tracer(batch, nearest, color);
        }
    }

    private static void tracer(DrawBatch batch, Find find, ToIntFunction<Find> color) {
        batch.tracer(FarShapes.pullIn(Vec3.atCenterOf(find.pos())), color.applyAsInt(find), true);
    }

    private void drawColumns(DrawBatch batch, Collection<Find> finds, ToIntFunction<Find> color, Vec3 eye) {
        ClientLevel level = OfflineClient.MC.level;
        double bottom = fullHeight.isOn() ? level.getMinY() : columnBottom.getValue();
        double top = fullHeight.isOn() ? level.getMaxY() + 1 : columnTop.getValue();
        double far = drawRange.getValue() * drawRange.getValue();
        for (Find find : finds) {
            if (log.distanceSqr(find, eye) > far) {
                continue;
            }
            int argb = color.applyAsInt(find);
            for (int corner = 0; corner < CORNERS; corner++) {
                double x = find.pos().getX() + (corner < 2 ? 0 : 1);
                double z = find.pos().getZ() + (corner % 2 == 0 ? 0 : 1);
                FarShapes.line(batch, new Vec3(x, bottom, z), new Vec3(x, top, z), argb);
            }
        }
    }
}
