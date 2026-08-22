package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.Render2DEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.render.WorldToScreen;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.ColorUtil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3x2fStack;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Remembers where players were when they left the server and marks the
 * spot with a box and a nametag. The position of every player is cached
 * each tick.
 */
public final class LogoutSpots extends Module {

    private record Spot(UUID id, String name, AABB box, float health, float maxHealth) {
    }

    private static final int COLOR = 0xFFFF40FF;
    private static final int BACKGROUND = 0x90000000;

    private final NumberSetting scale = new NumberSetting("Scale",
        "Size of the nametag.", 1, 0.5, 3, 0.1).min(0.1);
    private final BoolSetting nametag = new BoolSetting("Nametag",
        "Show the name and health above the box.", true);
    private final BoolSetting fill = new BoolSetting("Fill",
        "Adds a faint tint inside the box.", true);
    private final BoolSetting tracers = new BoolSetting("Tracers",
        "Draw a line to every spot.", false);

    /** Last known state of every player. Kept even after they walk out of view. */
    private final Map<UUID, Spot> lastSeen = new HashMap<>();
    /** Filled from the network thread and handled on the next tick. */
    private final Queue<UUID> loggedOut = new ConcurrentLinkedQueue<>();
    private final Queue<UUID> returned = new ConcurrentLinkedQueue<>();
    private final Map<UUID, Spot> spots = new LinkedHashMap<>();
    private ResourceKey<Level> dimension;

    public LogoutSpots() {
        super("LogoutSpots", "Marks where players logged out.", Category.RENDER);
        addSettings(scale, nametag, fill, tracers);
        searchTags("log out", "disconnect");
    }

    @Override
    public String getSuffix() {
        return String.valueOf(spots.size());
    }

    @Override
    protected void onEnable() {
        if (inGame()) {
            dimension = mc.level.dimension();
            snapshot();
        }
    }

    @Override
    protected void onDisable() {
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
        if (mc.level.dimension() != dimension) {
            dimension = mc.level.dimension();
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

    /** Caches every visible player and drops spots for anyone standing there again. */
    private void snapshot() {
        for (Player player : mc.level.players()) {
            if (player == mc.player) {
                continue;
            }
            spots.remove(player.getUUID());
            lastSeen.put(player.getUUID(), new Spot(player.getUUID(),
                player.getGameProfile().name(), player.getBoundingBox(),
                player.getHealth() + player.getAbsorptionAmount(),
                player.getMaxHealth() + player.getAbsorptionAmount()));
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
        Font font = mc.font;
        String name = spot.name();
        String health = String.format(" %.0f", spot.health());
        float fraction = spot.maxHealth() <= 0 ? 0 : spot.health() / spot.maxHealth();
        int healthColor = fraction > 0.66f ? 0xFF50FF50 : fraction > 0.33f ? 0xFFFFD040 : 0xFFFF5050;

        int width = font.width(name) + font.width(health);
        int height = font.lineHeight;

        Matrix3x2fStack pose = context.pose();
        pose.pushMatrix();
        pose.translate((float) screen.x, (float) screen.y);
        pose.scale(scale.getFloat(), scale.getFloat());

        int half = width / 2 + 2;
        context.fill(-half, -height - 2, half, 1, BACKGROUND);
        context.guiRenderState.up();
        context.text(font, name, -width / 2, -height, COLOR, true);
        context.text(font, health, -width / 2 + font.width(name), -height, healthColor, true);
        pose.popMatrix();
    }
}
