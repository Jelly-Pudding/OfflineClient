package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.ChatFormatting;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.equine.AbstractChestedHorse;
import net.minecraft.world.entity.animal.equine.SkeletonHorse;
import net.minecraft.world.entity.animal.fox.Fox;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.GlowItemFrame;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.decoration.painting.Painting;
import net.minecraft.world.entity.monster.Strider;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

// Reads the entities the server shows you for signs of players. Every rule was checked against
// the structure templates and natural spawning of 26.3. The world makes no pearl or boat or
// painting or name at all and its only item frames hang in End ships.
public final class EntityClues {

    // The clue an entity gives and the words read after found such as a painting.
    public record Clue(BaseClue clue, String what) {
    }

    // One test of an entity. It hands back the words or null when the entity tells nothing.
    private record Rule(BaseClue clue, Function<Entity, String> test) {
    }

    // Village armourers set out one of these pieces on its own.
    private static final Map<EquipmentSlot, Item> VILLAGE_ARMOUR = Map.of(EquipmentSlot.CHEST,
        Items.IRON_CHESTPLATE, EquipmentSlot.HEAD, Items.IRON_HELMET);

    // Villagers start at this level. Only trading raises it.
    private static final int FIRST_LEVEL = 1;

    // Tried in this order. A traded villager with a name reads as a villager.
    private static final List<Rule> RULES = List.of(new Rule(BaseClue.DECORATION, EntityClues::decoration),
        new Rule(BaseClue.PEARL, EntityClues::pearl), new Rule(BaseClue.BOAT, EntityClues::boat),
        new Rule(BaseClue.VILLAGER, EntityClues::villager), new Rule(BaseClue.NAMED, EntityClues::named),
        new Rule(BaseClue.PET, EntityClues::pet));

    private EntityClues() {
    }

    // The first wanted clue the entity gives. Null when it gives none.
    public static Clue judge(Entity entity, Set<BaseClue> wanted) {
        for (Rule rule : RULES) {
            if (wanted.contains(rule.clue())) {
                String what = rule.test().apply(entity);
                if (what != null) {
                    return new Clue(rule.clue(), what);
                }
            }
        }
        return null;
    }

    private static String decoration(Entity entity) {
        if (entity instanceof ItemFrame frame) {
            return frame(frame);
        }
        if (entity instanceof Painting) {
            return "a painting";
        }
        return entity instanceof ArmorStand stand && dressed(stand) ? "a dressed armour stand" : null;
    }

    // An End ship hangs a plain frame with an elytra that stays empty once looted.
    private static String frame(ItemFrame frame) {
        ItemStack item = frame.getItem();
        boolean glow = frame instanceof GlowItemFrame;
        if (!glow && frame.level().dimension() == Level.END && (item.isEmpty() || item.is(Items.ELYTRA))) {
            return null;
        }
        String kind = glow ? "glow item frame" : "item frame";
        if (item.isEmpty()) {
            return "an empty " + kind;
        }
        String held = ChatUtil.words(item.getItem());
        return ChatUtil.withArticle(kind) + " holding " + ChatUtil.withArticle(held);
    }

    // Wearing or holding anything but a lone village armour piece. A bare stand may be a looted one.
    private static boolean dressed(ArmorStand stand) {
        Map.Entry<EquipmentSlot, Item> only = null;
        int worn = 0;
        for (EquipmentSlot slot : EquipmentSlot.VALUES) {
            ItemStack stack = stand.getItemBySlot(slot);
            if (!stack.isEmpty()) {
                worn++;
                only = Map.entry(slot, stack.getItem());
            }
        }
        return worn > 1 || (worn == 1 && VILLAGE_ARMOUR.get(only.getKey()) != only.getValue());
    }

    // A pearl kept for later in a stasis chamber. Your own never count.
    private static String pearl(Entity entity) {
        boolean stasis = entity instanceof ThrownEnderpearl pearl && pearl.getOwner() != OfflineClient.MC.player
            && ProjectileUtil.inStasis(pearl);
        return stasis ? "an ender pearl held in a bubble column" : null;
    }

    // A boat somebody rides is only passing through. One you rode is your own.
    private static String boat(Entity entity) {
        boolean parked = entity instanceof AbstractBoat && !carriesPlayer(entity) && !Ridden.has(entity);
        return parked ? ChatUtil.withArticle(typeName(entity)) : null;
    }

    private static String villager(Entity entity) {
        if (entity instanceof Villager villager && villager.getVillagerData().level() > FIRST_LEVEL) {
            return "a level " + villager.getVillagerData().level() + " villager";
        }
        return null;
    }

    private static String named(Entity entity) {
        if (!(entity instanceof LivingEntity living) || living instanceof Player || !living.hasCustomName()
            || (living instanceof Mob mob && EntityUtil.isYours(mob))) {
            return null;
        }
        String given = ChatFormatting.stripFormatting(living.getCustomName().getString());
        return ChatUtil.withArticle(typeName(living)) + " named " + given;
    }

    // Tamed or saddled or leashed or carrying a chest. Your own pets and mounts and one ridden
    // in passing never count.
    private static String pet(Entity entity) {
        if (!(entity instanceof Mob mob) || EntityUtil.isYours(mob) || carriesPlayer(mob)) {
            return null;
        }
        String kind = typeName(mob);
        Entity holder = mob.getLeashHolder();
        // A wandering trader leads its own llamas.
        if (holder != null && holder != OfflineClient.MC.player && !(holder instanceof WanderingTrader)) {
            return "a leashed " + kind;
        }
        if (mob instanceof AbstractChestedHorse horse && horse.hasChest()) {
            return ChatUtil.withArticle(kind) + " carrying a chest";
        }
        if (!kept(mob)) {
            return null;
        }
        return (mob.isSaddled() ? "a saddled " : "a tamed ") + kind;
    }

    // A skeleton trap tames its horses and strider jockeys spawn saddled. A fox only shows
    // whether it trusts you.
    private static boolean kept(Mob mob) {
        if (mob instanceof Strider || mob instanceof Fox) {
            return false;
        }
        if (mob instanceof SkeletonHorse) {
            return mob.isSaddled();
        }
        return EntityUtil.isPet(mob);
    }

    private static boolean carriesPlayer(Entity entity) {
        return entity.hasPassenger(passenger -> passenger instanceof Player);
    }

    private static String typeName(Entity entity) {
        return ChatUtil.words(entity.getType());
    }
}
