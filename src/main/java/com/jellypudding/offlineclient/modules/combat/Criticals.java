package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.AttackEntityEvent;
import com.jellypudding.offlineclient.event.events.PostMotionEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;

/**
 * A critical hit needs the server to believe the player is falling. Packet
 * mode builds that belief out of position packets and never moves the player.
 * The two jump modes leave the ground for real.
 */
public final class Criticals extends Module {

    // The server rebuilds fall distance from the gap between position packets.
    // A sixteenth of a block is the smallest drop that banks any fall at all.
    private static final double CRIT_LIFT = 0.0625;

    // The packet that settles back down has to stay above zero for the drop to hold.
    private static final double CRIT_SETTLE = 1.1e-5;

    /**
     * The server allows a hundred squared blocks of movement for each position
     * packet it has taken this tick and stops counting past the fifth. Three
     * settling packets buy the lift an allowance of four hundred and the
     * return home is covered by the fifth.
     */
    private static final int MACE_SETTLE_PACKETS = 3;

    // The fourth packet allows four hundred squared blocks which is twenty of travel.
    private static final double MACE_LIFT = 19.9;

    public enum Mode { PACKET, MINI_JUMP, FULL_JUMP }

    private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
        "How to get the fall a critical hit needs.", Mode.PACKET)
        .describe(Mode.PACKET, "Sends the tiny fall the server checks for without moving you.")
        .describe(Mode.MINI_JUMP, "A small hop that lands as a critical.")
        .describe(Mode.FULL_JUMP, "A normal jump. Slowest but the most natural.");

    private final BoolSetting mace = new BoolSetting("Mace smash",
        "Fakes a long fall whilst holding a mace to land every swing as a smash attack.", false);

    private final BoolSetting stopSprint = new BoolSetting("Stop sprinting",
        "Drops sprint for the hit and takes it straight back. A sprinting player cannot crit.", true);

    // True between dropping sprint for a hit and handing it back.
    private boolean resumeSprint;

    public Criticals() {
        super("Criticals", "Makes every melee hit a critical hit.", Category.COMBAT);
        addSettings(mode, mace, stopSprint);
        searchTags("crit", "mace", "smash");
    }

    @Override
    public String getSuffix() {
        return mode.getValueString();
    }

    @Override
    protected void onDisable() {
        // Leaving mid hit would leave the server believing the sprint had ended.
        if (resumeSprint && inGame() && mc.player.isSprinting()) {
            sendSprint(ServerboundPlayerCommandPacket.Action.START_SPRINTING);
        }
        resumeSprint = false;
    }

    @Subscribe
    private void onAttack(AttackEntityEvent event) {
        if (!inGame() || mc.player.isSpectator()
            || !(event.getTarget() instanceof LivingEntity)) {
            return;
        }
        if (!mc.player.onGround() || mc.player.isInWater() || mc.player.isInLava()) {
            return;
        }
        dropSprint();
        if (mace.isOn() && mc.player.getMainHandItem().is(Items.MACE)) {
            smash();
            return;
        }

        switch (mode.getValue()) {
            case PACKET -> {
                sendFakeY(CRIT_LIFT, true);
                sendFakeY(0, false);
                sendFakeY(CRIT_SETTLE, false);
                sendFakeY(0, false);
            }
            case MINI_JUMP -> {
                mc.player.push(0, 0.1, 0);
                mc.player.fallDistance = 0.1f;
                mc.player.setOnGround(false);
            }
            case FULL_JUMP -> mc.player.jumpFromGround();
        }
    }

    /**
     * The server refuses a critical to anyone it believes is sprinting. This
     * is the packet pair a player makes by letting go of sprint for the swing.
     * The client keeps sprinting throughout and never sees a stutter.
     */
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
        sendFakeY(MACE_LIFT, false);
        sendFakeY(0, false);
    }

    private void sendFakeY(double offset, boolean onGround) {
        mc.player.connection.send(new ServerboundMovePlayerPacket.Pos(
            mc.player.getX(), mc.player.getY() + offset, mc.player.getZ(),
            onGround, mc.player.horizontalCollision));
    }
}
