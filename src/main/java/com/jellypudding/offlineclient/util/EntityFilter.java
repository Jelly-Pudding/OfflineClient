package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.setting.Setting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;

import java.util.List;

// The choice of which entities a render module marks. Players and mobs and dropped
// items each have their own switch and can be narrowed to a picked list.
public final class EntityFilter {

    public enum Pick { NONE, ALL, CHOSEN }

    private final BoolSetting players;
    private final EnumSetting<Pick> mobs;
    private final RegistryListSetting<EntityType<?>> mobTypes;
    private final EnumSetting<Pick> items;
    private final RegistryListSetting<Item> itemTypes;
    private final RegistryListSetting<EntityType<?>> others;

    // False hides the dropped item rows for a module that only aims at or hits things.
    private final boolean withItems;

    // The verb reads as "Highlight" and the past form as "highlighted".
    // They make the descriptions fit whatever the module does to an entity.
    public EntityFilter(String verb, String past, boolean playersDefault, Pick mobsDefault,
                        Pick itemsDefault, List<EntityType<?>> othersDefault) {
        this(verb, past, playersDefault, mobsDefault, itemsDefault, othersDefault, true);
    }

    // A filter with no dropped item rows.
    public static EntityFilter living(String verb, String past, boolean playersDefault,
                                      Pick mobsDefault, List<EntityType<?>> othersDefault) {
        return new EntityFilter(verb, past, playersDefault, mobsDefault, Pick.NONE, othersDefault, false);
    }

    private EntityFilter(String verb, String past, boolean playersDefault, Pick mobsDefault,
                         Pick itemsDefault, List<EntityType<?>> othersDefault, boolean withItems) {
        this.withItems = withItems;
        players = new BoolSetting("Players", verb + " other players.", playersDefault);
        mobs = new EnumSetting<>("Mobs", "Which mobs are " + past + ".", mobsDefault)
            .describe(Pick.NONE, "No mobs.")
            .describe(Pick.ALL, "Every mob.")
            .describe(Pick.CHOSEN, "Only the mobs picked below.");
        mobTypes = new RegistryListSetting<>("Mob types", "The mobs that are " + past + ".",
            BuiltInRegistries.ENTITY_TYPE, List.of());
        mobTypes.under(mobs, Pick.CHOSEN);
        items = new EnumSetting<>("Items", "Which dropped items are " + past + ".", itemsDefault)
            .describe(Pick.NONE, "No dropped items.")
            .describe(Pick.ALL, "Every dropped item.")
            .describe(Pick.CHOSEN, "Only drops of the items picked below.");
        itemTypes = new RegistryListSetting<>("Item types", "The items whose drops are " + past + ".",
            BuiltInRegistries.ITEM, List.of());
        itemTypes.under(items, Pick.CHOSEN);
        others = new RegistryListSetting<>("Other entities",
            "Other kinds of entity that are " + past + " such as crystals and boats.",
            BuiltInRegistries.ENTITY_TYPE, othersDefault);
    }

    public Setting<?>[] settings() {
        if (!withItems) {
            return new Setting<?>[] {players, mobs, mobTypes, others};
        }
        return new Setting<?>[] {players, mobs, mobTypes, items, itemTypes, others};
    }

    public boolean wantsPlayers() {
        return players.isOn();
    }

    public boolean matches(Entity entity) {
        Player self = OfflineClient.MC.player;
        if (self == null || entity == self) {
            return false;
        }
        if (entity instanceof Player player) {
            return players.isOn() && player.isAlive() && !player.isSpectator();
        }
        if (entity instanceof ItemEntity drop) {
            return picked(items, itemTypes, drop.getItem().getItem()) || others.contains(entity.getType());
        }
        if (entity instanceof LivingEntity living) {
            return living.isAlive()
                && (picked(mobs, mobTypes, entity.getType()) || others.contains(entity.getType()));
        }
        return others.contains(entity.getType());
    }

    private static <T> boolean picked(EnumSetting<Pick> pick, RegistryListSetting<T> chosen, T entry) {
        return switch (pick.getValue()) {
            case NONE -> false;
            case ALL -> true;
            case CHOSEN -> chosen.contains(entry);
        };
    }
}
