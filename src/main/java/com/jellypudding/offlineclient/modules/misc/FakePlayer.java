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

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Drops a client side copy of the player wearing their skin and gear.
 * The server never knows it exists.
 */
public final class FakePlayer extends Module {

    private final BoolSetting copyGear = new BoolSetting("Copy gear",
        "Give the copy your armor and held items.", true);
    private final NumberSetting health = new NumberSetting("Health",
        "Health points the copy starts with. Anything above 20 becomes absorption.",
        20, 1, 40, 1).min(1);

    private Body body;

    public FakePlayer() {
        super("FakePlayer", "Spawns a copy of you for testing combat modules.", Category.MISC);
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

    /** The module turns off once the copy is gone. */
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

    public boolean isFake(Entity entity) {
        return entity instanceof Body;
    }

    /**
     * A remote player that borrows the local player's tab entry for the
     * skin. It gets its own UUID because the level refuses two entities
     * with the same one.
     */
    public static final class Body extends RemotePlayer {

        /** Far above any entity id the server hands out. */
        private static final int ID_BASE = Integer.MAX_VALUE - 100_000;

        private final UUID skinOwner;

        Body(ClientLevel level, LocalPlayer source, float startHealth, boolean copyGear) {
            super(level, new GameProfile(UUID.randomUUID(), source.getGameProfile().name()));
            skinOwner = source.getUUID();
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
                getInventory().replaceWith(source.getInventory());
            }
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
