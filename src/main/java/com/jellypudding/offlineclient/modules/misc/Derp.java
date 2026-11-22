package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.jellypudding.offlineclient.event.events.PreMotionEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.GlideSwitch;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.MovementUtil;
import com.jellypudding.offlineclient.util.PacketUtil;
import com.jellypudding.offlineclient.util.RotationManager;
import com.jellypudding.offlineclient.util.RotationPriority;
import com.jellypudding.offlineclient.util.ServerInfo;
import com.jellypudding.offlineclient.util.ServerSneak;
import com.jellypudding.offlineclient.util.SwingMode;
import net.minecraft.SharedConstants;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;

import java.util.concurrent.ThreadLocalRandom;

// The glide pose tells the server you are off the ground whilst you walk. The server keeps a glide
// going only for a player in the air and caps the fall it holds at one block during the glide.
public final class Derp extends Module {

    public enum Mode { SPIN, SHAKE, HEADBANG, FLAIL, TWERK, RANDOM }

    // The fastest twerk flips every tick which is as quick as the server will show.
    // Each step slower waits one more tick between flips.
    private static final int TWERK_FASTEST = 10;

    // A target that slips out of reach or out of view for a moment is back within this.
    private static final int FIGHT_MEMORY_TICKS = SharedConstants.TICKS_PER_SECOND;

    // A swing reaches about four blocks and two sprinters close seven more whilst the cooldown of a
    // sword fills.
    private static final double FIGHT_RANGE = 12;

    private static final int ECHO_WAIT_TICKS = SharedConstants.TICKS_PER_SECOND;
    private static final int RETRY_TICKS = 3 * SharedConstants.TICKS_PER_SECOND;

    private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
        "How you look to everyone else.", Mode.SPIN)
        .describe(Mode.SPIN, "Turns your head round and round.")
        .describe(Mode.SHAKE, "Shakes your head from side to side.")
        .describe(Mode.HEADBANG, "Nods your head up and down.")
        .describe(Mode.FLAIL, "Waves your main arm about. Each wave restarts your attack cooldown.")
        .describe(Mode.TWERK, "Crouches up and down on the spot.")
        .describe(Mode.RANDOM, "Points your head somewhere new every tick and flails now and then.");
    private final NumberSetting speed = new NumberSetting("Speed",
        "How far the head moves each tick.", 30, 1, 90, 1, " degrees").max(180)
        .under(mode, Mode.SPIN, Mode.SHAKE, Mode.HEADBANG);
    private final NumberSetting twerkSpeed = new NumberSetting("Twerk speed",
        "How fast you crouch and stand. Ten flips every tick which is as fast as it gets.",
        5, 1, TWERK_FASTEST, 1).max(TWERK_FASTEST)
        .under(mode, Mode.TWERK);
    private final BoolSetting pauseInCombat = new BoolSetting("Pause in combat",
        "Stops whilst your hands are busy or a combat module has had a target within the last second. The arm"
            + " also keeps still whilst something a combat module would fight is within twelve blocks.", true);
    private final BoolSetting glidePose = new BoolSetting("Glide pose",
        "Shows you gliding on your elytra to everyone whilst you walk. The elytra wears one point a second.",
        false);

    private float yaw;
    private float pitch;
    private boolean rising = true;
    private boolean left;
    private int flailTimer;
    private int fightMemory;
    private int twerkTimer;
    private boolean crouched;
    private final ServerSneak twerkSneak = new ServerSneak();

    private final GlideSwitch glide = GlideSwitch.get();
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
        fightMemory = 0;
        twerkTimer = 0;
        waitFor = 0;
        retryIn = 0;
        if (inGame()) {
            yaw = mc.player.getYRot();
            pitch = mc.player.getXRot();
        }
    }

    // The glide the server shows comes back to you as well and walking carries on only once it
    // is closed here. First of all because any module that reads the glide must find it shut.
    @Subscribe(priority = Subscribe.FIRST)
    private void onTick(TickEvent event) {
        if (!poseWanted()) {
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
        if (glide.serverGlides()) {
            waitFor = 0;
            posing = true;
            return;
        }
        if (waitFor > 0) {
            if (--waitFor == 0) {
                refused();
            }
            return;
        }
        posing = true;
        PacketUtil.claimAir(player);
        MovementUtil.sendStartGlide();
        waitFor = ECHO_WAIT_TICKS;
    }

    // Mining stands aside because the server digs slowly for a player in the air.
    private boolean poseWanted() {
        LocalPlayer player = mc.player;
        return glidePose.isOn() && player.onGround() && !player.isPassenger() && !player.isInLiquid()
            && !player.isSpectator() && !player.getAbilities().flying && !player.hasEffect(MobEffects.LEVITATION)
            && MovementUtil.wearsGlider() && !mc.gameMode.isDestroying();
    }

    private void refused() {
        posing = false;
        waitFor = 0;
        retryIn = RETRY_TICKS;
        PacketUtil.claimGround(mc.player);
    }

    // On the ground the truth ends the glide. In the air a second start closes it and a fall
    // from there counts as it would without the pose. A glide somebody has taken up to fly
    // with is left to them.
    private void endPose() {
        if (!posing) {
            return;
        }
        posing = false;
        waitFor = 0;
        LocalPlayer player = mc.player;
        if (player == null || !glide.serverGlides()) {
            return;
        }
        if (player.onGround()) {
            PacketUtil.claimGround(player);
        } else if (!player.isFallFlying()) {
            MovementUtil.sendStartGlide();
        }
    }

    // Runs after every other module has settled the packet.
    @Subscribe(priority = -50)
    private void onPacketSend(PacketSendEvent event) {
        // The server takes a click from a player it holds for sneaking as a sneak click and throws a
        // rider it holds for sneaking off the mount. The twerk stands up ahead of a click and rests
        // until the answer is in. A ride the click began is known by then.
        if (event.getPacket() instanceof ServerboundUseItemOnPacket
            || event.getPacket() instanceof ServerboundInteractPacket) {
            standUp();
            twerkTimer = Math.max(twerkTimer, ServerInfo.answerTicks());
        }
        twerkSneak.keep(event);
        LocalPlayer player = mc.player;
        if (!posing || player == null || !(event.getPacket() instanceof ServerboundMovePlayerPacket packet)
            || !packet.isOnGround() || PacketUtil.isGroundClaim(packet)) {
            return;
        }
        event.setPacket(PacketUtil.withOnGround(packet, player, false));
    }

    @Subscribe
    private void onPreMotion(PreMotionEvent event) {
        if (!inGame() || mc.player.isSpectator()) {
            return;
        }
        boolean fighting = inFight();
        if (pauseInCombat.isOn() && (fighting || handsBusy())) {
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
        twerkSneak.want(crouched);
    }

    private void standUp() {
        twerkSneak.release();
        crouched = false;
    }

    // The punch carries no hand and the server never echoes the swing back. Every punch restarts
    // the attack cooldown on the server and the arm keeps still whilst a fight may begin.
    private void flail() {
        if (flailTimer > 0 || pauseInCombat.isOn() && Modules.fightNear(FIGHT_RANGE)) {
            return;
        }
        flailTimer = flailGap();
        SwingMode.PACKET.hit();
    }

    // The server starts a fresh swing only once the one before is past its half. A punch sooner
    // than that shows nothing and still restarts the cooldown.
    private int flailGap() {
        return mc.player.getItemInHand(InteractionHand.MAIN_HAND).getAttackAnimation().duration() / 2 + 1;
    }

    private boolean inFight() {
        fightMemory = Modules.fighting() ? FIGHT_MEMORY_TICKS : Math.max(0, fightMemory - 1);
        return fightMemory > 0;
    }

    private boolean handsBusy() {
        return mc.player.isSwinging() && !mode.is(Mode.FLAIL) && !mode.is(Mode.RANDOM)
            || mc.options.keyAttack.isDown() || mc.player.isUsingItem();
    }
}
