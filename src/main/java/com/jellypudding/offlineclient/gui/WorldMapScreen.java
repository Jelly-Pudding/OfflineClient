package com.jellypudding.offlineclient.gui;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.config.WaypointStore;
import com.jellypudding.offlineclient.modules.render.Minimap;
import com.jellypudding.offlineclient.path.Travel;
import com.jellypudding.offlineclient.render.MapMarks;
import com.jellypudding.offlineclient.render.MapView;
import com.jellypudding.offlineclient.render.TerrainPicture;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.ServerInfo;
import com.jellypudding.offlineclient.util.TerrainLayer;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

// The map of everything you explored on this server in the dimension you are in. Drag it to move
// it and scroll to zoom. A click walks there and a right click saves a waypoint. It follows the
// ClickGUI scale like every other panel.
public final class WorldMapScreen extends Screen implements ClientScreen {

    // Blocks a pixel at the closest and the furthest zoom.
    private static final double MIN_ZOOM = 0.125;
    private static final double MAX_ZOOM = 64;
    // Each notch of the wheel zooms by this much.
    private static final double ZOOM_STEP = Math.sqrt(2);
    // A press that moves less than this many pixels is a click and not a drag.
    private static final double CLICK_SLOP = 3;
    // The land changes as chunks arrive. The picture catches up at most this often.
    private static final long REPAINT_GAP_MS = 500;
    // How near the pointer a mark must be to be named at the foot.
    private static final double HOVER_REACH = 5;
    // Space round the window.
    private static final int GAP = 10;
    // Land never seen.
    private static final int UNSEEN = 0xFF14141B;
    private static final String WAYPOINT_STEM = "map";
    private static final String SEPARATOR = " · ";
    private static final String HINT = "Drag to move and scroll to zoom. Click to walk there. Right click saves a "
        + "waypoint. Space centres on you.";

    // The zoom the map last had. It opens at it for the rest of the session.
    private static double lastZoom = 1;

    private final Minimap minimap;
    private final TerrainPicture picture = new TerrainPicture();
    private final LatchedScale latched = new LatchedScale();
    private final Footer footer = new Footer();
    private double centreX;
    private double centreZ;
    private double zoom = lastZoom;
    // Where a press with the left button began and where the map stood then.
    private boolean pressed;
    private boolean dragged;
    private double pressX;
    private double pressY;
    private double pressCentreX;
    private double pressCentreZ;
    // The view drawn last frame. Clicks are read against it.
    private MapView view;

    public WorldMapScreen(Minimap minimap) {
        super(Component.literal("Map"));
        this.minimap = minimap;
        centreOnYou();
    }

    private void centreOnYou() {
        LocalPlayer player = OfflineClient.MC.player;
        if (player != null) {
            centreX = player.getX();
            centreZ = player.getZ();
        }
    }

    private int windowWidth() {
        return (int) latched.toView(width) - 2 * GAP;
    }

    private int windowHeight() {
        return (int) latched.toView(height) - 2 * GAP;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean isTyping() {
        return false;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float deltaTicks) {
        GuiScreenBase.dimBackground(this, context);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float partialTicks) {
        latched.refresh();
        context.pose().pushMatrix();
        context.pose().scale(latched.get(), latched.get());
        renderView(context, latched.toView(mouseX), latched.toView(mouseY), partialTicks);
        context.pose().popMatrix();
    }

    private void renderView(GuiGraphicsExtractor context, double mouseX, double mouseY, float partialTicks) {
        Font font = OfflineClient.MC.font;
        int ww = windowWidth();
        int wh = windowHeight();
        WindowParts.frame(context, GAP, GAP, ww, wh);
        WindowParts.title(context, font, GAP, GAP, ww, "Map",
            WindowParts.place() + SEPARATOR + ServerInfo.dimensionName(ServerInfo.dimension()), scaleText());
        int top = GAP + WindowParts.TITLE_HEIGHT + 1;
        view = new MapView(centreX, centreZ, zoom, 0, GAP + 1, top, ww - 2,
            wh - WindowParts.TITLE_HEIGHT - WindowParts.FOOT_HEIGHT - 2);
        context.fill(view.left(), view.top(), view.left() + view.width(), view.top() + view.height(), UNSEEN);
        TerrainLayer land = minimap.explored();
        if (land != null) {
            picture.draw(context, view, land, minimap.tints(), REPAINT_GAP_MS);
        }
        context.enableScissor(view.left(), view.top(), view.left() + view.width(), view.top() + view.height());
        if (minimap.drawsMarks()) {
            MapMarks.marks(context, font, view, minimap.marks(), false, true, false);
        }
        if (minimap.drawsEntities()) {
            MapMarks.entities(context, font, view, false);
        }
        LocalPlayer player = OfflineClient.MC.player;
        if (player != null) {
            Vec3 at = player.getPosition(partialTicks);
            MapMarks.self(context, view, at.x, at.z, player.getYRot());
        }
        context.disableScissor();
        footer.begin();
        footer.showStatus();
        footer.render(context, font, GAP, GAP, ww, wh, pointedAt(mouseX, mouseY));
    }

    // Such as 4 blocks a pixel or 2.8 pixels a block.
    private String scaleText() {
        boolean wide = zoom >= 1;
        String number = String.format(Locale.ROOT, "%.1f", wide ? zoom : 1 / zoom).replace(".0", "");
        String unit = wide ? "block" : "pixel";
        return number + " " + (number.equals("1") ? unit : unit + "s") + (wide ? " a pixel" : " a block");
    }

    // The mark under the pointer beats the spot under it.
    private String pointedAt(double mouseX, double mouseY) {
        if (view == null || !view.inside(mouseX, mouseY, 0)) {
            return HINT;
        }
        if (minimap.drawsMarks()) {
            MapMarks.Mark mark = MapMarks.nearest(view, minimap.marks(), mouseX, mouseY, HOVER_REACH);
            if (mark != null) {
                return mark.label() + SEPARATOR + Mth.floor(mark.x()) + " " + Mth.floor(mark.z());
            }
        }
        return Mth.floor(view.worldX(mouseX, mouseY)) + " " + Mth.floor(view.worldZ(mouseX, mouseY)) + SEPARATOR
            + HINT;
    }

    // The block under a spot of the map at the height to stand at there.
    private BlockPos spotAt(double screenX, double screenY) {
        int x = Mth.floor(view.worldX(screenX, screenY));
        int z = Mth.floor(view.worldZ(screenX, screenY));
        return new BlockPos(x, minimap.standY(x, z), z);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = latched.toView(event.x());
        double my = latched.toView(event.y());
        if (view == null || !view.inside(mx, my, 0) || OfflineClient.MC.player == null) {
            return super.mouseClicked(event, doubleClick);
        }
        if (InputUtil.isRight(event.button())) {
            BlockPos spot = spotAt(mx, my);
            String name = WaypointStore.get().markFresh(WAYPOINT_STEM, spot, ServerInfo.dimension(),
                Modules.nextWaypointHue());
            minimap.refreshMarks();
            footer.say("Saved the waypoint " + name + " at " + BlockUtil.text(spot));
        } else if (InputUtil.isLeft(event.button())) {
            latched.hold(true);
            pressed = true;
            dragged = false;
            pressX = mx;
            pressY = my;
            pressCentreX = centreX;
            pressCentreZ = centreZ;
        }
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (!pressed) {
            return super.mouseDragged(event, dragX, dragY);
        }
        double mx = latched.toView(event.x());
        double my = latched.toView(event.y());
        if (Math.hypot(mx - pressX, my - pressY) >= CLICK_SLOP) {
            dragged = true;
        }
        if (dragged) {
            centreX = pressCentreX - (mx - pressX) * zoom;
            centreZ = pressCentreZ - (my - pressY) * zoom;
        }
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (!pressed || !InputUtil.isLeft(event.button())) {
            return super.mouseReleased(event);
        }
        pressed = false;
        latched.hold(false);
        if (!dragged && view != null) {
            walk(spotAt(latched.toView(event.x()), latched.toView(event.y())));
        }
        return true;
    }

    // A walk cannot run behind a screen and the map closes once it starts.
    private void walk(BlockPos spot) {
        if (Travel.to(spot)) {
            onClose();
        } else {
            footer.say("Could not start the walk there", GuiTheme.RED_TEXT);
        }
    }

    // Zooms about the spot under the pointer.
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        double mx = latched.toView(mouseX);
        double my = latched.toView(mouseY);
        if (view == null || scrollY == 0 || !view.inside(mx, my, 0)) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        double worldX = view.worldX(mx, my);
        double worldZ = view.worldZ(mx, my);
        zoom = Math.clamp(zoom * Math.pow(ZOOM_STEP, -Math.signum(scrollY)), MIN_ZOOM, MAX_ZOOM);
        lastZoom = zoom;
        centreX = worldX - (mx - view.middleX()) * zoom;
        centreZ = worldZ - (my - view.middleY()) * zoom;
        if (pressed) {
            pressX = mx;
            pressY = my;
            pressCentreX = centreX;
            pressCentreZ = centreZ;
        }
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (key == InputConstants.KEY_ESCAPE || minimap.isMapKey(key)) {
            onClose();
            return true;
        }
        if (key == InputConstants.KEY_SPACE) {
            centreOnYou();
            return true;
        }
        return super.keyPressed(event);
    }

    // The texture of the map goes with the screen.
    @Override
    public void removed() {
        picture.close();
        super.removed();
    }
}
