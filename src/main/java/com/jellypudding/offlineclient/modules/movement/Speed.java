package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.event.events.KnockbackEvent;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.modules.misc.Timer;
import com.jellypudding.offlineclient.path.PathWalker;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.MovementUtil;
import com.jellypudding.offlineclient.util.WholeMove;
import net.minecraft.SharedConstants;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.atomic.DoubleAccumulator;

public final class Speed extends Module {

    public enum Mode {
        STRAFE, BHOP;

        // The automatic label would read Bhop.
        @Override
        public String toString() {
            return this == BHOP ? "BHop" : name();
        }
    }

    private static final String TIMER_KEY = "speed";

    // Runs after NoKnockback has scaled a push.
    private static final int AFTER_SCALING = -10;

    // The steps a walk the client makes needs ahead of it before it next goes down. A stride at
    // this pace skips a gap one block wide and lands past a ledge.
    private static final int STRIDE_RUN = 2;

    private static final double PUSH_KEPT = 0.9;
    private static final double LEAST_PUSH = 0.02;

    private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
        "How the speed is gained.", Mode.STRAFE)
        .describe(Mode.STRAFE, "Holds you at a set multiple of your normal speed.")
        .describe(Mode.BHOP, "Bunnyhops and multiplies your speed on every landing. Keeps to the ground whilst"
            + " the client walks for you as it does on a goto.");
    private final NumberSetting multiplier = new NumberSetting("Multiplier",
        "Your speed as a multiple of normal.", 1.6, 1, 10, 0.1, "x")
        .visibleWhen(() -> mode.is(Mode.STRAFE));
    private final NumberSetting hopBoost = new NumberSetting("Hop boost",
        "Multiplies your speed on every hop. Much past 1.6 the server pulls you back.",
        1.3, 1, 2, 0.05, "x")
        .visibleWhen(() -> mode.is(Mode.BHOP));
    private final BoolSetting capSpeed = new BoolSetting("Speed cap",
        "Limits your top speed.", false);
    private final NumberSetting cap = new NumberSetting("Cap",
        "Top speed in blocks a second. Never slows you below your normal pace and still holds under a timer.",
        12, 4, 30, 0.5, " bps").min(1)
        .under(capSpeed);
    private final BoolSetting keepInAir = new BoolSetting("Keep in air",
        "Keeps your speed whilst airborne.", true)
        .visibleWhen(() -> mode.is(Mode.STRAFE));
    private final BoolSetting keepKnockback = new BoolSetting("Keep knockback",
        "A hit or a blast adds its push to your speed for a moment.", false)
        .visibleWhen(() -> mode.is(Mode.STRAFE));
    private final BoolSetting forceSprint = new BoolSetting("Force sprint",
        "Sprints whilst you move for longer jumps.", true);
    private final BoolSetting inLiquids = new BoolSetting("In liquids",
        "Keeps the speed in water and lava.", false)
        .visibleWhen(() -> mode.is(Mode.STRAFE));
    private final BoolSetting whilstSneaking = new BoolSetting("Whilst sneaking",
        "Keeps the speed whilst you sneak.", false);
    private final NumberSetting timer = new NumberSetting("Timer",
        "Speeds up the game whilst you move. 1 is normal.", 1, 1, 3, 0.1, "x");

    // A blast is heard on the netty thread.
    private final DoubleAccumulator pushed = new DoubleAccumulator(Math::max, 0);
    private double push;
    private int hopTick = Integer.MIN_VALUE;
    private Vec3 jumped = Vec3.ZERO;
    private Vec3 boosted = Vec3.ZERO;

    public Speed() {
        super("Speed", "Makes you move faster on the ground.", Category.MOVEMENT);
        addSettings(mode, multiplier, hopBoost, capSpeed, cap, keepInAir, keepKnockback, forceSprint,
            inLiquids, whilstSneaking, timer);
        searchTags("bunnyhop", "bhop", "strafe");
    }

    @Override
    protected void onEnable() {
        pushed.reset();
        push = 0;
    }

    @Override
    protected void onDisable() {
        Timer.override(TIMER_KEY, 1f);
    }

    @Override
    public String getSuffix() {
        NumberSetting amount = mode.is(Mode.BHOP) ? hopBoost : multiplier;
        return mode.getValueString() + " " + amount.getValueString();
    }

    // TickEvent stops at a disconnect. ClientTickEvent still runs in the menus.
    // A sprint set any earlier in the tick is cleared again by the player tick.
    @Subscribe
    private void onClientTick(ClientTickEvent event) {
        if (!inGame()) {
            Timer.override(TIMER_KEY, 1f);
            return;
        }
        // A walk the client makes sprints by itself where a sprint helps. Forced on in water it
        // would swim from side to side.
        if (forceSprint.isOn() && !mc.player.isShiftKeyDown() && !mc.player.isUsingItem() && !PathWalker.steering()) {
            mc.player.setSprinting(true);
        }
    }

    // The speed the server gave counts as the push. One NoKnockback took away changes nothing.
    @Subscribe(priority = AFTER_SCALING)
    private void onKnockback(KnockbackEvent event) {
        if (!inGame()) {
            return;
        }
        Vec3 before = mc.player.getDeltaMovement();
        if (Math.hypot(event.getX() - before.x, event.getZ() - before.z) > LEAST_PUSH) {
            pushed.accumulate(Math.hypot(event.getX(), event.getZ()));
        }
    }

    @Subscribe(priority = AFTER_SCALING)
    private void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacket() instanceof ClientboundExplodePacket blast) {
            blast.playerKnockback().ifPresent(blown -> pushed.accumulate(blown.horizontalDistance()));
        }
    }

    // Runs after HoleSnap. A hole it has found is not pushed past.
    @Subscribe(priority = -10)
    private void onTick(TickEvent event) {
        push = Math.max(push * PUSH_KEPT, pushed.getThenReset());
        if (push < LEAST_PUSH) {
            push = 0;
        }
        Vec3 heading = MovementUtil.inputDirection();
        boolean moving = heading.lengthSqr() > 0;
        Timer.override(TIMER_KEY, moving ? timer.getFloat() : 1f);

        if (mc.player.isSpectator() || mc.player.isPassenger() || HoleSnap.holds()) {
            return;
        }
        if (mc.player.isShiftKeyDown() && !whilstSneaking.isOn()) {
            return;
        }
        boolean inLiquid = mc.player.isInWater() || mc.player.isInLava();
        if (inLiquid && (mode.is(Mode.BHOP) || !inLiquids.isOn())) {
            return;
        }
        if (mc.player.onClimbable() || mc.player.isFallFlying() || mc.player.getAbilities().flying) {
            return;
        }
        if (!moving) {
            return;
        }

        // A walk the client makes keeps its footing at a ledge. A hop comes down wherever it
        // will and no walk can steer one round a corner.
        if (mode.is(Mode.BHOP)) {
            if (!PathWalker.steering()) {
                hop();
            }
        } else if (PathWalker.levelRun() >= STRIDE_RUN) {
            strafe(heading);
        }
    }

    private void strafe(Vec3 heading) {
        if (!mc.player.onGround() && !keepInAir.isOn()) {
            return;
        }
        double base = MovementUtil.withSpeedEffects(mc.player, MovementUtil.SPRINT_SPEED);
        double speed = held(base * multiplier.getValue() + (keepKnockback.isOn() ? push : 0), base);
        WholeMove.set(heading.x * speed, heading.z * speed);
    }

    // Holds a move at the cap but never below the plain move at normal game speed and never above
    // the move itself. Under a timer the whole game runs faster and the cap shrinks with it to stay
    // true in real seconds.
    private double held(double move, double plain) {
        if (!capSpeed.isOn()) {
            return move;
        }
        double top = cap.getValue() / (SharedConstants.TICKS_PER_SECOND * Timer.current());
        return Math.min(move, Math.max(top, plain / Timer.current()));
    }

    // The air movement stays vanilla. The keys push a hop the way they push a plain one. The cap
    // counts that push and a hop under the cap keeps it.
    private void hop() {
        if (!mc.player.onGround()) {
            return;
        }
        mc.player.jumpFromGround();
        jumped = mc.player.getDeltaMovement();
        double boost = hopBoost.getValue();
        boosted = new Vec3(jumped.x * boost, jumped.y, jumped.z * boost);
        mc.player.setDeltaMovement(boosted);
        hopTick = mc.player.tickCount;
    }

    // Called from LivingEntityMixin once the keys have pushed the move.
    public void holdHop(Vec3 unpushed) {
        if (mc.player.tickCount != hopTick || unpushed.x != boosted.x || unpushed.z != boosted.z) {
            return;
        }
        Vec3 velocity = mc.player.getDeltaMovement();
        double flat = velocity.horizontalDistance();
        double plain = Math.hypot(jumped.x + velocity.x - unpushed.x, jumped.z + velocity.z - unpushed.z);
        double kept = held(flat, plain);
        if (kept < flat) {
            mc.player.setDeltaMovement(velocity.x * kept / flat, velocity.y, velocity.z * kept / flat);
        }
    }
}
