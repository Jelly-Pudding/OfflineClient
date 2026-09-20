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
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.MoveGate;
import com.jellypudding.offlineclient.util.SprintPause;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

// A critical hit needs the server to believe the player is falling. The packet
// modes lower the tick's movement packet and hold the hit back one tick. The
// jump modes really jump.
public final class Criticals extends Module {

    // The smallest drop the server rebuilds any fall distance from.
    private static final double CRIT_DIP = 0.0625;

    private static final double SUBTLE_DIP = 0.0000008;

    private static final int SMASH_FILLERS = 4;

    // Twenty blocks of fall is the vanilla cap on the smash bonus.
    private static final double MACE_LIFT = 19.9;

    private static final double MINI_JUMP_SPEED = 0.25;
    private static final int MINI_JUMP_TICKS = 4;

    private static final int GIVE_UP_TICKS = 10;

    private enum Stage { IDLE, DIP, LIFT, RETURN, READY, JUMP }

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

    private final NumberSetting extraHeight = new NumberSetting("Extra height",
        "Extra fall on top of the twenty blocks vanilla allows. Only looser servers accept more.",
        0, 0, 100, 1, " blocks").under(mace);

    private final BoolSetting stopSprint = new BoolSetting("Stop sprinting",
        "Drops sprint for the hit and takes it straight back. A sprinting player cannot crit.",
        true);

    // Drops the sprint for a hit and hands it back afterwards.
    private final SprintPause sprintPause = new SprintPause();

    private ServerboundAttackPacket heldAttack;
    private Stage stage = Stage.IDLE;
    private boolean waitingForPeak;
    private double lastY;
    private int sendTimer;

    // How far the next outgoing packet moves the reported height.
    private double offset;
    private int waited;

    // Packets sent by release come straight back through the send handler.
    private boolean releasing;

    public Criticals() {
        super("Criticals", "Makes every melee hit a critical hit.", Category.COMBAT);
        addSettings(mode, onlyKillAura, mace, extraHeight, stopSprint);
        searchTags("crit", "mace", "smash");
    }

    @Override
    public String getSuffix() {
        return mode.getValueString();
    }

    @Override
    protected void onEnable() {
        clearHeld();
    }

    @Override
    protected void onDisable() {
        clearHeld();
        // Leaving mid hit would leave the server believing the sprint had ended.
        sprintPause.resume();
    }

    @Subscribe
    private void onPacketSend(PacketSendEvent event) {
        if (releasing || !inGame() || mc.player.isSpectator()) {
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
            if (!mc.player.isFallFlying() && !mc.player.isInWater() && !mc.player.isInLava()
                && startSmash()) {
                heldAttack = attack;
                event.cancel();
            }
            return;
        }
        if (mode.is(Mode.NONE) || skipCrit() || !wantsCrit(attack.entityId())) {
            return;
        }
        switch (mode.getValue()) {
            case PACKET, SUBTLE -> {
                heldAttack = attack;
                offset = -(mode.is(Mode.PACKET) ? CRIT_DIP : SUBTLE_DIP);
                stage = Stage.DIP;
                event.cancel();
            }
            case MINI_JUMP, FULL_JUMP -> {
                heldAttack = attack;
                stage = Stage.JUMP;
                jump();
                event.cancel();
            }
            case NONE -> {
            }
        }
    }

    // A shorter lift is taken where the room above runs out.
    private boolean startSmash() {
        double wanted = MACE_LIFT + extraHeight.getValue();
        for (int i = 0; i < 4; i++) {
            double lift = wanted * (4 - i) / 4;
            if (mc.level.noCollision(mc.player, mc.player.getBoundingBox().move(0, lift, 0))) {
                offset = lift;
                stage = Stage.LIFT;
                return true;
            }
        }
        return false;
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

    // A jump cannot start from a ladder or a web. The packet modes only need solid ground.
    private boolean skipCrit() {
        if (!mc.player.onGround() || mc.player.isInWater() || mc.player.isInLava()
            || mc.player.onClimbable()) {
            return true;
        }
        return mode.isAny(Mode.MINI_JUMP, Mode.FULL_JUMP) && inCobweb();
    }

    private boolean inCobweb() {
        return mc.level.getBlockStates(mc.player.getBoundingBox())
            .anyMatch(state -> state.is(Blocks.COBWEB));
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

    // The fall the hit needs rides on the one position packet this tick allows.
    // A lift has to come back down before the server counts it as a fall.
    @Subscribe
    private void onPreMotion(PreMotionEvent event) {
        if (!inGame()) {
            clearHeld();
            return;
        }
        if (stage != Stage.DIP && stage != Stage.LIFT && stage != Stage.RETURN) {
            return;
        }
        if (++waited > GIVE_UP_TICKS) {
            stage = Stage.READY;
            return;
        }
        if (stage != Stage.DIP) {
            MoveGate.fillers(SMASH_FILLERS);
        }
        double height = mc.player.getY() + (stage == Stage.RETURN ? 0 : offset);
        if (MoveGate.send(mc.player.getX(), height, mc.player.getZ(), false)) {
            stage = stage == Stage.LIFT ? Stage.RETURN : Stage.READY;
            waited = 0;
        }
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (stage == Stage.IDLE || !inGame()) {
            return;
        }
        if (stage == Stage.READY) {
            release();
            return;
        }
        if (stage != Stage.JUMP) {
            return;
        }
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
        ServerboundAttackPacket attack = heldAttack;
        clearHeld();
        if (attack == null || mc.player == null) {
            return;
        }
        releasing = true;
        try {
            dropSprint();
            mc.player.connection.send(attack);
        } finally {
            releasing = false;
        }
    }

    private void clearHeld() {
        stage = Stage.IDLE;
        waitingForPeak = false;
        heldAttack = null;
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
