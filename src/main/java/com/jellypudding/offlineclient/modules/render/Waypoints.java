package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.config.WaypointStore;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.EntityDeathEvent;
import com.jellypudding.offlineclient.event.events.Render2DEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.gui.WaypointsScreen;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.render.NearFade;
import com.jellypudding.offlineclient.render.WaypointIcons;
import com.jellypudding.offlineclient.render.WorldToScreen;
import com.jellypudding.offlineclient.setting.ActionSetting;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.Dimension;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.LifeWatch;
import com.jellypudding.offlineclient.util.RenderUtil;
import com.jellypudding.offlineclient.util.ServerInfo;
import com.jellypudding.offlineclient.util.WorldWatch;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
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
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

public final class Waypoints extends Module {

    private static final String DEATH_PREFIX = "Death";
    private static final DateTimeFormatter DEATH_TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final WaypointStore.Colour DEATH_COLOUR = WaypointStore.Colour.ofHue(0);
    // A waypoint of your own named Graveyard never matches.
    private static final String GRAVE_PREFIX = "Grave";
    private static final Pattern GRAVE = Pattern.compile(GRAVE_PREFIX + ".+\\d{2}:\\d{2}:\\d{2}");
    private static final String PORTAL_PREFIX = "Portal";
    private static final Pattern PORTAL = Pattern.compile(PORTAL_PREFIX + "\\d+");
    private static final WaypointStore.Colour PORTAL_COLOUR = WaypointStore.Colour.ofHue(275);
    private static final double SAME_PORTAL = 8;
    // A death marker goes once you stand this close to it. A find lies somewhere about the spot
    // the server gave away and goes from further off.
    private static final WaypointStore.Near REACHED = WaypointStore.Near.of(WaypointStore.Action.DELETE, 3);
    private static final int FIND_BLOCKS = 16;
    private static final WaypointStore.Near FIND_REACHED =
        WaypointStore.Near.of(WaypointStore.Action.DELETE, FIND_BLOCKS);
    // A trip starts within a second of last standing in a portal. The portal you come out of is
    // waited for five seconds since a busy server sends the new world late.
    private static final int DEPARTURE_TICKS = 20;
    private static final int ARRIVAL_TICKS = 100;

    private final BoolSetting beam = new BoolSetting("Beam",
        "Draws a tall line that shows a marker over hills.", true);
    private final NumberSetting beamHeight = new NumberSetting("Beam height",
        "How far the line reaches up and down.", 64, 8, 320, 8, " blocks").min(1)
        .under(beam);
    private final BoolSetting box = new BoolSetting("Box",
        "Draws a box on the marked block.", true);
    private final BoolSetting label = new BoolSetting("Label",
        "Writes the name above the marker.", true);
    private final BoolSetting distance = new BoolSetting("Distance",
        "Adds how far away the marker is to the label.", true)
        .under(label);
    private final BoolSetting coordinates = new BoolSetting("Coordinates",
        "Adds the block position to the label.", false)
        .under(label);
    private final NumberSetting labelReach = new NumberSetting("Label reach",
        "Writes a label only whilst its marker is within this many pixels of the crosshair. Nought writes"
            + " every label.", 0, 0, 400, 10, " px")
        .under(label);
    private final EnumSetting<WaypointStore.Icon> icon = new EnumSetting<>("Icon",
        "The shape drawn on a waypoint that has no icon of its own.", WaypointStore.Icon.NONE);
    private final NumberSetting scale = scaleRow(
        "Size of the label and icon of a waypoint that has no scale of its own.", 1);
    private final NumberSetting range = rangeRow(
        "How far away a waypoint with no range of its own is still drawn. Nought draws it from any distance.", 0);
    private final BoolSetting acrossDimensions = new BoolSetting("Across dimensions",
        "Shows overworld markers in the nether and the other way round with the coordinates scaled by eight."
            + " A waypoint with a switch of its own follows that.", true);
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
        5, 1, 20, 1)
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
    private final BoolSetting forgetFinds = new BoolSetting("Forget finds",
        "Removes a waypoint a module or the locate command saved for a find once you come within "
            + FIND_BLOCKS + " blocks of it.", false);
    private final BoolSetting markPortals = new BoolSetting("Mark portals",
        "Saves a waypoint on both sides of each nether portal you travel through.", false);
    private final ActionSetting openList = new ActionSetting("Open waypoints",
        "Opens the list of the waypoints on this server where each one can be edited.", WaypointsScreen::open);

    private List<WaypointStore.Waypoint> shown = List.of();

    private final LifeWatch marked = new LifeWatch();
    // Waypoints you have been outside the near distance of in this world since they last changed.
    // Only these act when you come near. One made or edited where you stand waits until you have left.
    private final Set<WaypointStore.Waypoint> armed = new HashSet<>();
    private final WorldWatch world = new WorldWatch();
    private BlockPos lastPortal;
    private String lastPortalDimension;
    private int sincePortal;
    private int arrivalWait;

    public Waypoints() {
        super("Waypoints", "Marks the coordinates you saved in the world.", Category.RENDER);
        addSettings(beam, beamHeight, box, label, distance, coordinates, labelReach, icon, scale, range,
            acrossDimensions, fade.setting(), autoColor, nextColor, markDeaths, deathsKept, deathChat,
            otherDeaths, forgetReached, forgetFinds, markPortals, openList);
        searchTags("waypoint", "marker", "coords", "grave", "portal", "icon");
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
        armed.clear();
        forgetPortal();
    }

    private void forgetPortal() {
        lastPortal = null;
        arrivalWait = 0;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        shown = visible();
        boolean newWorld = world.changed();
        if (newWorld) {
            armed.clear();
        }
        if (mc.player.isDeadOrDying()) {
            markDeath(mc.player.position());
        } else {
            actNear();
        }
        followPortals(newWorld);
    }

    public List<WaypointStore.Waypoint> visible() {
        List<WaypointStore.Waypoint> all = new ArrayList<>(WaypointStore.get().here());
        for (WaypointStore.Waypoint far : WaypointStore.get().mirrored()) {
            if (resolved(far).mirrored()) {
                all.add(far);
            }
        }
        all.removeIf(WaypointStore.Waypoint::hidden);
        return all;
    }

    // What the module gives a waypoint wherever it has nothing of its own. The list edits a
    // waypoint against these.
    public WaypointStore.Options defaults(WaypointStore.Waypoint waypoint) {
        return new WaypointStore.Options(icon.getValue(), scale.getValue(), range.getValue(),
            acrossDimensions.isOn(), usualRule(waypoint));
    }

    public WaypointStore.Options resolved(WaypointStore.Waypoint waypoint) {
        return waypoint.options().over(defaults(waypoint));
    }

    // The Scale row here and the one each waypoint has in the list. Only the text differs.
    public static NumberSetting scaleRow(String description, double value) {
        return new NumberSetting("Scale", description, value, 0.5, 3, 0.1).min(WaypointStore.Options.SMALLEST_SCALE);
    }

    public static NumberSetting rangeRow(String description, double value) {
        return new NumberSetting("Range", description, value, 0, 2000, 100, " blocks");
    }

    private WaypointStore.Near usualRule(WaypointStore.Waypoint waypoint) {
        String name = waypoint.name();
        if (markDeaths.isOn() && forgetReached.isOn() && (isDeathMarker(name) || isGrave(name))) {
            return REACHED;
        }
        return waypoint.found() && forgetFinds.isOn() ? FIND_REACHED : WaypointStore.Near.NOTHING;
    }

    // AutoRespawn calls this before it respawns and the tick above catches a death without it.
    public void markDeath(Vec3 position) {
        if (!markDeaths.isOn() || !inGame() || !marked.changed()) {
            return;
        }
        BlockPos at = BlockPos.containing(position);
        String name = DEATH_PREFIX + LocalTime.now().format(DEATH_TIME);
        WaypointStore.get().mark(name, at, DEATH_COLOUR);
        dropOld(Waypoints::isDeathMarker);
        if (deathChat.isOn()) {
            ChatUtil.message("§cYou died at §f" + BlockUtil.text(at) + "§c.");
        }
    }

    // Only the newest markers of a kind are kept. Saving moves a marker to the end.
    private void dropOld(Predicate<String> kind) {
        List<WaypointStore.Waypoint> marked = new ArrayList<>();
        for (WaypointStore.Waypoint waypoint : WaypointStore.get().here()) {
            if (kind.test(waypoint.name())) {
                marked.add(waypoint);
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
        WaypointStore.get().mark(name, player.blockPosition(), DEATH_COLOUR);
        dropOld(Waypoints::isGrave);
    }

    private void actNear() {
        Vec3 feet = mc.player.position();
        List<WaypointStore.Waypoint> here = WaypointStore.get().here();
        armed.retainAll(new HashSet<>(here));
        for (WaypointStore.Waypoint waypoint : here) {
            WaypointStore.Near rule = resolved(waypoint).near();
            if (!rule.acts() || waypoint.hidden()) {
                armed.remove(waypoint);
            } else if (away(waypoint, feet) > rule.distance()) {
                armed.add(waypoint);
            } else if (armed.remove(waypoint)) {
                if (rule.action() == WaypointStore.Action.DELETE) {
                    WaypointStore.get().remove(waypoint);
                } else {
                    WaypointStore.get().replace(waypoint, waypoint.withHidden(true));
                }
            }
        }
    }

    // A find often has no real height and only how far off it lies on the level counts.
    private static double away(WaypointStore.Waypoint waypoint, Vec3 feet) {
        Vec3 middle = waypoint.centre();
        return waypoint.found() ? Math.hypot(middle.x - feet.x, middle.z - feet.z) : middle.distanceTo(feet);
    }

    private void followPortals(boolean newWorld) {
        if (!markPortals.isOn()) {
            forgetPortal();
            return;
        }
        String dimension = ServerInfo.dimension();
        if (newWorld) {
            if (lastPortal != null && sincePortal <= DEPARTURE_TICKS
                && dimension.equals(Dimension.otherSide(lastPortalDimension))) {
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
        WaypointStore.get().markFresh(PORTAL_PREFIX, pos, dimension, PORTAL_COLOUR);
    }

    public WaypointStore.Colour nextColour() {
        return autoColor.isOn() ? WaypointStore.Colour.AUTO : WaypointStore.Colour.of(nextColor);
    }

    private static boolean outOfRange(WaypointStore.Options look, double away) {
        return look.range() > 0 && away > look.range();
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!inGame()) {
            return;
        }
        DrawBatch batch = event.getBatch();
        double reach = beamHeight.getValue();
        Vec3 eye = mc.player.getEyePosition();

        for (WaypointStore.Waypoint waypoint : shown) {
            Vec3 middle = waypoint.centre();
            if (outOfRange(resolved(waypoint), eye.distanceTo(middle))) {
                continue;
            }
            float strength = fade.strengthAt(middle);
            if (strength <= 0) {
                continue;
            }
            int color = ColorUtil.fade(waypoint.argb(), strength);
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
        List<WaypointStore.Waypoint> waypoints = shown;
        if (waypoints.isEmpty() || !inGame() || !WorldToScreen.update()) {
            return;
        }
        GuiGraphicsExtractor context = event.getContext();
        Font font = mc.font;
        Vec3 camera = WorldToScreen.cameraPos();
        double reach = labelReach.getValue();
        double middleX = context.guiWidth() / 2.0;
        double middleY = context.guiHeight() / 2.0;

        for (WaypointStore.Waypoint waypoint : waypoints) {
            WaypointStore.Options look = resolved(waypoint);
            Vec3 middle = waypoint.centre().add(0, 1, 0);
            double away = camera.distanceTo(middle);
            if (outOfRange(look, away)) {
                continue;
            }
            Vec3 screen = WorldToScreen.project(middle);
            if (screen == null || fade.strengthAt(middle) < 1) {
                continue;
            }
            float factor = look.scale().floatValue();
            int color = waypoint.argb();
            double labelY = screen.y;
            if (look.icon() != WaypointStore.Icon.NONE) {
                WaypointIcons.draw(context, look.icon(), screen.x, screen.y, factor, color);
                labelY += WaypointIcons.ROOM_BELOW * factor;
            }
            if (!label.isOn() || reach > 0 && Math.hypot(screen.x - middleX, screen.y - middleY) > reach) {
                continue;
            }

            StringBuilder text = new StringBuilder(waypoint.name());
            if (coordinates.isOn()) {
                text.append(" ").append(BlockUtil.text(waypoint.pos()));
            }
            if (distance.isOn()) {
                text.append(" ").append(Math.round(away)).append("m");
            }
            RenderUtil.label(context, font, screen.x, labelY, factor, List.of(text.toString()), List.of(color));
        }
    }
}
