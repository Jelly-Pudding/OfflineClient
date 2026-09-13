package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.modules.combat.AutoTotem;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;

// Tops up hotbar stacks by watching what each slot held last tick.
public final class AutoReplenish extends Module {

    public enum Refills { EVERY_SLOT, HELD_SLOT, ONE_SLOT }

    private static final int OFFHAND_INDEX = 9;

    private final EnumSetting<Refills> refills = new EnumSetting<>("Refills",
        "Which slots are kept topped up.", Refills.EVERY_SLOT)
        .describe(Refills.EVERY_SLOT, "Every hotbar slot that runs low.")
        .describe(Refills.HELD_SLOT, "Only the slot you are holding.")
        .describe(Refills.ONE_SLOT, "Only the slot picked below.");
    private final NumberSetting slot = new NumberSetting("Slot",
        "The hotbar slot to keep filled. Ten is your offhand.", 1, 1, 10, 1)
        .under(refills, Refills.ONE_SLOT);
    private final RegistryListSetting<Item> pinned = new RegistryListSetting<>("Pinned items",
        "Items the chosen slot is filled with even whilst it holds something else. Leave it empty to keep whatever the slot had.",
        BuiltInRegistries.ITEM, List.of())
        .under(refills, Refills.HELD_SLOT, Refills.ONE_SLOT);
    private final NumberSetting threshold = new NumberSetting("Threshold",
        "Refill a stack once it drops to this many items.", 8, 1, 63, 1).max(64);
    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks to wait between refills.", 1, 0, 20, 1, " ticks");
    private final BoolSetting offhand = new BoolSetting("Offhand",
        "Also refill your offhand.", true);
    private final BoolSetting unstackable = new BoolSetting("Unstackables",
        "Replace a used up single item like a totem or a pearl.", true);
    private final BoolSetting sameEnchants = new BoolSetting("Same enchantments",
        "Only replaces a used up single item with one carrying exactly the same enchantments.", true)
        .under(unstackable);
    private final BoolSetting fromHotbar = new BoolSetting("Search hotbar",
        "Pull from other hotbar slots when the inventory has none.", false);
    private final RegistryListSetting<Item> excluded = new RegistryListSetting<>("Excluded",
        "Items that are never refilled. Click to pick them.", BuiltInRegistries.ITEM, List.of());
    private final NumberSetting repairAt = new NumberSetting("Swap worn tools",
        "Move a tool into your inventory once it has this many uses left so it can be mended. Zero leaves it in place.",
        0, 0, 100, 1, " uses").max(2000);

    // What each hotbar slot and the offhand held last tick.
    private final ItemStack[] previous = new ItemStack[10];
    private int timer;
    private boolean hadScreen;

    public AutoReplenish() {
        super("AutoReplenish", "Refills your hotbar stacks from your inventory.", Category.PLAYER);
        addSettings(refills, slot, pinned, threshold, delay, offhand, unstackable, sameEnchants,
            fromHotbar, excluded, repairAt);
        searchTags("refill", "restock", "hotbar");
    }

    @Override
    protected void onEnable() {
        timer = 0;
        hadScreen = mc.gui.screen() != null;
        if (inGame()) {
            snapshot();
        } else {
            for (int i = 0; i < previous.length; i++) {
                previous[i] = ItemStack.EMPTY;
            }
        }
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.player.isSpectator()) {
            return;
        }
        boolean screen = mc.gui.screen() != null;
        // Items get moved around whilst a screen is open.
        if (hadScreen && !screen) {
            snapshot();
        }
        hadScreen = screen;
        if (screen || mc.player.containerMenu.containerId != 0
            || !mc.player.containerMenu.getCarried().isEmpty()) {
            return;
        }
        // AutoEat and AutoGap and AutoPotion shuffle stacks between the inventory
        // and the hotbar. A slot they emptied looks exactly like one that ran out.
        if (feederBusy()) {
            snapshot();
            return;
        }
        if (timer > 0) {
            timer--;
            snapshot();
            return;
        }

        boolean totemBusy = Modules.enabled(AutoTotem.class);
        boolean moved = false;
        int only = trackedSlot();
        if (only >= 0) {
            if (only != OFFHAND_INDEX || !totemBusy) {
                moved = check(only, stackAt(only));
            }
        } else {
            for (int i = 0; i < InventoryUtil.HOTBAR_SIZE && !moved; i++) {
                moved = check(i, mc.player.getInventory().getItem(i));
            }
            if (!moved && offhand.isOn() && !totemBusy) {
                moved = check(OFFHAND_INDEX, mc.player.getOffhandItem());
            }
        }
        snapshot();
        if (moved) {
            timer = delay.getInt();
        }
    }

    private static boolean feederBusy() {
        return Modules.feeding(null);
    }

    // The one slot the settings pin us to or minus one for the whole hotbar.
    private int trackedSlot() {
        return switch (refills.getValue()) {
            case EVERY_SLOT -> -1;
            case HELD_SLOT -> InventoryUtil.selectedSlot();
            case ONE_SLOT -> slot.getInt() - 1;
        };
    }

    private ItemStack stackAt(int index) {
        return index == OFFHAND_INDEX
            ? mc.player.getOffhandItem() : mc.player.getInventory().getItem(index);
    }

    private boolean check(int index, ItemStack now) {
        if (swapWorn(index, now)) {
            return true;
        }
        ItemStack before = previous[index];
        if (excluded.contains(now.getItem()) || excluded.contains(before.getItem())) {
            return false;
        }
        int wanted = threshold.getInt();

        if (pinned.size() > 0 && index == trackedSlot()) {
            return fillPinned(index, now, wanted);
        }

        ItemStack lookFor = null;
        boolean topUp = false;
        if (!now.isEmpty() && now.isStackable() && now.getCount() <= wanted
            && now.getCount() < now.getMaxStackSize()) {
            lookFor = now;
            topUp = true;
        } else if (now.isEmpty() && !before.isEmpty()) {
            if (before.isStackable() || unstackable.isOn()) {
                lookFor = before;
            }
        }
        if (lookFor == null) {
            return false;
        }

        // The offhand is not part of the inventory index space. Passing its own
        // index would skip a real backpack slot.
        boolean matchEnchants = !topUp && !lookFor.isStackable() && sameEnchants.isOn();
        int source = findSource(lookFor, index == OFFHAND_INDEX ? -1 : index, topUp, matchEnchants);
        if (source == -1) {
            return false;
        }
        // Only the held slot and the offhand may take from the hotbar.
        if (source < InventoryUtil.HOTBAR_SIZE && index < InventoryUtil.HOTBAR_SIZE
            && index != InventoryUtil.selectedSlot()) {
            return false;
        }

        return move(source, index);
    }

    // A pinned slot takes the first listed item the rest of the inventory still holds.
    private boolean fillPinned(int index, ItemStack now, int wanted) {
        if (!now.isEmpty() && pinned.contains(now.getItem())
            && (now.getCount() > wanted || now.getCount() >= now.getMaxStackSize())) {
            return false;
        }
        for (Item item : pinned.chosen()) {
            int source = InventoryUtil.findSlot(stack -> stack.is(item) && !tooWorn(stack),
                InventoryUtil.WHOLE_INVENTORY);
            if (source == -1 || source == index) {
                continue;
            }
            if (source < InventoryUtil.HOTBAR_SIZE && index < InventoryUtil.HOTBAR_SIZE
                && index != InventoryUtil.selectedSlot()) {
                continue;
            }
            return move(source, index);
        }
        return false;
    }

    // A tool close to snapping is put away before it breaks.
    private boolean swapWorn(int index, ItemStack now) {
        if (!tooWorn(now)) {
            return false;
        }
        for (int i = InventoryUtil.HOTBAR_SIZE; i < InventoryUtil.WHOLE_INVENTORY; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.isEmpty() || !stack.isDamageableItem()) {
                return move(i, index);
            }
        }
        return false;
    }

    private boolean tooWorn(ItemStack stack) {
        int limit = repairAt.getInt();
        return limit > 0 && !stack.isEmpty() && stack.isDamageableItem()
            && stack.getMaxDamage() - stack.getDamageValue() <= limit;
    }

    private boolean move(int source, int index) {
        int target = index == OFFHAND_INDEX
            ? InventoryUtil.OFFHAND_SLOT : InventoryUtil.networkSlot(index);
        return InventoryUtil.swap(InventoryUtil.networkSlot(source), target)
            != InventoryUtil.Swap.REFUSED;
    }

    // Only exact matches merge into a partial stack. An emptied slot accepts
    // the same item with any data unless the enchantments have to match.
    private int findSource(ItemStack lookFor, int excludedIndex, boolean mustMerge,
                           boolean matchEnchants) {
        int best = -1;
        int bestCount = 0;
        int lowest = fromHotbar.isOn() ? 0 : 9;
        int selected = InventoryUtil.selectedSlot();
        for (int i = 35; i >= lowest; i--) {
            if (i == excludedIndex || i == selected) {
                continue;
            }
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.isEmpty() || !stack.is(lookFor.getItem())) {
                continue;
            }
            if (mustMerge && !ItemStack.isSameItemSameComponents(stack, lookFor)) {
                continue;
            }
            if (matchEnchants && !stack.getEnchantments().equals(lookFor.getEnchantments())) {
                continue;
            }
            if (stack.getCount() > bestCount) {
                bestCount = stack.getCount();
                best = i;
            }
        }
        return best;
    }

    private void snapshot() {
        for (int i = 0; i < InventoryUtil.HOTBAR_SIZE; i++) {
            previous[i] = mc.player.getInventory().getItem(i).copy();
        }
        previous[OFFHAND_INDEX] = mc.player.getOffhandItem().copy();
    }
}
