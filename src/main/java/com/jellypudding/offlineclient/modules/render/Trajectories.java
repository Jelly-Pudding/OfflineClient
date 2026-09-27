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
import com.jellypudding.offlineclient.util.ProjectilePath;
import com.jellypudding.offlineclient.util.ProjectilePath.Launch;
import com.jellypudding.offlineclient.util.ProjectilePath.Path;
import com.jellypudding.offlineclient.util.ProjectilePath.Shot;
import com.jellypudding.offlineclient.util.ProjectileUtil;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.hurtingprojectile.WitherSkull;
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
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

// Uses the same launch speed and gravity and drag as the real projectile.
public final class Trajectories extends Module {

    // Launches only a held item makes. ProjectilePath knows the rest.
    private static final Launch CROSSBOW_ARROW = ProjectilePath.ARROW.withPower(ProjectileUtil.CROSSBOW_SPEED);
    private static final Launch FIREWORK = new Launch(1.6, 0, 1, 1, 0, ProjectilePath.Motion.ARROW, false);
    private static final Launch BOBBER = new Launch(0, 0.03, 0.92, 0, 0, ProjectilePath.Motion.BOBBER, true);

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

    private final RegistryListSetting<Item> items = new RegistryListSetting<>("Items",
        "Which held items get an arc.", BuiltInRegistries.ITEM, throwableItems());
    private final BoolSetting otherPlayers = new BoolSetting("Other players",
        "Also show where other players are aiming.", true);
    private final BoolSetting firedProjectiles = new BoolSetting("Fired projectiles",
        "Also predict the rest of the flight of projectiles already in the air.", false);
    private final BoolSetting ignoreWitherSkulls = new BoolSetting("Ignore wither skulls",
        "Wither skulls get no arc.", false).under(firedProjectiles);
    private final NumberSetting skipFirstTicks = new NumberSetting("Skip first ticks",
        "Starts your own arc a few ticks out instead of in your face.",
        3, 0, 20, 1, " ticks").min(0);
    private final NumberSetting steps = new NumberSetting("Steps",
        "How many ticks of flight to predict.", 200, 20, 500, 10).min(1);
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
        int skip = player == mc.player ? skipFirstTicks.getInt() : 0;
        int pierce = stack.getItem() instanceof ProjectileWeaponItem
            ? ItemUtil.enchantLevel(Enchantments.PIERCING, stack) : 0;
        drawPath(batch, ProjectilePath.fly(player, launch, leaveHand(player, launch, partialTicks, 0), pierce,
            steps.getInt()), partialTicks, skip);
        if (stack.getItem() instanceof CrossbowItem && ItemUtil.enchantLevel(Enchantments.MULTISHOT, stack) > 0) {
            for (double angle : new double[] {MULTISHOT_ANGLE, -MULTISHOT_ANGLE}) {
                drawPath(batch, ProjectilePath.fly(player, launch, leaveHand(player, launch, partialTicks, angle),
                    pierce, steps.getInt()), partialTicks, skip);
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
        Path path = ProjectilePath.of(projectile, steps.getInt());
        if (path == null) {
            return;
        }
        if (!path.points().isEmpty()) {
            path.points().set(0, projectile.getPosition(partialTicks));
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
            return ProjectilePath.ARROW.withPower(charge * ProjectilePath.ARROW.power());
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
            return ProjectilePath.TRIDENT;
        }
        if (item instanceof SnowballItem || item instanceof EggItem || item instanceof EnderpearlItem) {
            return ProjectilePath.THROWABLE;
        }
        if (item instanceof ExperienceBottleItem) {
            return ProjectilePath.XP_BOTTLE;
        }
        if (item instanceof ThrowablePotionItem) {
            return ProjectilePath.POTION;
        }
        if (item instanceof WindChargeItem) {
            return ProjectilePath.WIND_CHARGE;
        }
        if (item instanceof FishingRodItem) {
            return BOBBER;
        }
        return null;
    }

    // The position and speed the projectile starts with. The angle turns the aim left or right.
    private static Shot leaveHand(Player shooter, Launch launch, float partialTicks, double angle) {
        return ProjectilePath.leaveHand(shooter, launch, shooter.getPosition(partialTicks),
            shooter.getYRot(partialTicks) + angle, shooter.getXRot(partialTicks));
    }
}
