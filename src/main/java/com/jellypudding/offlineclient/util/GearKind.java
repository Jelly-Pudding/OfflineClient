package com.jellypudding.offlineclient.util;

import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.TridentItem;

import java.util.function.Predicate;

// The kinds of gear one piece can stand in for another of. A stack belongs to the first kind
// that covers it.
public enum GearKind {
    HELMETS(Items.DIAMOND_HELMET, ItemTags.HEAD_ARMOR),
    CHESTPLATES(Items.DIAMOND_CHESTPLATE, ItemTags.CHEST_ARMOR),
    LEGGINGS(Items.DIAMOND_LEGGINGS, ItemTags.LEG_ARMOR),
    BOOTS(Items.DIAMOND_BOOTS, ItemTags.FOOT_ARMOR),
    SWORDS(Items.DIAMOND_SWORD, ItemTags.SWORDS),
    AXES(Items.DIAMOND_AXE, ItemTags.AXES),
    PICKAXES(Items.DIAMOND_PICKAXE, ItemTags.PICKAXES),
    SHOVELS(Items.DIAMOND_SHOVEL, ItemTags.SHOVELS),
    HOES(Items.DIAMOND_HOE, ItemTags.HOES),
    SPEARS(Items.DIAMOND_SPEAR, ItemTags.SPEARS),
    MACES(Items.MACE, stack -> stack.getItem() instanceof MaceItem),
    TRIDENTS(Items.TRIDENT, stack -> stack.getItem() instanceof TridentItem),
    BOWS(Items.BOW, stack -> stack.getItem() instanceof BowItem),
    CROSSBOWS(Items.CROSSBOW, stack -> stack.getItem() instanceof CrossbowItem),
    SHIELDS(Items.SHIELD, stack -> stack.getItem() instanceof ShieldItem),
    SHULKER_BOXES(Items.SHULKER_BOX, stack -> ItemUtil.isShulkerBox(stack.getItem()));

    private final Item sample;
    private final Predicate<ItemStack> covers;

    GearKind(Item sample, TagKey<Item> tag) {
        this(sample, stack -> stack.is(tag));
    }

    GearKind(Item sample, Predicate<ItemStack> covers) {
        this.sample = sample;
        this.covers = covers;
    }

    // An item of the kind for questions such as which enchantments the kind takes.
    public Item sample() {
        return sample;
    }

    // Null for an empty stack or anything that is no gear.
    public static GearKind of(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        for (GearKind kind : values()) {
            if (kind.covers.test(stack)) {
                return kind;
            }
        }
        return null;
    }

    public static boolean alike(ItemStack one, ItemStack two) {
        if (one.isEmpty() || two.isEmpty()) {
            return false;
        }
        if (one.is(two.getItem())) {
            return true;
        }
        GearKind kind = of(one);
        return kind != null && kind == of(two);
    }
}
