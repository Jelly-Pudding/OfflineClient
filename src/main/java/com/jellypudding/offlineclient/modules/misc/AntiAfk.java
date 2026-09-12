package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.modules.movement.Jesus;
import com.jellypudding.offlineclient.path.PathFinder;
import com.jellypudding.offlineclient.path.PathGoal;
import com.jellypudding.offlineclient.path.PathWalker;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.RotationManager;
import com.jellypudding.offlineclient.util.RotationPriority;
import com.jellypudding.offlineclient.util.TextLines;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.ThreadLocalRandom;

public final class AntiAfk extends Module {

    // Ticks between each change of strafe direction.
    private static final int STRAFE_TICKS = 20;

    // How close a plain walk gets before it counts as there.
    private static final double WALK_ARRIVED = 0.5;

    private static final int TICKS_PER_SECOND = 20;

    public enum Turn { NONE, NUDGE, SPIN, SPIN_VIEW }

    private final NumberSetting interval = new NumberSetting("Interval",
        "Average seconds between hops and swings and crouches. Each one fires at a random moment.",
        5, 1, 60, 1, "s").min(1);
    private final BoolSetting useAi = new BoolSetting("Use AI",
        "Finds a path to each random spot instead of walking straight at it.", false);
    private final NumberSetting aiRange = new NumberSetting("AI range",
        "How far from where you started the random goals may be.", 16, 1, 64, 1, " blocks")
        .min(1).max(128).under(useAi);
    private final NumberSetting walkRange = new NumberSetting("Walk range",
        "How far from where you started the plain walks go. One is a shuffle on the spot.",
        1, 1, 64, 1, " blocks").min(1).max(128).unless(useAi);
    private final NumberSetting waitTime = new NumberSetting("Wait time",
        "Seconds of standing still between one walk and the next.", 2.5, 0, 60, 0.5, "s").min(0);
    private final NumberSetting waitSpread = new NumberSetting("Wait spread",
        "Random seconds taken off or added to each wait.", 0.5, 0, 60, 0.5, "s").min(0);
    private final BoolSetting showWait = new BoolSetting("Show wait time",
        "Shows the seconds left until the next walk after the name.", true);
    private final BoolSetting jump = new BoolSetting("Jump",
        "Hops on the spot.", true);
    private final BoolSetting swing = new BoolSetting("Swing",
        "Swings your arm.", false);
    private final BoolSetting sneak = new BoolSetting("Sneak",
        "Crouches for a moment and stands up again.", false);
    private final NumberSetting sneakTime = new NumberSetting("Sneak time",
        "How many ticks each crouch lasts.", 5, 1, 20, 1, " ticks").min(1)
        .under(sneak);
    private final BoolSetting strafe = new BoolSetting("Strafe",
        "Steps left and right on the spot.", false);
    private final EnumSetting<Turn> turn = new EnumSetting<>("Turn",
        "How your head moves.", Turn.SPIN)
        .describe(Turn.NONE, "Your head stays where it is.")
        .describe(Turn.NUDGE, "Turns your view a little every now and then.")
        .describe(Turn.SPIN, "Spins you round for everyone else whilst your own view stays still.")
        .describe(Turn.SPIN_VIEW, "Spins your own view round and round. The plain walk stops whilst this is on.");
    private final NumberSetting speed = new NumberSetting("Speed",
        "Degrees turned each tick.", 7, 1, 45, 1, " degrees").min(1).max(180)
        .under(turn, Turn.SPIN, Turn.SPIN_VIEW);
    private final NumberSetting pitch = new NumberSetting("Pitch",
        "How far up or down the spin looks for everyone else.", 0, -90, 90, 1, " degrees")
        .under(turn, Turn.SPIN);
    private final BoolSetting sendMessages = new BoolSetting("Send messages",
        "Sends chat lines on a timer.", false);
    private final NumberSetting messageDelay = new NumberSetting("Message delay",
        "Seconds between messages.", 15, 1, 30, 1, "s").min(1).max(600)
        .under(sendMessages);
    private final TextLines lines = new TextLines("Messages",
        "How many lines to rotate through.", "I am still here", "Just standing about")
        .under(sendMessages);

    private int sneakTimer;
    private boolean crouching;
    private int strafeTimer;
    private boolean strafeLeft;
    private boolean strafing;
    private float spinYaw;
    private int messageTimer;

    private final PathFinder finder = new PathFinder();
    // An idle wander should not burn hunger.
    private final PathWalker walker = new PathWalker().sprint(false);
    private BlockPos home;
    private int waitTicks;
    // Where the plain walk is heading or null whilst it rests.
    private Vec3 walkTarget;
    private boolean walking;
    private boolean surfacing;
    // Creative flight as it was on enable. A wander must not drop a flying player.
    private boolean creativeFlight;

    public AntiAfk() {
        super("AntiAFK", "Keeps you from being kicked for idling.", Category.MISC);
        addSettings(interval, useAi, aiRange, walkRange, waitTime, waitSpread, showWait, jump, swing,
            sneak, sneakTime, strafe, turn, speed, pitch, sendMessages, messageDelay);
        addSettings(lines.settings());
        searchTags("afk", "idle", "away", "wander");
    }

    @Override
    public String getSuffix() {
        if (!showWait.isOn() || wandering() || waitTicks <= 0) {
            return null;
        }
        return String.format("%.1fs", waitTicks / (double) TICKS_PER_SECOND);
    }

    @Override
    protected void onEnable() {
        sneakTimer = 0;
        strafeTimer = 0;
        strafeLeft = false;
        messageTimer = messageDelay.getInt() * TICKS_PER_SECOND;
        waitTicks = nextWait();
        home = null;
        walkTarget = null;
        lines.restart();
        if (inGame()) {
            spinYaw = mc.player.getYRot();
            home = mc.player.blockPosition();
            creativeFlight = mc.player.getAbilities().flying;
        }
        if (sendMessages.isOn() && lines.isEmpty()) {
            ChatUtil.error("No message lines are filled in so messages are switched off.");
            sendMessages.setValue(false);
        }
    }

    @Override
    protected void onDisable() {
        stopStrafe();
        standUp();
        stopWander();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            return;
        }
        if (mc.player.isDeadOrDying()) {
            setEnabled(false);
            return;
        }
        if (creativeFlight && mc.player.getAbilities().mayfly) {
            mc.player.getAbilities().flying = true;
        }
        if (jump.isOn() && mc.player.onGround() && chance()) {
            mc.player.jumpFromGround();
        }
        if (swing.isOn() && chance()) {
            mc.player.swing(InteractionHand.MAIN_HAND);
        }
        tickWander();
        tickSneak();
        tickStrafe();
        tickTurn();
        tickMessages();
    }

    // Either walk waits between goals. The wait is the same for both.
    private void tickWander() {
        if (home == null) {
            home = mc.player.blockPosition();
        }
        if (useAi.isOn()) {
            tickPathWander();
        } else {
            tickPlainWalk();
        }
    }

    // Walks to a random spot round the one the module was switched on at.
    // Falling and diving are off. A wander cannot end somewhere nasty.
    private void tickPathWander() {
        walkTarget = null;
        if (surface()) {
            return;
        }
        PathFinder.Result result = finder.poll();
        if (result != null) {
            walker.follow(result.nodes());
        }
        if (finder.busy()) {
            return;
        }
        if (walker.arrived() || walker.lost()) {
            walker.releaseKeys();
            walking = false;
            if (waitTicks-- > 0) {
                return;
            }
            waitTicks = nextWait();
            walking = true;
            pickGoal();
            return;
        }
        // The Turn setting owns the view. The walker steers with the keys.
        walker.turn(PathWalker.Turn.NONE);
        walker.tick(finder.liveRules());
    }

    private void pickGoal() {
        int reach = aiRange.getInt();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        BlockPos spot = home.offset(random.nextInt(-reach, reach + 1), 0,
            random.nextInt(-reach, reach + 1));
        finder.maxFall(0).swim(false)
            .search(PathFinder.standingAt(mc.player), new PathGoal.Around(spot, 2));
    }

    // Under water the wander holds jump until the head is out. Jesus keeps you up anyway.
    private boolean surface() {
        if (mc.player.isUnderWater() && !Modules.enabled(Jesus.class)) {
            walker.releaseKeys();
            InputUtil.hold(mc.options.keyJump);
            surfacing = true;
            return true;
        }
        if (surfacing) {
            InputUtil.release(mc.options.keyJump);
            surfacing = false;
        }
        return false;
    }

    // Turns to a random spot and holds forward until it is reached.
    private void tickPlainWalk() {
        finder.cancel();
        if (turn.is(Turn.SPIN_VIEW)) {
            stopPlainWalk();
            return;
        }
        if (walkTarget == null) {
            if (waitTicks-- > 0) {
                return;
            }
            waitTicks = nextWait();
            int reach = walkRange.getInt();
            ThreadLocalRandom random = ThreadLocalRandom.current();
            walkTarget = new Vec3(home.getX() + 0.5 + random.nextInt(-reach, reach + 1),
                mc.player.getY(), home.getZ() + 0.5 + random.nextInt(-reach, reach + 1));
        }
        double dx = walkTarget.x - mc.player.getX();
        double dz = walkTarget.z - mc.player.getZ();
        if (dx * dx + dz * dz <= WALK_ARRIVED * WALK_ARRIVED) {
            stopPlainWalk();
            return;
        }
        walking = true;
        mc.player.setYRot(RotationManager.yawTo(walkTarget));
        InputUtil.hold(mc.options.keyUp);
        if (mc.player.isInWater()) {
            InputUtil.hold(mc.options.keyJump);
        } else {
            InputUtil.release(mc.options.keyJump);
        }
    }

    private void stopPlainWalk() {
        if (walking) {
            InputUtil.release(mc.options.keyUp);
            InputUtil.release(mc.options.keyJump);
        }
        walking = false;
        walkTarget = null;
    }

    private void stopWander() {
        finder.cancel();
        walker.stop();
        stopPlainWalk();
        if (surfacing) {
            InputUtil.release(mc.options.keyJump);
            surfacing = false;
        }
    }

    // Ticks to rest before the next walk. The spread keeps the rhythm uneven.
    private int nextWait() {
        double spread = waitSpread.getValue();
        double seconds = waitTime.getValue()
            + (spread > 0 ? ThreadLocalRandom.current().nextDouble(-spread, spread) : 0);
        return (int) Math.round(Math.max(0, seconds) * TICKS_PER_SECOND);
    }

    // True whilst a walk of either kind is driving the movement keys.
    private boolean wandering() {
        return walking || surfacing;
    }

    // Holds the crouch for the set ticks then waits a random moment before the next.
    private void tickSneak() {
        if (!sneak.isOn() || wandering()) {
            standUp();
            return;
        }
        if (sneakTimer < sneakTime.getInt()) {
            sneakTimer++;
            InputUtil.hold(mc.options.keyShift);
            crouching = true;
            return;
        }
        standUp();
        if (chance()) {
            sneakTimer = 0;
        }
    }

    private void standUp() {
        if (crouching) {
            InputUtil.release(mc.options.keyShift);
            crouching = false;
        }
    }

    private void tickStrafe() {
        if (!strafe.isOn() || wandering()) {
            stopStrafe();
            return;
        }
        if (strafeTimer-- > 0) {
            return;
        }
        strafeTimer = STRAFE_TICKS;
        strafeLeft = !strafeLeft;
        strafing = true;
        if (strafeLeft) {
            InputUtil.release(mc.options.keyRight);
            InputUtil.hold(mc.options.keyLeft);
        } else {
            InputUtil.release(mc.options.keyLeft);
            InputUtil.hold(mc.options.keyRight);
        }
    }

    private void stopStrafe() {
        if (strafing) {
            InputUtil.release(mc.options.keyLeft);
            InputUtil.release(mc.options.keyRight);
            strafing = false;
        }
        strafeTimer = 0;
    }

    private void tickTurn() {
        switch (turn.getValue()) {
            case NONE -> {
            }
            case NUDGE -> {
                if (chance()) {
                    // The vanilla turn keeps the last frame in step. A raw set snaps.
                    float delta = ThreadLocalRandom.current().nextFloat(-15f, 15f);
                    mc.player.turn(delta / InputUtil.MOUSE_TURN, 0);
                }
            }
            case SPIN -> {
                spinYaw = Mth.wrapDegrees(spinYaw + speed.getFloat());
                RotationManager.requestExact(spinYaw, pitch.getFloat(), RotationPriority.IDLE);
            }
            case SPIN_VIEW -> mc.player.turn(speed.getFloat() / InputUtil.MOUSE_TURN, 0);
        }
    }

    private void tickMessages() {
        if (!sendMessages.isOn() || mc.getConnection() == null) {
            return;
        }
        if (messageTimer-- > 0) {
            return;
        }
        messageTimer = messageDelay.getInt() * TICKS_PER_SECOND;
        String text = lines.pick();
        if (text == null) {
            return;
        }
        if (text.startsWith("/")) {
            mc.getConnection().sendCommand(text.substring(1));
        } else {
            mc.getConnection().sendChat(text);
        }
    }

    // Fires once every interval on average.
    private boolean chance() {
        return ThreadLocalRandom.current().nextInt(interval.getInt() * TICKS_PER_SECOND) == 0;
    }
}
