package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.module.StationModule;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.MenuClicks;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Locale;

// Enchants the chosen items one at a time at an open enchanting table. The table only
// shows offers once the server has worked them out for the item in the slot.
public final class AutoEnchant extends StationModule<EnchantmentMenu> {

    public enum Offer { TOP, MIDDLE, BOTTOM, BEST }

    // Ticks between two rounds of clicks.
    private static final int PACE = 4;

    private static final int ITEM_SLOT = 0;
    private static final int LAPIS_SLOT = 1;
    private static final int ROWS = 3;

    private final RegistryListSetting<Item> items = new RegistryListSetting<>("Items",
        "Items to enchant. Anything already enchanted is left alone.",
        BuiltInRegistries.ITEM, List.of(Items.BOOK));
    private final EnumSetting<Offer> offer = new EnumSetting<>("Offer",
        "Which of the three offers on the table to take.", Offer.BEST)
        .describe(Offer.TOP, "The cheapest offer at the top.")
        .describe(Offer.MIDDLE, "The offer in the middle.")
        .describe(Offer.BOTTOM, "The dearest offer at the bottom.")
        .describe(Offer.BEST, "The dearest offer you have the levels and lapis for.");

    // The item in the table is one the module put in.
    private boolean placedByUs;
    private int enchanted;

    public AutoEnchant() {
        super("AutoEnchant", "Enchants the items you pick whilst an enchanting table is open.",
            EnchantmentMenu.class, PACE);
        addSettings(items, offer);
        searchTags("enchanting table", "lapis");
    }

    @Override
    public String getSuffix() {
        return count(enchanted, "enchanted");
    }

    @Override
    protected void onEnable() {
        super.onEnable();
        enchanted = 0;
    }

    @Override
    protected void closed() {
        placedByUs = false;
    }

    @Override
    protected void work(EnchantmentMenu menu) {
        ItemStack held = menu.getSlot(ITEM_SLOT).getItem();
        if (held.isEmpty()) {
            putNext(menu);
            return;
        }
        if (!held.isEnchantable() || !items.contains(held.getItem())) {
            takeOut(menu, held);
            return;
        }
        if (!anyOffer(menu)) {
            if (stalled()) {
                disable("The table offers nothing for " + held.getHoverName().getString() + ".");
            }
            return;
        }
        int row = pickRow(menu);
        if (row == -1) {
            return;
        }
        if (!mc.player.hasInfiniteMaterials() && menu.getSlot(LAPIS_SLOT).getItem().getCount() < row + 1) {
            MenuClicks.quickMove(menu, MenuClicks.firstInventorySlot(menu, stack -> stack.is(Items.LAPIS_LAZULI)));
            return;
        }
        MenuClicks.pressButton(menu, row);
        awaitServer(menu);
    }

    private void putNext(EnchantmentMenu menu) {
        int slot = MenuClicks.firstInventorySlot(menu,
            stack -> items.contains(stack.getItem()) && stack.isEnchantable());
        if (slot == -1) {
            disable("Nothing left to enchant.");
            return;
        }
        // One item goes in even from a stack.
        MenuClicks.quickMove(menu, slot);
        placedByUs = true;
        startClock();
    }

    private void takeOut(EnchantmentMenu menu, ItemStack held) {
        if (!moveOut(menu, ITEM_SLOT, "for " + held.getHoverName().getString() + ".")) {
            return;
        }
        if (placedByUs) {
            enchanted++;
        }
        placedByUs = false;
    }

    // The row to press or minus one once the module has said why it stopped.
    private int pickRow(EnchantmentMenu menu) {
        if (offer.is(Offer.BEST)) {
            String reason = null;
            for (int row = ROWS - 1; row >= 0; row--) {
                if (!offered(menu, row)) {
                    continue;
                }
                reason = shortfall(menu, row);
                if (reason == null) {
                    return row;
                }
            }
            disable(reason);
            return -1;
        }
        int row = offer.getValue().ordinal();
        if (!offered(menu, row)) {
            String label = EnumSetting.label(offer.getValue()).toLowerCase(Locale.ROOT);
            disable("The table has no " + label + " offer for this item.");
            return -1;
        }
        String reason = shortfall(menu, row);
        if (reason != null) {
            disable(reason);
            return -1;
        }
        return row;
    }

    // Paper and the game both refuse a row that shows no enchantment.
    private static boolean offered(EnchantmentMenu menu, int row) {
        return menu.costs[row] > 0 && menu.enchantClue[row] >= 0;
    }

    private static boolean anyOffer(EnchantmentMenu menu) {
        for (int row = 0; row < ROWS; row++) {
            if (offered(menu, row)) {
                return true;
            }
        }
        return false;
    }

    // Null when the row can be paid for and otherwise what is missing. A row asks for its
    // cost in levels but only spends one to three levels and as much lapis.
    private String shortfall(EnchantmentMenu menu, int row) {
        if (mc.player.hasInfiniteMaterials()) {
            return null;
        }
        if (mc.player.experienceLevel < Math.max(menu.costs[row], row + 1)) {
            return "Not enough levels for the offer.";
        }
        int lapis = menu.getSlot(LAPIS_SLOT).getItem().getCount()
            + InventoryUtil.count(Items.LAPIS_LAZULI, InventoryUtil.WHOLE_INVENTORY);
        return lapis < row + 1 ? "Out of lapis lazuli." : null;
    }
}
