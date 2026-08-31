package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.ProjectileUtil;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

// The pitch comes from the real arrow physics and the flight time leads the target.
public final class BowAimbot extends Module {

    public enum Priority { NEAREST, LOW_HEALTH, CROSSHAIR }

    // Arrow speed in blocks per tick at a full bow draw.
    private static final double BOW_SPEED = 3.0;
    // Arrow speed in blocks per tick from a crossbow.
    private static final double CROSSBOW_SPEED = 3.15;

    private final BoolSetting players = new BoolSetting("Players",
        "Aim at other players.", true);
    private final BoolSetting mobs = new BoolSetting("Mobs",
        "Aim at mobs.", false);
    private final NumberSetting range = new NumberSetting("Range",
        "Furthest target to aim at in blocks.", 40, 5, 80, 1);
    private final EnumSetting<Priority> priority = new EnumSetting<>("Priority",
        "Which target to pick when several are in range.", Priority.NEAREST)
        .describe(Priority.NEAREST, "Aims at the closest target.")
        .describe(Priority.LOW_HEALTH, "Aims at whoever has the least health left.")
        .describe(Priority.CROSSHAIR, "Aims at the target nearest your crosshair.");
    private final BoolSetting predict = new BoolSetting("Predict",
        "Lead moving targets by their speed and the arrow flight time.", true);
    private final BoolSetting walls = new BoolSetting("Through walls",
        "Also aim at targets you cannot see.", false);
    private final BoolSetting render = new BoolSetting("Highlight",
        "Draw a box around the target that fills in as the bow charges.", true);
    private final BoolSetting noSlow = new BoolSetting("No slowdown",
        "Move at full speed whilst drawing.", true);

    private LivingEntity target;
    private float charge;
    private boolean wasDrawing;

    public BowAimbot() {
        super("BowAimbot", "Aims your bow or crossbow at the nearest target whilst you draw it.", Category.COMBAT);
        addSettings(players, mobs, range, priority, predict, walls, render, noSlow);
        searchTags("bow aim", "crossbow", "aimbot", "arrow");
    }

    @Override
    public String getSuffix() {
        return target == null ? null : target.getName().getString();
    }

    @Override
    protected void onDisable() {
        target = null;
        charge = 0;
        wasDrawing = false;
    }

    // Consulted by LocalPlayerMixin to skip the bow draw slowdown.
    public boolean suppressesSlowdown() {
        return isEnabled() && noSlow.isOn() && inGame() && mc.player.isUsingItem();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.player.isSpectator()) {
            target = null;
            return;
        }

        double speed = launchSpeed();
        if (speed <= 0) {
            target = null;
            charge = 0;
            wasDrawing = false;
            return;
        }

        if (target != null && !valid(target)) {
            target = null;
        }
        // Crosshair mode picks once at the start of the draw whilst the
        // rotation is still the player's own.
        boolean drawStart = !wasDrawing;
        wasDrawing = true;
        if (target == null || (priority.getValue() == Priority.CROSSHAIR && drawStart)) {
            target = pickTarget();
        }
        if (target == null) {
            return;
        }

        aim(target, speed);
    }

    // Speed the arrow would leave with right now or zero when no ranged weapon is ready.
    private double launchSpeed() {
        ItemStack held = mc.player.getMainHandItem();
        if (held.getItem() instanceof BowItem) {
            if (!mc.player.isUsingItem() || !(mc.player.getUseItem().getItem() instanceof BowItem)) {
                return 0;
            }
            charge = BowItem.getPowerForTime(mc.player.getTicksUsingItem());
            return Math.max(charge, 0.1f) * BOW_SPEED;
        }
        if (held.getItem() instanceof CrossbowItem) {
            if (!CrossbowItem.isCharged(held) || !mc.options.keyUse.isDown()) {
                return 0;
            }
            charge = 1;
            return CROSSBOW_SPEED;
        }
        ItemStack offhand = mc.player.getOffhandItem();
        if (offhand.getItem() instanceof BowItem && mc.player.isUsingItem()
            && mc.player.getUseItem().getItem() instanceof BowItem) {
            charge = BowItem.getPowerForTime(mc.player.getTicksUsingItem());
            return Math.max(charge, 0.1f) * BOW_SPEED;
        }
        return 0;
    }

    private LivingEntity pickTarget() {
        LivingEntity best = null;
        double bestScore = Double.MAX_VALUE;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living) || !valid(living)) {
                continue;
            }
            double score = switch (priority.getValue()) {
                case NEAREST -> mc.player.distanceToSqr(living);
                case LOW_HEALTH -> living.getHealth();
                case CROSSHAIR -> EntityUtil.lookAngleTo(living);
            };
            if (score < bestScore) {
                bestScore = score;
                best = living;
            }
        }
        return best;
    }

    private boolean valid(LivingEntity entity) {
        if (entity == mc.player || entity == mc.getCameraEntity()) {
            return false;
        }
        if (!entity.isAlive() || entity.isDeadOrDying() || entity.isSpectator()) {
            return false;
        }
        if (mc.player.distanceTo(entity) > range.getValue()) {
            return false;
        }
        if (entity instanceof Player player) {
            if (!players.isOn() || player.isCreative()) {
                return false;
            }
            if (EntityUtil.isFriend(player)) {
                return false;
            }
        } else if (entity instanceof Mob) {
            if (!mobs.isOn()) {
                return false;
            }
        } else {
            return false;
        }
        return walls.isOn() || mc.player.hasLineOfSight(entity);
    }

    // Turns the player towards the point the arrow needs to fly through.
    private void aim(LivingEntity entity, double speed) {
        Vec3 eye = mc.player.getEyePosition();
        Vec3 aimPoint = visiblePoint(entity, eye);
        Vec3 targetVelocity = predict.isOn() ? EntityUtil.velocityOf(entity) : Vec3.ZERO;

        // The server hands the arrow the distance the last packet moved.
        // The vertical part only counts in the air.
        Vec3 own = EntityUtil.velocityOf(mc.player);
        Vec3 drift = new Vec3(own.x, mc.player.onGround() ? 0 : own.y, own.z);

        double[] solution = solve(eye, aimPoint, speed);
        if (solution != null) {
            // The target keeps its pace whilst the momentum the arrow inherited from
            // a sprint or a jump bleeds off to the drag every tick.
            double flight = solution[1];
            Vec3 lead = aimPoint.add(targetVelocity.scale(flight))
                .subtract(drift.scale(carriedTicks(flight)));
            double[] again = solve(eye, lead, speed);
            if (again != null) {
                solution = again;
                aimPoint = lead;
            }
        }

        double dx = aimPoint.x - eye.x;
        double dz = aimPoint.z - eye.z;
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
        float pitch;
        if (solution == null) {
            double dy = aimPoint.y - eye.y;
            pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        } else {
            pitch = (float) -solution[0];
        }

        // The view itself turns. A bow shot has to leave from where the camera points.
        mc.player.setYRot(yaw);
        mc.player.setXRot(Math.clamp(pitch, -90f, 90f));
    }

    /**
     * The middle of the target when it is in view. Otherwise the highest
     * part that is. A target in a hole shows only the head and chest and an
     * arrow aimed at the middle hits the rim.
     */
    private Vec3 visiblePoint(LivingEntity entity, Vec3 eye) {
        AABB box = entity.getBoundingBox();
        double x = (box.minX + box.maxX) / 2;
        double z = (box.minZ + box.maxZ) / 2;
        double height = box.maxY - box.minY;
        double[] shares = {0.5, 0.75, 0.9, 0.3};
        for (double share : shares) {
            Vec3 point = new Vec3(x, box.minY + height * share, z);
            HitResult hit = mc.level.clip(new ClipContext(eye, point,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
            if (hit.getType() == HitResult.Type.MISS) {
                return point;
            }
        }
        return box.getCenter();
    }

    // Sum of the drag series over the flight. How far the inherited speed really carries.
    private static double carriedTicks(double ticks) {
        if (ticks <= 0) {
            return 0;
        }
        return (1 - Math.pow(ProjectileUtil.ARROW_DRAG, ticks)) / (1 - ProjectileUtil.ARROW_DRAG);
    }

    /**
     * Finds the lowest launch angle in degrees that lands an arrow on the
     * point. Returns the angle and the flight time in ticks or null when
     * the point is out of reach.
     */
    private static double[] solve(Vec3 from, Vec3 to, double speed) {
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
                return new double[] {high, flightTicks(high, distance, speed)};
            }
            if (h >= height) {
                return new double[] {angle, flightTicks(angle, distance, speed)};
            }
            previous = angle;
            previousHeight = h;
        }
        return null;
    }

    // Ticks an arrow needs to travel the horizontal distance or NaN if it never gets there.
    private static double flightTicks(double angleDegrees, double distance, double speed) {
        double horizontal = speed * Math.cos(Math.toRadians(angleDegrees));
        double reach = horizontal / (1 - ProjectileUtil.ARROW_DRAG);
        if (horizontal <= 0 || distance >= reach) {
            return Double.NaN;
        }
        return Math.log(1 - distance / reach) / Math.log(ProjectileUtil.ARROW_DRAG);
    }

    /**
     * Height an arrow has when it reaches the horizontal distance. Uses the
     * closed form of the per tick drag and gravity update.
     */
    private static double heightAt(double angleDegrees, double distance, double speed) {
        double ticks = flightTicks(angleDegrees, distance, speed);
        if (Double.isNaN(ticks)) {
            return Double.NaN;
        }
        double vertical = speed * Math.sin(Math.toRadians(angleDegrees));
        double terminal = ProjectileUtil.ARROW_GRAVITY / (1 - ProjectileUtil.ARROW_DRAG);
        double fallen = 1 - Math.pow(ProjectileUtil.ARROW_DRAG, ticks);
        return (vertical + terminal) * fallen / (1 - ProjectileUtil.ARROW_DRAG) - terminal * ticks;
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!render.isOn() || target == null || !inGame()) {
            return;
        }
        int color = 0xFFFF3030;
        event.getBatch().outlineBox(EntityUtil.lerpedBox(target, event.getPartialTicks()), color, true);
        event.getBatch().solidBox(EntityUtil.lerpedBox(target, event.getPartialTicks()),
            ColorUtil.withAlpha(color, (int) (90 * charge)), true);
    }
}
