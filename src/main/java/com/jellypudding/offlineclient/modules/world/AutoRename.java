package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.module.StationModule;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.setting.TextSetting;
import com.jellypudding.offlineclient.util.MenuClicks;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.StringUtil;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.block.ShulkerBoxBlock;

import java.util.List;

// Renames the chosen items at an open anvil one stack after another. A rename costs one level
// plus the work the item has had before and the anvil never calls a rename too expensive.
// The anvil screen blanks the name each time a new item goes in and the name is typed again.
public final class AutoRename extends StationModule<AnvilMenu> {

    // Ticks between two rounds of clicks.
    private static final int PACE = 4;

    private final TextSetting name = new TextSetting("Name",
        "The name every chosen item gets. An anvil takes up to fifty letters.", "");
    private final RegistryListSetting<Item> items = new RegistryListSetting<>("Items",
        "Items to rename. A whole stack is renamed for the price of one.",
        BuiltInRegistries.ITEM, shulkerBoxes());

    private int renamed;

    public AutoRename() {
        super("AutoRename", "Gives the items you pick a name whilst an anvil is open.",
            AnvilMenu.class, PACE);
        addSettings(name, items);
        searchTags("anvil", "name", "shulker box", "brand");
    }

    private static List<Item> shulkerBoxes() {
        return BuiltInRegistries.ITEM.stream()
            .filter(item -> item instanceof BlockItem block && block.getBlock() instanceof ShulkerBoxBlock)
            .toList();
    }

    @Override
    public String getSuffix() {
        return count(renamed, "renamed");
    }

    @Override
    protected void onEnable() {
        super.onEnable();
        renamed = 0;
    }

    @Override
    protected void work(AnvilMenu menu) {
        // The server drops letters it does not allow in names. This does the same.
        String wanted = StringUtil.filterText(name.getValue()).strip();
        if (wanted.isEmpty()) {
            disable("Type the name to give first.");
            return;
        }
        if (wanted.length() > AnvilMenu.MAX_NAME_LENGTH) {
            disable("That name is too long for an anvil.");
            return;
        }
        if (menu.getSlot(AnvilMenu.ADDITIONAL_SLOT).hasItem()
            && !moveOut(menu, AnvilMenu.ADDITIONAL_SLOT, "to empty the anvil.")) {
            return;
        }
        ItemStack input = menu.getSlot(AnvilMenu.INPUT_SLOT).getItem();
        if (input.isEmpty()) {
            putNext(menu, wanted);
            return;
        }
        MenuClicks.rename(menu, wanted);
        if (!items.contains(input.getItem()) || !menu.getSlot(AnvilMenu.RESULT_SLOT).hasItem()) {
            // Not a chosen item or already named or not something an anvil will rename.
            moveOut(menu, AnvilMenu.INPUT_SLOT, "to empty the anvil.");
            return;
        }
        if (!mc.player.hasInfiniteMaterials() && mc.player.experienceLevel < menu.getCost()) {
            disable("Not enough levels. The next rename costs " + menu.getCost() + ".");
            return;
        }
        if (moveOut(menu, AnvilMenu.RESULT_SLOT, "for the renamed item.")) {
            renamed++;
        }
    }

    private void putNext(AnvilMenu menu, String wanted) {
        int slot = MenuClicks.firstInventorySlot(menu, stack -> items.contains(stack.getItem())
            && EnchantmentHelper.canStoreEnchantments(stack) && !stack.getHoverName().getString().equals(wanted));
        if (slot == -1) {
            disable("Nothing left to rename.");
            return;
        }
        MenuClicks.quickMove(menu, slot);
        MenuClicks.rename(menu, wanted);
        awaitServer(menu);
    }
}
