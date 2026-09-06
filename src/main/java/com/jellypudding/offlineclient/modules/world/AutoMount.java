package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.animal.equine.Llama;
import net.minecraft.world.entity.animal.equine.SkeletonHorse;
import net.minecraft.world.entity.animal.equine.ZombieHorse;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.monster.Strider;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.entity.vehicle.minecart.Minecart;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.phys.EntityHitResult;

import java.util.List;

// Climbs onto the first rideable thing beside you.
public final class AutoMount extends Module {

    public enum Targets { ANY, LIST }

    private final EnumSetting<Targets> targets = new EnumSetting<>("Targets",
        "Which rides count.", Targets.ANY)
        .describe(Targets.ANY, "Anything you can sit on.")
        .describe(Targets.LIST, "Only the types listed below.");
    private final RegistryListSetting<EntityType<?>> entities = new RegistryListSetting<>("Entities",
        "The rides to climb onto. Click to pick them.", BuiltInRegistries.ENTITY_TYPE,
        List.of(EntityTypes.HORSE, EntityTypes.DONKEY, EntityTypes.MULE,
            EntityTypes.CAMEL, EntityTypes.STRIDER, EntityTypes.PIG))
        .under(targets, Targets.LIST);
    private final NumberSetting range = new NumberSetting("Range",
        "How close a ride has to be.", 4, 1, 6, 0.1).min(1).max(6);
    private final BoolSetting checkSaddle = new BoolSetting("Check saddle",
        "Skips any mob with no saddle on it. Llamas never need one.", false);
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Turn towards the ride on the server side.", true);

    public AutoMount() {
        super("AutoMount", "Climbs onto the nearest rideable mob or vehicle.", Category.WORLD);
        addSettings(targets, entities, range, checkSaddle, rotate);
        searchTags("ride", "mount", "horse", "boat");
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.player.isPassenger() || mc.player.isShiftKeyDown()) {
            return;
        }
        // A spawn egg on a mob makes a baby instead of a ride.
        if (mc.player.getMainHandItem().getItem() instanceof SpawnEggItem) {
            return;
        }
        Entity ride = EntityUtil.nearest(range.getValue(), this::wanted);
        if (ride == null) {
            return;
        }
        if (rotate.isOn()) {
            BlockUtil.faceVector(ride.getBoundingBox().getCenter());
        }
        EntityHitResult hit = new EntityHitResult(ride, ride.getBoundingBox().getCenter());
        if (mc.gameMode.interact(mc.player, ride, hit, InteractionHand.MAIN_HAND).consumesAction()) {
            mc.player.swing(InteractionHand.MAIN_HAND);
        }
    }

    private boolean wanted(Entity entity) {
        if (!entity.isAlive() || entity.isPassenger()) {
            return false;
        }
        if (targets.is(Targets.LIST) ? !entities.contains(entity.getType()) : !rideable(entity)) {
            return false;
        }
        // These four throw you straight off with a bare back.
        if (entity instanceof Pig || entity instanceof Strider
            || entity instanceof SkeletonHorse || entity instanceof ZombieHorse) {
            return entity instanceof Mob mob && mob.isSaddled();
        }
        if (!checkSaddle.isOn() || entity instanceof Llama) {
            return true;
        }
        return !(entity instanceof Mob mob) || mob.isSaddled();
    }

    private static boolean rideable(Entity entity) {
        return entity instanceof AbstractHorse
            || entity instanceof Pig
            || entity instanceof Strider
            || entity instanceof AbstractBoat
            || entity instanceof Minecart;
    }
}
