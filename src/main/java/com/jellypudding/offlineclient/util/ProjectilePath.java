package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.LlamaSpit;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.entity.projectile.hurtingprojectile.AbstractHurtingProjectile;
import net.minecraft.world.entity.projectile.hurtingprojectile.WitherSkull;
import net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.AbstractWindCharge;
import net.minecraft.world.entity.projectile.throwableitemprojectile.AbstractThrownPotion;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrowableItemProjectile;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownExperienceBottle;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

public final class ProjectilePath {

    // A thrown item spawns this far below the eyes of whoever threw it.
    public static final double BELOW_EYES = 0.1;

    // How the game moves a projectile each tick. The order matters.
    public enum Motion {
        // Arrows and tridents. Move first and then slow down and fall.
        ARROW,
        // Thrown items. Fall and slow down first and then move.
        THROWN,
        // Fireballs and wind charges. Speed up along the heading and slow down first and then move.
        HURTING,
        // Fishing bobbers. Fall then move then slow down.
        BOBBER
    }

    // The push is the speed a fireball gains along its heading each tick.
    public record Launch(double power, double gravity, double airDrag, double waterDrag,
                         double pitchOffset, Motion motion, boolean stopsInWater, double push) {

        public Launch(double power, double gravity, double airDrag, double waterDrag,
                      double pitchOffset, Motion motion, boolean stopsInWater) {
            this(power, gravity, airDrag, waterDrag, pitchOffset, motion, stopsInWater, 0);
        }

        public Launch withPower(double newPower) {
            return new Launch(newPower, gravity, airDrag, waterDrag, pitchOffset, motion, stopsInWater, push);
        }

        private Launch weightless() {
            return new Launch(power, 0, airDrag, waterDrag, pitchOffset, motion, stopsInWater, push);
        }

        private Launch pushed(double newAirDrag, double newPush) {
            return new Launch(power, gravity, newAirDrag, waterDrag, pitchOffset, motion, stopsInWater, newPush);
        }
    }

    public static final Launch ARROW = new Launch(ProjectileUtil.BOW_SPEED, ProjectileUtil.ARROW_GRAVITY,
        ProjectileUtil.AIR_DRAG, 0.6, 0, Motion.ARROW, false);
    public static final Launch TRIDENT = new Launch(2.5, ProjectileUtil.ARROW_GRAVITY, ProjectileUtil.AIR_DRAG,
        0.99, 0, Motion.ARROW, false);
    public static final Launch THROWABLE = new Launch(1.5, ProjectileUtil.THROWN_GRAVITY, ProjectileUtil.AIR_DRAG,
        0.8, 0, Motion.THROWN, false);
    public static final Launch POTION = new Launch(0.5, 0.05, ProjectileUtil.AIR_DRAG,
        0.8, -20, Motion.THROWN, false);
    public static final Launch XP_BOTTLE = new Launch(0.7, 0.07, ProjectileUtil.AIR_DRAG,
        0.8, -20, Motion.THROWN, false);
    public static final Launch WIND_CHARGE = new Launch(1.5, 0, 1, 1, 0, Motion.HURTING, false);
    private static final Launch EXPLOSIVE = new Launch(0, 0, 0.95, 0.8, 0, Motion.HURTING, false);
    // A charged wither skull slows down harder in the air than other fireballs.
    private static final double DANGEROUS_SKULL_DRAG = 0.73;
    // Llama spit is gone the moment it touches water.
    private static final Launch LLAMA_SPIT = new Launch(0, 0.06, ProjectileUtil.AIR_DRAG, 0, 0, Motion.ARROW, true);

    // The landing is the block face the flight struck and stays null when it hit an entity or ran
    // out of steps.
    public record Path(List<Vec3> points, HitResult.Type type, List<Entity> hits, BlockHitResult landing) {

        // Where the projectile stood at the start of its last tick. A pearl
        // puts its thrower here and never on the face it struck.
        public Vec3 lastStart() {
            return points.size() < 2 ? points.getFirst() : points.get(points.size() - 2);
        }

        public int ticks() {
            return points.size() - 1;
        }
    }

    public record Aim(float yaw, float pitch) {
    }

    public record Arc(float pitch, double ticks) {
    }

    public record Shot(Vec3 pos, Vec3 velocity) {
    }

    private ProjectilePath() {
    }

    // Null for kinds that are not predicted.
    private static Launch launchFor(Projectile projectile) {
        if (projectile instanceof ThrownTrident) {
            return TRIDENT;
        }
        if (projectile instanceof AbstractArrow) {
            return ARROW;
        }
        if (projectile instanceof ThrownExperienceBottle) {
            return XP_BOTTLE;
        }
        if (projectile instanceof AbstractThrownPotion) {
            return POTION;
        }
        if (projectile instanceof ThrowableItemProjectile) {
            return THROWABLE;
        }
        if (projectile instanceof AbstractWindCharge) {
            return WIND_CHARGE;
        }
        // The server sends each fireball's push along with its motion.
        if (projectile instanceof AbstractHurtingProjectile hurting) {
            double drag = hurting instanceof WitherSkull skull && skull.isDangerous()
                ? DANGEROUS_SKULL_DRAG : EXPLOSIVE.airDrag();
            return EXPLOSIVE.pushed(drag, hurting.accelerationPower);
        }
        if (projectile instanceof LlamaSpit) {
            return LLAMA_SPIT;
        }
        return null;
    }

    public static Path of(Projectile projectile, int maxSteps) {
        Launch launch = launchFor(projectile);
        if (launch == null) {
            return null;
        }
        if (projectile.isNoGravity()) {
            launch = launch.weightless();
        }
        int pierce = projectile instanceof AbstractArrow arrow ? arrow.getPierceLevel() : 0;
        return fly(projectile, launch, new Shot(projectile.position(), projectile.getDeltaMovement()), pierce,
            maxSteps);
    }

    // The aim that carries a thrown item sideways to the target by the end of its
    // first tick with the rest of its speed pointing down. The height it reaches
    // follows from that. Null when the target lies beyond one tick of flight.
    public static Aim firstTickAim(Entity shooter, Launch launch, Vec3 target) {
        Vec3 start = shooter.position().add(0, shooter.getEyeHeight() - BELOW_EYES, 0);
        Vec3 movement = shooter.getKnownMovement();
        double x = (target.x - start.x) / launch.airDrag() - movement.x;
        double z = (target.z - start.z) / launch.airDrag() - movement.z;
        double power = launch.power();
        double sideways = x * x + z * z;
        if (sideways > power * power) {
            return null;
        }
        double y = -Math.sqrt(power * power - sideways);
        return new Aim((float) Math.toDegrees(Math.atan2(-x, z)), (float) Math.toDegrees(-Math.asin(y / power)));
    }

    // The lowest arc. Null when the point is out of reach.
    public static Arc arrowArc(Vec3 from, Vec3 to, double speed) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        double distance = Math.sqrt(dx * dx + dz * dz);
        double height = to.y - from.y;
        if (distance < 0.01) {
            return null;
        }

        double previous = -90;
        double previousHeight = Double.NaN;
        for (double angle = -89; angle <= 89; angle += 1) {
            double h = heightAt(angle, distance, speed);
            if (Double.isNaN(h)) {
                continue;
            }
            if (h >= height && !Double.isNaN(previousHeight)) {
                double low = previous;
                double high = angle;
                for (int i = 0; i < 16; i++) {
                    double mid = (low + high) / 2;
                    double midHeight = heightAt(mid, distance, speed);
                    if (Double.isNaN(midHeight) || midHeight < height) {
                        low = mid;
                    } else {
                        high = mid;
                    }
                }
                return arc(high, distance, speed);
            }
            if (h >= height) {
                return arc(angle, distance, speed);
            }
            previous = angle;
            previousHeight = h;
        }
        return null;
    }

    // The game counts pitch downwards. Ten degrees above the level is a pitch of minus ten.
    private static Arc arc(double angleDegrees, double distance, double speed) {
        return new Arc((float) -angleDegrees, flightTicks(angleDegrees, distance, speed));
    }

    // NaN if the arrow never gets there.
    private static double flightTicks(double angleDegrees, double distance, double speed) {
        double horizontal = speed * Math.cos(Math.toRadians(angleDegrees));
        double reach = horizontal / (1 - ProjectileUtil.AIR_DRAG);
        if (horizontal <= 0 || distance >= reach) {
            return Double.NaN;
        }
        return Math.log(1 - distance / reach) / Math.log(ProjectileUtil.AIR_DRAG);
    }

    // Height an arrow reaches at the horizontal distance using the closed
    // form of the per tick drag and gravity update.
    private static double heightAt(double angleDegrees, double distance, double speed) {
        double ticks = flightTicks(angleDegrees, distance, speed);
        if (Double.isNaN(ticks)) {
            return Double.NaN;
        }
        double vertical = speed * Math.sin(Math.toRadians(angleDegrees));
        double terminal = ProjectileUtil.ARROW_GRAVITY / (1 - ProjectileUtil.AIR_DRAG);
        double fallen = 1 - Math.pow(ProjectileUtil.AIR_DRAG, ticks);
        return (vertical + terminal) * fallen / (1 - ProjectileUtil.AIR_DRAG) - terminal * ticks;
    }

    public static Shot leaveHand(Entity shooter, Launch launch, Vec3 origin, double yaw, double pitch) {
        if (launch.motion() == Motion.BOBBER) {
            double sinYaw = Math.sin(Math.toRadians(-yaw) - Math.PI);
            double cosYaw = Math.cos(Math.toRadians(-yaw) - Math.PI);
            double cosPitch = -Math.cos(Math.toRadians(-pitch));
            double sinPitch = Math.sin(Math.toRadians(-pitch));
            Vec3 pos = origin.add(-sinYaw * 0.3, shooter.getEyeHeight(), -cosYaw * 0.3);
            Vec3 velocity = new Vec3(-sinYaw, Math.clamp(-(sinPitch / cosPitch), -5, 5), -cosYaw);
            return new Shot(pos, velocity.scale(0.6 / velocity.length() + 0.5));
        }
        Vec3 pos = origin.add(0, shooter.getEyeHeight() - BELOW_EYES, 0);
        double radYaw = Math.toRadians(yaw);
        double radPitch = Math.toRadians(pitch);
        double x = -Math.sin(radYaw) * Math.cos(radPitch);
        double y = -Math.sin(Math.toRadians(pitch + launch.pitchOffset()));
        double z = Math.cos(radYaw) * Math.cos(radPitch);
        Vec3 velocity = new Vec3(x, y, z).normalize().scale(launch.power());
        // The game adds the thrower's own movement to the projectile.
        Vec3 movement = shooter.getKnownMovement();
        velocity = velocity.add(movement.x, shooter.onGround() ? 0 : movement.y, movement.z);
        return new Shot(pos, velocity);
    }

    // A piercing arrow passes through that many entities before it stops. The thrower is left out
    // until the projectile has flown clear of them as the game does.
    public static Path fly(Entity shooter, Launch launch, Shot shot, int pierce, int maxSteps) {
        ClientLevel level = OfflineClient.MC.level;
        Entity thrower = shooter instanceof Projectile projectile && !projectile.leftOwner ? projectile.getOwner() : null;
        Vec3 pos = shot.pos();
        Vec3 velocity = shot.velocity();

        List<Vec3> points = new ArrayList<>();
        List<Entity> hits = new ArrayList<>();
        points.add(pos);
        HitResult.Type type = HitResult.Type.MISS;
        BlockHitResult landing = null;
        int minY = level.getMinY();
        int piercesLeft = pierce;

        for (int i = 0; i < maxSteps; i++) {
            Vec3 previous = pos;
            boolean inWater = level.getFluidState(BlockPos.containing(pos)).is(FluidTags.WATER);
            double drag = inWater ? launch.waterDrag() : launch.airDrag();

            Shot next = advance(launch, pos, velocity, drag);
            pos = next.pos();
            velocity = next.velocity();

            if (pos.y < minY) {
                points.add(pos);
                break;
            }

            BlockHitResult blockHit = level.clip(new ClipContext(previous, pos,
                ClipContext.Block.COLLIDER,
                launch.stopsInWater() ? ClipContext.Fluid.ANY : ClipContext.Fluid.NONE, shooter));
            Vec3 end = blockHit.getType() == HitResult.Type.MISS ? pos : blockHit.getLocation();
            // The game lets the projectile hit its thrower once its box grown by one stops touching them.
            if (thrower != null && !shooter.getBoundingBox().move(previous.subtract(shooter.position()))
                    .expandTowards(pos.subtract(previous)).inflate(1).intersects(thrower.getBoundingBox())) {
                thrower = null;
            }
            Entity ignored = thrower;

            boolean stopped = false;
            for (EntityHitResult entityHit : net.minecraft.world.entity.projectile.ProjectileUtil
                .getManyEntityHitResult(level, shooter, previous, end,
                new AABB(previous, end).inflate(1),
                entity -> entity != shooter && entity != ignored && !entity.isSpectator() && entity.isAlive()
                    && entity.isPickable(), false, false)) {
                if (hits.contains(entityHit.getEntity())) {
                    continue;
                }
                hits.add(entityHit.getEntity());
                if (piercesLeft <= 0) {
                    points.add(entityHit.getLocation());
                    type = HitResult.Type.ENTITY;
                    stopped = true;
                    break;
                }
                piercesLeft--;
            }
            if (stopped) {
                break;
            }
            if (blockHit.getType() != HitResult.Type.MISS) {
                points.add(blockHit.getLocation());
                type = HitResult.Type.BLOCK;
                landing = blockHit;
                break;
            }
            points.add(pos);
            if (velocity.lengthSqr() < 1.0E-6) {
                break;
            }
        }
        return new Path(points, type, hits, landing);
    }

    private static Shot advance(Launch launch, Vec3 pos, Vec3 velocity, double drag) {
        return switch (launch.motion()) {
            case ARROW -> new Shot(pos.add(velocity),
                velocity.scale(drag).subtract(0, launch.gravity(), 0));
            case THROWN -> {
                Vec3 moved = velocity.subtract(0, launch.gravity(), 0).scale(drag);
                yield new Shot(pos.add(moved), moved);
            }
            case HURTING -> {
                Vec3 pushed = velocity.add(velocity.normalize().scale(launch.push())).scale(drag);
                yield new Shot(pos.add(pushed), pushed);
            }
            case BOBBER -> {
                Vec3 fallen = velocity.subtract(0, launch.gravity(), 0);
                yield new Shot(pos.add(fallen), fallen.scale(drag));
            }
        };
    }
}
