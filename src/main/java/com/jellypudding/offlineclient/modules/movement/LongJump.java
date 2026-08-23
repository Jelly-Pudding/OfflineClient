package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.MovementUtil;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.world.phys.Vec3;

public final class LongJump extends Module {

    // Average horizontal speed per tick of a normal sprint jump.
    private static final double BASE_SPEED = 0.35;

    private final NumberSetting multiplier = new NumberSetting("Multiplier",
        "How much further a jump carries you.",
        3, 1, 10, 0.5, "x").min(0.1);
    private final BoolSetting stopOnLagback = new BoolSetting("Stop on lagback",
        "Ends the boost when the server teleports you back.", true);

    // Cleared from the packet thread.
    private volatile boolean boosting;
    private volatile boolean hasDirection;
    private double speed;
    private double dirX;
    private double dirZ;

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
        boosting = false;
        hasDirection = false;
    }

    // Called from LocalPlayerMixin once the game has set the jump velocity.
    public void onJump() {
        if (!isEnabled() || !inGame() || !canBoost()) {
            return;
        }
        if (!updateDirection()) {
            return;
        }
        speed = BASE_SPEED * multiplier.getValue();
        boosting = true;
        applySpeed();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!boosting || !inGame()) {
            return;
        }
        if (mc.player.onGround() || !canBoost()) {
            boosting = false;
            hasDirection = false;
            return;
        }
        updateDirection();
        applySpeed();
    }

    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        if (stopOnLagback.isOn() && event.getPacket() instanceof ClientboundPlayerPositionPacket) {
            boosting = false;
            hasDirection = false;
        }
    }

    private boolean canBoost() {
        return !mc.player.isSpectator() && !mc.player.isPassenger()
            && !mc.player.getAbilities().flying && !mc.player.isFallFlying()
            && !mc.player.isInWater() && !mc.player.isInLava() && !mc.player.onClimbable();
    }

    // Keeps the old heading when no key is held. False until there is one at all.
    private boolean updateDirection() {
        Vec3 heading = MovementUtil.inputDirection();
        if (heading.lengthSqr() == 0) {
            return hasDirection;
        }
        dirX = heading.x;
        dirZ = heading.z;
        hasDirection = true;
        return true;
    }

    private void applySpeed() {
        Vec3 velocity = mc.player.getDeltaMovement();
        mc.player.setDeltaMovement(dirX * speed, velocity.y, dirZ * speed);
    }
}
