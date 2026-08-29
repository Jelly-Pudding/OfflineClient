package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.JumpCarry;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;

public final class LongJump extends Module {

    // Average horizontal speed per tick of a normal sprint jump.
    private static final double BASE_SPEED = 0.35;

    private final NumberSetting multiplier = new NumberSetting("Multiplier",
        "How much further a jump carries you.",
        3, 1, 10, 0.5, "x").min(0.1);
    private final BoolSetting stopOnLagback = new BoolSetting("Stop on lagback",
        "Ends the boost when the server teleports you back.", true);

    private final JumpCarry carry = new JumpCarry();

    public LongJump() {
        super("LongJump", "Jump much further than normal.", Category.MOVEMENT);
        addSettings(multiplier, stopOnLagback);
    }

    @Override
    public String getSuffix() {
        return multiplier.getValueString();
    }

    @Override
    protected void onEnable() {
        carry.stop();
    }

    // Called from LocalPlayerMixin once the game has set the jump velocity.
    public void onJump() {
        if (!isEnabled() || !inGame() || !JumpCarry.inPlainAir(mc.player)) {
            return;
        }
        carry.start(BASE_SPEED * multiplier.getValue());
    }

    @Subscribe
    private void onTick(TickEvent event) {
        carry.tick();
    }

    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        if (stopOnLagback.isOn() && event.getPacket() instanceof ClientboundPlayerPositionPacket) {
            carry.stop();
        }
    }
}
