package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

/**
 * Holds the launch speed of a jump for the whole flight while the direction
 * follows the movement keys. LocalPlayerMixin calls onJump right after the
 * game applies a jump.
 */
public final class LongJump extends Module {

    /** Average horizontal speed per tick of a normal sprint jump. */
    private static final double BASE_SPEED = 0.35;

    private final NumberSetting multiplier = new NumberSetting("Multiplier",
        "How much further a jump carries you compared to a normal sprint jump.",
        3, 1, 10, 0.5, "x").min(0.1);
    private final BoolSetting stopOnLagback = new BoolSetting("Stop on lagback",
        "Ends the boost when the server teleports you back.", true);

    private boolean boosting;
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
    }

    /** Called from LocalPlayerMixin once the game has set the jump velocity. */
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
            return;
        }
        updateDirection();
        applySpeed();
    }

    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        if (stopOnLagback.isOn() && event.getPacket() instanceof ClientboundPlayerPositionPacket) {
            boosting = false;
        }
    }

    private boolean canBoost() {
        return !mc.player.isSpectator() && !mc.player.isPassenger()
            && !mc.player.getAbilities().flying && !mc.player.isFallFlying()
            && !mc.player.isInWater() && !mc.player.isInLava() && !mc.player.onClimbable();
    }

    /** Points the boost where the movement keys say. Keeps the old heading when no key is held. */
    private boolean updateDirection() {
        Vec2 move = mc.player.input.getMoveVector();
        if (move.length() < 1e-4f) {
            return boosting;
        }
        double angle = Math.toRadians(mc.player.getYRot()) + Math.atan2(-move.x, move.y);
        dirX = -Math.sin(angle);
        dirZ = Math.cos(angle);
        return true;
    }

    private void applySpeed() {
        Vec3 velocity = mc.player.getDeltaMovement();
        mc.player.setDeltaMovement(dirX * speed, velocity.y, dirZ * speed);
    }
}
