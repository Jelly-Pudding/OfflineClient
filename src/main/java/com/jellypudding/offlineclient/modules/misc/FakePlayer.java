package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

// A client side copy of the player. The server never knows it exists.
public final class FakePlayer extends Module {

    private final BoolSetting copyGear = new BoolSetting("Copy gear",
        "Give the copy your armour and held items.", true);
    private final NumberSetting health = new NumberSetting("Health",
        "Health points the copy starts with.",
        20, 1, 40, 1).min(1);

    private Body body;

    public FakePlayer() {
        super("FakePlayer", "Spawns a copy of you that only you can see. Practise your combat on it.", Category.MISC);
        addSettings(copyGear, health);
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
        body = new Body(mc.level, mc.player, health.getFloat(), copyGear.isOn());
        mc.level.addEntity(body);
    }

    @Override
    protected void onDisable() {
        if (body != null && mc.level != null && body.level() == mc.level) {
            mc.level.removeEntity(body.getId(), Entity.RemovalReason.DISCARDED);
        }
        body = null;
    }

    @Subscribe
    private void onClientTick(ClientTickEvent event) {
        if (body == null) {
            return;
        }
        if (mc.level == null || body.level() != mc.level || body.isRemoved()) {
            body = null;
            setEnabled(false);
        }
    }

    // Borrows the local player's tab entry for the skin.
    // The level refuses two entities with the same UUID.
    public static final class Body extends RemotePlayer {

        // Far above any entity id the server hands out.
        private static final int ID_BASE = Integer.MAX_VALUE - 100_000;

        private final UUID skinOwner;

        // A ghost is only a picture. Nothing hits it and it pushes nobody.
        private final boolean ghost;

        Body(ClientLevel level, LocalPlayer source, float startHealth, boolean copyGear) {
            this(level, source, startHealth, copyGear, false);
        }

        public Body(ClientLevel level, LocalPlayer source, float startHealth, boolean copyGear,
                    boolean ghost) {
            super(level, new GameProfile(UUID.randomUUID(), source.getGameProfile().name()));
            skinOwner = source.getUUID();
            this.ghost = ghost;
            setId(ID_BASE + ThreadLocalRandom.current().nextInt(100_000));

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
        protected PlayerInfo getPlayerInfo() {
            if (OfflineClient.MC.getConnection() == null) {
                return null;
            }
            return OfflineClient.MC.getConnection().getPlayerInfo(skinOwner);
        }
    }
}
