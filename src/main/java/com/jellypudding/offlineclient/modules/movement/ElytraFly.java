package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * Gentle nudges on top of the real glide physics. The glide itself stays
 * vanilla.
 */
public final class ElytraFly extends Module {

    private final NumberSetting speed = new NumberSetting("Speed",
        "How hard the keys push you.", 1, 0.2, 3, 0.1, "x");
    private final BoolSetting holdHeight = new BoolSetting("Hold height",
        "Stops the slow sink whilst you glide level.", true);
    private final BoolSetting instantStop = new BoolSetting("Instant stop",
        "Kills your momentum when you release the keys.", false);
    private final BoolSetting keepGliding = new BoolSetting("Keep gliding",
        "Restarts a glide the game cancels midair.", true);

    private int restartCooldown;

    public ElytraFly() {
        super("ElytraFly", "Full elytra control without firework rockets.", Category.MOVEMENT);
        addSettings(speed, holdHeight, instantStop, keepGliding);
        searchTags("elytra", "glide", "fly");
    }

    @Override
    public String getSuffix() {
        return speed.getValueString();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            return;
        }
        if (restartCooldown > 0) {
            restartCooldown--;
        }
        if (!mc.player.isFallFlying()) {
            restartIfCancelled();
            return;
        }

        Input keys = mc.player.input.keyPresses;
        Vec3 velocity = mc.player.getDeltaMovement();
        double accel = 0.08 * speed.getValue();

        double vx = velocity.x;
        double vy = velocity.y;
        double vz = velocity.z;

        double yaw = Math.toRadians(mc.player.getYRot());
        double forwardX = -Math.sin(yaw);
        double forwardZ = Math.cos(yaw);
        double rightX = -Math.cos(yaw);
        double rightZ = -Math.sin(yaw);

        boolean steering = keys.forward() || keys.backward() || keys.left() || keys.right();
        if (keys.forward()) {
            vx += forwardX * accel;
            vz += forwardZ * accel;
        }
        if (keys.backward()) {
            vx -= forwardX * accel;
            vz -= forwardZ * accel;
        }
        if (keys.right()) {
            vx += rightX * accel;
            vz += rightZ * accel;
        }
        if (keys.left()) {
            vx -= rightX * accel;
            vz -= rightZ * accel;
        }

        if (keys.jump()) {
            vy += 0.08;
        } else if (keys.shift()) {
            vy -= 0.04;
        } else if (holdHeight.isOn() && mc.player.getXRot() < 25) {
            // Lift cancels the glide sink except while pitched down into a dive.
            double cos = Math.cos(Math.toRadians(mc.player.getXRot()));
            vy = Math.max(vy, 0.08 * (1 - 0.75 * cos * cos));
        }

        if (instantStop.isOn() && !steering) {
            vx = 0;
            vz = 0;
            // Leftover climb from a jump boost dies while sinking is left alone.
            if (!keys.jump() && vy > 0) {
                vy = 0;
            }
        }

        mc.player.setDeltaMovement(vx, vy, vz);
    }

    /**
     * The game sometimes ends a glide on its own. Whilst still midair with
     * an elytra on the glide can simply be started again.
     */
    private void restartIfCancelled() {
        if (!keepGliding.isOn() || restartCooldown > 0) {
            return;
        }
        if (mc.player.onGround() || mc.player.isPassenger() || mc.player.isInWater()
            || mc.player.getAbilities().flying
            || !mc.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)) {
            return;
        }
        // Close to the ground a restart just stutters between landing and
        // gliding. Let the landing happen.
        if (!mc.level.noCollision(mc.player,
            mc.player.getBoundingBox().expandTowards(0, -1.5, 0))) {
            return;
        }
        mc.player.connection.send(new ServerboundPlayerCommandPacket(mc.player,
            ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
        restartCooldown = 5;
    }
}
