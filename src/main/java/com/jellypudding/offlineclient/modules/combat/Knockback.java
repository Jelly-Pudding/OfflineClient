package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.DamageUtil;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.RotationManager;
import com.jellypudding.offlineclient.util.SprintPause;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.world.entity.LivingEntity;

// The server adds knockback to a full strength hit from anyone it believes is sprinting
// and then stops that sprint. Every hit lands soft until word of the stop reaches the
// client and it starts again. A sprint start sent just ahead of each hit earns the
// knockback whether you run or not. The server pushes along the yaw it holds as a hit
// lands. A look packet sent just ahead of the hit steers the push.
public final class Knockback extends Module {

    // Degrees clockwise from the line that runs from you through the target. Measured from
    // that line in place of your view the push stays true when you hit off centre.
    public enum Push {
        // Leaves the yaw alone. The game pushes along your view.
        FORWARD(0),
        TOWARDS_YOU(180),
        LEFT(270),
        RIGHT(90),
        // Takes its turn from the Angle slider.
        ANGLE(0);

        private final float degrees;

        Push(float degrees) {
            this.degrees = degrees;
        }
    }

    private final BoolSetting keepCrits = new BoolSetting("Keep crits",
        "Leaves a hit alone whilst you fall. It lands as a critical hit instead.", true);
    private final EnumSetting<Push> direction = new EnumSetting<>("Direction",
        "Which way a hit sends the target flying.", Push.FORWARD)
        .describe(Push.FORWARD, "Along your view as the game does it. No extra packets.")
        .describe(Push.TOWARDS_YOU, "Straight back towards you.")
        .describe(Push.LEFT, "Off to your left.")
        .describe(Push.RIGHT, "Off to your right.")
        .describe(Push.ANGLE, "At the angle below.");
    private final NumberSetting angle = new NumberSetting("Angle",
        "Degrees clockwise from straight away from you. Ninety sends the target to your right and"
            + " 180 back to you.", 180, 0, 360, 5, " degrees").max(360)
        .under(direction, Push.ANGLE);

    public Knockback() {
        super("Knockback", "Makes every hit knock the target as far as a sprinting hit and the way you choose.",
            Category.COMBAT);
        addSettings(keepCrits, direction, angle);
        searchTags("super knockback", "more knockback", "w tap", "sprint reset", "kb", "pull",
            "reverse knockback");
    }

    @Override
    protected void onEnable() {
        SprintPause.attach(this);
    }

    @Override
    protected void onDisable() {
        SprintPause.detach(this);
    }

    // Runs after Sprint and Criticals have had their say about the hit.
    @Subscribe(priority = -100)
    private void onPacketSend(PacketSendEvent event) {
        if (!(event.getPacket() instanceof ServerboundAttackPacket attack) || !inGame() || mc.player.isSpectator()
            || !(mc.level.getEntity(attack.entityId()) instanceof LivingEntity target)) {
            return;
        }
        if (!direction.is(Push.FORWARD)) {
            RotationManager.glance(pushYaw(target), RotationManager.serverPitch());
        }
        if (lendsSprint()) {
            SprintPause.lend();
        }
    }

    // A weak hit gets no sprint knockback and a falling one is left to land as a crit.
    private boolean lendsSprint() {
        Criticals criticals = Modules.active(Criticals.class);
        if (criticals != null && criticals.releasing()) {
            return false;
        }
        if (!SprintPause.fullStrength(mc.player)) {
            return false;
        }
        return !(keepCrits.isOn() && DamageUtil.critFall(mc.player));
    }

    private float pushYaw(LivingEntity target) {
        float away = RotationManager.yawTo(target.getBoundingBox().getCenter());
        return away + (direction.is(Push.ANGLE) ? angle.getFloat() : direction.getValue().degrees);
    }
}
