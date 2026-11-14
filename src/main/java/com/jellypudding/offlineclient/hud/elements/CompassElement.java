package com.jellypudding.offlineclient.hud.elements;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.config.WaypointStore;
import com.jellypudding.offlineclient.hud.HudElement;
import com.jellypudding.offlineclient.hud.RowCache;
import com.jellypudding.offlineclient.modules.render.Waypoints;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.Bearing;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.CompassPoint;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;

// A strip of the compass that slides past as you turn. Stretching it wider lengthens the strip
// and leaves the marks and letters their shape.
public final class CompassElement extends HudElement {

    public enum Reading { NONE, YAW, BEARING }

    public enum Labels { POINTS, AXES, BOTH }

    // Degrees between the small marks. Every third one is a compass point.
    private static final int MINOR_STEP = 15;
    private static final int FULL_TURN = 360;

    // The strip before any stretch.
    private static final int LENGTH = 160;

    private static final int TICK_HEIGHT = 3;
    private static final int MINOR_TICK_HEIGHT = 1;
    // A waypoint is a block three pixels wide. The marks beside it are one.
    private static final int WAYPOINT_WIDTH = 3;
    // The needle reaches a little past the marks.
    private static final int NEEDLE_HEIGHT = TICK_HEIGHT + 2;
    private static final int GAP = 2;
    // The first row of names sits under the line and the marks.
    private static final int NAMES_TOP = 1 + TICK_HEIGHT + GAP;

    // Marks fade out across this share of the field at each end of the strip.
    private static final double FADE_SHARE = 0.1;
    private static final int LINE_COLOUR = 0x50FFFFFF;

    private final NumberSetting field = new NumberSetting("Compass field",
        "How many degrees fit across the strip.", 180, 60, 360, 10, " degrees").min(30).max(FULL_TURN);
    private final EnumSetting<Labels> labels = new EnumSetting<>("Compass labels",
        "What the marks are called.", Labels.POINTS)
        .describe(Labels.POINTS, "Compass points such as N and NE.")
        .describe(Labels.AXES, "Axis directions such as +X on the four main marks.")
        .describe(Labels.BOTH, "Compass points with the axis direction under each of the four main marks.");
    private final EnumSetting<Reading> reading = new EnumSetting<>("Compass number",
        "Writes a number under the strip.", Reading.NONE)
        .describe(Reading.NONE, "No number under the strip.")
        .describe(Reading.YAW, "The yaw your view faces to one decimal place.")
        .describe(Reading.BEARING, "Degrees clockwise from north up to 359.");
    private final BoolSetting needle = new BoolSetting("Compass needle",
        "Draws a needle in the middle of the strip where your view faces.", true);
    private final ColorSetting needleColor = new ColorSetting("Compass needle colour",
        "Colour of the needle.", 0, 0, 1, false).under(needle);
    private final ColorSetting color = new ColorSetting("Compass colour",
        "Colour of the marks and the number.", 190, false);
    private final ColorSetting northColor = new ColorSetting("North colour",
        "Colour of the north mark.", 0, 0.85f, 1f, false);
    private final BoolSetting waypoints = new BoolSetting("Compass waypoints",
        "Shows each waypoint on the strip in its own colour.", true);

    // The waypoints on the strip. They are gathered at most every fifty milliseconds.
    private final RowCache<WaypointStore.Waypoint> marks = new RowCache<>(() -> {
        Waypoints module = Modules.get(Waypoints.class);
        return module == null ? List.of() : module.visible();
    });

    public CompassElement() {
        super("Compass", "A sliding strip of the compass points.", false, 50, 0);
        add(field, labels, reading, needle, needleColor, color, northColor, waypoints);
    }

    @Override
    public void render(GuiGraphicsExtractor context, Font font) {
        // The view and not the body. Freecam and FreeLook and the front view turn one without the other.
        float yaw = OfflineClient.MC.gameRenderer.mainCamera().yRot();
        double half = field.getValue() / 2;
        context.enableScissor(0, 0, LENGTH, height(font));
        context.fill(0, 0, LENGTH, 1, LINE_COLOUR);
        for (int heading = 0; heading < FULL_TURN; heading += MINOR_STEP) {
            double offset = Mth.wrapDegrees(heading - yaw);
            if (Math.abs(offset) < half) {
                mark(context, font, heading, along(offset), strength(offset));
            }
        }
        if (waypoints.isOn()) {
            drawWaypoints(context, yaw);
        }
        if (needle.isOn()) {
            bar(context, LENGTH / 2f, 1, 0, NEEDLE_HEIGHT, needleColor.getColor());
        }
        String number = number(yaw);
        if (number != null) {
            label(context, font, number, LENGTH / 2f, numberTop(font), color.getColor());
        }
        context.disableScissor();
    }

    // Where a heading this many degrees off the view sits along the strip.
    private float along(double offset) {
        double span = field.getValue();
        return (float) ((offset + span / 2) / span * LENGTH);
    }

    // Marks fade out towards each end of the strip.
    private float strength(double offset) {
        double span = field.getValue();
        return (float) ((span / 2 - Math.abs(offset)) / (span * FADE_SHARE));
    }

    // Each waypoint sits on the strip the way it lies from the camera.
    private void drawWaypoints(GuiGraphicsExtractor context, float yaw) {
        if (OfflineClient.MC.player == null) {
            return;
        }
        Vec3 camera = OfflineClient.MC.gameRenderer.mainCamera().position();
        double half = field.getValue() / 2;
        for (WaypointStore.Waypoint waypoint : marks.get()) {
            Vec3 spot = Vec3.atCenterOfWithY(waypoint.pos(), camera.y);
            double offset = Mth.wrapDegrees(Bearing.between(camera, spot).degrees() - yaw);
            if (Math.abs(offset) < half) {
                int tint = ColorUtil.fade(waypoint.argb(), strength(offset));
                bar(context, along(offset), WAYPOINT_WIDTH, 1, TICK_HEIGHT, tint);
            }
        }
    }

    private void mark(GuiGraphicsExtractor context, Font font, int heading, float x, float strength) {
        CompassPoint point = heading % CompassPoint.STEP == 0 ? CompassPoint.nearest(heading) : null;
        int tint = ColorUtil.fade(point == CompassPoint.NORTH ? northColor.getColor() : color.getColor(), strength);
        bar(context, x, 1, 1, point == null ? MINOR_TICK_HEIGHT : TICK_HEIGHT, tint);
        if (point == null) {
            return;
        }
        int y = NAMES_TOP;
        if (!labels.is(Labels.AXES)) {
            label(context, font, point.letters(), x, y, tint);
            y += font.lineHeight;
        }
        if (!labels.is(Labels.POINTS) && point.axis() != null) {
            label(context, font, point.axis(), x, y, tint);
        }
    }

    // The number under the names. Null whilst there is none.
    private String number(float yaw) {
        return switch (reading.getValue()) {
            case NONE -> null;
            case YAW -> String.format(Locale.ROOT, "%.1f", Mth.wrapDegrees(yaw));
            case BEARING -> String.valueOf(Math.floorMod(Math.round(yaw) - CompassPoint.NORTH.yaw(), FULL_TURN));
        };
    }

    // An upright bar with its middle on x.
    private void bar(GuiGraphicsExtractor context, float x, int width, int top, int height, int tint) {
        float squeeze = squeeze();
        context.pose().pushMatrix();
        context.pose().translate(x - width * squeeze / 2, 0);
        context.pose().scale(squeeze, 1);
        context.fill(0, top, width, top + height, tint);
        context.pose().popMatrix();
    }

    // The ink centred on x. A glyph advances one pixel past its ink.
    private void label(GuiGraphicsExtractor context, Font font, String text, float x, int y, int tint) {
        float squeeze = squeeze();
        context.pose().pushMatrix();
        context.pose().translate(x - (font.width(text) - 1) * squeeze / 2, y);
        context.pose().scale(squeeze, 1);
        write(context, font, text, 0, 0, tint);
        context.pose().popMatrix();
    }

    // Undoes the extra width a stretch gave the strip. Marks and letters keep their shape.
    private float squeeze() {
        return (float) (scaleY() / scaleX());
    }

    private int numberTop(Font font) {
        return NAMES_TOP + (labels.is(Labels.BOTH) ? 2 : 1) * font.lineHeight;
    }

    @Override
    public int width(Font font) {
        return LENGTH;
    }

    @Override
    public int height(Font font) {
        return numberTop(font) + (reading.is(Reading.NONE) ? 0 : font.lineHeight);
    }
}
