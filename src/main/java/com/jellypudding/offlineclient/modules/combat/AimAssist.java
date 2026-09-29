package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.EntityFilter;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.TargetFilter;
import com.jellypudding.offlineclient.util.RotationManager;
import net.minecraft.SharedConstants;
import net.minecraft.client.DeltaTracker;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

// Turns the real camera rather than sending silent look packets. The aim
// looks like your own hand on the mouse. The target is picked once a tick and
// the view turns once a frame towards where the target is drawn.
public final class AimAssist extends Module {

    public enum AimPoint { AUTO, HEAD, CENTRE, FEET }

    // The weapons the held item list starts with.
    private static final List<Item> MELEE_WEAPONS = List.of(
        Items.WOODEN_SWORD, Items.STONE_SWORD, Items.COPPER_SWORD, Items.IRON_SWORD,
        Items.GOLDEN_SWORD, Items.DIAMOND_SWORD, Items.NETHERITE_SWORD,
        Items.WOODEN_AXE, Items.STONE_AXE, Items.COPPER_AXE, Items.IRON_AXE,
        Items.GOLDEN_AXE, Items.DIAMOND_AXE, Items.NETHERITE_AXE,
        Items.WOODEN_SPEAR, Items.STONE_SPEAR, Items.COPPER_SPEAR, Items.IRON_SPEAR,
        Items.GOLDEN_SPEAR, Items.DIAMOND_SPEAR, Items.NETHERITE_SPEAR,
        Items.MACE, Items.TRIDENT);

    private final NumberSetting range = new NumberSetting("Range",
        "How far away a target can be.", 4.5, 1, 6, 0.05, " blocks").min(1);
    private final BoolSetting snap = new BoolSetting("Snap",
        "Holds the crosshair on the target every frame instead of turning at the turn speed.", false);
    private final NumberSetting speed = new NumberSetting("Turn speed",
        "Degrees a second the view turns at.", 600, 10, 3600, 10, " degrees a second").min(10)
        .unless(snap);
    private final NumberSetting fov = new NumberSetting("Field of view",
        "Only targets inside this cone in front of you are picked.", 120, 30, 360, 10, " degrees").min(1).max(360);
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
    private final BoolSetting lockOn = new BoolSetting("Lock on",
        "Stays on one target until it dies or leaves your range or your sight. It is followed even"
            + " outside the field of view.", false);
    private final BoolSetting keepThroughWalls = new BoolSetting("Keep through walls",
        "Stays on the locked target whilst it is hidden behind blocks.", false)
        .under(lockOn, () -> lockOn.isOn() && lineOfSight.isOn());
    private final BoolSetting whileUsing = new BoolSetting("Aim whilst using",
        "Keep aiming whilst you eat or block or draw a bow.", false);
    private final BoolSetting onlyHolding = new BoolSetting("Only whilst holding",
        "Aims only whilst your main hand holds one of the items below.", false);
    private final RegistryListSetting<Item> heldItems = new RegistryListSetting<>("Held items",
        "The items that let the aim work. Click to pick them.", BuiltInRegistries.ITEM, MELEE_WEAPONS)
        .under(onlyHolding);
    private final EntityFilter filter = EntityFilter.living("Aim at", "aimed at", true,
        EntityFilter.Pick.NONE, List.of());

    private final TargetFilter targets = new TargetFilter();

    private Entity target;

    // False whilst the aim sits the tick out. A locked target is kept through the pause.
    private boolean aiming;

    public AimAssist() {
        super("AimAssist", "Turns your view towards whatever you are fighting.", Category.COMBAT);
        addSettings(range, snap, speed, fov, aimPoint, ignoreMouse, lineOfSight, lockOn, keepThroughWalls,
            whileUsing, onlyHolding, heldItems);
        addSettings(filter.settings());
        addSettings(targets.settings());
        searchTags("aim", "aimbot", "aim bot", "legit", "assist", "lock on", "snap");
    }

    @Override
    public String getSuffix() {
        return target instanceof Player player ? EntityUtil.nameOf(player) : null;
    }

    @Override
    protected void onDisable() {
        target = null;
        aiming = false;
    }

    // Read by MouseHandlerMixin. One means your mouse moves the view as usual.
    public double mouseScale() {
        return isEnabled() && aiming && target != null ? 1 - ignoreMouse.getValue() / 100.0 : 1;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        aiming = ready();
        if (!aiming) {
            if (!lockOn.isOn()) {
                target = null;
            }
            return;
        }
        if (!lockOn.isOn() || !keeps(target)) {
            target = pickTarget();
        }
    }

    // Called by CameraMixin once a frame just before the view is placed. The turn shows in
    // the very frame it was made for and follows the target where it is drawn.
    public void onFrame(DeltaTracker delta) {
        if (!aiming || target == null || !inGame() || mc.gui.screen() != null) {
            return;
        }
        float partialTicks = delta.getGameTimeDeltaPartialTick(true);
        Vec3 point = aimAt(EntityUtil.lerpedBox(target, partialTicks), mc.player.getEyePosition(partialTicks));
        RotationManager.turnFrame(point, partialTicks, frameStep(delta));
    }

    // Snap turns all the way. Otherwise the turn speed covers the time the frame took.
    private float frameStep(DeltaTracker delta) {
        if (snap.isOn()) {
            return RotationManager.NO_STEP;
        }
        return (float) (speed.getValue() * delta.getRealtimeDeltaTicks() / SharedConstants.TICKS_PER_SECOND);
    }

    // Every reason the aim sits a tick out.
    private boolean ready() {
        if (!inGame() || mc.player.isSpectator() || mc.gui.screen() != null) {
            return false;
        }
        if (!whileUsing.isOn() && mc.player.isUsingItem()) {
            return false;
        }
        return !onlyHolding.isOn() || heldItems.contains(mc.player.getMainHandItem().getItem());
    }

    // A locked target is followed outside the field of view.
    private boolean keeps(Entity locked) {
        return locked != null && !locked.isRemoved() && eligible(locked)
            && (keepThroughWalls.isOn() || inSight(locked));
    }

    // The entity closest to where you already point rather than the nearest one.
    private Entity pickTarget() {
        Entity best = null;
        double bestAngle = fov.getValue() / 2;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!eligible(entity) || !inSight(entity)) {
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

    // Inside the range and allowed by every filter.
    private boolean eligible(Entity entity) {
        return entity != mc.player && mc.player.distanceTo(entity) <= range.getValue()
            && targets.attackable(entity, filter);
    }

    private boolean inSight(Entity entity) {
        return !lineOfSight.isOn() || mc.player.hasLineOfSight(entity);
    }

    private Vec3 aimAt(AABB box, Vec3 eyes) {
        Vec3 middle = box.getCenter();
        return switch (aimPoint.getValue()) {
            case AUTO -> nearestPoint(box, eyes);
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
}
