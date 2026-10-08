package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.config.WaypointStore;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.Render2DEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.util.BoundedMap;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.render.WorldToScreen;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.RenderUtil;
import com.jellypudding.offlineclient.util.ServerInfo;
import com.jellypudding.offlineclient.util.WorldWatch;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

// The position of every player is cached each tick.
public final class LogoutSpots extends Module {

    // Where a player was last seen and on which tick. A spot gets the time its player logged out.
    private record Spot(String name, AABB box, float health, float maxHealth, int seenTick, long loggedOut) {

        Spot loggedOutNow() {
            return new Spot(name, box, health, maxHealth, seenTick, System.currentTimeMillis());
        }
    }

    // Saved waypoints are named Logout and the player such as LogoutSteve1.
    private static final String WAYPOINT_PREFIX = "Logout";
    // A player seen within this many ticks of leaving the tab list was still in view. The body and
    // the tab list entry go in separate packets that can land either side of a tick.
    private static final int IN_VIEW_TICKS = 20;

    private static final int MAX_TRACKED = 512;
    private static final int MAX_SPOTS = 128;
    // A flat marker still needs a sliver of height. Its edges then have a direction.
    private static final double FLAT_HEIGHT = 0.01;

    private final BoolSetting fullHeight = new BoolSetting("Full height",
        "Draw the whole body box. Off draws a flat square at the feet.", true);
    private final BoxStyle style = new BoxStyle(BoxStyle.Shape.BOTH, 300f);
    private final BoolSetting tracers = new BoolSetting("Tracers",
        "Draw a line to every spot.", false);
    private final BoolSetting nametag = new BoolSetting("Nametag",
        "Show the name and health above the box.", true);
    private final NumberSetting scale = new NumberSetting("Scale",
        "Size of the nametag.", 1, 0.5, 3, 0.1, "").min(0.1).under(nametag);
    private final ColorSetting nameColor = new ColorSetting("Name colour",
        "Colour of the name text.", 0, 0f, 1f, false).under(nametag);
    private final ColorSetting nameBackground = new ColorSetting("Name background colour",
        "Colour behind the name.", 0, 0f, 0f, false).under(nametag);
    private final NumberSetting nameBackgroundOpacity = new NumberSetting("Name background opacity",
        "How solid the background behind the name is.", 30, 0, 100, 5, "%").under(nametag);
    private final BoolSetting saveWaypoints = new BoolSetting("Save waypoints",
        "Saves a waypoint where a player in view logged out.", false);
    private final NumberSetting forgetAfter = new NumberSetting("Forget after",
        "Minutes before a spot is forgotten. Nought keeps it until the player returns.",
        0, 0, 120, 5, " minutes").min(0);

    // Kept even after a player walks out of view. The least recently seen player
    // drops when the store fills up.
    private final Map<UUID, Spot> lastSeen = new BoundedMap<>(MAX_TRACKED, true);
    // Filled from the network thread and handled on the next tick.
    private final Queue<UUID> loggedOut = new ConcurrentLinkedQueue<>();
    private final Queue<UUID> returned = new ConcurrentLinkedQueue<>();
    private final Map<UUID, Spot> spots = new BoundedMap<>(MAX_SPOTS);
    private final WorldWatch world = new WorldWatch();
    private int ticks;

    public LogoutSpots() {
        super("LogoutSpots", "Marks where players logged out.", Category.RENDER);
        addSettings(fullHeight);
        addSettings(style.settings());
        addSettings(tracers, nametag, scale, nameColor, nameBackground, nameBackgroundOpacity, saveWaypoints,
            forgetAfter);
        searchTags("log out", "disconnect", "logout waypoint");
    }

    @Override
    public String getSuffix() {
        return count(spots.size());
    }

    @Override
    protected void onEnable() {
        if (inGame()) {
            world.accept();
            snapshot();
        }
    }

    @Override
    protected void onDisable() {
        world.forget();
        clear();
    }

    private void clear() {
        lastSeen.clear();
        loggedOut.clear();
        returned.clear();
        spots.clear();
    }

    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacket() instanceof ClientboundPlayerInfoRemovePacket remove) {
            loggedOut.addAll(remove.profileIds());
        } else if (event.getPacket() instanceof ClientboundPlayerInfoUpdatePacket update
            && update.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER)) {
            for (ClientboundPlayerInfoUpdatePacket.Entry entry : update.entries()) {
                returned.add(entry.profileId());
            }
        }
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            return;
        }
        if (world.changed()) {
            clear();
        }
        ticks++;

        UUID id;
        while ((id = returned.poll()) != null) {
            spots.remove(id);
        }
        while ((id = loggedOut.poll()) != null) {
            Spot spot = lastSeen.get(id);
            if (spot != null && mc.getConnection() != null
                && mc.getConnection().getPlayerInfo(id) == null) {
                spots.put(id, spot.loggedOutNow());
                if (saveWaypoints.isOn() && ticks - spot.seenTick() <= IN_VIEW_TICKS) {
                    saveWaypoint(spot);
                }
            }
        }
        forgetOld();
        snapshot();
    }

    // A block another waypoint already marks gets no second one.
    private void saveWaypoint(Spot spot) {
        BlockPos feet = BlockPos.containing(spot.box().getCenter().x, spot.box().minY, spot.box().getCenter().z);
        for (WaypointStore.Waypoint waypoint : WaypointStore.get().here()) {
            if (waypoint.pos().equals(feet)) {
                return;
            }
        }
        WaypointStore.get().markFresh(WAYPOINT_PREFIX + spot.name(), feet, ServerInfo.dimension(),
            Modules.nextWaypointHue());
    }

    private void forgetOld() {
        if (forgetAfter.getValue() <= 0) {
            return;
        }
        long oldest = System.currentTimeMillis() - (long) (forgetAfter.getValue() * TimeUnit.MINUTES.toMillis(1));
        spots.values().removeIf(spot -> spot.loggedOut() < oldest);
    }

    private void snapshot() {
        for (Player player : mc.level.players()) {
            if (player == mc.player) {
                continue;
            }
            spots.remove(player.getUUID());
            lastSeen.put(player.getUUID(), new Spot(
                player.getGameProfile().name(), player.getBoundingBox(),
                EntityUtil.totalHealth(player),
                EntityUtil.totalMaxHealth(player), ticks, 0));
        }
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        DrawBatch batch = event.getBatch();
        for (Spot spot : spots.values()) {
            style.draw(batch, fullHeight.isOn() ? spot.box() : footprint(spot.box()), true);
            if (tracers.isOn()) {
                batch.tracer(spot.box().getCenter(), style.lineColor(), true);
            }
        }
    }

    private static AABB footprint(AABB box) {
        return new AABB(box.minX, box.minY, box.minZ, box.maxX, box.minY + FLAT_HEIGHT, box.maxZ);
    }

    @Subscribe
    private void onRender2D(Render2DEvent event) {
        if (!nametag.isOn() || !inGame() || spots.isEmpty() || !WorldToScreen.update()) {
            return;
        }
        for (Spot spot : spots.values()) {
            AABB box = spot.box();
            Vec3 top = new Vec3((box.minX + box.maxX) / 2, box.maxY + 0.5, (box.minZ + box.maxZ) / 2);
            Vec3 screen = WorldToScreen.project(top);
            if (screen != null) {
                drawTag(event.getContext(), spot, screen);
            }
        }
    }

    private void drawTag(GuiGraphicsExtractor context, Spot spot, Vec3 screen) {
        float fraction = spot.maxHealth() <= 0 ? 0 : spot.health() / spot.maxHealth();
        RenderUtil.label(context, mc.font, screen.x, screen.y, scale.getFloat(),
            List.of(spot.name(), String.format(Locale.ROOT, " %.0f", spot.health())),
            List.of(nameColor.getColor(), ColorUtil.health(fraction)),
            ColorUtil.fade(nameBackground.getColor(), nameBackgroundOpacity.getFloat() / 100f));
    }
}
