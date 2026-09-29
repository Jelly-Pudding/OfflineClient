package com.jellypudding.offlineclient.util;

import net.minecraft.SharedConstants;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.camel.Camel;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.animal.equine.Llama;
import net.minecraft.world.entity.animal.happyghast.HappyGhast;
import net.minecraft.world.entity.animal.nautilus.AbstractNautilus;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.monster.Strider;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// What a rider gets out of a mount. Worked out from the attributes the server sends every
// client the way the game moves a ridden mount. Speeds are the steady top speed on flat
// ground in blocks a second and jumps are the height a full charge reaches.
public final class Mounts {

    // What a figure measures.
    public enum Measure { HEALTH, SPEED, JUMP, STRENGTH }

    // One figure with where it sits from the worst wild horse at nought to the best at one.
    public record Figure(Measure measure, String value, float share) {
    }

    // The forward key after the game trims it.
    private static final double RIDER_INPUT = 0.98;

    // Ordinary ground with the drag across and down before a mount's own modifiers.
    private static final double GROUND_FRICTION = 0.6;
    private static final double AIR_DRAG = 0.91;
    private static final double FALL_DRAG = 0.98;
    // Ground slicker than ordinary scales the push by this over the friction cubed.
    private static final double GRIP = 0.21600002;

    // Pigs and warm striders walk at these shares of their speed.
    private static final double PIG_SHARE = 0.225;
    private static final double STRIDER_SHARE = 0.55;

    // A camel runs this much faster whilst its rider sprints. Its dash lifts it this many
    // times its jump strength.
    private static final double CAMEL_SPRINT = 0.1;
    private static final double CAMEL_DASH_LIFT = 1.4285;

    // A ridden happy ghast pushes this many times its flying speed at five thirds of it.
    private static final double GHAST_PUSH = 3.9;
    private static final double GHAST_SPEED = 5.0 / 3.0;
    private static final double GHAST_DRAG = 0.91;

    // A ridden nautilus swims at this share of its speed and keeps this much of it each tick.
    private static final double NAUTILUS_SHARE = 0.0325;
    private static final double NAUTILUS_DRAG = 0.9;

    // A jump still climbing after this many ticks is left there.
    private static final int MAX_RISE_TICKS = 200;

    // The worst and the best a wild horse spawns with. Breeding never goes past them.
    private static final double WORST_SPEED = 0.1125;
    private static final double BEST_SPEED = 0.3375;
    private static final double WORST_JUMP = 0.4;
    private static final double BEST_JUMP = 1;
    private static final double WORST_HEALTH = 15;
    private static final double BEST_HEALTH = 30;
    private static final double WORST_STRENGTH = 1;
    private static final double BEST_STRENGTH = 5;

    private static final double DEFAULT_GRAVITY = Attributes.GRAVITY.value().getDefaultValue();
    private static final double SLOWEST = steady(RIDER_INPUT * WORST_SPEED, GROUND_FRICTION * AIR_DRAG);
    private static final double FASTEST = steady(RIDER_INPUT * BEST_SPEED, GROUND_FRICTION * AIR_DRAG);
    private static final double LOWEST = rise(WORST_JUMP, DEFAULT_GRAVITY, FALL_DRAG);
    private static final double HIGHEST = rise(BEST_JUMP, DEFAULT_GRAVITY, FALL_DRAG);

    private Mounts() {
    }

    public static boolean isMount(Entity entity) {
        return entity instanceof AbstractHorse || entity instanceof Pig || entity instanceof Strider
            || entity instanceof HappyGhast || entity instanceof AbstractNautilus;
    }

    // Full health then top speed and jump height and a llama's strength. A figure the
    // mount does not have is left out. The value carries its unit.
    public static List<Figure> figures(LivingEntity mount) {
        List<Figure> figures = new ArrayList<>(Measure.values().length);
        float maxHealth = mount.getMaxHealth();
        figures.add(new Figure(Measure.HEALTH, String.format(Locale.ROOT, "%.0f", maxHealth),
            healthShare(maxHealth)));
        double speed = topSpeed(mount);
        if (!Double.isNaN(speed)) {
            figures.add(new Figure(Measure.SPEED, String.format(Locale.ROOT, "%.1f m/s", speed), speedShare(speed)));
        }
        double jump = jumpHeight(mount);
        if (!Double.isNaN(jump)) {
            figures.add(new Figure(Measure.JUMP, String.format(Locale.ROOT, "%.1f m", jump), jumpShare(jump)));
        }
        if (mount instanceof Llama llama) {
            figures.add(new Figure(Measure.STRENGTH, String.valueOf(llama.getStrength()),
                strengthShare(llama.getStrength())));
        }
        return figures;
    }

    // Blocks a second at full speed. NaN for a mount nobody can steer.
    private static double topSpeed(LivingEntity mount) {
        double speed = mount.getAttributeValue(Attributes.MOVEMENT_SPEED);
        return switch (mount) {
            case Llama _ -> Double.NaN;
            case Camel _ -> overGround(mount, RIDER_INPUT, speed + CAMEL_SPRINT);
            case AbstractHorse _ -> overGround(mount, RIDER_INPUT, speed);
            case Pig _ -> overGround(mount, 1, speed * PIG_SHARE);
            case Strider _ -> overGround(mount, 1, speed * STRIDER_SHARE);
            case HappyGhast _ -> flying(mount.getAttributeValue(Attributes.FLYING_SPEED));
            case AbstractNautilus _ -> steady(speed * NAUTILUS_SHARE, NAUTILUS_DRAG);
            default -> Double.NaN;
        };
    }

    // Blocks a full charge lifts the mount. NaN for one that cannot jump on command.
    private static double jumpHeight(LivingEntity mount) {
        if (!(mount instanceof AbstractHorse) || mount instanceof Llama) {
            return Double.NaN;
        }
        double gravity = mount.getGravity();
        double drag = modified(FALL_DRAG, mount.getAttributeValue(Attributes.AIR_DRAG_MODIFIER));
        double power = mount.getAttributeValue(Attributes.JUMP_STRENGTH) + mount.getJumpBoostPower();
        // A camel's dash adds to the small fall the ground holds it against. A horse's jump replaces it.
        double lift = mount instanceof Camel ? CAMEL_DASH_LIFT * power - gravity * drag : power;
        return rise(lift, gravity, drag);
    }

    // Where a figure sits from the worst wild horse at nought to the best at one.
    private static float speedShare(double blocksPerSecond) {
        return share(blocksPerSecond, SLOWEST, FASTEST);
    }

    private static float jumpShare(double height) {
        return share(height, LOWEST, HIGHEST);
    }

    private static float healthShare(double maxHealth) {
        return share(maxHealth, WORST_HEALTH, BEST_HEALTH);
    }

    private static float strengthShare(int strength) {
        return share(strength, WORST_STRENGTH, BEST_STRENGTH);
    }

    private static float share(double value, double worst, double best) {
        return (float) Math.clamp((value - worst) / (best - worst), 0, 1);
    }

    // The push a mount gets on ordinary ground under its own friction and drag modifiers.
    private static double overGround(LivingEntity mount, double input, double speed) {
        double friction = modified(GROUND_FRICTION, mount.getAttributeValue(Attributes.FRICTION_MODIFIER));
        double drag = modified(AIR_DRAG, mount.getAttributeValue(Attributes.AIR_DRAG_MODIFIER));
        double grip = friction > GROUND_FRICTION ? GRIP / (friction * friction * friction) : 1;
        return steady(input * speed * grip, friction * drag);
    }

    // A long push makes the input longer than one and the game cuts it back to one.
    private static double flying(double flyingSpeed) {
        return steady(Math.min(GHAST_PUSH * flyingSpeed, 1) * flyingSpeed * GHAST_SPEED, GHAST_DRAG);
    }

    // The game's modifier rule. One keeps the base value and nought takes all its loss away.
    private static double modified(double base, double modifier) {
        return Math.clamp(1 - (1 - base) * modifier, 0, 1);
    }

    // A push each tick that keeps the given share of the speed from tick to tick levels off here.
    private static double steady(double push, double kept) {
        return push / (1 - kept) * SharedConstants.TICKS_PER_SECOND;
    }

    // Each tick moves by the speed before gravity and drag take their share.
    private static double rise(double speed, double gravity, double drag) {
        double height = 0;
        for (int tick = 0; tick < MAX_RISE_TICKS && speed > 0; tick++) {
            height += speed;
            speed = (speed - gravity) * drag;
        }
        return height;
    }
}
