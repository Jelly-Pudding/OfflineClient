package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.Render2DEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.render.WorldToScreen;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.RenderUtil;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.lang.ref.WeakReference;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

// The position of every player is cached each tick.
public final class LogoutSpots extends Module {

    private record Spot(String name, AABB box, float health, float maxHealth) {
    }

    private static final int COLOR = 0xFFFF40FF;

    // Players remembered at once.
    private static final int MAX_TRACKED = 512;
    // Markers kept at once.
    private static final int MAX_SPOTS = 128;

    private final NumberSetting scale = new NumberSetting("Scale",
        "Size of the nametag.", 1, 0.5, 3, 0.1).min(0.1);
    private final BoolSetting nametag = new BoolSetting("Nametag",
        "Show the name and health above the box.", true);
    private final BoolSetting fill = new BoolSetting("Fill",
        "Adds a faint tint inside the box.", true);
    private final BoolSetting tracers = new BoolSetting("Tracers",
        "Draw a line to every spot.", false);

    /**
     * Kept even after a player walks out of view. Access ordered. The player
     * seen least recently is the one that drops when the store fills up.
     */
    private final Map<UUID, Spot> lastSeen = new LinkedHashMap<UUID, Spot>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, Spot> eldest) {
            return size() > MAX_TRACKED;
        }
    };
    // Filled from the network thread and handled on the next tick.
    private final Queue<UUID> loggedOut = new ConcurrentLinkedQueue<>();
    private final Queue<UUID> returned = new ConcurrentLinkedQueue<>();
    private final Map<UUID, Spot> spots = new LinkedHashMap<UUID, Spot>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, Spot> eldest) {
            return size() > MAX_SPOTS;
        }
    };
    private WeakReference<Level> world = new WeakReference<>(null);

    public LogoutSpots() {
        super("LogoutSpots", "Marks where players logged out.", Category.RENDER);
        addSettings(scale, nametag, fill, tracers);
        searchTags("log out", "disconnect");
    }

    @Override
    public String getSuffix() {
        return count(spots.size());
    }

    @Override
    protected void onEnable() {
        if (inGame()) {
            world = new WeakReference<>(mc.level);
            snapshot();
        }
    }

    @Override
    protected void onDisable() {
        world = new WeakReference<>(null);
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
        // A new world object covers a dimension change and a rejoin alike.
        if (mc.level != world.get()) {
            world = new WeakReference<>(mc.level);
            clear();
        }

        UUID id;
        while ((id = returned.poll()) != null) {
            spots.remove(id);
        }
        while ((id = loggedOut.poll()) != null) {
            Spot spot = lastSeen.get(id);
            if (spot != null && mc.getConnection() != null
                && mc.getConnection().getPlayerInfo(id) == null) {
                spots.put(id, spot);
            }
        }

        snapshot();
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
                EntityUtil.totalMaxHealth(player)));
        }
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        DrawBatch batch = event.getBatch();
        for (Spot spot : spots.values()) {
            batch.outlineBox(spot.box(), COLOR, true);
            if (fill.isOn()) {
                batch.solidBox(spot.box(), ColorUtil.withAlpha(COLOR, 40), true);
            }
            if (tracers.isOn()) {
                batch.tracer(spot.box().getCenter(), COLOR, true);
            }
        }
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
            List.of(spot.name(), String.format(" %.0f", spot.health())),
            List.of(COLOR, ColorUtil.health(fraction)));
    }
}
