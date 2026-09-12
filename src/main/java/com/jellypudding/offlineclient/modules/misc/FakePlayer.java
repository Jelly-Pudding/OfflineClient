package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.TextSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

// A client side copy of the player. The server never knows it exists.
public final class FakePlayer extends Module {

    // How far apart several copies stand.
    private static final double RING = 1.5;

    private final TextSetting name = new TextSetting("Name",
        "The name over the copy. Blank uses your own name and skin.", "");
    private final NumberSetting copies = new NumberSetting("Copies",
        "How many are spawned in a ring round you.", 1, 1, 5, 1).min(1).max(10);
    private final BoolSetting copyGear = new BoolSetting("Copy gear",
        "Give the copy your armour and held items.", true);
    private final NumberSetting health = new NumberSetting("Health",
        "Health points the copy starts with.",
        20, 1, 40, 1).min(1);

    private final List<Body> bodies = new ArrayList<>();

    public FakePlayer() {
        super("FakePlayer", "Spawns a copy of you that only you can see. Practise your combat on it.", Category.MISC);
        addSettings(name, copies, copyGear, health);
        searchTags("dummy", "bot", "target");
    }

    @Override
    public boolean savesEnabledState() {
        return false;
    }

    @Override
    protected void onEnable() {
        if (!inGame()) {
            ChatUtil.error("Join a world before spawning a fake player.");
            setEnabled(false);
            return;
        }
        int count = copies.getInt();
        for (int i = 0; i < count; i++) {
            Body body = new Body(mc.level, mc.player, name.getValue().trim(), health.getFloat(),
                copyGear.isOn(), false);
            if (count > 1) {
                Vec3 offset = Vec3.directionFromRotation(0, mc.player.getYRot() + 360f * i / count)
                    .scale(RING);
                body.setPos(mc.player.getX() + offset.x, mc.player.getY(), mc.player.getZ() + offset.z);
                body.setOldPosAndRot();
            }
            mc.level.addEntity(body);
            bodies.add(body);
        }
    }

    @Override
    protected void onDisable() {
        for (Body body : bodies) {
            if (mc.level != null && body.level() == mc.level) {
                mc.level.removeEntity(body.getId(), Entity.RemovalReason.DISCARDED);
            }
        }
        bodies.clear();
    }

    @Subscribe
    private void onClientTick(ClientTickEvent event) {
        if (bodies.isEmpty()) {
            return;
        }
        bodies.removeIf(body -> mc.level == null || body.level() != mc.level || body.isRemoved());
        if (bodies.isEmpty()) {
            setEnabled(false);
        }
    }

    // The level refuses two entities with the same UUID. Every body gets a fresh one.
    public static final class Body extends RemotePlayer {

        // Far above any entity id the server hands out.
        private static final int ID_BASE = Integer.MAX_VALUE - 100_000;

        // Read live. A skin that finishes downloading later still shows up.
        private final Supplier<PlayerSkin> skin;
        private final UUID infoOwner;

        // A ghost is only a picture. Nothing hits it and it pushes nobody.
        private final boolean ghost;

        // A copy under the player's own name and skin.
        public Body(ClientLevel level, LocalPlayer source, float startHealth, boolean copyGear,
                    boolean ghost) {
            this(level, source, "", startHealth, copyGear, ghost);
        }

        public Body(ClientLevel level, LocalPlayer source, String name, float startHealth,
                    boolean copyGear, boolean ghost) {
            super(level, new GameProfile(UUID.randomUUID(),
                name.isEmpty() ? source.getGameProfile().name() : name));
            this.ghost = ghost;
            setId(ID_BASE + ThreadLocalRandom.current().nextInt(100_000));

            PlayerInfo other = name.isEmpty() || OfflineClient.MC.getConnection() == null ? null
                : OfflineClient.MC.getConnection().getPlayerInfoIgnoreCase(name);
            if (name.isEmpty() || name.equalsIgnoreCase(source.getGameProfile().name())) {
                skin = source::getSkin;
                infoOwner = source.getUUID();
            } else if (other != null) {
                skin = other::getSkin;
                infoOwner = other.getProfile().id();
            } else {
                PlayerSkin fallback = DefaultPlayerSkin.get(getUUID());
                skin = () -> fallback;
                infoOwner = null;
            }

            copyPosition(source);
            yRotO = getYRot();
            xRotO = getXRot();
            yHeadRot = source.yHeadRot;
            yHeadRotO = yHeadRot;
            yBodyRot = source.yBodyRot;
            yBodyRotO = yBodyRot;
            setPose(source.getPose());
            getAttributes().assignAllValues(source.getAttributes());

            setHealth(Math.min(startHealth, getMaxHealth()));
            if (startHealth > getMaxHealth()) {
                setAbsorptionAmount(startHealth - getMaxHealth());
            }
            if (copyGear) {
                Inventory theirs = source.getInventory();
                Inventory ours = getInventory();
                for (int slot = 0; slot < ours.getContainerSize(); slot++) {
                    ours.setItem(slot, theirs.getItem(slot).copy());
                }
                ours.setSelectedSlot(theirs.getSelectedSlot());
            }
        }

        // Read by EntityRendererMixin. A ghost vanishes whilst the camera stands inside it.
        public boolean hidesAroundCamera() {
            return ghost;
        }

        @Override
        public boolean isPushable() {
            return !ghost && super.isPushable();
        }

        @Override
        public boolean isPickable() {
            return !ghost && super.isPickable();
        }

        @Override
        public PlayerSkin getSkin() {
            return skin.get();
        }

        @Override
        protected PlayerInfo getPlayerInfo() {
            if (infoOwner == null || OfflineClient.MC.getConnection() == null) {
                return null;
            }
            return OfflineClient.MC.getConnection().getPlayerInfo(infoOwner);
        }
    }
}
