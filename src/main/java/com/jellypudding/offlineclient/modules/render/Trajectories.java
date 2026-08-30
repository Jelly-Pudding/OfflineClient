package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.EggItem;
import net.minecraft.world.item.EnderpearlItem;
import net.minecraft.world.item.ExperienceBottleItem;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SnowballItem;
import net.minecraft.world.item.ThrowablePotionItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.WindChargeItem;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

// Uses the same launch speed and gravity and drag as the real projectile.
public final class Trajectories extends Module {

    // How the game moves a projectile each tick. The order matters.
    private enum Motion {
        // Arrows and tridents. Move first and then slow down and fall.
        ARROW,
        // Thrown items. Fall and slow down first and then move.
        THROWN,
        // Fishing bobbers. Fall then move then slow down.
        BOBBER
    }

    private record Launch(double power, double gravity, double airDrag, double waterDrag,
                          double pitchOffset, Motion motion, boolean stopsInWater) {
    }

    private static final Launch ARROW = new Launch(3, 0.05, 0.99, 0.6, 0, Motion.ARROW, false);
    private static final Launch CROSSBOW_ARROW = new Launch(3.15, 0.05, 0.99, 0.6, 0, Motion.ARROW, false);
    private static final Launch FIREWORK = new Launch(1.6, 0, 1, 1, 0, Motion.ARROW, false);
    private static final Launch TRIDENT = new Launch(2.5, 0.05, 0.99, 0.99, 0, Motion.ARROW, false);
    private static final Launch THROWABLE = new Launch(1.5, 0.03, 0.99, 0.8, 0, Motion.THROWN, false);
    private static final Launch POTION = new Launch(0.5, 0.05, 0.99, 0.8, -20, Motion.THROWN, false);
    private static final Launch XP_BOTTLE = new Launch(0.7, 0.07, 0.99, 0.8, -20, Motion.THROWN, false);
    private static final Launch WIND_CHARGE = new Launch(1.5, 0, 1, 1, 0, Motion.THROWN, false);
    private static final Launch BOBBER = new Launch(0, 0.03, 0.92, 0, 0, Motion.BOBBER, true);

    private static final int COLOR_MISS = 0xFFB0B0B0;
    private static final int COLOR_BLOCK = 0xFF40FF60;
    private static final int COLOR_ENTITY = 0xFFFF4040;

    private record Path(List<Vec3> points, HitResult.Type type, Entity hit) {
    }

    private final BoolSetting otherPlayers = new BoolSetting("Other players",
        "Also show where other players are aiming.", true);
    // Other players further off than this get no arc.
    private static final double OTHER_RANGE_SQ = 64 * 64;

    private final BoolSetting hitBox = new BoolSetting("Hit box",
        "Draw a box where the projectile lands.", true);
    private final NumberSetting steps = new NumberSetting("Steps",
        "How many ticks of flight to predict.", 200, 20, 500, 10).min(1).max(2000);

    public Trajectories() {
        super("Trajectories", "Shows the path a thrown or shot item will take.", Category.RENDER);
        addSettings(otherPlayers, hitBox, steps);
        searchTags("bow", "arrow", "aim");
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!inGame()) {
            return;
        }
        float partialTicks = event.getPartialTicks();
        draw(event.getBatch(), mc.player, partialTicks);
        if (!otherPlayers.isOn()) {
            return;
        }
        for (Player player : mc.level.players()) {
            if (player == mc.player || player.isSpectator() || player.distanceToSqr(mc.player) > OTHER_RANGE_SQ) {
                continue;
            }
            draw(event.getBatch(), player, partialTicks);
        }
    }

    private void draw(DrawBatch batch, Player player, float partialTicks) {
        ItemStack stack = player.getMainHandItem();
        Launch launch = launchFor(player, stack);
        if (launch == null) {
            stack = player.getOffhandItem();
            launch = launchFor(player, stack);
        }
        if (launch == null) {
            return;
        }
        Path path = simulate(player, launch, partialTicks);
        if (path.points().size() < 2) {
            return;
        }
        int color = switch (path.type()) {
            case BLOCK -> COLOR_BLOCK;
            case ENTITY -> COLOR_ENTITY;
            default -> COLOR_MISS;
        };
        List<Vec3> points = path.points();
        for (int i = 1; i < points.size(); i++) {
            batch.line(points.get(i - 1), points.get(i), color, true);
        }
        Vec3 end = points.getLast();
        if (hitBox.isOn()) {
            AABB box = new AABB(end.subtract(0.25, 0.25, 0.25), end.add(0.25, 0.25, 0.25));
            batch.outlineBox(box, color, true);
            batch.solidBox(box, ColorUtil.withAlpha(color, 50), true);
        }
        if (path.hit() != null) {
            batch.outlineBox(EntityUtil.lerpedBox(path.hit(), partialTicks), COLOR_ENTITY, true);
        }
    }

    // Null if the item cannot be thrown or shot.
    private static Launch launchFor(Player player, ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        Item item = stack.getItem();
        if (item instanceof BowItem) {
            double charge = 1;
            if (player.isUsingItem() && player.getUseItem() == stack) {
                charge = BowItem.getPowerForTime(player.getTicksUsingItem());
                // A bow this early in the draw does not fire.
                if (charge < 0.1) {
                    return null;
                }
            }
            return new Launch(charge * ARROW.power(), ARROW.gravity(), ARROW.airDrag(),
                ARROW.waterDrag(), 0, Motion.ARROW, false);
        }
        if (item instanceof CrossbowItem) {
            if (!CrossbowItem.isCharged(stack)) {
                return null;
            }
            ChargedProjectiles loaded = stack.get(DataComponents.CHARGED_PROJECTILES);
            if (loaded != null && loaded.contains(Items.FIREWORK_ROCKET)) {
                return FIREWORK;
            }
            return CROSSBOW_ARROW;
        }
        if (item instanceof TridentItem) {
            return TRIDENT;
        }
        if (item instanceof SnowballItem || item instanceof EggItem || item instanceof EnderpearlItem) {
            return THROWABLE;
        }
        if (item instanceof ExperienceBottleItem) {
            return XP_BOTTLE;
        }
        if (item instanceof ThrowablePotionItem) {
            return POTION;
        }
        if (item instanceof WindChargeItem) {
            return WIND_CHARGE;
        }
        if (item instanceof FishingRodItem) {
            return BOBBER;
        }
        return null;
    }

    // Where a projectile is at one moment in its flight.
    private record Shot(Vec3 pos, Vec3 velocity) {
    }

    private Path simulate(Player shooter, Launch launch, float partialTicks) {
        return fly(shooter, launch, leaveHand(shooter, launch, partialTicks));
    }

    // The position and speed the projectile starts with.
    private Shot leaveHand(Player shooter, Launch launch, float partialTicks) {
        double yaw = shooter.getYRot(partialTicks);
        double pitch = shooter.getXRot(partialTicks);
        Vec3 origin = shooter.getPosition(partialTicks);
        Vec3 pos;
        Vec3 velocity;

        if (launch.motion() == Motion.BOBBER) {
            double sinYaw = Math.sin(Math.toRadians(-yaw) - Math.PI);
            double cosYaw = Math.cos(Math.toRadians(-yaw) - Math.PI);
            double cosPitch = -Math.cos(Math.toRadians(-pitch));
            double sinPitch = Math.sin(Math.toRadians(-pitch));
            pos = origin.add(-sinYaw * 0.3, shooter.getEyeHeight(), -cosYaw * 0.3);
            velocity = new Vec3(-sinYaw, Math.clamp(-(sinPitch / cosPitch), -5, 5), -cosYaw);
            double length = velocity.length();
            velocity = velocity.scale(0.6 / length + 0.5);
        } else {
            pos = origin.add(0, shooter.getEyeHeight() - 0.1, 0);
            double radYaw = Math.toRadians(yaw);
            double radPitch = Math.toRadians(pitch);
            double x = -Math.sin(radYaw) * Math.cos(radPitch);
            double y = -Math.sin(Math.toRadians(pitch + launch.pitchOffset()));
            double z = Math.cos(radYaw) * Math.cos(radPitch);
            velocity = new Vec3(x, y, z).normalize().scale(launch.power());
            // The game adds the thrower's own movement to the projectile.
            Vec3 movement = shooter.getKnownMovement();
            velocity = velocity.add(movement.x, shooter.onGround() ? 0 : movement.y, movement.z);
        }
        return new Shot(pos, velocity);
    }

    // Steps the projectile forward until it lands or the step budget runs out.
    private Path fly(Player shooter, Launch launch, Shot shot) {
        Vec3 pos = shot.pos();
        Vec3 velocity = shot.velocity();

        List<Vec3> points = new ArrayList<>();
        points.add(pos);
        HitResult.Type type = HitResult.Type.MISS;
        Entity hit = null;
        int minY = mc.level.getMinY();
        int maxSteps = steps.getInt();

        for (int i = 0; i < maxSteps; i++) {
            Vec3 previous = pos;
            boolean inWater = mc.level.getFluidState(BlockPos.containing(pos)).is(FluidTags.WATER);
            double drag = inWater ? launch.waterDrag() : launch.airDrag();

            Shot next = advance(launch, pos, velocity, drag);
            pos = next.pos();
            velocity = next.velocity();

            if (pos.y < minY) {
                points.add(pos);
                break;
            }

            BlockHitResult blockHit = mc.level.clip(new ClipContext(previous, pos,
                ClipContext.Block.COLLIDER,
                launch.stopsInWater() ? ClipContext.Fluid.ANY : ClipContext.Fluid.NONE, shooter));
            Vec3 end = blockHit.getType() == HitResult.Type.MISS ? pos : blockHit.getLocation();

            EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(shooter, previous, end,
                new AABB(previous, end).inflate(1),
                entity -> entity != shooter && !entity.isSpectator() && entity.isAlive() && entity.isPickable(),
                OTHER_RANGE_SQ);
            if (entityHit != null && entityHit.getType() != HitResult.Type.MISS) {
                points.add(entityHit.getLocation());
                type = HitResult.Type.ENTITY;
                hit = entityHit.getEntity();
                break;
            }
            if (blockHit.getType() != HitResult.Type.MISS) {
                points.add(blockHit.getLocation());
                type = HitResult.Type.BLOCK;
                break;
            }
            points.add(pos);
            if (velocity.lengthSqr() < 1.0E-6) {
                break;
            }
        }
        return new Path(points, type, hit);
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
            case BOBBER -> {
                Vec3 fallen = velocity.subtract(0, launch.gravity(), 0);
                yield new Shot(pos.add(fallen), fallen.scale(drag));
            }
        };
    }
}
