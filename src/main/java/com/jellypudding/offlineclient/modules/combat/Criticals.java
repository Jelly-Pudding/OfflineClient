package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.AttackEntityEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.EnumSetting;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.entity.LivingEntity;

public final class Criticals extends Module {

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
        "The game only gives critical hits while you are falling. "
            + "Packet fakes a tiny fall with packets so nothing moves on your screen. "
            + "Mini jump does a small real hop. Full jump is a normal jump.",
        Mode.PACKET);

    public Criticals() {
        super("Criticals", "Makes every melee hit a critical hit.", Category.COMBAT);
        addSettings(mode);
    }

    @Override
    public String getSuffix() {
        return mode.getValue().toString();
    }

    @Subscribe
    private void onAttack(AttackEntityEvent event) {
        if (!inGame() || !(event.getTarget() instanceof LivingEntity)) {
            return;
        }
        if (!mc.player.onGround() || mc.player.isInWater() || mc.player.isInLava()) {
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

    private void sendFakeY(double offset, boolean onGround) {
        mc.player.connection.send(new ServerboundMovePlayerPacket.Pos(
            mc.player.getX(), mc.player.getY() + offset, mc.player.getZ(),
            onGround, mc.player.horizontalCollision));
    }
}
