package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.modules.misc.Timer;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.JumpCarry;
import com.jellypudding.offlineclient.util.MovementUtil;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.phys.Vec3;

// Vanilla carries a real jump further. Burst and Glide are staged speed
// exploits from older servers and a modern movement check pulls them back.
public final class LongJump extends Module {

    public enum Mode { VANILLA, BURST, GLIDE }

    // Average horizontal speed per tick of a normal sprint jump.
    private static final double BASE_SPEED = 0.35;

    private static final String TIMER_KEY = "longjump";

    // Walking pace on flat ground and the boost each Speed level adds to it.
    private static final double WALK_SPEED = 0.2873;
    private static final double SPEED_EFFECT_STEP = 0.2;

    // Burst lifts with a vanilla jump then bleeds a sliver of speed a tick.
    private static final double BURST_JUMP = 0.42;
    private static final double BURST_DECAY = 1.0 / 159;
    private static final double HOVER_PIN = -0.001;
    private static final double HOVER_REACH = 0.4;
    private static final double LANDED_FRACTION = 0.01;

    // Glide pace starts here and falls off each tick in the air to a floor.
    private static final double GLIDE_START = 0.4206;
    private static final double GLIDE_FALL_OFF = 0.0024;
    private static final double GLIDE_FLOOR = 0.237;
    private static final double GLIDE_PUSH = 3;
    // Ticks in the air before the descent is softened and for how long.
    private static final int GLIDE_SOFTEN_FROM = 6;
    private static final int GLIDE_SOFTEN_TICKS = 9;
    private static final double GLIDE_SOFTEN = 0.65;
    // Two ticks of a small step off the ground then a re jump.
    private static final int GLIDE_GROUND_TICKS = 2;
    private static final double GLIDE_STEP = 0.01;
    private static final double GLIDE_REJUMP_PUSH = 0.3;
    private static final double GLIDE_REJUMP = 0.424;

    private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
        "How the jump is stretched.", Mode.VANILLA)
        .describe(Mode.VANILLA, "A real jump carries you further along.")
        .describe(Mode.BURST, "A short run up then one huge hop. An old exploit that most servers pull back.")
        .describe(Mode.GLIDE, "Hops and glides along on a fixed speed curve. An old exploit that most servers pull back.");
    private final NumberSetting multiplier = new NumberSetting("Multiplier",
        "How much further a jump carries you.",
        3, 1, 10, 0.5, "x").min(0.1)
        .under(mode, Mode.VANILLA);
    private final NumberSetting burstStart = new NumberSetting("Burst start speed",
        "How many times walking pace the run up starts at.", 6, 0, 20, 0.5, "x").min(0)
        .under(mode, Mode.BURST);
    private final NumberSetting burstBoost = new NumberSetting("Burst boost",
        "How much the hop multiplies the run up speed by.", 2.149, 0, 20, 0.05, "x").min(0)
        .under(mode, Mode.BURST);
    private final BoolSetting onlyOnGround = new BoolSetting("Only on ground",
        "The burst only starts whilst you stand on the ground.", true)
        .under(mode, Mode.BURST);
    private final BoolSetting onJump = new BoolSetting("On jump",
        "Waits for the jump key instead of bursting as soon as you move.", false)
        .under(mode, Mode.BURST);
    private final NumberSetting glideMultiplier = new NumberSetting("Glide multiplier",
        "Scales every speed of the glide curve.", 1, 0, 5, 0.1, "x").min(0)
        .under(mode, Mode.GLIDE);
    private final NumberSetting timer = new NumberSetting("Timer",
        "Also speeds up the game whilst you move. 1 does nothing.", 1, 1, 3, 0.1, "x").min(1);
    private final BoolSetting autoDisable = new BoolSetting("Auto disable",
        "Turns the module off once you land after the jump.", true)
        .under(mode, Mode.BURST, Mode.GLIDE);
    private final BoolSetting stopOnLagback = new BoolSetting("Stop on lagback",
        "Ends the boost when the server teleports you back.", true);

    private final JumpCarry carry = new JumpCarry();

    // Burst stage and the speed it holds.
    private int stage;
    private double burstSpeed;
    private boolean airborne;

    // Glide ticks in the air and on the ground.
    private int airTicks;
    private int groundTicks;

    public LongJump() {
        super("LongJump", "Jump much further than normal.", Category.MOVEMENT);
        addSettings(mode, multiplier, burstStart, burstBoost, onlyOnGround, onJump,
            glideMultiplier, timer, autoDisable, stopOnLagback);
    }

    @Override
    public String getSuffix() {
        return mode.is(Mode.VANILLA) ? multiplier.getValueString() : mode.getValueString();
    }

    @Override
    protected void onEnable() {
        carry.stop();
        stage = 0;
        burstSpeed = 0;
        airborne = false;
        airTicks = 0;
        groundTicks = -GLIDE_GROUND_TICKS;
    }

    @Override
    protected void onDisable() {
        Timer.override(TIMER_KEY, 1f);
    }

    // Called from LocalPlayerMixin once the game has set the jump velocity.
    public void onJump() {
        if (!isEnabled() || !mode.is(Mode.VANILLA) || !inGame()
            || !JumpCarry.inPlainAir(mc.player)) {
            return;
        }
        carry.start(BASE_SPEED * multiplier.getValue());
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            return;
        }
        boolean moving = MovementUtil.inputDirection().lengthSqr() > 0;
        Timer.override(TIMER_KEY, moving ? timer.getFloat() : 1f);
        switch (mode.getValue()) {
            case VANILLA -> carry.tick();
            case BURST -> burstTick(moving);
            case GLIDE -> glideTick(moving);
        }
    }

    // Stage 0 sets off at a run. Stage 1 hops and multiplies the speed.
    // Every stage after lets a sliver go so the server sees a slowing player.
    private void burstTick(boolean moving) {
        if (stage != 0 && !mc.player.onGround() && autoDisable.isOn()) {
            airborne = true;
        }
        if (airborne && mc.player.getY() - Math.floor(mc.player.getY()) < LANDED_FRACTION) {
            disableAfterJump();
            return;
        }
        if (onlyOnGround.isOn() && !mc.player.onGround() && stage == 0) {
            return;
        }
        if (!moving || (onJump.isOn() && !mc.options.keyJump.isDown())
            || mc.player.isInLava() || mc.player.isInWater()) {
            return;
        }
        double lastDistance = Math.hypot(mc.player.getX() - mc.player.xo,
            mc.player.getZ() - mc.player.zo);
        Vec3 velocity = mc.player.getDeltaMovement();
        double vy = velocity.y;
        switch (stage) {
            case 0 -> burstSpeed = walkSpeed() * burstStart.getValue();
            case 1 -> {
                vy = BURST_JUMP;
                burstSpeed *= burstBoost.getValue();
            }
            case 2 -> burstSpeed = walkSpeed();
            default -> burstSpeed = lastDistance - lastDistance * BURST_DECAY;
        }
        burstSpeed = Math.max(walkSpeed(), burstSpeed);
        Vec3 heading = MovementUtil.inputDirection().scale(burstSpeed);
        // Hanging just above a block is pinned down onto it.
        if (!mc.player.verticalCollision && !fits(vy) && !fits(-HOVER_REACH)) {
            vy = HOVER_PIN;
        }
        mc.player.setDeltaMovement(heading.x, vy, heading.z);
        stage++;
    }

    private boolean fits(double dy) {
        return mc.level.noCollision(mc.player, mc.player.getBoundingBox().move(0, dy, 0));
    }

    // Walking pace with the Speed effect counted in.
    private double walkSpeed() {
        MobEffectInstance speed = mc.player.getEffect(MobEffects.SPEED);
        if (speed == null) {
            return WALK_SPEED;
        }
        return WALK_SPEED * (1 + SPEED_EFFECT_STEP * (speed.getAmplifier() + 1));
    }

    // In the air the pace follows a falling curve and the descent is softened.
    // On the ground two small steps lead into a fresh hop.
    private void glideTick(boolean moving) {
        if (!moving) {
            return;
        }
        double scale = glideMultiplier.getValue();
        Vec3 heading = MovementUtil.inputDirection();
        Vec3 velocity = mc.player.getDeltaMovement();
        if (!mc.player.verticalCollision && !mc.player.onGround()) {
            airborne = true;
            airTicks++;
            groundTicks = -GLIDE_GROUND_TICKS;
            double vy = softenedDescent(velocity.y) * scale;
            double pace = Math.max(GLIDE_FLOOR, GLIDE_START - GLIDE_FALL_OFF * (airTicks - 1))
                * GLIDE_PUSH * scale;
            mc.player.setDeltaMovement(heading.x * pace, vy, heading.z * pace);
            return;
        }
        if (autoDisable.isOn() && airborne) {
            disableAfterJump();
            return;
        }
        airTicks = 0;
        groundTicks++;
        if (groundTicks <= GLIDE_GROUND_TICKS) {
            double pace = GLIDE_STEP * scale;
            mc.player.setDeltaMovement(heading.x * pace, velocity.y, heading.z * pace);
        } else {
            double pace = GLIDE_REJUMP_PUSH * scale;
            mc.player.setDeltaMovement(heading.x * pace, GLIDE_REJUMP, heading.z * pace);
        }
    }

    // The fall is held back hardest for a few ticks after the peak of the hop
    // and a little at every speed a real fall passes through.
    private double softenedDescent(double vy) {
        int sinceSoften = airTicks - GLIDE_SOFTEN_FROM;
        if (sinceSoften >= 0 && sinceSoften < GLIDE_SOFTEN_TICKS) {
            vy *= GLIDE_SOFTEN;
        }
        if (vy < -0.2 && vy > -0.24) {
            return vy * 0.7;
        }
        if (vy < -0.25 && vy > -0.32) {
            return vy * 0.8;
        }
        if (vy < -0.35 && vy > -0.8) {
            return vy * 0.98;
        }
        return vy;
    }

    private void disableAfterJump() {
        airborne = false;
        setEnabled(false);
        ChatUtil.message("LongJump turned off after the jump.");
    }

    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        if (stopOnLagback.isOn() && event.getPacket() instanceof ClientboundPlayerPositionPacket) {
            carry.stop();
            stage = 0;
            airTicks = 0;
        }
    }
}
