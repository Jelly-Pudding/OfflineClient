package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.entity.projectile.hurtingprojectile.AbstractHurtingProjectile;
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

// Steps a projectile forward with the same launch speed and gravity and drag as the
// game until it lands. Trajectories draws the arcs and PearlTracker the pearls.
public final class ProjectilePath {

    // A thrown item spawns this far below the eyes of whoever threw it.
    private static final double BELOW_EYES = 0.1;

    // How the game moves a projectile each tick. The order matters.
    public enum Motion {
        // Arrows and tridents. Move first and then slow down and fall.
        ARROW,
        // Thrown items. Fall and slow down first and then move.
        THROWN,
        // Fireballs and wind charges. Fall then move then slow down.
        HURTING,
        // Fishing bobbers. Fall then move then slow down.
        BOBBER
    }

    public record Launch(double power, double gravity, double airDrag, double waterDrag,
                         double pitchOffset, Motion motion, boolean stopsInWater) {

        public Launch withPower(double newPower) {
            return new Launch(newPower, gravity, airDrag, waterDrag, pitchOffset, motion, stopsInWater);
        }

        private Launch weightless() {
            return new Launch(power, 0, airDrag, waterDrag, pitchOffset, motion, stopsInWater);
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

    // The ticks of a flight one after another and how it ended. The landing is the block
    // face it struck and stays null when it hit an entity or ran out of steps.
    public record Path(List<Vec3> points, HitResult.Type type, List<Entity> hits, BlockHitResult landing) {

        // Where the projectile stood at the start of its last tick. A pearl
        // puts its thrower here and never on the face it struck.
        public Vec3 lastStart() {
            return points.size() < 2 ? points.getFirst() : points.get(points.size() - 2);
        }

        // Ticks spent in the air before the flight ended.
        public int ticks() {
            return points.size() - 1;
        }
    }

    // A heading and a pitch in degrees.
    public record Aim(float yaw, float pitch) {
    }

    // Where a projectile is at one moment in its flight.
    public record Shot(Vec3 pos, Vec3 velocity) {
    }

    private ProjectilePath() {
    }

    // The physics of a projectile already in the air. Null for kinds that are not predicted.
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
        if (projectile instanceof AbstractHurtingProjectile) {
            return EXPLOSIVE;
        }
        return null;
    }

    // The rest of the flight of a projectile in the air. Null for kinds that are not predicted.
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

    // The position and speed a projectile starts with when loosed at this yaw and pitch.
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

    // Steps the projectile forward until it lands or the step budget runs out.
    // A piercing arrow passes through that many entities before it stops. The thrower is
    // left out until the projectile has flown clear of them as the game does.
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

    // One tick of motion. Each projectile applies drag and gravity in its own order.
    private static Shot advance(Launch launch, Vec3 pos, Vec3 velocity, double drag) {
        return switch (launch.motion()) {
            case ARROW -> new Shot(pos.add(velocity),
                velocity.scale(drag).subtract(0, launch.gravity(), 0));
            case THROWN -> {
                Vec3 moved = velocity.subtract(0, launch.gravity(), 0).scale(drag);
                yield new Shot(pos.add(moved), moved);
            }
            case HURTING, BOBBER -> {
                Vec3 fallen = velocity.subtract(0, launch.gravity(), 0);
                yield new Shot(pos.add(fallen), fallen.scale(drag));
            }
        };
    }
}
