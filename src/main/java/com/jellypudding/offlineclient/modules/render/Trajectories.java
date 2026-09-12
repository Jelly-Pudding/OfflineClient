package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.ItemUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.entity.projectile.hurtingprojectile.AbstractHurtingProjectile;
import net.minecraft.world.entity.projectile.hurtingprojectile.WitherSkull;
import net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.AbstractWindCharge;
import net.minecraft.world.entity.projectile.throwableitemprojectile.AbstractThrownPotion;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrowableItemProjectile;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownExperienceBottle;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.EggItem;
import net.minecraft.world.item.EnderpearlItem;
import net.minecraft.world.item.ExperienceBottleItem;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.SnowballItem;
import net.minecraft.world.item.ThrowablePotionItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.WindChargeItem;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.item.enchantment.Enchantments;
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
        // Fireballs and wind charges. Fall then move then slow down.
        HURTING,
        // Fishing bobbers. Fall then move then slow down.
        BOBBER
    }

    private record Launch(double power, double gravity, double airDrag, double waterDrag,
                          double pitchOffset, Motion motion, boolean stopsInWater) {

        private Launch withPower(double newPower) {
            return new Launch(newPower, gravity, airDrag, waterDrag, pitchOffset, motion, stopsInWater);
        }

        private Launch weightless() {
            return new Launch(power, 0, airDrag, waterDrag, pitchOffset, motion, stopsInWater);
        }
    }

    private static final Launch ARROW = new Launch(3, 0.05, 0.99, 0.6, 0, Motion.ARROW, false);
    private static final Launch CROSSBOW_ARROW = new Launch(3.15, 0.05, 0.99, 0.6, 0, Motion.ARROW, false);
    private static final Launch FIREWORK = new Launch(1.6, 0, 1, 1, 0, Motion.ARROW, false);
    private static final Launch TRIDENT = new Launch(2.5, 0.05, 0.99, 0.99, 0, Motion.ARROW, false);
    private static final Launch THROWABLE = new Launch(1.5, 0.03, 0.99, 0.8, 0, Motion.THROWN, false);
    private static final Launch POTION = new Launch(0.5, 0.05, 0.99, 0.8, -20, Motion.THROWN, false);
    private static final Launch XP_BOTTLE = new Launch(0.7, 0.07, 0.99, 0.8, -20, Motion.THROWN, false);
    private static final Launch WIND_CHARGE = new Launch(1.5, 0, 1, 1, 0, Motion.HURTING, false);
    private static final Launch EXPLOSIVE = new Launch(0, 0, 0.95, 0.8, 0, Motion.HURTING, false);
    private static final Launch BOBBER = new Launch(0, 0.03, 0.92, 0, 0, Motion.BOBBER, true);

    private static final int COLOR_BLOCK = 0xFF40FF60;
    private static final int COLOR_ENTITY = 0xFFFF4040;
    // A multishot crossbow fires its side arrows this far off the middle one.
    private static final double MULTISHOT_ANGLE = 10;
    // Half the width of the landing marker.
    private static final double MARKER_HALF = 0.25;
    // How thick the flat landing marker is. Its faces need a direction.
    private static final double MARKER_DEPTH = 0.005;
    // Other players further off than this get no arc.
    private static final double OTHER_RANGE_SQ = 64 * 64;

    private record Path(List<Vec3> points, HitResult.Type type, List<Entity> hits, BlockHitResult landing) {
    }

    // Where a projectile is at one moment in its flight.
    private record Shot(Vec3 pos, Vec3 velocity) {
    }

    private final RegistryListSetting<Item> items = new RegistryListSetting<>("Items",
        "Which held items get an arc.", BuiltInRegistries.ITEM, throwableItems());
    private final BoolSetting otherPlayers = new BoolSetting("Other players",
        "Also show where other players are aiming.", true);
    private final BoolSetting firedProjectiles = new BoolSetting("Fired projectiles",
        "Also predict the rest of the flight of projectiles already in the air.", false);
    private final BoolSetting ignoreWitherSkulls = new BoolSetting("Ignore wither skulls",
        "Wither skulls get no arc.", false).under(firedProjectiles);
    private final NumberSetting skipFirstTicks = new NumberSetting("Skip first ticks",
        "Leaves out the first points of your own arc so the line does not start in your face.",
        3, 0, 20, 1, " ticks").min(0);
    private final NumberSetting steps = new NumberSetting("Steps",
        "How many ticks of flight to predict.", 200, 20, 500, 10).min(1).max(2000);
    private final BoxStyle style = new BoxStyle(BoxStyle.Shape.BOTH, 35f);
    private final BoolSetting resultColour = new BoolSetting("Colour by result",
        "The arc turns green when it lands on a block and red when it hits something.", true);
    private final BoolSetting landingMarker = new BoolSetting("Landing marker",
        "Draw a flat square on the block face the projectile lands on.", true);
    private final BoolSetting positionBoxes = new BoolSetting("Position boxes",
        "Draw a tiny box at every predicted tick along the arc.", false);
    private final NumberSetting positionBoxSize = new NumberSetting("Position box size",
        "Half the size of those boxes.", 0.02, 0.01, 0.1, 0.01, " blocks").under(positionBoxes);
    private final BoxStyle positionStyle = new BoxStyle("Position", BoxStyle.Shape.BOTH, 35f).under(positionBoxes);

    public Trajectories() {
        super("Trajectories", "Shows the path a thrown or shot item will take.", Category.RENDER);
        addSettings(items, otherPlayers, firedProjectiles, ignoreWitherSkulls, skipFirstTicks, steps);
        addSettings(style.settings());
        addSettings(resultColour, landingMarker, positionBoxes, positionBoxSize);
        addSettings(positionStyle.settings());
        searchTags("bow", "arrow", "aim");
    }

    // Every item that can be shot or thrown.
    private static List<Item> throwableItems() {
        List<Item> list = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            if (item instanceof ProjectileWeaponItem || item instanceof FishingRodItem
                || item instanceof TridentItem || item instanceof SnowballItem || item instanceof EggItem
                || item instanceof EnderpearlItem || item instanceof ExperienceBottleItem
                || item instanceof ThrowablePotionItem || item instanceof WindChargeItem) {
                list.add(item);
            }
        }
        return list;
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!inGame()) {
            return;
        }
        float partialTicks = event.getPartialTicks();
        draw(event.getBatch(), mc.player, partialTicks);
        if (otherPlayers.isOn()) {
            for (Player player : mc.level.players()) {
                if (player != mc.player && !player.isSpectator()
                    && player.distanceToSqr(mc.player) <= OTHER_RANGE_SQ) {
                    draw(event.getBatch(), player, partialTicks);
                }
            }
        }
        if (firedProjectiles.isOn()) {
            for (Entity entity : mc.level.entitiesForRendering()) {
                if (entity instanceof Projectile projectile) {
                    drawFired(event.getBatch(), projectile, partialTicks);
                }
            }
        }
    }

    // Also called by BowAimbot for its own arc whilst this module is off.
    public void draw(DrawBatch batch, Player player, float partialTicks) {
        ItemStack stack = player.getMainHandItem();
        Launch launch = launchFor(player, stack);
        if (launch == null) {
            stack = player.getOffhandItem();
            launch = launchFor(player, stack);
        }
        if (launch == null) {
            return;
        }
        int skip = player == mc.player ? skipFirstTicks.getInt() : 0;
        int pierce = stack.getItem() instanceof ProjectileWeaponItem
            ? ItemUtil.enchantLevel(Enchantments.PIERCING, stack) : 0;
        drawPath(batch, fly(player, launch, leaveHand(player, launch, partialTicks, 0), pierce),
            partialTicks, skip);
        if (stack.getItem() instanceof CrossbowItem && ItemUtil.enchantLevel(Enchantments.MULTISHOT, stack) > 0) {
            for (double angle : new double[] {MULTISHOT_ANGLE, -MULTISHOT_ANGLE}) {
                drawPath(batch, fly(player, launch, leaveHand(player, launch, partialTicks, angle), pierce),
                    partialTicks, skip);
            }
        }
    }

    private void drawFired(DrawBatch batch, Projectile projectile, float partialTicks) {
        if (ignoreWitherSkulls.isOn() && projectile instanceof WitherSkull) {
            return;
        }
        // A trident flying home on loyalty and an arrow stuck in a block go nowhere new.
        if (projectile instanceof AbstractArrow arrow && (arrow.isNoPhysics() || arrow.isInGround())) {
            return;
        }
        Launch launch = launchFor(projectile);
        if (launch == null) {
            return;
        }
        if (projectile.isNoGravity()) {
            launch = launch.weightless();
        }
        int pierce = projectile instanceof AbstractArrow arrow ? arrow.getPierceLevel() : 0;
        Vec3 start = projectile.getPosition(partialTicks);
        Path path = fly(projectile, launch, new Shot(projectile.position(), projectile.getDeltaMovement()), pierce);
        if (!path.points().isEmpty()) {
            path.points().set(0, start);
        }
        drawPath(batch, path, partialTicks, 0);
    }

    private void drawPath(DrawBatch batch, Path path, float partialTicks, int skip) {
        List<Vec3> points = path.points();
        if (points.size() < 2) {
            return;
        }
        int line = style.lineColor();
        int fill = line;
        if (resultColour.isOn() && path.type() != HitResult.Type.MISS) {
            line = path.type() == HitResult.Type.BLOCK ? COLOR_BLOCK : COLOR_ENTITY;
            fill = line;
        }
        int first = points.size() <= skip ? 0 : skip;
        for (int i = first + 1; i < points.size(); i++) {
            batch.line(points.get(i - 1), points.get(i), line, true);
            if (positionBoxes.isOn()) {
                double half = positionBoxSize.getValue();
                Vec3 point = points.get(i);
                positionStyle.draw(batch, new AABB(point.subtract(half, half, half), point.add(half, half, half)), true);
            }
        }
        if (landingMarker.isOn() && path.landing() != null) {
            style.draw(batch, marker(path.landing()), line, fill, true);
        }
        for (Entity hit : path.hits()) {
            style.draw(batch, EntityUtil.lerpedBox(hit, partialTicks), COLOR_ENTITY, COLOR_ENTITY, true);
        }
    }

    // A half block square lying flat on the face that was hit.
    private static AABB marker(BlockHitResult hit) {
        Vec3 at = hit.getLocation();
        Direction.Axis axis = hit.getDirection().getAxis();
        double x = axis == Direction.Axis.X ? MARKER_DEPTH : MARKER_HALF;
        double y = axis == Direction.Axis.Y ? MARKER_DEPTH : MARKER_HALF;
        double z = axis == Direction.Axis.Z ? MARKER_DEPTH : MARKER_HALF;
        return new AABB(at.subtract(x, y, z), at.add(x, y, z));
    }

    // Null if the item cannot be thrown or shot or is not on the list.
    private Launch launchFor(Player player, ItemStack stack) {
        if (stack.isEmpty() || !items.contains(stack.getItem())) {
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
            return ARROW.withPower(charge * ARROW.power());
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

    // The position and speed the projectile starts with. The angle turns the aim left or right.
    private Shot leaveHand(Player shooter, Launch launch, float partialTicks, double angle) {
        double yaw = shooter.getYRot(partialTicks) + angle;
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
    // A piercing arrow passes through that many entities before it stops.
    private Path fly(Entity shooter, Launch launch, Shot shot, int pierce) {
        Vec3 pos = shot.pos();
        Vec3 velocity = shot.velocity();

        List<Vec3> points = new ArrayList<>();
        List<Entity> hits = new ArrayList<>();
        points.add(pos);
        HitResult.Type type = HitResult.Type.MISS;
        BlockHitResult landing = null;
        int minY = mc.level.getMinY();
        int maxSteps = steps.getInt();
        int piercesLeft = pierce;

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

            boolean stopped = false;
            for (EntityHitResult entityHit : ProjectileUtil.getManyEntityHitResult(mc.level, shooter, previous, end,
                new AABB(previous, end).inflate(1),
                entity -> entity != shooter && !entity.isSpectator() && entity.isAlive() && entity.isPickable(), false)) {
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
