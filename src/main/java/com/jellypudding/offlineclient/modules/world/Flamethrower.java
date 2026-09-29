package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.Ignition;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import com.jellypudding.offlineclient.util.UseBudget;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.BlockHitResult;

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
        "How close an animal has to be.", 5, 1, 6, 0.1).min(1);
    private final NumberSetting interval = new NumberSetting("Interval",
        "Ticks between one light and the next.", 5, 1, 20, 1, " ticks").min(1);
    private final BoolSetting babies = new BoolSetting("Babies",
        "Also sets fire to young animals.", false);
    private final Ignition ignition = new Ignition();
    private final BoolSetting saveDrops = new BoolSetting("Save drops",
        "Puts the fire out on a nearly dead animal to save its drops.", true);
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Turn towards the fire on the server side.", true);

    private final SlotSwap slots = new SlotSwap();
    private int ticks;
    private int burnt;

    public Flamethrower() {
        super("Flamethrower", "Sets fire to nearby animals for cooked meat.", Category.WORLD);
        addSettings(entities, range, interval, babies);
        addSettings(ignition.settings());
        addSettings(saveDrops, rotate);
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
        if (saveDrops.isOn() && target instanceof LivingEntity living
            && living.getHealth() < SAVE_DROPS_HEALTH) {
            putOut(feet);
            slots.restoreIfMine();
            return;
        }
        if (Ignition.clear(feet) || ticks < interval.getInt() || target.isOnFire()
            || !Ignition.fireFits(feet) || UseBudget.remaining() == 0) {
            slots.restoreIfMine();
            return;
        }
        light(feet);
    }

    private void light(BlockPos feet) {
        BlockHitResult hit = Ignition.fireClick(feet, Direction.DOWN);
        if (ignition.use(slots, hand -> Ignition.strike(hit, hand, rotate.isOn()))) {
            burnt++;
        }
        ticks = 0;
        slots.restoreIfMine();
    }

    // Punching the fire out beats waiting for the animal to burn its own drops.
    private static void putOut(BlockPos feet) {
        Ignition.putOut(feet);
        for (Direction side : Direction.Plane.HORIZONTAL) {
            Ignition.putOut(feet.relative(side));
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
