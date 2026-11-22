package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.Hop;
import com.jellypudding.offlineclient.util.Lagback;
import com.jellypudding.offlineclient.util.MovementUtil;
import net.minecraft.world.phys.Vec3;

// Free keeps physics off through PlayerMixin and only works on a server that never
// checks a move. Phase sends one hop through a wall. A vanilla server lets that stand
// for a player it holds inside a block and Hop leans the player into one first.
public final class NoClip extends Module {

    public enum Mode { FREE, PHASE }

    private static final int SETTLE_TICKS = 10;

    // A box this far inside a block is stuck in it. Less is a rounding slip.
    private static final double STUCK_DEPTH = 0.01;

    private static final float LANDING_HUE = 120;

    private final EnumSetting<Mode> mode = new EnumSetting<>("Mode", "How you get through blocks.", Mode.FREE)
        .describe(Mode.FREE, "Turns your collision off and flies you through the world. Vanilla servers"
            + " and single player pull you straight back.")
        .describe(Mode.PHASE, "Pushing against a wall brings you out at the first free space behind it."
            + " Stuck inside a block jump and sneak take you up and down as well. Paper only lets"
            + " you through a wall in creative.");
    private final NumberSetting speed = new NumberSetting("Speed",
        "Blocks a tick you move through the world.", 0.5, 0.1, 5, 0.1, " blocks").under(mode, Mode.FREE);
    private final NumberSetting range = new NumberSetting("Range",
        "The furthest a push takes you. A vanilla server allows about nineteen blocks.", 20, 1, 20, 1,
        " blocks").under(mode, Mode.PHASE);
    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks you push against a wall before you go through it.", 5, 1, 20, 1, " ticks")
        .under(mode, Mode.PHASE);
    private final BoolSetting showLanding = new BoolSetting("Show landing",
        "Outlines where the wall you face or push against lets you out.", true).under(mode, Mode.PHASE);
    private final BoxStyle landingStyle = new BoxStyle("Landing", BoxStyle.Shape.LINES, LANDING_HUE)
        .under(showLanding);

    private final Lagback.Watcher lagback = new Lagback.Watcher();
    private int settle;

    private Vec3 landing;
    private int pushed;
    // A push that went wrong is not tried again until the keys are let go.
    private boolean refused;
    private Hop.Result told;

    public NoClip() {
        super("NoClip", "Takes you through blocks.", Category.MOVEMENT);
        addSettings(mode, speed, range, delay, showLanding);
        addSettings(landingStyle.settings());
        searchTags("phase", "through walls", "wall clip");
    }

    @Override
    public String getSuffix() {
        return settle > 0 ? "pulled back" : null;
    }

    // Read by PlayerMixin. The physics flag stays off only whilst this is true.
    public boolean passesThroughBlocks() {
        return isEnabled() && mode.is(Mode.FREE) && settle == 0;
    }

    // Read by LocalPlayerMixin. Phase goes on from inside a block and the game does not
    // push the player out of one meanwhile.
    public boolean holdsInBlock() {
        return isEnabled() && mode.is(Mode.PHASE);
    }

    @Override
    protected void onEnable() {
        lagback.sync();
        settle = 0;
        refused = false;
        told = null;
        rest();
    }

    @Override
    protected void onDisable() {
        rest();
        if (mc.player != null && !mc.player.isSpectator()) {
            mc.player.noPhysics = false;
        }
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (lagback.happened()) {
            settle = SETTLE_TICKS;
            mc.player.noPhysics = false;
        }
        if (settle > 0) {
            settle--;
            rest();
        } else if (mode.is(Mode.FREE)) {
            rest();
            fly();
        } else {
            phase();
        }
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        Vec3 shown = landing;
        if (shown != null && showLanding.isOn() && mc.player != null) {
            landingStyle.draw(event.getBatch(), Hop.boxAt(mc.player, shown), true);
        }
    }

    private void fly() {
        double pace = speed.getValue();
        Vec3 heading = MovementUtil.inputDirection();
        mc.player.setDeltaMovement(heading.x * pace, MovementUtil.verticalInput() * pace, heading.z * pace);
    }

    private void phase() {
        if (Hop.travelling() || mc.gui.screen() != null || mc.player.isPassenger()) {
            rest();
            return;
        }
        Vec3 push = pushedWay();
        Hop.Plan plan = Hop.plan();
        Vec3 spot = Hop.beyond(mc.player, push == null ? facing() : push,
            Math.min(range.getValue(), plan.longestHop()));
        if (spot != null) {
            plan.through(spot);
        }
        landing = spot != null && plan.problem() == null ? spot : null;
        if (push == null) {
            pushed = 0;
            refused = false;
            return;
        }
        if (spot == null || refused || ++pushed < delay.getInt()) {
            return;
        }
        rest();
        plan.go(this::arrived);
    }

    // Hop answers once the server has had time to pull the push back. The module may be
    // off by then.
    private void arrived(Hop.Result result) {
        if (result == Hop.Result.MOVED) {
            told = null;
        } else if (isEnabled()) {
            refuse(result);
        }
    }

    private void refuse(Hop.Result trouble) {
        refused = true;
        if (trouble != told) {
            told = trouble;
            ChatUtil.error(trouble.problem());
        }
    }

    private void rest() {
        landing = null;
        pushed = 0;
    }

    private Vec3 pushedWay() {
        Vec3 heading = MovementUtil.inputDirection();
        if (heading != Vec3.ZERO && mc.player.horizontalCollision) {
            return heading;
        }
        int vertical = MovementUtil.verticalInput();
        if (vertical == 0 || !stuck()) {
            return null;
        }
        return new Vec3(0, vertical, 0);
    }

    private boolean stuck() {
        return !mc.level.noCollision(mc.player, mc.player.getBoundingBox().deflate(STUCK_DEPTH));
    }

    private Vec3 facing() {
        return Vec3.directionFromRotation(0, mc.player.getYRot());
    }
}
