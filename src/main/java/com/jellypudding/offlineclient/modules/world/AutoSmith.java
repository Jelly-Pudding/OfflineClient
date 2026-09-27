package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.module.StationModule;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.MenuClicks;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.function.Predicate;

// Upgrades diamond gear to netherite at an open smithing table one piece after another.
// Only the server knows the smithing recipes. The result shows up once it has answered.
public final class AutoSmith extends StationModule<SmithingMenu> {

    // Ticks between two rounds of clicks.
    private static final int PACE = 4;

    private final RegistryListSetting<Item> gear = new RegistryListSetting<>("Gear",
        "Diamond gear to upgrade. Enchantments carry over to the netherite piece.", BuiltInRegistries.ITEM,
        List.of(Items.DIAMOND_SWORD, Items.DIAMOND_PICKAXE, Items.DIAMOND_AXE, Items.DIAMOND_SHOVEL,
            Items.DIAMOND_HOE, Items.DIAMOND_SPEAR, Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE,
            Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS));

    private int upgraded;

    public AutoSmith() {
        super("AutoSmith", "Upgrades your diamond gear to netherite whilst a smithing table is open.",
            SmithingMenu.class, PACE);
        addSettings(gear);
        searchTags("smithing table", "netherite", "upgrade");
    }

    @Override
    public String getSuffix() {
        return count(upgraded, "upgraded");
    }

    @Override
    protected void onEnable() {
        super.onEnable();
        upgraded = 0;
    }

    @Override
    protected void work(SmithingMenu menu) {
        if (!clearForeign(menu, SmithingMenu.TEMPLATE_SLOT, AutoSmith::isTemplate)
            || !clearForeign(menu, SmithingMenu.ADDITIONAL_SLOT, AutoSmith::isIngot)
            || !clearForeign(menu, SmithingMenu.BASE_SLOT, this::isGear)) {
            return;
        }
        if (menu.getSlot(SmithingMenu.RESULT_SLOT).hasItem()) {
            if (moveOut(menu, SmithingMenu.RESULT_SLOT, "for the netherite piece.")) {
                upgraded++;
            }
            return;
        }
        boolean baseEmpty = !menu.getSlot(SmithingMenu.BASE_SLOT).hasItem();
        if (!ready(menu, SmithingMenu.BASE_SLOT, this::isGear, "Nothing left to upgrade.")
            || !ready(menu, SmithingMenu.TEMPLATE_SLOT, AutoSmith::isTemplate, "Out of netherite upgrade templates.")
            || !ready(menu, SmithingMenu.ADDITIONAL_SLOT, AutoSmith::isIngot, "Out of netherite ingots.")) {
            return;
        }
        fill(menu, SmithingMenu.TEMPLATE_SLOT, AutoSmith::isTemplate);
        fill(menu, SmithingMenu.ADDITIONAL_SLOT, AutoSmith::isIngot);
        if (baseEmpty) {
            fill(menu, SmithingMenu.BASE_SLOT, this::isGear);
            startClock();
            awaitServer(menu);
            return;
        }
        if (stalled()) {
            String refused = menu.getSlot(SmithingMenu.BASE_SLOT).getItem().getHoverName().getString();
            MenuClicks.quickMove(menu, SmithingMenu.BASE_SLOT);
            disable("The server will not upgrade " + refused + ".");
        }
    }

    // Moves out anything that does not belong in the slot. False once the module has stopped.
    private boolean clearForeign(SmithingMenu menu, int slot, Predicate<ItemStack> belongs) {
        ItemStack stack = menu.getSlot(slot).getItem();
        return stack.isEmpty() || belongs.test(stack) || moveOut(menu, slot, "to empty the smithing table.");
    }

    // True when the slot holds its part or the inventory has one to give. Nothing moves
    // until all three parts are there.
    private boolean ready(SmithingMenu menu, int slot, Predicate<ItemStack> fits, String missing) {
        if (menu.getSlot(slot).hasItem() || MenuClicks.firstInventorySlot(menu, fits) != -1) {
            return true;
        }
        disable(missing);
        return false;
    }

    // The whole stack goes in. Each upgrade takes one of it.
    private static void fill(SmithingMenu menu, int slot, Predicate<ItemStack> fits) {
        if (!menu.getSlot(slot).hasItem()) {
            MenuClicks.quickMove(menu, MenuClicks.firstInventorySlot(menu, fits));
        }
    }

    private boolean isGear(ItemStack stack) {
        return gear.contains(stack.getItem());
    }

    private static boolean isTemplate(ItemStack stack) {
        return stack.is(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE);
    }

    private static boolean isIngot(ItemStack stack) {
        return stack.is(ItemTags.NETHERITE_TOOL_MATERIALS);
    }
}
