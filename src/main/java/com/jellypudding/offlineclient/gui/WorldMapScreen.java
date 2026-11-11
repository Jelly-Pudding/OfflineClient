package com.jellypudding.offlineclient.gui;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.config.WaypointStore;
import com.jellypudding.offlineclient.modules.render.WorldMap;
import com.jellypudding.offlineclient.path.Travel;
import com.jellypudding.offlineclient.render.ClientFonts;
import com.jellypudding.offlineclient.render.MapMarks;
import com.jellypudding.offlineclient.render.MapOverlay;
import com.jellypudding.offlineclient.render.MapView;
import com.jellypudding.offlineclient.render.TerrainPicture;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.ExploredLand;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.ServerInfo;
import com.jellypudding.offlineclient.util.TerrainLayer;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

// The map of everything you explored on this server in the dimension you are in. Drag it to move
// it and scroll to zoom. A click walks there and a right click saves a waypoint. It follows the
// ClickGUI scale like every other panel.
public final class WorldMapScreen extends ScaledScreen {

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
    // Places the scale at the foot is written to.
    private static final int SCALE_PLACES = 1;
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

    private final WorldMap worldMap;
    private final MapOverlay overlay;
    private final TerrainPicture picture = new TerrainPicture();
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

    public WorldMapScreen(WorldMap worldMap) {
        super(Component.literal("Map"));
        this.worldMap = worldMap;
        overlay = worldMap.overlay();
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
        return viewWidth() - 2 * GAP;
    }

    private int windowHeight() {
        return viewHeight() - 2 * GAP;
    }

    @Override
    public boolean isTyping() {
        return false;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float deltaTicks) {
        GuiTheme.dimBackground(this, context);
    }

    // The readout at the foot reads fractions of a view pixel. One whole pixel is many blocks at the
    // furthest zoom.
    @Override
    protected void renderScaled(GuiGraphicsExtractor context, double mouseX, double mouseY, float partialTicks) {
        Font font = ClientFonts.gui();
        int ww = windowWidth();
        int wh = windowHeight();
        WindowParts.frame(context, GAP, GAP, ww, wh);
        WindowParts.title(context, font, GAP, GAP, ww, "Map",
            WindowParts.place() + SEPARATOR + ServerInfo.dimensionName(ServerInfo.dimension()), scaleText());
        int top = GAP + WindowParts.TITLE_HEIGHT + 1;
        view = new MapView(centreX, centreZ, zoom, 0, GAP + 1, top, ww - 2,
            wh - WindowParts.TITLE_HEIGHT - WindowParts.FOOT_HEIGHT - 2);
        context.fill(view.left(), view.top(), view.left() + view.width(), view.top() + view.height(), UNSEEN);
        TerrainLayer land = ExploredLand.here();
        if (land != null) {
            picture.draw(context, view, land, overlay.tints(), REPAINT_GAP_MS);
        }
        context.enableScissor(view.left(), view.top(), view.left() + view.width(), view.top() + view.height());
        overlay.draw(context, font, view, false, true, false);
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
        String number = ChatUtil.decimal(wide ? zoom : 1 / zoom, SCALE_PLACES);
        String unit = wide ? "block" : "pixel";
        return number + " " + (number.equals("1") ? unit : unit + "s") + (wide ? " a pixel" : " a block");
    }

    // The mark under the pointer beats the spot under it.
    private String pointedAt(double mouseX, double mouseY) {
        if (view == null || !view.inside(mouseX, mouseY, 0)) {
            return HINT;
        }
        MapMarks.Mark mark = overlay.nearest(view, mouseX, mouseY, HOVER_REACH);
        if (mark != null) {
            return mark.label() + SEPARATOR + Mth.floor(mark.x()) + " " + Mth.floor(mark.z());
        }
        return Mth.floor(view.worldX(mouseX, mouseY)) + " " + Mth.floor(view.worldZ(mouseX, mouseY)) + SEPARATOR
            + HINT;
    }

    // The block under a spot of the map at the height to stand at there.
    private BlockPos spotAt(double screenX, double screenY) {
        int x = Mth.floor(view.worldX(screenX, screenY));
        int z = Mth.floor(view.worldZ(screenX, screenY));
        return new BlockPos(x, ExploredLand.standY(x, z), z);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (worldMap.isMapKey(event)) {
            onClose();
            return true;
        }
        double mx = toView(event.x());
        double my = toView(event.y());
        if (view == null || !view.inside(mx, my, 0) || OfflineClient.MC.player == null) {
            return super.mouseClicked(event, doubleClick);
        }
        if (InputUtil.isRight(event.button())) {
            BlockPos spot = spotAt(mx, my);
            String name = WaypointStore.get().markFresh(WAYPOINT_STEM, spot, ServerInfo.dimension(),
                Modules.nextWaypointColour());
            overlay.refresh();
            footer.say("Saved the waypoint " + name + " at " + BlockUtil.text(spot));
        } else if (InputUtil.isLeft(event.button())) {
            holdScale(true);
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
        double mx = toView(event.x());
        double my = toView(event.y());
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
        holdScale(false);
        if (!dragged && view != null) {
            walk(spotAt(toView(event.x()), toView(event.y())));
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
        double mx = toView(mouseX);
        double my = toView(mouseY);
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
        if (key == InputConstants.KEY_ESCAPE || worldMap.isMapKey(event)) {
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
