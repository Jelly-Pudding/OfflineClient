package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.Cooldowns;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.Hands;
import com.jellypudding.offlineclient.util.ServerInfo;
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

import java.util.List;

public final class AutoMount extends Module {

    public enum Targets { ANY, LIST }

    // Ticks before the same ride is clicked again. One that turns you down is not asked every tick.
    private static final int RETRY_TICKS = 20;

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
        "How close a ride has to be.", 4, 1, 6, 0.1, " blocks");
    private final BoolSetting checkSaddle = new BoolSetting("Check saddle",
        "Skips any mob with no saddle on it. Llamas never need one.", false);
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Turns towards the ride on the server side.", true);

    private final Cooldowns<Integer> tried = new Cooldowns<>();
    private int wait;

    public AutoMount() {
        super("AutoMount", "Climbs onto the nearest rideable mob or vehicle.", Category.WORLD);
        addSettings(targets, entities, range, checkSaddle, rotate);
        searchTags("ride", "mount", "horse", "boat");
    }

    @Override
    protected void onEnable() {
        tried.clear();
        wait = 0;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (Hands.tied() || mc.player.isPassenger() || mc.player.isShiftKeyDown()) {
            return;
        }
        // A spawn egg on a mob makes a baby instead of a ride.
        if (mc.player.getMainHandItem().getItem() instanceof SpawnEggItem) {
            return;
        }
        // The server seats you a round trip after the click. Another ride clicked before then
        // would take you off this one.
        if (wait > 0) {
            wait--;
            return;
        }
        tried.tick();
        Entity ride = EntityUtil.nearest(range.getValue(), this::wanted);
        if (ride == null) {
            return;
        }
        EntityUtil.interact(ride, InteractionHand.MAIN_HAND, rotate.isOn());
        tried.put(ride.getId(), RETRY_TICKS);
        wait = ServerInfo.answerTicks();
    }

    private boolean wanted(Entity entity) {
        if (!entity.isAlive() || entity.isPassenger() || tried.contains(entity.getId())) {
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
