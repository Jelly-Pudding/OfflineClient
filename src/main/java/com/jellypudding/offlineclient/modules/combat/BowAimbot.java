package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render2DEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.modules.render.Trajectories;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.EntityFilter;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.ProjectileUtil;
import com.jellypudding.offlineclient.util.RenderUtil;
import com.jellypudding.offlineclient.util.TargetPriority;

import net.minecraft.client.gui.GuiGraphicsExtractor;
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

import java.util.List;

// The pitch comes from the real arrow physics and the flight time leads the target.
public final class BowAimbot extends Module {

    public enum Calm { ALWAYS, ANGRY_ONLY, NEVER }


    // Pixels below the middle of the screen the readout sits.
    private static final int CROSSHAIR_GAP = 12;

    // Arrow speed in blocks per tick at a full bow draw.
    private static final double BOW_SPEED = 3.0;
    // Arrow speed in blocks per tick from a crossbow.
    private static final double CROSSBOW_SPEED = 3.15;

    private final EntityFilter filter = EntityFilter.living("Aim at", "aimed at", true,
        EntityFilter.Pick.NONE, List.of());
    private final BoolSetting babies = new BoolSetting("Babies",
        "Also aim at baby mobs.", true);
    private final BoolSetting named = new BoolSetting("Named mobs",
        "Also aim at mobs that have been given a name tag.", false);
    private final NumberSetting range = new NumberSetting("Range",
        "Furthest target to aim at in blocks.", 40, 5, 80, 1);
    private final EnumSetting<TargetPriority> priority = TargetPriority.setting("Aims at",
        TargetPriority.NEAREST);
    private final BoolSetting predict = new BoolSetting("Predict",
        "Lead moving targets by their speed and the arrow flight time.", true);
    private final NumberSetting predictStrength = new NumberSetting("Predict strength",
        "How much of the worked out lead is used.", 100, 0, 200, 5, "%").min(0).max(200)
        .under(predict);
    private final BoolSetting sleeping = new BoolSetting("Aim at sleeping",
        "Also aim at players lying in a bed.", false);
    private final BoolSetting invisible = new BoolSetting("Aim at invisible",
        "Also aim at entities you cannot see.", false);
    private final BoolSetting pets = new BoolSetting("Aim at pets",
        "Also aim at tamed animals and mounts.", false);
    private final EnumSetting<Calm> neutral = new EnumSetting<>("Neutral mobs",
        "Whether mobs that only fight back are aimed at.", Calm.NEVER)
        .describe(Calm.ALWAYS, "Always aim at them.")
        .describe(Calm.ANGRY_ONLY, "Only once they have turned on you.")
        .describe(Calm.NEVER, "Never aim at them.");
    private final NumberSetting flying = new NumberSetting("Ignore flying",
        "Skip players with no block this far under them. Nought turns it off.",
        0, 0, 4, 0.5, " blocks").min(0);
    private final BoolSetting walls = new BoolSetting("Through walls",
        "Also aim at targets you cannot see.", false);
    private final BoolSetting render = new BoolSetting("Highlight",
        "Draw a box around the target that fills in as the bow charges.", true);
    private final ColorSetting highlightColor = new ColorSetting("Highlight colour",
        "Colour of that box.", 0, 0.81f, 1f, false).under(render);
    private final BoolSetting readout = new BoolSetting("Charge readout",
        "Write how far the bow is drawn under your crosshair.", true);
    private final BoolSetting trajectory = new BoolSetting("Trajectory",
        "Draw the arc the arrow will fly. The Trajectories module draws the same arc so it takes over whilst it is on.", true);
    private final BoolSetting noSlow = new BoolSetting("No slowdown",
        "Move at full speed whilst drawing.", true);

    private Entity target;
    private float charge;
    private boolean wasDrawing;

    public BowAimbot() {
        super("BowAimbot", "Aims your bow or crossbow at the nearest target whilst you draw it.", Category.COMBAT);
        addSettings(filter.settings());
        addSettings(babies, named, sleeping, invisible, pets, neutral, flying,
            range, priority, predict, predictStrength, walls,
            render, highlightColor, readout, trajectory, noSlow);
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
        // The crosshair choice is made once at the start of the draw whilst the
        // rotation is still the player's own.
        boolean drawStart = !wasDrawing;
        wasDrawing = true;
        if (target == null || (priority.is(TargetPriority.CLOSEST_ANGLE) && drawStart)) {
            target = EntityUtil.best(range.getValue(), priority.getValue(), this::valid);
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

    private boolean valid(Entity entity) {
        if (entity == mc.player || entity == mc.getCameraEntity() || !filter.matches(entity)) {
            return false;
        }
        if (!entity.isAlive() || mc.player.distanceTo(entity) > range.getValue()) {
            return false;
        }
        if (!invisible.isOn() && entity.isInvisible()) {
            return false;
        }
        if (entity instanceof Player player) {
            if (player.isCreative() || EntityUtil.isFriend(player)) {
                return false;
            }
            if (!sleeping.isOn() && player.isSleeping()) {
                return false;
            }
            if (flying.getValue() > 0 && airborne(player)) {
                return false;
            }
        } else {
            if (!pets.isOn() && entity instanceof Mob mob && EntityUtil.isPet(mob)) {
                return false;
            }
            if (entity instanceof Mob mob && EntityUtil.isNeutral(mob) && skipNeutral(mob)) {
                return false;
            }
            if (entity instanceof LivingEntity living && living.isDeadOrDying()) {
                return false;
            }
            if (!babies.isOn() && entity instanceof LivingEntity living && living.isBaby()) {
                return false;
            }
            if (!named.isOn() && entity.hasCustomName()) {
                return false;
            }
        }
        return walls.isOn() || mc.player.hasLineOfSight(entity);
    }

    private boolean skipNeutral(Mob mob) {
        return switch (neutral.getValue()) {
            case ALWAYS -> false;
            case ANGRY_ONLY -> EntityUtil.isCalm(mob);
            case NEVER -> true;
        };
    }

    // True whilst nothing solid sits under the player within the set drop.
    private boolean airborne(Player player) {
        AABB feet = player.getBoundingBox().move(0, -flying.getValue(), 0)
            .expandTowards(0, flying.getValue(), 0);
        return mc.level.noCollision(player, feet);
    }

    // Turns the player towards the point the arrow needs to fly through.
    private void aim(Entity entity, double speed) {
        Vec3 eye = mc.player.getEyePosition();
        Vec3 aimPoint = visiblePoint(entity, eye);
        Vec3 targetVelocity = predict.isOn()
            ? EntityUtil.velocityOf(entity).scale(predictStrength.getValue() / 100.0)
            : Vec3.ZERO;

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

    // Aims at the target's middle when it is visible otherwise the highest
    // visible part since a target in a hole only shows head and chest.
    private Vec3 visiblePoint(Entity entity, Vec3 eye) {
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

    // Finds the lowest launch angle that lands an arrow on the point.
    // Returns the angle and flight time in ticks or null when out of reach.
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

    // Height an arrow reaches at the horizontal distance using the closed
    // form of the per tick drag and gravity update.
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

    // A word under the crosshair so you know when to let go.
    @Subscribe
    private void onRender2D(Render2DEvent event) {
        if (!readout.isOn() || target == null || !inGame()) {
            return;
        }
        String line = charge >= 1 ? "Target locked"
            : "Charging " + Math.round(charge * 100) + "%";
        GuiGraphicsExtractor context = event.getContext();
        int x = (context.guiWidth() - mc.font.width(line)) / 2;
        int y = context.guiHeight() / 2 + CROSSHAIR_GAP;
        context.guiRenderState.up();
        context.text(mc.font, line, x, y,
            charge >= 1 ? highlightColor.getColor() : RenderUtil.MUTED_TEXT, true);
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (target == null || !inGame()) {
            return;
        }
        if (render.isOn()) {
            int color = highlightColor.getColor();
            AABB box = EntityUtil.lerpedBox(target, event.getPartialTicks());
            event.getBatch().outlineBox(box, color, true);
            event.getBatch().solidBox(box, ColorUtil.withAlpha(color, (int) (90 * charge)), true);
        }
        Trajectories trajectories = Modules.get(Trajectories.class);
        if (trajectory.isOn() && trajectories != null && !trajectories.isEnabled()) {
            trajectories.draw(event.getBatch(), mc.player, event.getPartialTicks());
        }
    }
}
