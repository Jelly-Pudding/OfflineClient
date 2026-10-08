package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.config.WaypointStore;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.EntityDeathEvent;
import com.jellypudding.offlineclient.event.events.Render2DEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.render.NearFade;
import com.jellypudding.offlineclient.render.WorldToScreen;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.RenderUtil;
import com.jellypudding.offlineclient.util.ServerInfo;
import com.jellypudding.offlineclient.util.WorldWatch;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

public final class Waypoints extends Module {

    // Death markers are named Death followed by the time of day.
    private static final String DEATH_PREFIX = "Death";
    private static final DateTimeFormatter DEATH_TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int DEATH_HUE = 0;
    // Another player's death marker is named Grave and the player and the time of day such as
    // GraveSteve14:03:22. A waypoint of your own named Graveyard never matches.
    private static final String GRAVE_PREFIX = "Grave";
    private static final Pattern GRAVE = Pattern.compile(GRAVE_PREFIX + ".+\\d{2}:\\d{2}:\\d{2}");
    // Portal markers are named Portal and a number in the purple of a lit portal.
    private static final String PORTAL_PREFIX = "Portal";
    private static final Pattern PORTAL = Pattern.compile(PORTAL_PREFIX + "\\d+");
    private static final int PORTAL_HUE = 275;
    // A portal marker this close already marks the portal.
    private static final double SAME_PORTAL = 8;
    // A death marker this close counts as reached.
    private static final double REACHED = 3;
    // A trip starts within a second of last standing in a portal. The portal you come out of is
    // waited for five seconds since a busy server sends the new world late.
    private static final int DEPARTURE_TICKS = 20;
    private static final int ARRIVAL_TICKS = 100;

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
    private final NearFade fade = new NearFade(0);
    private final BoolSetting autoColor = new BoolSetting("Automatic colours",
        "Each new waypoint takes a colour from its name. Off gives it the colour below.", true);
    private final ColorSetting nextColor = new ColorSetting("Next colour",
        "Colour of the next waypoint you add. Recolour an old one with the waypoint colour command.", 200, false)
        .unless(autoColor);
    private final BoolSetting markDeaths = new BoolSetting("Mark deaths",
        "Saves a red waypoint where you die.", false);
    private final NumberSetting deathsKept = new NumberSetting("Deaths kept",
        "How many death markers are kept in each world for you and as many again for other players.",
        5, 1, 20, 1).min(1)
        .under(markDeaths);
    private final BoolSetting deathChat = new BoolSetting("Death message",
        "Tells you in chat where you died.", false)
        .under(markDeaths);
    private final BoolSetting otherDeaths = new BoolSetting("Other deaths",
        "Also saves a waypoint where a player you can see dies.", false)
        .under(markDeaths);
    private final BoolSetting forgetReached = new BoolSetting("Forget when reached",
        "Removes a death marker once you walk up to it.", false)
        .under(markDeaths);
    private final BoolSetting markPortals = new BoolSetting("Mark portals",
        "Saves a waypoint on both sides of each nether portal you travel through.", false);

    // Rebuilt once a tick.
    private List<WaypointStore.Waypoint> shown = List.of();

    // The player whose death was last marked. A respawn hands over a new one.
    private LocalPlayer marked;
    // Death markers you have been away from in this world. Only these count as reached. A
    // respawn right beside one leaves it.
    private final Set<String> leftBehind = new HashSet<>();
    private final WorldWatch world = new WorldWatch();
    // The nether portal block you last stood in and the dimension it is in.
    private BlockPos lastPortal;
    private String lastPortalDimension;
    private int sincePortal;
    // Ticks left to find the portal a trip came out of.
    private int arrivalWait;

    public Waypoints() {
        super("Waypoints", "Marks the coordinates you saved in the world.", Category.RENDER);
        addSettings(beam, beamHeight, box, label, distance, coordinates, scale, range,
            acrossDimensions, fade.setting(), autoColor, nextColor, markDeaths, deathsKept, deathChat,
            otherDeaths, forgetReached, markPortals);
        searchTags("waypoint", "marker", "coords", "grave", "portal");
    }

    @Override
    public String getSuffix() {
        return count(shown.size());
    }

    @Override
    protected void onEnable() {
        shown = List.of();
        forgetPortal();
    }

    @Override
    protected void onDisable() {
        shown = List.of();
        leftBehind.clear();
        forgetPortal();
    }

    private void forgetPortal() {
        lastPortal = null;
        arrivalWait = 0;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        shown = inGame() ? visible() : List.of();
        if (!inGame()) {
            return;
        }
        boolean newWorld = world.changed();
        if (newWorld) {
            leftBehind.clear();
        }
        if (mc.player.isDeadOrDying()) {
            markDeath(mc.player.position());
        } else if (markDeaths.isOn() && forgetReached.isOn()) {
            forgetReached();
        }
        followPortals(newWorld);
    }

    // The waypoints the markers show. The compass strip shows the same.
    public List<WaypointStore.Waypoint> visible() {
        List<WaypointStore.Waypoint> all = new ArrayList<>(WaypointStore.get().here());
        if (acrossDimensions.isOn()) {
            all.addAll(WaypointStore.get().mirrored());
        }
        all.removeIf(WaypointStore.Waypoint::hidden);
        return all;
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
        WaypointStore.get().mark(name, at, DEATH_HUE);
        dropOld(Waypoints::isDeathMarker);
        if (deathChat.isOn()) {
            ChatUtil.message("§cYou died at §f" + BlockUtil.text(at) + "§c.");
        }
    }

    // Only the newest markers of a kind are kept. Saving moves a marker to the end.
    private void dropOld(Predicate<String> kind) {
        List<String> marked = new ArrayList<>();
        for (WaypointStore.Waypoint waypoint : WaypointStore.get().here()) {
            if (kind.test(waypoint.name())) {
                marked.add(waypoint.name());
            }
        }
        for (int i = 0; i < marked.size() - deathsKept.getInt(); i++) {
            WaypointStore.get().remove(marked.get(i));
        }
    }

    private static boolean isDeathMarker(String name) {
        return name.length() > DEATH_PREFIX.length() && name.startsWith(DEATH_PREFIX)
            && Character.isDigit(name.charAt(DEATH_PREFIX.length()));
    }

    private static boolean isGrave(String name) {
        return GRAVE.matcher(name).matches();
    }

    @Subscribe
    private void onEntityDeath(EntityDeathEvent event) {
        if (!markDeaths.isOn() || !otherDeaths.isOn() || !(event.getEntity() instanceof Player player)
            || !EntityUtil.isRealPlayer(player)) {
            return;
        }
        String name = GRAVE_PREFIX + player.getGameProfile().name() + LocalTime.now().format(DEATH_TIME);
        WaypointStore.get().mark(name, player.blockPosition(), DEATH_HUE);
        dropOld(Waypoints::isGrave);
    }

    private void forgetReached() {
        Vec3 feet = mc.player.position();
        for (WaypointStore.Waypoint waypoint : WaypointStore.get().here()) {
            String name = waypoint.name();
            if (!isDeathMarker(name) && !isGrave(name)) {
                continue;
            }
            if (!waypoint.centre().closerThan(feet, REACHED)) {
                leftBehind.add(name);
            } else if (leftBehind.remove(name)) {
                WaypointStore.get().remove(name);
            }
        }
    }

    // A new world reached within a second of standing in a nether portal is the other side of it.
    // The portal block you come out of is marked once you stand in it.
    private void followPortals(boolean newWorld) {
        if (!markPortals.isOn()) {
            forgetPortal();
            return;
        }
        String dimension = ServerInfo.dimension();
        if (newWorld) {
            if (lastPortal != null && sincePortal <= DEPARTURE_TICKS
                && dimension.equals(WaypointStore.otherSide(lastPortalDimension))) {
                markPortal(lastPortal, lastPortalDimension);
                arrivalWait = ARRIVAL_TICKS;
            }
            lastPortal = null;
        }
        BlockPos portal = portalAt(mc.player.blockPosition());
        if (portal == null) {
            sincePortal++;
            arrivalWait = Math.max(0, arrivalWait - 1);
            return;
        }
        if (arrivalWait > 0) {
            markPortal(portal, dimension);
            arrivalWait = 0;
        }
        lastPortal = portal;
        lastPortalDimension = dimension;
        sincePortal = 0;
    }

    // The portal block at your feet or at your head. Null whilst you stand in neither.
    private BlockPos portalAt(BlockPos feet) {
        if (mc.level.getBlockState(feet).is(Blocks.NETHER_PORTAL)) {
            return feet;
        }
        BlockPos head = feet.above();
        return mc.level.getBlockState(head).is(Blocks.NETHER_PORTAL) ? head : null;
    }

    private void markPortal(BlockPos pos, String dimension) {
        Vec3 middle = Vec3.atCenterOf(pos);
        for (WaypointStore.Waypoint waypoint : WaypointStore.get().in(dimension)) {
            if (PORTAL.matcher(waypoint.name()).matches() && waypoint.centre().closerThan(middle, SAME_PORTAL)) {
                return;
            }
        }
        WaypointStore.get().markFresh(PORTAL_PREFIX, pos, dimension, PORTAL_HUE);
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
            Vec3 middle = waypoint.centre();
            if (limit > 0 && eye.distanceTo(middle) > limit) {
                continue;
            }
            float strength = fade.strengthAt(middle);
            if (strength <= 0) {
                continue;
            }
            int color = ColorUtil.fade(colorOf(waypoint), strength);
            if (beam.isOn()) {
                batch.line(middle.subtract(0, reach, 0), middle.add(0, reach, 0), color, true);
            }
            if (box.isOn()) {
                AABB shape = new AABB(waypoint.pos());
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
            Vec3 middle = waypoint.centre().add(0, 1, 0);
            double away = camera.distanceTo(middle);
            if (limit > 0 && away > limit) {
                continue;
            }
            Vec3 screen = WorldToScreen.project(middle);
            if (screen == null || fade.strengthAt(middle) < 1) {
                continue;
            }

            StringBuilder text = new StringBuilder(waypoint.name());
            if (coordinates.isOn()) {
                text.append(" ").append(BlockUtil.text(waypoint.pos()));
            }
            if (distance.isOn()) {
                text.append(" ").append(Math.round(away)).append("m");
            }
            RenderUtil.label(context, font, screen.x, screen.y, factor,
                List.of(text.toString()), List.of(colorOf(waypoint)));
        }
    }
}
