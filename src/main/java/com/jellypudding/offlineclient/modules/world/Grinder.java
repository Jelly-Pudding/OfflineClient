package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.module.StationModule;
import com.jellypudding.offlineclient.setting.ChoiceListSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.ItemUtil;
import com.jellypudding.offlineclient.util.MenuClicks;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

import java.util.List;

// Strips the enchantments off the chosen items at an open grindstone one item at a time.
// The grindstone works its result out on this side as well and the item comes straight back.
public final class Grinder extends StationModule<GrindstoneMenu> {

    // Ticks between two rounds of clicks.
    private static final int PACE = 4;

    private static final int[] INPUT_SLOTS = {GrindstoneMenu.INPUT_SLOT, GrindstoneMenu.ADDITIONAL_SLOT};

    private final RegistryListSetting<Item> items = new RegistryListSetting<>("Items",
        "Items to strip of their enchantments. Curses cannot be ground off and stay.",
        BuiltInRegistries.ITEM, List.of(Items.ENCHANTED_BOOK, Items.BOW, Items.FISHING_ROD));
    private final ChoiceListSetting keep = new ChoiceListSetting("Keep",
        "Items carrying any of these enchantments are left alone.",
        ItemUtil::enchantmentIds, List.of("minecraft:mending", "minecraft:infinity"));

    private int ground;

    public Grinder() {
        super("Grinder", "Grinds the enchantments off the items you pick whilst a grindstone is open.",
            GrindstoneMenu.class, PACE);
        addSettings(items, keep);
        searchTags("grindstone", "disenchant", "experience");
    }

    @Override
    public String getSuffix() {
        return count(ground, "ground");
    }

    @Override
    protected void onEnable() {
        super.onEnable();
        ground = 0;
    }

    @Override
    protected void work(GrindstoneMenu menu) {
        if (menu.getSlot(GrindstoneMenu.RESULT_SLOT).hasItem() && onlyGrindable(menu)) {
            takeResult(menu);
            return;
        }
        // Anything else in the grindstone goes back to the inventory untouched.
        for (int slot : INPUT_SLOTS) {
            if (menu.getSlot(slot).hasItem() && !moveOut(menu, slot, "to empty the grindstone.")) {
                return;
            }
        }
        int next = MenuClicks.firstInventorySlot(menu, this::grindable);
        if (next == -1) {
            disable("Nothing left to grind.");
            return;
        }
        MenuClicks.quickMove(menu, next);
        if (menu.getSlot(GrindstoneMenu.RESULT_SLOT).hasItem()) {
            takeResult(menu);
        }
    }

    private void takeResult(GrindstoneMenu menu) {
        if (moveOut(menu, GrindstoneMenu.RESULT_SLOT, "for the ground item.")) {
            ground++;
        }
    }

    // An item you put in yourself is only ground when the module would have picked it.
    private boolean onlyGrindable(GrindstoneMenu menu) {
        for (int slot : INPUT_SLOTS) {
            ItemStack stack = menu.getSlot(slot).getItem();
            if (!stack.isEmpty() && !grindable(stack)) {
                return false;
            }
        }
        return true;
    }

    // A chosen item with something besides curses on it and nothing worth keeping.
    private boolean grindable(ItemStack stack) {
        if (!items.contains(stack.getItem())) {
            return false;
        }
        boolean strippable = false;
        for (Holder<Enchantment> enchantment : EnchantmentHelper.getEnchantmentsForCrafting(stack).keySet()) {
            if (keep.contains(enchantment.getRegisteredName())) {
                return false;
            }
            strippable |= !enchantment.is(EnchantmentTags.CURSE);
        }
        return strippable;
    }
}
