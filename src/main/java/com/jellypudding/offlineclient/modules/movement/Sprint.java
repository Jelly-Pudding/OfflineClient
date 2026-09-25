package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.jellypudding.offlineclient.event.events.PostMotionEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.modules.player.InvWalk;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.MovementUtil;
import com.jellypudding.offlineclient.util.SprintPause;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.world.phys.Vec3;

public final class Sprint extends Module {

    public enum Mode {
        STRICT,
        RAGE
    }

    private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
        "Which sprint rules to keep.", Mode.STRICT)
        .describe(Mode.STRICT, "Sprints only when the game allows it.")
        .describe(Mode.RAGE, "Ignores the sprint rules and sprints any way you move.");
    private final BoolSetting anyDirection = new BoolSetting("Any direction",
        "Also sprints sideways and backwards.", false).under(mode, Mode.STRICT);
    private final BoolSetting whilstHungry = new BoolSetting("Whilst hungry",
        "Sprints even with low hunger.", false).under(mode, Mode.STRICT);
    private final BoolSetting whilstStill = new BoolSetting("Whilst still",
        "Keeps the sprint on whilst you stand still.", false).under(mode, Mode.RAGE);
    private final BoolSetting stopInWater = new BoolSetting("Stop in water",
        "Stops sprinting in water.", true).under(mode, Mode.RAGE);
    private final BoolSetting whilstUsing = new BoolSetting("Whilst using items",
        "Keeps your sprint and speed whilst you eat or drink or block.", false);
    private final BoolSetting sprintThroughHits = new BoolSetting("Sprint through hits",
        "Hitting something no longer stops your sprint or slows you down.", false);
    private final BoolSetting stopOnHit = new BoolSetting("Stop on hit",
        "Drops the sprint for each hit to allow crits and sweeps.", false);

    private final SprintPause sprintPause = new SprintPause();

    public Sprint() {
        super("Sprint", "Sprints for you whenever you move.", Category.MOVEMENT);
        addSettings(mode, anyDirection, whilstHungry, whilstStill, stopInWater, whilstUsing,
            sprintThroughHits, stopOnHit);
        searchTags("auto sprint", "omnidirectional");
    }

    @Override
    public String getSuffix() {
        return mode.getValueString();
    }

    @Override
    protected void onDisable() {
        sprintPause.resume();
    }

    // Read by ClientInputMixin.
    public boolean sprintsAnyDirection() {
        return isEnabled() && (rage() || anyDirection.isOn());
    }

    // Read by FoodDataMixin.
    public boolean sprintsHungry() {
        return isEnabled() && (rage() || whilstHungry.isOn());
    }

    // Read by LocalPlayerMixin. True whilst the vanilla start and stop rules are replaced.
    public boolean rage() {
        return isEnabled() && mode.is(Mode.RAGE);
    }

    // What Rage mode answers in place of every vanilla sprint check.
    public boolean rageWantsSprint(LocalPlayer player) {
        if (stopInWater.isOn() && player.isInWater()) {
            return false;
        }
        return whilstStill.isOn() || moving(player);
    }

    // Read by LocalPlayerMixin. An item in use does not slow you.
    public boolean keepsSpeedWhilstUsing() {
        return isEnabled() && whilstUsing.isOn();
    }

    // Read by PlayerMixin. True whilst a landed hit must not halve the speed.
    public boolean keepsSprintOnHit() {
        return isEnabled() && sprintThroughHits.isOn();
    }

    // Read by LivingEntityMixin. The sprint jump push follows the keys rather than the camera.
    public float jumpYaw(float yaw) {
        if (!rage()) {
            return yaw;
        }
        Vec3 direction = MovementUtil.inputDirection();
        if (direction.lengthSqr() == 0) {
            return yaw;
        }
        return (float) Math.toDegrees(Math.atan2(-direction.x, direction.z));
    }

    // Read by LivingEntityMixin. A still player must not be pushed by the sprint jump.
    public boolean jumpBoost(boolean sprinting, LocalPlayer player) {
        return sprinting && (!rage() || moving(player));
    }

    // Runs at the end of the client tick.
    // The player tick reevaluates and clears any sprint set earlier.
    @Subscribe
    private void onClientTick(ClientTickEvent event) {
        if (!inGame() || (mc.gui.screen() != null && !screenAllowsSprint())) {
            return;
        }
        if (rage()) {
            mc.player.setSprinting(rageWantsSprint(mc.player));
            return;
        }
        if (mc.player.input.hasForwardImpulse()
            && (!mc.player.isUsingItem() || whilstUsing.isOn())) {
            mc.player.setSprinting(true);
        }
    }

    // The server refuses a critical hit to anyone it believes is sprinting.
    @Subscribe(priority = 100)
    private void onPacketSend(PacketSendEvent event) {
        if (stopOnHit.isOn() && event.getPacket() instanceof ServerboundAttackPacket) {
            sprintPause.pause();
        }
    }

    // The attack packet has gone out by this point.
    @Subscribe
    private void onPostMotion(PostMotionEvent event) {
        sprintPause.resume();
    }

    // A screen only keeps the sprint whilst InvWalk lets the sprint key through.
    private boolean screenAllowsSprint() {
        InvWalk invWalk = Modules.active(InvWalk.class);
        return invWalk != null && invWalk.allowsSprint();
    }

    private static boolean moving(LocalPlayer player) {
        return player.input.getMoveVector().lengthSquared() > 1e-5f;
    }
}
