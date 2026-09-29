package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.jellypudding.offlineclient.event.events.PostMotionEvent;
import com.jellypudding.offlineclient.event.events.PreMotionEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatWarning;
import com.jellypudding.offlineclient.util.HeldPacket;
import com.jellypudding.offlineclient.util.Hop;
import com.jellypudding.offlineclient.util.MaceSmash;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.MoveGate;
import com.jellypudding.offlineclient.util.SprintPause;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

// A critical hit needs the server to believe the player is falling. The packet modes
// lower the tick's movement packet and hold the hit back one tick. The jump modes really
// jump. A mace smash rides a lift and a drop by packet and lands the hit at the bottom.
public final class Criticals extends Module {

    // The smallest drop the server rebuilds any fall distance from.
    private static final double CRIT_DIP = 0.0625;

    private static final double SUBTLE_DIP = 0.0000008;

    private static final double MINI_JUMP_SPEED = 0.25;
    private static final int MINI_JUMP_TICKS = 4;

    private static final int GIVE_UP_TICKS = 10;

    private enum Stage { IDLE, DIP, READY, JUMP, SMASH }

    public enum Mode { NONE, PACKET, SUBTLE, MINI_JUMP, FULL_JUMP }

    private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
        "How to get the fall a critical hit needs.", Mode.PACKET)
        .describe(Mode.NONE, "No critical hits. Mace smash still works.")
        .describe(Mode.PACKET, "Drops your reported height a sixteenth of a block and hits a tick later.")
        .describe(Mode.SUBTLE, "The same with a drop far too small to notice.")
        .describe(Mode.MINI_JUMP, "A small hop and the hit waits four ticks for the fall.")
        .describe(Mode.FULL_JUMP, "A normal jump and the hit waits for the peak.");

    private final BoolSetting onlyKillAura = new BoolSetting("Only KillAura targets",
        "Only makes hits on the KillAura target critical.", false)
        .visibleWhen(() -> !mode.is(Mode.NONE));

    private final BoolSetting mace = new BoolSetting("Mace smash",
        "Fakes a long fall whilst holding a mace to land every swing as a smash attack.", false);

    private final NumberSetting height = new NumberSetting("Height",
        "How far the faked fall is. Each block past eight adds one damage.",
        20, 2, 100, 1, " blocks").min(2).under(mace);

    private final BoolSetting skipShields = new BoolSetting("Skip shields",
        "Hits normally when the target holds up a shield or plays in creative.", true).under(mace);

    private final BoolSetting totemBypass = new BoolSetting("Totem bypass",
        "Lands more smashes whilst the target is still hurt. A totem only saves them from the first.",
        false).under(mace);

    private final NumberSetting hits = new NumberSetting("Hits",
        "How many smashes land one after another.", 3, 2, 5, 1).min(2).under(totemBypass);

    private final NumberSetting heightStep = new NumberSetting("Height step",
        "How much further each smash after the first falls. It has to hit harder than the last.",
        9, 1, 30, 1, " blocks").min(1).under(totemBypass);

    private final BoolSetting stopSprint = new BoolSetting("Stop sprinting",
        "Drops sprint for the hit and takes it straight back. A sprinting player cannot crit.",
        true);

    private final SprintPause sprintPause = new SprintPause();
    private final ChatWarning warning = new ChatWarning();

    private final HeldPacket<ServerboundAttackPacket> held = new HeldPacket<>();
    private Stage stage = Stage.IDLE;
    private boolean waitingForPeak;
    private double lastY;
    private int sendTimer;

    // How far the next outgoing packet moves the reported height.
    private double offset;
    private int waited;

    // The smash under way. Hits after the first go out as fresh attacks on the target.
    private int smashTarget;
    private boolean struck;
    private boolean followingUp;

    public Criticals() {
        super("Criticals", "Makes every melee hit a critical hit.", Category.COMBAT);
        addSettings(mode, onlyKillAura, mace, height, skipShields, totemBypass, hits, heightStep,
            stopSprint);
        searchTags("crit", "mace", "smash");
    }

    @Override
    public String getSuffix() {
        return mode.getValueString();
    }

    @Override
    protected void onEnable() {
        clearHeld();
        warning.clear();
    }

    @Override
    protected void onDisable() {
        clearHeld();
        // Leaving mid hit would leave the server believing the sprint had ended.
        sprintPause.resume();
    }

    // Read by Knockback. True whilst a hit held back for a critical goes out.
    public boolean releasing() {
        return held.releasing() || followingUp;
    }

    @Subscribe
    private void onPacketSend(PacketSendEvent event) {
        if (releasing() || !inGame() || mc.player.isSpectator()) {
            return;
        }
        if (event.getPacket() instanceof ServerboundAttackPacket attack) {
            onAttackPacket(event, attack);
        }
    }

    private void onAttackPacket(PacketSendEvent event, ServerboundAttackPacket attack) {
        if (stage != Stage.IDLE) {
            return;
        }
        if (mace.isOn() && mc.player.getMainHandItem().is(Items.MACE)) {
            if (canSmash() && smashable(attack.entityId())) {
                smash(event, attack);
            }
            return;
        }
        if (mode.is(Mode.NONE) || skipCrit() || !wantsCrit(attack.entityId())) {
            return;
        }
        switch (mode.getValue()) {
            case PACKET, SUBTLE -> {
                held.hold(event, attack);
                offset = -(mode.is(Mode.PACKET) ? CRIT_DIP : SUBTLE_DIP);
                stage = Stage.DIP;
            }
            case MINI_JUMP, FULL_JUMP -> {
                held.hold(event, attack);
                stage = Stage.JUMP;
                jump();
            }
            case NONE -> {
            }
        }
    }

    // MaceCombo smashes on a real fall and puts the mace away in the same tick. A rider's
    // own position never reaches the server and neither does one Latency holds.
    private boolean canSmash() {
        return !Modules.maceComboAirborne() && !mc.player.isFallFlying() && !mc.player.isInWater()
            && !mc.player.isInLava() && !mc.player.isPassenger() && !Hop.travelling()
            && !MoveGate.held(false);
    }

    // Only a living target the hit can hurt pays for a smash. A missed smash leaves the
    // server holding the whole fall until it is wiped.
    private boolean smashable(int entityId) {
        Entity target = mc.level.getEntity(entityId);
        if (!(target instanceof LivingEntity living) || !living.isAlive()) {
            return false;
        }
        if (!skipShields.isOn()) {
            return true;
        }
        boolean untouchable = living instanceof Player player && (player.isCreative() || player.isSpectator());
        return !living.isBlocking() && !untouchable;
    }

    // Plans every lift and drop at once. Without room above the click goes out as it was.
    private void smash(PacketSendEvent event, ServerboundAttackPacket attack) {
        int wanted = totemBypass.isOn() ? hits.getInt() : 1;
        Hop.Plan plan = Hop.plan();
        MaceSmash.Smash smash = MaceSmash.append(plan, mc.player.position(), height.getValue(), wanted,
            heightStep.getValue(), this::strike);
        if (smash.hits() == 0) {
            return;
        }
        if (smash.hits() < wanted && smash.outOfTime()) {
            warning.say("Totem bypass needs " + smash.followTicks() + " ticks but the hurt window lasts ten. Only "
                + smash.hits() + " hits go out.");
        }
        held.hold(event, attack);
        smashTarget = attack.entityId();
        struck = false;
        stage = Stage.SMASH;
        plan.go(this::smashFinished);
    }

    // The first hit is the one the player swung. Later ones go out fresh whilst the mace
    // is still in hand.
    private void strike(int hit) {
        if (stage != Stage.SMASH || !inGame()) {
            return;
        }
        struck = true;
        if (hit == 0) {
            held.release(this::dropSprint);
            return;
        }
        if (!mc.player.getMainHandItem().is(Items.MACE)) {
            return;
        }
        followingUp = true;
        try {
            mc.player.connection.send(new ServerboundAttackPacket(smashTarget));
        } finally {
            followingUp = false;
        }
    }

    // A smash cut short still lands the swing as a plain hit. A player who left takes
    // nothing along.
    private void smashFinished(Hop.Result result) {
        if (stage != Stage.SMASH) {
            return;
        }
        if (result == Hop.Result.LEFT) {
            held.drop();
        } else if (!struck) {
            held.release(this::dropSprint);
        }
        resetStage();
    }

    private boolean wantsCrit(int entityId) {
        Entity target = mc.level.getEntity(entityId);
        if (!(target instanceof LivingEntity)) {
            return false;
        }
        if (!onlyKillAura.isOn()) {
            return true;
        }
        KillAura killAura = Modules.get(KillAura.class);
        return killAura != null && target == killAura.getTarget();
    }

    // A jump cannot start from a ladder or a web. The packet modes need solid ground and a dip
    // that goes out at once.
    private boolean skipCrit() {
        if (!mc.player.onGround() || mc.player.isInWater() || mc.player.isInLava()
            || mc.player.onClimbable() || mode.isAny(Mode.PACKET, Mode.SUBTLE) && MoveGate.held(false)) {
            return true;
        }
        return mode.isAny(Mode.MINI_JUMP, Mode.FULL_JUMP) && BlockUtil.inCobweb(mc.player);
    }

    private void jump() {
        if (mode.is(Mode.FULL_JUMP)) {
            mc.player.jumpFromGround();
            waitingForPeak = true;
            lastY = mc.player.getY();
        } else {
            Vec3 motion = mc.player.getDeltaMovement();
            mc.player.setDeltaMovement(motion.x, MINI_JUMP_SPEED, motion.z);
            sendTimer = MINI_JUMP_TICKS;
        }
    }

    // The dip the hit needs rides on the one position packet this tick allows.
    @Subscribe
    private void onPreMotion(PreMotionEvent event) {
        if (!inGame()) {
            clearHeld();
            return;
        }
        if (stage != Stage.DIP) {
            return;
        }
        if (++waited > GIVE_UP_TICKS) {
            stage = Stage.READY;
            return;
        }
        if (MoveGate.send(mc.player.getX(), mc.player.getY() + offset, mc.player.getZ(), false)) {
            stage = Stage.READY;
            waited = 0;
        }
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (stage == Stage.IDLE || !inGame()) {
            return;
        }
        switch (stage) {
            case READY -> release();
            case JUMP -> jumpTick();
            default -> {
            }
        }
    }

    private void jumpTick() {
        if (waitingForPeak) {
            double y = mc.player.getY();
            // The first tick that fails to climb is the peak.
            waitingForPeak = y > lastY;
            lastY = y;
            return;
        }
        if (sendTimer > 0) {
            sendTimer--;
            return;
        }
        release();
    }

    // Sends the held hit now that the server sees a fall.
    private void release() {
        resetStage();
        held.release(this::dropSprint);
    }

    private void clearHeld() {
        resetStage();
        held.drop();
    }

    private void resetStage() {
        stage = Stage.IDLE;
        waitingForPeak = false;
        sendTimer = 0;
        offset = 0;
        waited = 0;
    }

    // The server refuses a critical hit to anyone it believes is sprinting.
    // Dropping sprint for the swing keeps the client from stuttering.
    private void dropSprint() {
        if (stopSprint.isOn()) {
            sprintPause.pause();
        }
    }

    // The attack packet has gone out by this point.
    @Subscribe
    private void onPostMotion(PostMotionEvent event) {
        sprintPause.resume();
    }
}
