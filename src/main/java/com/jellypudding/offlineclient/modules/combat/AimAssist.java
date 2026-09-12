package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.EntityFilter;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.TargetFilter;
import com.jellypudding.offlineclient.util.RotationManager;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

// Turns the real camera rather than sending silent look packets. The aim
// looks like your own hand on the mouse.
public final class AimAssist extends Module {

    public enum AimPoint { AUTO, HEAD, CENTRE, FEET }

    // Ticks a second. Turn speed is given per second.
    private static final double TICKS = 20;

    private final NumberSetting range = new NumberSetting("Range",
        "How far away a target can be.", 4.5, 1, 6, 0.05, " blocks").min(1).max(6);
    private final NumberSetting speed = new NumberSetting("Turn speed",
        "Degrees a second the view turns at.", 600, 10, 3600, 10, " degrees a second").min(10);
    private final NumberSetting fov = new NumberSetting("Field of view",
        "Only targets inside this cone in front of you are picked.", 120, 30, 360, 10, " degrees");
    private final EnumSetting<AimPoint> aimPoint = new EnumSetting<>("Aim at",
        "The part of the target the view settles on.", AimPoint.AUTO)
        .describe(AimPoint.AUTO, "The nearest point of the hitbox to your eyes.")
        .describe(AimPoint.HEAD, "The top of the hitbox.")
        .describe(AimPoint.CENTRE, "The middle of the hitbox.")
        .describe(AimPoint.FEET, "The bottom of the hitbox.");
    private final NumberSetting ignoreMouse = new NumberSetting("Ignore mouse",
        "How much of your own mouse movement is swallowed whilst aiming.", 0, 0, 100, 1, "%");
    private final BoolSetting lineOfSight = new BoolSetting("Line of sight",
        "Drop the target when the aim point is behind a block.", true);
    private final BoolSetting whileBlocking = new BoolSetting("Aim whilst blocking",
        "Keep aiming whilst you use an item.", false);
    private final BoolSetting ignoreFriends = new BoolSetting("Ignore friends",
        "Never aim at a friend.", true);
    private final EntityFilter filter = EntityFilter.living("Aim at", "aimed at", true,
        EntityFilter.Pick.NONE, List.of());

    private final TargetFilter targets = new TargetFilter();

    private Entity target;

    public AimAssist() {
        super("AimAssist", "Nudges your view towards whatever you are fighting.", Category.COMBAT);
        addSettings(range, speed, fov, aimPoint, ignoreMouse, lineOfSight, whileBlocking, ignoreFriends);
        addSettings(filter.settings());
        addSettings(targets.settings());
        searchTags("aim", "aimbot", "legit", "assist");
    }

    @Override
    public String getSuffix() {
        return target instanceof Player player ? EntityUtil.nameOf(player) : null;
    }

    @Override
    protected void onDisable() {
        target = null;
    }

    // Read by MouseHandlerMixin. One means your mouse moves the view as usual.
    public double mouseScale() {
        return isEnabled() && target != null ? 1 - ignoreMouse.getValue() / 100.0 : 1;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        target = null;
        if (!inGame() || mc.player.isSpectator() || mc.gui.screen() != null) {
            return;
        }
        if (!whileBlocking.isOn() && mc.player.isUsingItem()) {
            return;
        }
        target = pickTarget();
        if (target != null) {
            turnTowards(aimAt(target));
        }
    }

    // The entity closest to where you already point rather than the nearest one.
    private Entity pickTarget() {
        double reach = range.getValue();
        double widest = fov.getValue() / 2;
        Entity best = null;
        double bestAngle = widest;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity == mc.player || !filter.matches(entity) || !targets.allows(entity)
                || mc.player.distanceTo(entity) > reach) {
                continue;
            }
            if (ignoreFriends.isOn() && EntityUtil.isFriend(entity)) {
                continue;
            }
            if (lineOfSight.isOn() && !mc.player.hasLineOfSight(entity)) {
                continue;
            }
            double angle = EntityUtil.lookAngleTo(entity);
            if (angle < bestAngle) {
                bestAngle = angle;
                best = entity;
            }
        }
        return best;
    }

    private Vec3 aimAt(Entity entity) {
        AABB box = entity.getBoundingBox();
        Vec3 middle = box.getCenter();
        return switch (aimPoint.getValue()) {
            case AUTO -> nearestPoint(box, mc.player.getEyePosition());
            case HEAD -> new Vec3(middle.x, box.maxY, middle.z);
            case CENTRE -> middle;
            case FEET -> new Vec3(middle.x, box.minY, middle.z);
        };
    }

    // The eye position pulled onto the box on every axis at once.
    private static Vec3 nearestPoint(AABB box, Vec3 eyes) {
        return new Vec3(Mth.clamp(eyes.x, box.minX, box.maxX),
            Mth.clamp(eyes.y, box.minY, box.maxY),
            Mth.clamp(eyes.z, box.minZ, box.maxZ));
    }

    private void turnTowards(Vec3 point) {
        float step = (float) (speed.getValue() / TICKS);
        mc.player.setYRot(approach(mc.player.getYRot(), RotationManager.yawTo(point), step));
        mc.player.setXRot(Mth.clamp(approach(mc.player.getXRot(),
            RotationManager.pitchTo(point), step), -90f, 90f));
    }

    // Lands exactly on the wanted angle once it is within one step.
    private static float approach(float from, float to, float step) {
        float difference = Mth.wrapDegrees(to - from);
        return Math.abs(difference) <= step ? to : from + Math.copySign(step, difference);
    }
}
