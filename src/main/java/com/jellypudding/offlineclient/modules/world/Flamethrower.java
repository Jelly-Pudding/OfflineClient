package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import com.jellypudding.offlineclient.util.ItemUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

// Sets fire to the food walking about. It drops cooked.
public final class Flamethrower extends Module {

    // Health at which the drops would burn up with the animal.
    private static final float SAVE_DROPS_HEALTH = 2;

    private final RegistryListSetting<EntityType<?>> entities = new RegistryListSetting<>("Entities",
        "Which animals to roast. Click to pick them.", BuiltInRegistries.ENTITY_TYPE,
        List.of(EntityTypes.PIG, EntityTypes.COW, EntityTypes.SHEEP,
            EntityTypes.CHICKEN, EntityTypes.RABBIT));
    private final NumberSetting range = new NumberSetting("Range",
        "How close an animal has to be.", 5, 1, 6, 0.1).min(1).max(6);
    private final NumberSetting interval = new NumberSetting("Interval",
        "Ticks between one light and the next.", 5, 1, 20, 1, " ticks").min(1);
    private final BoolSetting babies = new BoolSetting("Babies",
        "Also sets fire to young animals.", false);
    private final BoolSetting antiBreak = new BoolSetting("Anti break",
        "Leaves a flint and steel that is about to snap alone.", false);
    private final BoolSetting saveDrops = new BoolSetting("Save drops",
        "Puts the fire out once the animal is nearly dead so the meat survives.", true);
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Turn towards the animal on the server side.", true);

    private final SlotSwap slots = new SlotSwap();
    private int ticks;
    private int burnt;

    public Flamethrower() {
        super("Flamethrower", "Sets fire to the animals around you so they drop cooked meat.", Category.WORLD);
        addSettings(entities, range, interval, babies, antiBreak, saveDrops, rotate);
        searchTags("cook", "flint and steel", "burn", "roast");
    }

    @Override
    public String getSuffix() {
        return count(burnt);
    }

    @Override
    protected void onEnable() {
        ticks = 0;
        burnt = 0;
    }

    @Override
    protected void onDisable() {
        slots.restoreIfMine();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.player.isSpectator()) {
            return;
        }
        ticks++;
        Entity target = EntityUtil.nearest(range.getValue(), this::wanted);
        if (target == null) {
            slots.restoreIfMine();
            return;
        }
        BlockPos feet = target.blockPosition();
        BlockPos below = feet.below();
        BlockState standing = BlockUtil.state(feet);
        BlockState floor = BlockUtil.state(below);
        // Fire never takes on wet ground or on a worn path.
        if (!standing.getFluidState().isEmpty() || !floor.getFluidState().isEmpty()
            || floor.is(Blocks.DIRT_PATH)) {
            slots.restoreIfMine();
            return;
        }
        if (rotate.isOn()) {
            BlockUtil.faceVector(Vec3.atCenterOf(below));
        }
        if (saveDrops.isOn() && target instanceof LivingEntity living
            && living.getHealth() < SAVE_DROPS_HEALTH) {
            putOut(feet);
            slots.restoreIfMine();
            return;
        }
        // Tall grass in the way has to come out before the fire will sit there.
        if (standing.is(Blocks.SHORT_GRASS) || standing.is(Blocks.TALL_GRASS)) {
            mc.gameMode.startDestroyBlock(feet, Direction.DOWN);
        }
        if (ticks < interval.getInt() || target.isOnFire()) {
            slots.restoreIfMine();
            return;
        }
        light(below);
    }

    private void light(BlockPos below) {
        int slot = InventoryUtil.hotbarSlot(stack ->
            (stack.is(Items.FLINT_AND_STEEL) || stack.is(Items.FIRE_CHARGE))
                && (!antiBreak.isOn() || !ItemUtil.nearlyBroken(stack)));
        if (slot == -1) {
            slots.restoreIfMine();
            return;
        }
        slots.select(slot);
        BlockHitResult hit = new BlockHitResult(
            Vec3.atCenterOf(below).add(0, 0.5, 0), Direction.UP, below, false);
        if (mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit).consumesAction()) {
            mc.player.swing(InteractionHand.MAIN_HAND);
            burnt++;
        }
        ticks = 0;
        slots.restoreIfMine();
    }

    // Punching the fire out beats waiting for the animal to burn its own drops.
    private void putOut(BlockPos feet) {
        mc.gameMode.startDestroyBlock(feet, Direction.DOWN);
        for (Direction side : Direction.Plane.HORIZONTAL) {
            mc.gameMode.startDestroyBlock(feet.relative(side), Direction.DOWN);
        }
    }

    private boolean wanted(Entity entity) {
        if (!entities.contains(entity.getType()) || !entity.isAlive()) {
            return false;
        }
        if (entity.isInPowderSnow || entity.isInWaterOrRain() || entity.fireImmune()) {
            return false;
        }
        return babies.isOn() || !(entity instanceof LivingEntity living) || !living.isBaby();
    }
}
