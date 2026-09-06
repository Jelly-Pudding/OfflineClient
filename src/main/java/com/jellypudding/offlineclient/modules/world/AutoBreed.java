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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.EntityHitResult;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Feeds the food in your hand to any animal that will take it.
public final class AutoBreed extends Module {

    public enum Age { ADULTS, BABIES, BOTH }

    public enum Hand { MAIN_HAND, OFF_HAND }

    private final RegistryListSetting<EntityType<?>> animals = new RegistryListSetting<>("Animals",
        "The animals to feed.", BuiltInRegistries.ENTITY_TYPE,
        List.of(EntityTypes.COW, EntityTypes.MOOSHROOM, EntityTypes.SHEEP, EntityTypes.PIG,
            EntityTypes.CHICKEN, EntityTypes.HORSE, EntityTypes.DONKEY, EntityTypes.WOLF,
            EntityTypes.CAT, EntityTypes.OCELOT, EntityTypes.RABBIT, EntityTypes.LLAMA,
            EntityTypes.TURTLE, EntityTypes.PANDA, EntityTypes.FOX, EntityTypes.BEE,
            EntityTypes.STRIDER, EntityTypes.HOGLIN, EntityTypes.GOAT, EntityTypes.AXOLOTL,
            EntityTypes.FROG, EntityTypes.CAMEL, EntityTypes.SNIFFER, EntityTypes.ARMADILLO));
    private final NumberSetting range = new NumberSetting("Range",
        "How close an animal has to be.", 4.5, 1, 6, 0.1).max(6);
    private final EnumSetting<Hand> hand = new EnumSetting<>("Hand",
        "Which hand holds the food.", Hand.MAIN_HAND);
    private final EnumSetting<Age> age = new EnumSetting<>("Age",
        "Which animals are fed.", Age.ADULTS)
        .describe(Age.ADULTS, "Grown animals. Feeding them breeds them.")
        .describe(Age.BABIES, "Young animals. Feeding them makes them grow faster.")
        .describe(Age.BOTH, "Every animal that will eat.");
    private final BoolSetting repeat = new BoolSetting("Feed again",
        "Feeds the same animal again once its cooldown has passed.", false);
    private final NumberSetting interval = new NumberSetting("Interval",
        "Ticks before the same animal is fed again. Love lasts thirty seconds and the cooldown five minutes.",
        6600, 100, 24000, 100, " ticks").min(1).under(repeat);
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Turn towards the animal on the server side.", true);

    private static final int FULL_COOLDOWN = 6600;

    // Every animal fed and the tick it was fed on.
    private final Map<Integer, Integer> fed = new LinkedHashMap<>();
    private int count;
    private int lastTick;

    public AutoBreed() {
        super("AutoBreed", "Breeds the animals around you with the food you hold.", Category.WORLD);
        addSettings(animals, range, hand, age, repeat, interval, rotate);
        searchTags("breed", "feed animals", "farm");
    }

    @Override
    public String getSuffix() {
        return count == 0 ? null : count + " fed";
    }

    @Override
    protected void onEnable() {
        fed.clear();
        count = 0;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.player.isSpectator() || mc.gui.screen() != null) {
            return;
        }
        int now = mc.player.tickCount;
        forgetOld(now);
        InteractionHand useHand = hand.is(Hand.OFF_HAND) ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        ItemStack food = mc.player.getItemInHand(useHand);
        if (food.isEmpty()) {
            return;
        }
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof Animal animal) || !wanted(animal, food)) {
                continue;
            }
            if (mc.player.distanceTo(animal) > range.getValue()) {
                continue;
            }
            if (rotate.isOn()) {
                BlockUtil.faceVector(animal.getBoundingBox().getCenter());
            }
            EntityHitResult hit = new EntityHitResult(animal, animal.getBoundingBox().getCenter());
            if (mc.gameMode.interact(mc.player, animal, hit, useHand).consumesAction()) {
                mc.player.swing(useHand);
                count++;
                fed.put(animal.getId(), now);
            }
            return;
        }
    }

    private boolean wanted(Animal animal, ItemStack food) {
        if (!animal.isAlive() || !animals.contains(animal.getType()) || fed.containsKey(animal.getId())) {
            return false;
        }
        boolean baby = animal.isBaby();
        if (age.is(Age.ADULTS) && baby || age.is(Age.BABIES) && !baby) {
            return false;
        }
        return animal.isFood(food);
    }

    // An animal only comes back onto the list when feeding again is allowed.
    private void forgetOld(int now) {
        // The tick count restarts on a respawn.
        if (now < lastTick) {
            fed.clear();
        }
        lastTick = now;
        // Without repeats an animal only returns after the game's longest cooldown.
        int keep = repeat.isOn() ? interval.getInt() : FULL_COOLDOWN;
        Iterator<Map.Entry<Integer, Integer>> it = fed.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue() >= keep) {
                it.remove();
            }
        }
    }
}
