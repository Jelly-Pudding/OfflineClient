package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.jellypudding.offlineclient.event.events.PreMotionEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.modules.combat.CrystalAura;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.MovementUtil;
import com.jellypudding.offlineclient.util.PacketUtil;
import com.jellypudding.offlineclient.util.RotationManager;
import com.jellypudding.offlineclient.util.RotationPriority;
import net.minecraft.SharedConstants;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPunchPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;

import java.util.concurrent.ThreadLocalRandom;

// Sends silly rotations and swings and crouches to the server whilst the local view is left alone.
// The glide pose tells the server you are off the ground whilst you walk. The server keeps a glide
// going only for a player in the air and caps the fall it holds at one block during the glide.
public final class Derp extends Module {

    public enum Mode { SPIN, SHAKE, HEADBANG, FLAIL, TWERK, RANDOM }

    // The fastest twerk flips every tick which is as quick as the server will show.
    // Each step slower waits one more tick between flips.
    private static final int TWERK_FASTEST = 10;

    // Ticks between arm swings whilst flailing. A swing takes about six to play out.
    private static final int FLAIL_GAP = 3;

    // Entity defines its shared flags first and they travel as value nought. The glide is bit seven.
    private static final int SHARED_FLAGS = 0;
    private static final int GLIDE_FLAG = 1 << 7;

    // How long a start waits for the server to show the glide. A refused start answers sooner.
    private static final int ECHO_WAIT_TICKS = SharedConstants.TICKS_PER_SECOND;
    // How long a refused start waits before the next try.
    private static final int RETRY_TICKS = 3 * SharedConstants.TICKS_PER_SECOND;

    private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
        "How you look to everyone else.", Mode.SPIN)
        .describe(Mode.SPIN, "Turns your head round and round.")
        .describe(Mode.SHAKE, "Shakes your head from side to side.")
        .describe(Mode.HEADBANG, "Nods your head up and down.")
        .describe(Mode.FLAIL, "Waves both arms about.")
        .describe(Mode.TWERK, "Crouches up and down on the spot.")
        .describe(Mode.RANDOM, "Points your head somewhere new every tick and flails now and then.");
    private final NumberSetting speed = new NumberSetting("Speed",
        "How far the head moves each tick.", 30, 1, 90, 1, " degrees").min(1).max(180)
        .under(mode, Mode.SPIN, Mode.SHAKE, Mode.HEADBANG);
    private final NumberSetting twerkSpeed = new NumberSetting("Twerk speed",
        "How fast you crouch and stand. Ten flips every tick which is as fast as it gets.",
        5, 1, TWERK_FASTEST, 1).min(1).max(TWERK_FASTEST)
        .under(mode, Mode.TWERK);
    private final BoolSetting pauseInCombat = new BoolSetting("Pause in combat",
        "Stops whilst your hands are busy or CrystalAura has a target.", true);
    private final BoolSetting glidePose = new BoolSetting("Glide pose",
        "Shows you gliding on your elytra to everyone whilst you walk. The elytra wears one point a second.",
        false);

    private float yaw;
    private float pitch;
    private boolean rising = true;
    private boolean left;
    private int flailTimer;
    private int twerkTimer;
    private boolean crouched;

    // What your own entity data from the server last said about the glide. Set on the netty thread.
    private volatile boolean serverGlides;
    private volatile boolean echoed;
    // Whilst set the ground flag of every movement packet goes out as false.
    private volatile boolean posing;
    private int waitFor;
    private int retryIn;

    public Derp() {
        super("Derp", "Makes you look ridiculous to everyone else.", Category.MISC);
        addSettings(mode, speed, twerkSpeed, pauseInCombat, glidePose);
        searchTags("derp", "silly", "troll", "spin", "flail", "elytra walk", "glide pose");
    }

    @Override
    public String getSuffix() {
        return mode.getValueString();
    }

    @Override
    protected void onDisable() {
        standUp();
        endPose();
    }

    @Override
    protected void onEnable() {
        flailTimer = 0;
        twerkTimer = 0;
        waitFor = 0;
        retryIn = 0;
        serverGlides = inGame() && mc.player.isFallFlying();
        if (inGame()) {
            yaw = mc.player.getYRot();
            pitch = mc.player.getXRot();
        }
    }

    // Runs before the player moves. The glide the server shows comes back to you as well and
    // walking carries on only once it is closed here.
    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || !poseWanted()) {
            endPose();
            return;
        }
        LocalPlayer player = mc.player;
        if (player.isFallFlying()) {
            player.stopFallFlying();
        }
        if (retryIn > 0) {
            retryIn--;
            return;
        }
        if (serverGlides) {
            waitFor = 0;
            posing = true;
            return;
        }
        if (waitFor > 0) {
            if (--waitFor == 0 || echoed) {
                refused();
            }
            return;
        }
        // The server opens a glide only for a player it thinks is in the air.
        posing = true;
        echoed = false;
        player.connection.send(new ServerboundMovePlayerPacket.StatusOnly(false, player.horizontalCollision));
        MovementUtil.sendStartGlide();
        waitFor = ECHO_WAIT_TICKS;
    }

    // Walking with a glider the server lets you open. Mining stands aside because the server digs
    // slowly for a player in the air.
    private boolean poseWanted() {
        LocalPlayer player = mc.player;
        return glidePose.isOn() && player.onGround() && !player.isPassenger() && !player.isInLiquid()
            && !player.isSpectator() && !player.getAbilities().flying && !player.hasEffect(MobEffects.LEVITATION)
            && MovementUtil.wearsGlider() && !mc.gameMode.isDestroying();
    }

    // The server kept you from gliding. Your packets tell the truth again until the next try.
    private void refused() {
        posing = false;
        waitFor = 0;
        retryIn = RETRY_TICKS;
        PacketUtil.claimGround(mc.player);
    }

    // On the ground the truth ends the glide. In the air a second start closes it and a fall
    // from there counts as it would without the pose.
    private void endPose() {
        if (!posing) {
            return;
        }
        posing = false;
        waitFor = 0;
        LocalPlayer player = mc.player;
        if (player == null || !serverGlides) {
            return;
        }
        if (player.onGround()) {
            PacketUtil.claimGround(player);
        } else {
            MovementUtil.sendStartGlide();
        }
    }

    // Runs after every other module has settled the packet.
    @Subscribe(priority = -50)
    private void onPacketSend(PacketSendEvent event) {
        LocalPlayer player = mc.player;
        if (!posing || player == null || event.isCancelled()
            || !(event.getPacket() instanceof ServerboundMovePlayerPacket packet)
            || !packet.isOnGround() || PacketUtil.isGroundClaim(packet)) {
            return;
        }
        event.setPacket(PacketUtil.withOnGround(packet, player, false));
    }

    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        // A join or a respawn that keeps no entity data gives you a new player on the server. It is
        // not gliding and the server sends no flags to tell you.
        if (event.getPacket() instanceof ClientboundLoginPacket
            || event.getPacket() instanceof ClientboundRespawnPacket respawn
            && !respawn.shouldKeep(ClientboundRespawnPacket.KEEP_ENTITY_DATA)) {
            serverGlides = false;
            posing = false;
            echoed = false;
            return;
        }
        LocalPlayer player = mc.player;
        if (player == null || !(event.getPacket() instanceof ClientboundSetEntityDataPacket packet)
            || packet.id() != player.getId()) {
            return;
        }
        for (SynchedEntityData.DataValue<?> value : packet.packedItems()) {
            if (value.id() == SHARED_FLAGS && value.value() instanceof Byte flags) {
                serverGlides = (flags & GLIDE_FLAG) != 0;
                echoed = true;
            }
        }
    }

    @Subscribe
    private void onPreMotion(PreMotionEvent event) {
        if (!inGame() || mc.player.isSpectator()) {
            return;
        }
        if (pauseInCombat.isOn() && handsBusy()) {
            return;
        }
        if (flailTimer > 0) {
            flailTimer--;
        }
        if (!mode.is(Mode.TWERK)) {
            standUp();
        }

        float step = speed.getFloat();
        switch (mode.getValue()) {
            case SPIN -> {
                yaw += step;
                pitch = 0;
            }
            case SHAKE -> {
                // Alternates either side of where the player really looks.
                left = !left;
                yaw = mc.player.getYRot() + (left ? step : -step);
                pitch = mc.player.getXRot();
            }
            case HEADBANG -> {
                yaw = mc.player.getYRot();
                pitch += rising ? step : -step;
                if (pitch >= 90) {
                    rising = false;
                } else if (pitch <= -90) {
                    rising = true;
                }
            }
            case FLAIL -> {
                flail();
                return;
            }
            case TWERK -> {
                twerk();
                return;
            }
            case RANDOM -> {
                yaw = ThreadLocalRandom.current().nextFloat(-180f, 180f);
                float steepest = RotationManager.STRAIGHT_DOWN;
                pitch = ThreadLocalRandom.current().nextFloat(-steepest, steepest);
                if (ThreadLocalRandom.current().nextInt(4) == 0) {
                    flail();
                }
            }
        }
        yaw = Mth.wrapDegrees(yaw);
        pitch = RotationManager.clampPitch(pitch);

        RotationManager.requestExact(yaw, pitch, RotationPriority.IDLE);
    }

    // Only the server is told about the crouch. Your own view and speed stay as they are.
    private void twerk() {
        if (twerkTimer > 0) {
            twerkTimer--;
            return;
        }
        twerkTimer = TWERK_FASTEST - twerkSpeed.getInt();
        crouched = !crouched;
        InputUtil.sendShift(crouched);
    }

    // Puts the server back on the real key state.
    private void standUp() {
        if (crouched && mc.player != null) {
            InputUtil.sendShift(mc.player.getLastSentInput().shift());
        }
        crouched = false;
    }

    // Swings the arms in turn. Only the packet goes out.
    // The server plays the animation but never echoes it back. The view stays still.
    private void flail() {
        if (flailTimer > 0) {
            return;
        }
        flailTimer = FLAIL_GAP;
        // The punch packet carries no hand. Only the main arm can flail.
        mc.player.connection.send(ServerboundPunchPacket.INSTANCE);
    }

    private boolean handsBusy() {
        if (mc.player.isSwinging() && !mode.is(Mode.FLAIL) && !mode.is(Mode.RANDOM)
            || mc.options.keyAttack.isDown() || mc.player.isUsingItem()) {
            return true;
        }
        CrystalAura crystalAura = Modules.active(CrystalAura.class);
        return crystalAura != null && crystalAura.hasTarget();
    }
}
