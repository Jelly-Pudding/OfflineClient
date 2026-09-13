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
        "How far past the normal sprint rules to go.", Mode.STRICT)
        .describe(Mode.STRICT, "Sprints whenever the game would let you sprint.")
        .describe(Mode.RAGE, "Sprints through walls and shallow water and item use in any direction.");
    private final BoolSetting anyDirection = new BoolSetting("Any direction",
        "Also sprints sideways and backwards.", false).under(mode, Mode.STRICT);
    private final BoolSetting whilstHungry = new BoolSetting("Whilst hungry",
        "Sprints even when the hunger bar is too low for it.", false).under(mode, Mode.STRICT);
    private final BoolSetting whilstStill = new BoolSetting("Whilst still",
        "Keeps the sprint on whilst you stand still.", false).under(mode, Mode.RAGE);
    private final BoolSetting stopInWater = new BoolSetting("Stop in water",
        "Drops the sprint whilst you are in water.", true).under(mode, Mode.RAGE);
    private final BoolSetting keepSprint = new BoolSetting("Keep sprint",
        "Keeps your speed and sprint after a hit lands instead of the normal slowdown.", false);
    private final BoolSetting stopOnHit = new BoolSetting("Stop on hit",
        "Tells the server you stopped sprinting for each attack so it can crit and sweep.", false);

    private final SprintPause sprintPause = new SprintPause();

    public Sprint() {
        super("Sprint", "Automatically sprints whenever you move.", Category.MOVEMENT);
        addSettings(mode, anyDirection, whilstHungry, whilstStill, stopInWater, keepSprint, stopOnHit);
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

    // Read by PlayerMixin. True whilst a landed hit must not halve the speed.
    public boolean keepsSprintOnHit() {
        return isEnabled() && keepSprint.isOn();
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
        if (mc.player.input.hasForwardImpulse() && !mc.player.isUsingItem()) {
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
