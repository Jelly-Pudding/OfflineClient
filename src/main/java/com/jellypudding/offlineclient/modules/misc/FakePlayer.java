package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.TextSetting;
import com.jellypudding.offlineclient.util.EntityUtil;
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
import java.util.function.Supplier;

public final class FakePlayer extends Module {

    private static final double RING = 1.5;

    private final TextSetting name = new TextSetting("Name",
        "The name over the copy. Blank uses your own name and skin.", "");
    private final NumberSetting copies = new NumberSetting("Copies",
        "How many are spawned in a ring round you.", 1, 1, 5, 1);
    private final BoolSetting copyGear = new BoolSetting("Copy gear",
        "Gives the copy your armour and held items.", true);
    private final NumberSetting health = new NumberSetting("Health",
        "Health points the copy starts with.",
        20, 1, 40, 1);

    private final List<Body> bodies = new ArrayList<>();

    // True whilst the command switches the module on for a body of its own.
    private boolean adding;

    public FakePlayer() {
        super("FakePlayer", "Spawns a copy of you that only you can see to practise your combat on.", Category.MISC);
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
            disable("Join a world before spawning a fake player.");
            return;
        }
        if (adding) {
            return;
        }
        int count = copies.getInt();
        for (int i = 0; i < count; i++) {
            Vec3 offset = count == 1 ? Vec3.ZERO
                : Vec3.directionFromRotation(0, mc.player.getYRot() + 360f * i / count).scale(RING);
            spawn(name.getValue().trim(), mc.player.position().add(offset.x, 0, offset.z));
        }
    }

    @Override
    protected void onDisable() {
        bodies.forEach(FakePlayer::discard);
        bodies.clear();
    }

    // A blank name copies the player. Null when the module could not be switched on for it.
    public Body add(String name) {
        if (!isEnabled()) {
            adding = true;
            setEnabled(true);
            adding = false;
        }
        return isEnabled() ? spawn(name, mc.player.position()) : null;
    }

    // The module switches off with the last body.
    public int remove(String name) {
        List<Body> named = bodies.stream()
            .filter(body -> body.getGameProfile().name().equalsIgnoreCase(name)).toList();
        named.forEach(FakePlayer::discard);
        bodies.removeAll(named);
        if (!named.isEmpty() && bodies.isEmpty()) {
            setEnabled(false);
        }
        return named.size();
    }

    public List<Body> bodies() {
        return List.copyOf(bodies);
    }

    private Body spawn(String name, Vec3 spot) {
        Body body = new Body(mc.level, mc.player, name, health.getFloat(), copyGear.isOn(), false);
        body.setPos(spot);
        body.setOldPosAndRot();
        mc.level.addEntity(body);
        bodies.add(body);
        return body;
    }

    private static void discard(Body body) {
        if (mc.level != null && body.level() == mc.level) {
            mc.level.removeEntity(body.getId(), Entity.RemovalReason.DISCARDED);
        }
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

        // The arms reach past the box. A ghost stays hidden until the camera is clear of them.
        private static final double CAMERA_CLEARANCE = 0.5;

        // Read live. A skin that finishes downloading later still shows up.
        private final Supplier<PlayerSkin> skin;
        private final UUID infoOwner;

        // A ghost is only a picture. Nothing hits it and it pushes nobody.
        private final boolean ghost;

        public Body(ClientLevel level, LocalPlayer source, float startHealth, boolean copyGear,
                    boolean ghost) {
            this(level, source, "", startHealth, copyGear, ghost);
        }

        public Body(ClientLevel level, LocalPlayer source, String name, float startHealth,
                    boolean copyGear, boolean ghost) {
            super(level, new GameProfile(UUID.randomUUID(),
                name.isEmpty() ? source.getGameProfile().name() : name));
            this.ghost = ghost;
            setId(EntityUtil.nextLocalId());

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
            // Without this the first frame draws the body sliding in from the world origin.
            setOldPosAndRot();
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

        public boolean isGhost() {
            return ghost;
        }

        // Read by EntityRendererMixin.
        public boolean hidesFrom(double cameraX, double cameraY, double cameraZ) {
            return ghost && getBoundingBox().inflate(CAMERA_CLEARANCE).contains(cameraX, cameraY, cameraZ);
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
