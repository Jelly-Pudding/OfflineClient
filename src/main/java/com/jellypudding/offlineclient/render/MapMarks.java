package com.jellypudding.offlineclient.render;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.config.FindLog;
import com.jellypudding.offlineclient.config.FindSource;
import com.jellypudding.offlineclient.config.WaypointStore;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.module.ModuleManager;
import com.jellypudding.offlineclient.modules.render.Radar;
import com.jellypudding.offlineclient.modules.render.Waypoints;
import com.jellypudding.offlineclient.util.ChunkPaint;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.Modules;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3x2f;

import java.util.ArrayList;
import java.util.List;

// What a map draws over the land. The entities Radar picks and your waypoints and the hunting finds
// and you yourself. The minimap and the map of everything you explored share it and a map you
// carry takes the arrow for you.
public final class MapMarks {

    // A waypoint or a find with the colour it is drawn in and what it is called.
    public record Mark(double x, double z, int colour, String label, boolean waypoint) {
    }

    private static final int OUTLINE = 0xC0000000;
    private static final int SELF = 0xFFFFFFFF;
    private static final int NORTH = 0xFFE05050;
    // Half the side of the square a waypoint and a find and an entity are drawn as.
    private static final int WAYPOINT_SIZE = 2;
    private static final int FIND_SIZE = 1;
    private static final int PLAYER_SIZE = 2;
    private static final int ENTITY_SIZE = 1;
    // How far inside the edge a waypoint off the map sits and how far the middle of the letter
    // for north does.
    private static final int EDGE_INSET = 4;
    private static final int NORTH_INSET = 6;
    // Between a mark and its name and between a dot and the name over it.
    private static final int NAME_GAP = 2;
    // The arrow for you in pixels from your spot. Its tip and its back corners and the notch
    // between them.
    private static final double ARROW_TIP = 4.5;
    private static final double ARROW_BACK = 3.5;
    private static final double ARROW_SIDE = 3.5;
    private static final double ARROW_NOTCH = 1.5;
    // The dark arrow under it is this much bigger.
    private static final double OUTLINE_GROWTH = 1.4;
    // Finds take a colour from the name of the module that made them.
    private static final float FIND_SATURATION = 0.55f;
    private static final int FULL_TURN = 360;
    private static final String NORTH_LETTER = "N";

    private MapMarks() {
    }

    // Your waypoints in the dimension you are in and the finds of every hunting module here.
    public static List<Mark> gather() {
        List<Mark> marks = new ArrayList<>();
        Waypoints waypoints = Modules.get(Waypoints.class);
        List<WaypointStore.Waypoint> shown = waypoints == null ? WaypointStore.get().here() : waypoints.visible();
        for (WaypointStore.Waypoint waypoint : shown) {
            if (!waypoint.hidden()) {
                Vec3 centre = waypoint.centre();
                marks.add(new Mark(centre.x, centre.z, waypoint.argb(), waypoint.name(), true));
            }
        }
        for (FindLog log : FindSource.logs()) {
            int colour = colourOf(log);
            for (FindLog.Find find : log.here()) {
                String label = find.detail().isBlank() ? find.kind() : find.detail();
                marks.add(new Mark(find.pos().getX() + 0.5, find.pos().getZ() + 0.5, colour, label, false));
            }
        }
        return marks;
    }

    // The colour each hunting module that is on gave the chunks of the dimension you are in. A
    // module that marks whole chunks but paints none itself has its finds painted in its colour.
    public static Long2IntOpenHashMap chunkColours() {
        Long2IntOpenHashMap colours = new Long2IntOpenHashMap();
        ModuleManager manager = OfflineClient.INSTANCE.getModuleManager();
        if (manager == null) {
            return colours;
        }
        for (Module module : manager.getEnabled()) {
            if (module instanceof ChunkPaint painter) {
                painter.paintChunks(colours);
            } else if (module instanceof FindSource source && source.findLog().marksChunks()) {
                int colour = colourOf(source.findLog());
                for (FindLog.Find find : source.findLog().here()) {
                    colours.put(ChunkPos.pack(find.pos()), colour);
                }
            }
        }
        return colours;
    }

    private static int colourOf(FindLog log) {
        return ColorUtil.hsv(Math.floorMod(log.name().hashCode(), FULL_TURN), FIND_SATURATION, 1f);
    }

    // A dot for each entity Radar picks with the name of each player over theirs.
    public static void entities(GuiGraphicsExtractor context, Font font, MapView view, boolean shadow) {
        Radar radar = Modules.get(Radar.class);
        ClientLevel level = OfflineClient.MC.level;
        if (radar == null || level == null) {
            return;
        }
        for (Entity entity : level.entitiesForRendering()) {
            if (!radar.shows(entity)) {
                continue;
            }
            float x = view.screenX(entity.getX(), entity.getZ());
            float y = view.screenY(entity.getX(), entity.getZ());
            if (!view.inside(x, y, 1)) {
                continue;
            }
            int colour = radar.colorOf(entity);
            String name = entity instanceof Player player && radar.writesNames()
                ? EntityUtil.displayNameOf(player) : null;
            dot(context, font, view, Math.round(x), Math.round(y), entity instanceof Player ? PLAYER_SIZE : ENTITY_SIZE,
                colour, name, shadow);
        }
    }

    // A square dot with a name over it kept inside the map wherever it fits. A null name writes none.
    private static void dot(GuiGraphicsExtractor context, Font font, MapView view, int x, int y, int half,
                            int colour, String name, boolean shadow) {
        context.fill(x - half, y - half, x + half + 1, y + half + 1, colour);
        if (name == null) {
            return;
        }
        int wide = font.width(name);
        int left = x - wide / 2;
        if (wide < view.width() - 2) {
            left = Math.clamp(left, view.left() + 1, view.left() + view.width() - wide - 1);
        }
        context.text(font, name, left, Math.max(view.top() + 1, y - font.lineHeight - NAME_GAP), colour, shadow);
    }

    // Each mark as a small square. A waypoint off the map sits on the edge the way it lies whilst
    // edges is on. A find off the map is left out. Names go beside waypoints whilst names is on
    // and over every square.
    public static void marks(GuiGraphicsExtractor context, Font font, MapView view, List<Mark> marks, boolean edges,
                             boolean names, boolean shadow) {
        Shapes squares = Shapes.within(context, view);
        if (squares == null) {
            return;
        }
        List<Runnable> labels = new ArrayList<>();
        for (Mark mark : marks) {
            double x = view.screenX(mark.x(), mark.z());
            double y = view.screenY(mark.x(), mark.z());
            if (!view.inside(x, y, EDGE_INSET)) {
                if (!mark.waypoint() || !edges) {
                    continue;
                }
                double share = view.toEdge(x, y, EDGE_INSET);
                x = view.middleX() + (x - view.middleX()) * share;
                y = view.middleY() + (y - view.middleY()) * share;
            }
            int left = (int) Math.round(x);
            int top = (int) Math.round(y);
            int half = mark.waypoint() ? WAYPOINT_SIZE : FIND_SIZE;
            squares.square(left - half - 1, top - half - 1, left + half + 2, top + half + 2, OUTLINE);
            squares.square(left - half, top - half, left + half + 1, top + half + 1, mark.colour());
            if (names && mark.waypoint()) {
                int nameX = left + half + NAME_GAP + 1;
                int nameY = top - font.lineHeight / 2;
                labels.add(() -> context.text(font, mark.label(), nameX, nameY, mark.colour(), shadow));
            }
        }
        squares.draw(context);
        labels.forEach(Runnable::run);
    }

    // The mark drawn nearest a screen spot within the reach. Null when none is.
    public static Mark nearest(MapView view, List<Mark> marks, double screenX, double screenY, double reach) {
        Mark best = null;
        double bestDistance = reach * reach;
        for (Mark mark : marks) {
            double dx = view.screenX(mark.x(), mark.z()) - screenX;
            double dy = view.screenY(mark.x(), mark.z()) - screenY;
            double distance = dx * dx + dy * dy;
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = mark;
            }
        }
        return best;
    }

    // An arrow on your spot pointing the way you face. Yaw nought looks south. Its corners keep
    // their fractions of a pixel and the arrow glides and turns without a jump.
    public static void self(GuiGraphicsExtractor context, MapView view, double x, double z, float yaw) {
        Shapes arrows = Shapes.within(context, view);
        if (arrows == null) {
            return;
        }
        Vec3 facing = Vec3.directionFromRotation(0, yaw);
        double pointX = view.turnedX(facing.x, facing.z);
        double pointY = view.turnedY(facing.x, facing.z);
        double spotX = view.screenX(x, z);
        double spotY = view.screenY(x, z);
        arrow(arrows, spotX, spotY, pointX, pointY, OUTLINE_GROWTH, OUTLINE);
        arrow(arrows, spotX, spotY, pointX, pointY, 1, SELF);
        arrows.draw(context);
    }

    // Two triangles meeting at the tip with a notch cut between the back corners.
    private static void arrow(Shapes shapes, double x, double y, double pointX, double pointY, double size,
                              int colour) {
        double sideX = -pointY;
        double sideY = pointX;
        double tipX = x + pointX * ARROW_TIP * size;
        double tipY = y + pointY * ARROW_TIP * size;
        double backX = x - pointX * ARROW_BACK * size;
        double backY = y - pointY * ARROW_BACK * size;
        double notchX = x - pointX * ARROW_NOTCH * size;
        double notchY = y - pointY * ARROW_NOTCH * size;
        double wing = ARROW_SIDE * size;
        shapes.triangle(tipX, tipY, backX + sideX * wing, backY + sideY * wing, notchX, notchY, colour);
        shapes.triangle(tipX, tipY, notchX, notchY, backX - sideX * wing, backY - sideY * wing, colour);
    }

    // The letter for north on the edge of the map the way north lies.
    public static void north(GuiGraphicsExtractor context, Font font, MapView view, boolean shadow) {
        double x = view.middleX() + view.turnedX(0, -view.height());
        double y = view.middleY() + view.turnedY(0, -view.height());
        double share = view.toEdge(x, y, NORTH_INSET);
        x = view.middleX() + (x - view.middleX()) * share;
        y = view.middleY() + (y - view.middleY()) * share;
        context.text(font, NORTH_LETTER, (int) Math.round(x - font.width(NORTH_LETTER) / 2.0),
            (int) Math.round(y - font.lineHeight / 2.0), NORTH, shadow);
    }

    // The filled shapes of one call as a single element clipped to the view. The game places each
    // element it is given by searching the ones before it. Thousands of finds as an element each
    // would cost the square of their count every frame. A corner here may sit between two pixels
    // which a corner of the game's own filled rectangles cannot.
    private static final class Shapes implements GuiElementRenderState {

        // Every shape has four corners with an x and a y each.
        private static final int CORNERS = 4;
        private static final int PARTS = CORNERS * 2;

        private final Matrix3x2f pose;
        private final ScreenRectangle area;
        private final FloatArrayList corners = new FloatArrayList();
        private final IntArrayList colours = new IntArrayList();

        private Shapes(Matrix3x2f pose, ScreenRectangle area) {
            this.pose = pose;
            this.area = area;
        }

        // The view on the screen the way a scissor round it would clip. Null when none of it shows.
        private static Shapes within(GuiGraphicsExtractor context, MapView view) {
            ScreenRectangle screen = new ScreenRectangle(0, 0, context.guiWidth(), context.guiHeight());
            ScreenRectangle area = new ScreenRectangle(view.left(), view.top(), view.width(), view.height())
                .transformAxisAligned(context.pose()).intersection(screen);
            return area == null ? null : new Shapes(new Matrix3x2f(context.pose()), area);
        }

        // The corners go round the way the game's own filled rectangles do.
        private void square(int left, int top, int right, int bottom, int colour) {
            corner(right, bottom);
            corner(right, top);
            corner(left, top);
            corner(left, bottom);
            colours.add(colour);
        }

        // A triangle is a shape with two corners on one spot. The game only draws a shape whose
        // corners go round the way those of a square do.
        private void triangle(double ax, double ay, double bx, double by, double cx, double cy, int colour) {
            if ((bx - ax) * (cy - ay) - (by - ay) * (cx - ax) > 0) {
                triangle(ax, ay, cx, cy, bx, by, colour);
                return;
            }
            corner(ax, ay);
            corner(bx, by);
            corner(cx, cy);
            corner(cx, cy);
            colours.add(colour);
        }

        private void corner(double x, double y) {
            corners.add((float) x);
            corners.add((float) y);
        }

        private void draw(GuiGraphicsExtractor context) {
            if (!colours.isEmpty()) {
                context.guiRenderState.addGuiElement(this);
            }
        }

        @Override
        public void buildVertices(VertexConsumer vertices) {
            for (int shape = 0; shape < colours.size(); shape++) {
                int colour = colours.getInt(shape);
                for (int corner = 0; corner < CORNERS; corner++) {
                    int at = shape * PARTS + corner * 2;
                    vertices.addVertexWith2DPose(pose, corners.getFloat(at), corners.getFloat(at + 1))
                        .setColor(colour);
                }
            }
        }

        @Override
        public RenderPipeline pipeline() {
            return RenderPipelines.GUI;
        }

        @Override
        public TextureSetup textureSetup() {
            return TextureSetup.noTexture();
        }

        @Override
        public ScreenRectangle scissorArea() {
            return area;
        }

        @Override
        public ScreenRectangle bounds() {
            return area;
        }
    }
}
