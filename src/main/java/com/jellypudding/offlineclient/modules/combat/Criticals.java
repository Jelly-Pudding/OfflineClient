package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.jellypudding.offlineclient.event.events.PostMotionEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

// A critical hit needs the server to believe the player is falling.
// Packet modes fake that with position packets while the jump modes really jump.
public final class Criticals extends Module {

    // The server rebuilds fall distance from the gap between position packets.
    // A sixteenth of a block is the smallest drop that banks any fall at all.
    private static final double CRIT_LIFT = 0.0625;

    // The packet that settles back down has to stay above zero for the drop to hold.
    private static final double CRIT_SETTLE = 1.1e-5;

    // Each of the first five position packets in a tick buys a hundred squared
    // blocks of allowed movement. Three settling packets bank four hundred for the lift.
    private static final int MACE_SETTLE_PACKETS = 3;

    // The fourth packet allows four hundred squared blocks which is twenty of travel.
    private static final double MACE_LIFT = 19.9;

    private static final double MINI_JUMP_SPEED = 0.25;
    private static final int MINI_JUMP_TICKS = 4;

    public enum Mode {
        NONE, PACKET, NEW_NCP("New NCP"), OLD_NCP("Old NCP"), MINI_JUMP, FULL_JUMP;

        private final String label;

        Mode() {
            this.label = null;
        }

        Mode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label == null ? name() : label;
        }
    }

    private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
        "How to get the fall a critical hit needs.", Mode.PACKET)
        .describe(Mode.NONE, "No critical hits. Mace smash still works.")
        .describe(Mode.PACKET, "Sends the tiny fall the server checks for without moving you.")
        .describe(Mode.NEW_NCP, "A fall too small to notice for newer anti cheats.")
        .describe(Mode.OLD_NCP, "Three small climbs that older anti cheats let through.")
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

    // True between dropping sprint for a hit and handing it back.
    private boolean resumeSprint;

    // The hit held back by a jump mode until the fall exists.
    private ServerboundAttackPacket heldAttack;
    private ServerboundSwingPacket heldSwing;
    private boolean holding;
    private boolean waitingForPeak;
    private double lastY;
    private int sendTimer;

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
        if (resumeSprint && inGame() && mc.player.isSprinting()) {
            sendSprint(ServerboundPlayerCommandPacket.Action.START_SPRINTING);
        }
        resumeSprint = false;
    }

    @Subscribe
    private void onPacketSend(PacketSendEvent event) {
        if (releasing || !inGame() || mc.player.isSpectator()) {
            return;
        }
        if (event.getPacket() instanceof ServerboundAttackPacket attack) {
            onAttackPacket(event, attack);
        } else if (event.getPacket() instanceof ServerboundSwingPacket swing
            && holding && heldSwing == null) {
            heldSwing = swing;
            event.cancel();
        }
    }

    private void onAttackPacket(PacketSendEvent event, ServerboundAttackPacket attack) {
        if (mace.isOn() && mc.player.getMainHandItem().is(Items.MACE)) {
            if (!mc.player.isFallFlying() && !mc.player.isInWater() && !mc.player.isInLava()) {
                dropSprint();
                smash();
            }
            return;
        }
        if (mode.is(Mode.NONE) || skipCrit() || !wantsCrit(attack.entityId())) {
            return;
        }
        switch (mode.getValue()) {
            case PACKET -> {
                dropSprint();
                sendFakeY(CRIT_LIFT, true);
                sendFakeY(0, false);
                sendFakeY(CRIT_SETTLE, false);
                sendFakeY(0, false);
            }
            case NEW_NCP -> {
                dropSprint();
                sendFakeY(0.0000008, false);
                sendFakeY(0, false);
            }
            case OLD_NCP -> {
                dropSprint();
                sendFakeY(0.11, false);
                sendFakeY(0.1100013579, false);
                sendFakeY(0.0000013579, false);
            }
            case MINI_JUMP, FULL_JUMP -> {
                if (holding) {
                    return;
                }
                hold(attack);
                event.cancel();
            }
            case NONE -> {
            }
        }
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

    private void hold(ServerboundAttackPacket attack) {
        holding = true;
        heldAttack = attack;
        heldSwing = null;
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

    @Subscribe
    private void onTick(TickEvent event) {
        if (!holding || !inGame()) {
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
        ServerboundSwingPacket swing = heldSwing;
        clearHeld();
        releasing = true;
        try {
            dropSprint();
            mc.player.connection.send(attack);
            if (swing != null) {
                mc.player.connection.send(swing);
            }
        } finally {
            releasing = false;
        }
    }

    private void clearHeld() {
        holding = false;
        waitingForPeak = false;
        heldAttack = null;
        heldSwing = null;
        sendTimer = 0;
    }

    // The server refuses a critical hit to anyone it believes is sprinting.
    // Dropping sprint for the swing keeps the client from stuttering.
    private void dropSprint() {
        if (!stopSprint.isOn() || resumeSprint || !mc.player.isSprinting()) {
            return;
        }
        sendSprint(ServerboundPlayerCommandPacket.Action.STOP_SPRINTING);
        resumeSprint = true;
    }

    // The attack packet has gone out by this point.
    @Subscribe
    private void onPostMotion(PostMotionEvent event) {
        if (!resumeSprint) {
            return;
        }
        resumeSprint = false;
        if (inGame() && mc.player.isSprinting()) {
            sendSprint(ServerboundPlayerCommandPacket.Action.START_SPRINTING);
        }
    }

    private void sendSprint(ServerboundPlayerCommandPacket.Action action) {
        mc.player.connection.send(new ServerboundPlayerCommandPacket(mc.player, action));
    }

    private void smash() {
        for (int i = 0; i < MACE_SETTLE_PACKETS; i++) {
            sendFakeY(0, false);
        }
        sendFakeY(MACE_LIFT + extraHeight.getValue(), false);
        sendFakeY(0, false);
    }

    private void sendFakeY(double offset, boolean onGround) {
        mc.player.connection.send(new ServerboundMovePlayerPacket.Pos(
            mc.player.getX(), mc.player.getY() + offset, mc.player.getZ(),
            onGround, mc.player.horizontalCollision));
    }
}
