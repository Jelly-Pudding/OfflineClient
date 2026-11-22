package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PostMotionEvent;
import com.jellypudding.offlineclient.event.events.Render2DEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.EntityFilter;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.Hands;
import com.jellypudding.offlineclient.util.ProjectilePath;
import com.jellypudding.offlineclient.util.ProjectileUtil;
import com.jellypudding.offlineclient.util.RenderUtil;
import com.jellypudding.offlineclient.util.RotationManager;
import com.jellypudding.offlineclient.util.TargetFilter;
import com.jellypudding.offlineclient.util.TargetPriority;
import com.jellypudding.offlineclient.util.Targeting;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

// The pitch comes from the real physics of an arrow or a thrown trident and the flight time leads
// the target. A trident with Riptide is never aimed. In water or rain it throws you and not itself.
public final class BowAimbot extends Module implements Targeting {

    private final EntityFilter filter = EntityFilter.living("Aims at", "aimed at", true,
        EntityFilter.Pick.NONE, List.of());
    private final TargetFilter targets = new TargetFilter();
    private final NumberSetting range = new NumberSetting("Range",
        "Furthest target to aim at.", 40, 5, 80, 1, " blocks");
    private final EnumSetting<TargetPriority> priority = TargetPriority.setting("Aims at",
        TargetPriority.NEAREST);
    private final BoolSetting predict = new BoolSetting("Predict",
        "Leads moving targets by their speed and the arrow flight time.", true);
    private final NumberSetting predictStrength = new NumberSetting("Predict strength",
        "How much of the worked out lead is used.", 100, 0, 200, 5, "%")
        .under(predict);
    private final BoolSetting walls = new BoolSetting("Through walls",
        "Also aims at targets you cannot see.", false);
    private final BoolSetting render = new BoolSetting("Highlight",
        "Draws a box around the target that fills in as the shot charges.", true);
    private final ColorSetting highlightColor = new ColorSetting("Highlight colour",
        "Colour of that box.", 0, 0.81f, 1f, false).under(render);
    private final BoolSetting readout = new BoolSetting("Charge readout",
        "Writes how far the bow or trident is drawn under your crosshair.", true);

    private Entity target;
    private float charge;
    private boolean wasDrawing;
    // How far your last tick moved you. By the next tick the old position has caught up with you.
    private Vec3 ownMove = Vec3.ZERO;

    public BowAimbot() {
        super("BowAimbot", "Aims your bow or crossbow or trident at the nearest target whilst you draw it.",
            Category.COMBAT);
        addSettings(filter.settings());
        addSettings(targets.settings());
        addSettings(range, priority, predict, predictStrength, walls,
            render, highlightColor, readout);
        searchTags("bow aim", "crossbow", "aimbot", "arrow", "trident");
    }

    @Override
    public String getSuffix() {
        return target == null ? null : EntityUtil.displayNameOf(target);
    }

    @Override
    public Entity getFought() {
        return target;
    }

    @Override
    protected void onDisable() {
        target = null;
        charge = 0;
        wasDrawing = false;
        ownMove = Vec3.ZERO;
    }

    // A trident that leaves the hand when let go. Riptide throws the player instead.
    private boolean thrownTrident(ItemStack stack) {
        return stack.getItem() instanceof TridentItem
            && EnchantmentHelper.getTridentSpinAttackStrength(stack, mc.player) <= 0;
    }

    @Subscribe
    private void onPostMotion(PostMotionEvent event) {
        ownMove = EntityUtil.velocityOf(mc.player);
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (Hands.tied()) {
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

    // BowSpam's rapid fire holds a crossbow drawn and the client seldom sees it loaded.
    private double launchSpeed() {
        if (mc.player.isUsingItem()) {
            ItemStack using = mc.player.getUseItem();
            if (using.getItem() instanceof BowItem) {
                charge = BowItem.getPowerForTime(mc.player.getTicksUsingItem());
                return Math.max(charge, 0.1f) * ProjectileUtil.BOW_SPEED;
            }
            if (using.getItem() instanceof CrossbowItem) {
                charge = Math.min(1, (float) mc.player.getTicksUsingItem()
                    / CrossbowItem.getChargeDuration(using, mc.player));
                return ProjectileUtil.CROSSBOW_SPEED;
            }
            // A trident flies like an arrow at a speed of its own once held long enough to throw.
            if (thrownTrident(using)) {
                charge = Math.min(1, (float) mc.player.getTicksUsingItem() / TridentItem.THROW_THRESHOLD_TIME);
                return ProjectilePath.TRIDENT.power();
            }
        }
        ItemStack held = mc.player.getMainHandItem();
        if (held.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(held)
            && mc.options.keyUse.isDown()) {
            charge = 1;
            return ProjectileUtil.CROSSBOW_SPEED;
        }
        return 0;
    }

    @Override
    public boolean wouldFight(Entity entity) {
        return targets.attackable(entity, filter);
    }

    private boolean valid(Entity entity) {
        if (entity == mc.player || entity == mc.getCameraEntity()
            || mc.player.distanceTo(entity) > range.getValue() || !wouldFight(entity)) {
            return false;
        }
        return walls.isOn() || mc.player.hasLineOfSight(entity);
    }

    private void aim(Entity entity, double speed) {
        Vec3 eye = mc.player.getEyePosition();
        Vec3 aimPoint = visiblePoint(entity, eye);
        Vec3 targetVelocity = predict.isOn()
            ? EntityUtil.velocityOf(entity).scale(predictStrength.getValue() / 100.0)
            : Vec3.ZERO;

        // The server hands the arrow the distance the last packet moved.
        // The vertical part only counts in the air.
        Vec3 drift = new Vec3(ownMove.x, mc.player.onGround() ? 0 : ownMove.y, ownMove.z);

        ProjectilePath.Arc arc = ProjectilePath.arrowArc(eye, aimPoint, speed);
        if (arc != null) {
            // The target keeps its pace whilst the momentum the arrow inherited from
            // a sprint or a jump bleeds off to the drag every tick.
            double flight = arc.ticks();
            Vec3 lead = aimPoint.add(targetVelocity.scale(flight))
                .subtract(drift.scale(carriedTicks(flight)));
            ProjectilePath.Arc again = ProjectilePath.arrowArc(eye, lead, speed);
            if (again != null) {
                arc = again;
                aimPoint = lead;
            }
        }

        float yaw = RotationManager.yawTo(aimPoint);
        float pitch = arc == null ? RotationManager.pitchTo(aimPoint) : arc.pitch();

        // The view itself turns. A bow shot has to leave from where the camera points.
        mc.player.setYRot(yaw);
        mc.player.setXRot(RotationManager.clampPitch(pitch));
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
        return (1 - Math.pow(ProjectileUtil.AIR_DRAG, ticks)) / (1 - ProjectileUtil.AIR_DRAG);
    }

    @Subscribe
    private void onRender2D(Render2DEvent event) {
        if (!readout.isOn() || target == null || !inGame()) {
            return;
        }
        String line = charge >= 1 ? "Target locked"
            : "Charging " + Math.round(charge * 100) + "%";
        RenderUtil.underCrosshair(event.getContext(), line, charge >= 1 ? highlightColor.getColor() : RenderUtil.MUTED_TEXT);
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (target == null || !inGame() || !render.isOn()) {
            return;
        }
        int color = highlightColor.getColor();
        AABB box = EntityUtil.lerpedBox(target, event.getPartialTicks());
        event.getBatch().outlineBox(box, color, true);
        event.getBatch().solidBox(box, ColorUtil.withAlpha(color, (int) (90 * charge)), true);
    }
}
