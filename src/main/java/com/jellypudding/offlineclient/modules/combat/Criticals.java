package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.AttackEntityEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;

public final class Criticals extends Module {

    // The server rebuilds fall distance from the gap between position packets.
    // Five blocks is the threshold a mace smash needs and twenty two keeps the
    // bonus at the flat cap.
    private static final double MACE_LIFT = 22.36;

    // Leading packets that keep the movement checker from rejecting the lift.
    private static final int MACE_SETTLE_PACKETS = 4;

    public enum Mode {
        PACKET("Packet"),
        MINI_JUMP("Mini jump"),
        FULL_JUMP("Full jump");

        private final String name;

        Mode(String name) {
            this.name = name;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
        "How to get the fall a critical hit needs.",
        Mode.PACKET);

    private final BoolSetting mace = new BoolSetting("Mace smash",
        "Fakes a long fall whilst holding a mace so every swing lands as a smash attack.", false);

    public Criticals() {
        super("Criticals", "Makes every melee hit a critical hit.", Category.COMBAT);
        addSettings(mode, mace);
        searchTags("crit", "mace", "smash");
    }

    @Override
    public String getSuffix() {
        return mode.getValue().toString();
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
        if (mace.isOn() && mc.player.getMainHandItem().is(Items.MACE)) {
            smash();
            return;
        }

        switch (mode.getValue()) {
            case PACKET -> {
                sendFakeY(0.0625, true);
                sendFakeY(0, false);
                sendFakeY(1.1e-5, false);
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
