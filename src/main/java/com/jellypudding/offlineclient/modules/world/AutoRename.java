package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.module.StationModule;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.setting.TextSetting;
import com.jellypudding.offlineclient.util.ItemUtil;
import com.jellypudding.offlineclient.util.MenuClicks;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.StringUtil;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

import java.util.Iterator;
import java.util.List;

// Renames the chosen items at an open anvil one stack after another. A rename costs one level
// plus the work the item has had before and the anvil never calls a rename too expensive.
// The anvil screen blanks the name each time a new item goes in and the name is typed again.
public final class AutoRename extends StationModule<AnvilMenu> {

    public enum Source { TYPED, CONTENTS, NONE }

    // Ticks between two rounds of clicks.
    private static final int PACE = 4;

    // The words a typed name can count with.
    private static final String NUMBER = "{n}";
    private static final String COLUMN = "{column}";
    private static final String ROW = "{row}";

    private final EnumSetting<Source> source = new EnumSetting<>("Name from",
        "Where the name of each item comes from.", Source.TYPED)
        .describe(Source.TYPED, "Every chosen item gets the name typed below.")
        .describe(Source.CONTENTS, "A chosen shulker box or bundle with no name of its own is named after"
            + " the first item inside it.")
        .describe(Source.NONE, "Takes the names off the chosen items.");
    private final TextSetting name = new TextSetting("Name",
        "The name every chosen item gets. Write " + NUMBER + " for a number that counts up or " + COLUMN
            + " and " + ROW + " for a place in a wall. An anvil takes up to fifty letters.", "")
        .under(source, Source.TYPED);
    private final NumberSetting next = new NumberSetting("Next number",
        "The number the next item gets. Items are taken from the top left of your inventory on"
            + " and it moves on by one with every rename.", 1, 1, 64, 1)
        .under(name, this::counts);
    private final NumberSetting rowLength = new NumberSetting("Row length",
        "How many items make one row of the wall.", 8, 1, 16, 1)
        .under(name, () -> typed().contains(COLUMN) || typed().contains(ROW));
    private final RegistryListSetting<Item> items = new RegistryListSetting<>("Items",
        "Items to rename. A whole stack is renamed for the price of one.",
        BuiltInRegistries.ITEM, shulkerBoxes());

    private int renamed;

    public AutoRename() {
        super("AutoRename", "Gives the items you pick a name whilst an anvil is open.",
            AnvilMenu.class, PACE);
        addSettings(source, name, next, rowLength, items);
        searchTags("anvil", "name", "shulker box", "brand", "label", "number maps", "strip names");
    }

    private static List<Item> shulkerBoxes() {
        return BuiltInRegistries.ITEM.stream()
            .filter(ItemUtil::isShulkerBox)
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
        if (source.is(Source.TYPED) && typed().isEmpty()) {
            disable("Type the name to give first.");
            return;
        }
        if (menu.getSlot(AnvilMenu.ADDITIONAL_SLOT).hasItem()
            && !moveOut(menu, AnvilMenu.ADDITIONAL_SLOT, "to empty the anvil.")) {
            return;
        }
        ItemStack input = menu.getSlot(AnvilMenu.INPUT_SLOT).getItem();
        if (input.isEmpty()) {
            putNext(menu);
            return;
        }
        String wanted = nameFor(input);
        if (wanted == null) {
            moveOut(menu, AnvilMenu.INPUT_SLOT, "to empty the anvil.");
            return;
        }
        if (tooLong(wanted)) {
            return;
        }
        MenuClicks.rename(menu, wanted);
        if (!menu.getSlot(AnvilMenu.RESULT_SLOT).hasItem()) {
            String refused = input.getHoverName().getString();
            if (moveOut(menu, AnvilMenu.INPUT_SLOT, "to empty the anvil.")) {
                disable("The anvil will not rename " + refused + ".");
            }
            return;
        }
        if (!mc.player.hasInfiniteMaterials() && mc.player.experienceLevel < menu.getCost()) {
            disable("Not enough levels. The next rename costs " + menu.getCost() + ".");
            return;
        }
        if (moveOut(menu, AnvilMenu.RESULT_SLOT, "for the renamed item.")) {
            renamed++;
            if (source.is(Source.TYPED) && counts()) {
                next.setValue(next.getValue() + 1);
                OfflineClient.INSTANCE.getConfigManager().saveSoon();
            }
        }
    }

    private void putNext(AnvilMenu menu) {
        int slot = MenuClicks.firstInventorySlot(menu, stack -> nameFor(stack) != null);
        if (slot == -1) {
            disable("Nothing left to rename.");
            return;
        }
        String wanted = nameFor(menu.getSlot(slot).getItem());
        if (tooLong(wanted)) {
            return;
        }
        MenuClicks.quickMove(menu, slot);
        MenuClicks.rename(menu, wanted);
        awaitServer(menu);
    }

    // True once the module has stopped over a name the anvil would throw away.
    private boolean tooLong(String wanted) {
        if (wanted.length() <= AnvilMenu.MAX_NAME_LENGTH) {
            return false;
        }
        disable("The name " + wanted + " is too long for an anvil.");
        return true;
    }

    // The name the anvil should give the stack. An empty one takes its name off. Null for a
    // stack that is not chosen or carries the right name or is nothing an anvil will rename.
    private String nameFor(ItemStack stack) {
        if (!items.contains(stack.getItem()) || !EnchantmentHelper.canStoreEnchantments(stack)) {
            return null;
        }
        boolean named = stack.has(DataComponents.CUSTOM_NAME);
        return switch (source.getValue()) {
            case NONE -> named ? "" : null;
            // A name somebody gave it stays.
            case CONTENTS -> named ? null : differing(stack, firstInside(stack));
            // A numbered name differs for every item. One that carries a name has had its number.
            case TYPED -> counts() ? (named ? null : numbered(typed())) : differing(stack, typed());
        };
    }

    // Null for no name at all and for the one the stack already shows.
    private static String differing(ItemStack stack, String wanted) {
        return wanted.isEmpty() || stack.getHoverName().getString().equals(wanted) ? null : wanted;
    }

    // The server drops letters it does not allow in names. This does the same.
    private String typed() {
        return StringUtil.filterText(name.getValue()).strip();
    }

    private boolean counts() {
        String typed = typed();
        return typed.contains(NUMBER) || typed.contains(COLUMN) || typed.contains(ROW);
    }

    // Columns and rows count from one like the number itself.
    private String numbered(String typed) {
        int place = next.getInt() - 1;
        int width = rowLength.getInt();
        return typed.replace(NUMBER, String.valueOf(place + 1))
            .replace(COLUMN, String.valueOf(place % width + 1))
            .replace(ROW, String.valueOf(place / width + 1));
    }

    // The name of the first item a box or bundle holds cut to what an anvil takes. Empty for
    // an item that holds nothing.
    private static String firstInside(ItemStack stack) {
        ItemContainerContents box = stack.get(DataComponents.CONTAINER);
        BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
        Iterator<ItemStackTemplate> held = box != null ? box.nonEmptyItems().iterator()
            : bundle != null ? bundle.items().iterator() : null;
        if (held == null || !held.hasNext()) {
            return "";
        }
        String shown = held.next().create().getHoverName().getString(AnvilMenu.MAX_NAME_LENGTH);
        return StringUtil.filterText(shown).strip();
    }
}
