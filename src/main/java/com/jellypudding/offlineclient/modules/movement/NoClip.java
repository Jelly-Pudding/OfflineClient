package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.MovementUtil;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.world.phys.Vec3;

/**
 * PlayerMixin keeps the physics flag off whilst this is active. A vanilla
 * server refuses any move into a block it did not see you in and pulls you
 * back. The module only works where the server allows it. After a pull
 * back the collision comes back for a moment to let you settle instead of
 * fighting the server every tick.
 */
public final class NoClip extends Module {

    // Ticks the collision stays on after the server pulls the player back.
    private static final int SETTLE_TICKS = 10;

    private final NumberSetting speed = new NumberSetting("Speed",
        "Blocks a tick you move through the world.", 0.5, 0.1, 5, 0.1, " blocks");

    // Set from the packet thread when the server sends the player back.
    private volatile boolean pulledBack;
    private int settle;

    public NoClip() {
        super("NoClip", "Turns your collision off. Only works on servers without the vanilla movement check.",
            Category.MOVEMENT);
        addSettings(speed);
        searchTags("phase", "through walls");
    }

    @Override
    public String getSuffix() {
        return settle > 0 ? "pulled back" : null;
    }

    // Read by PlayerMixin. The physics flag stays off only whilst this is true.
    public boolean passesThroughBlocks() {
        return isEnabled() && settle == 0;
    }

    @Override
    protected void onEnable() {
        pulledBack = false;
        settle = 0;
    }

    @Override
    protected void onDisable() {
        if (mc.player != null && !mc.player.isSpectator()) {
            mc.player.noPhysics = false;
        }
    }

    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacket() instanceof ClientboundPlayerPositionPacket) {
            pulledBack = true;
        }
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            return;
        }
        if (pulledBack) {
            pulledBack = false;
            settle = SETTLE_TICKS;
            mc.player.noPhysics = false;
        }
        if (settle > 0) {
            settle--;
            return;
        }
        double pace = speed.getValue();
        double vy = 0;
        if (mc.options.keyJump.isDown()) {
            vy += pace;
        }
        if (mc.options.keyShift.isDown()) {
            vy -= pace;
        }
        Vec3 heading = MovementUtil.inputDirection();
        mc.player.setDeltaMovement(heading.x * pace, vy, heading.z * pace);
    }
}
