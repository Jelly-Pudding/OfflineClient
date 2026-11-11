package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
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

    public enum OnHit {
        VANILLA,
        KEEP_SPRINT,
        DROP_SPRINT
    }

    // Runs after the modules that hold a hit back for a tick. A hit one of them holds is
    // asked for on the tick it really leaves.
    private static final int AFTER_HELD_HITS = -50;

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
    private final EnumSetting<OnHit> onHit = new EnumSetting<>("On hit",
        "What a hit does to your sprint.", OnHit.VANILLA)
        .describe(OnHit.VANILLA, "A sprinting hit knocks further back and ends your sprint as in the plain game.")
        .describe(OnHit.KEEP_SPRINT, "A sprinting hit knocks further back and you keep your sprint and your speed.")
        .describe(OnHit.DROP_SPRINT, "Tells the server the sprint stopped for each hit. A hit whilst you fall"
            + " lands as a crit and you keep your speed but give up the extra knockback.");

    public Sprint() {
        super("Sprint", "Sprints for you whenever you move.", Category.MOVEMENT);
        addSettings(mode, anyDirection, whilstHungry, whilstStill, stopInWater, onHit);
        searchTags("auto sprint", "omnidirectional");
    }

    @Override
    public String getSuffix() {
        return mode.getValueString();
    }

    @Override
    protected void onEnable() {
        SprintPause.attach(this);
    }

    @Override
    protected void onDisable() {
        SprintPause.detach(this);
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
        return whilstStill.isOn() || MovementUtil.hasInput(player);
    }

    // Read by PlayerMixin. True whilst a landed hit must not cost the client its sprint or speed.
    public boolean keepsSprintOnHit() {
        return isEnabled() && !onHit.is(OnHit.VANILLA);
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
        return sprinting && (!rage() || MovementUtil.hasInput(player));
    }

    // Runs at the end of the client tick.
    // The player tick reevaluates and clears any sprint set earlier.
    @Subscribe
    private void onClientTick(ClientTickEvent event) {
        sprint();
    }

    // Ahead of every other module's tick. It only puts right what came in between ticks. A
    // sprint the server called off for a moment between a stop and a start is called off
    // here a little later and the game would report that as a stop. The sprint is back on
    // before the report and nothing goes out.
    @Subscribe(priority = Subscribe.FIRST)
    private void onTick(TickEvent event) {
        if (SprintPause.staleStop()) {
            sprint();
        }
    }

    private void sprint() {
        if (!inGame() || (mc.gui.screen() != null && !screenAllowsSprint())) {
            return;
        }
        if (rage()) {
            mc.player.setSprinting(rageWantsSprint(mc.player));
            return;
        }
        if (mc.player.input.hasForwardImpulse() && (!mc.player.isUsingItem() || Modules.itemsKeepSpeed())) {
            mc.player.setSprinting(true);
        }
    }

    @Subscribe(priority = AFTER_HELD_HITS)
    private void onPacketSend(PacketSendEvent event) {
        if (!(event.getPacket() instanceof ServerboundAttackPacket)) {
            return;
        }
        switch (onHit.getValue()) {
            case KEEP_SPRINT -> SprintPause.renew();
            case DROP_SPRINT -> SprintPause.hide();
            case VANILLA -> {
            }
        }
    }

    // A screen only keeps the sprint whilst InvWalk lets the sprint key through.
    private boolean screenAllowsSprint() {
        InvWalk invWalk = Modules.active(InvWalk.class);
        return invWalk != null && invWalk.allowsSprint();
    }
}
