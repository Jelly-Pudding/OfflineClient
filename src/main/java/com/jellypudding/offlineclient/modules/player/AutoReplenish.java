package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.ChatWarning;
import com.jellypudding.offlineclient.util.GearKind;
import com.jellypudding.offlineclient.util.Hands;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.ItemUtil;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.RestockErrand;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.function.Predicate;

// Tops up hotbar stacks by watching what each slot held last tick. A slot that runs out with
// none of its item left in the inventory can be filled from a shulker box or the ender chest.
public final class AutoReplenish extends Module {

    public enum Refills { EVERY_SLOT, HELD_SLOT, ONE_SLOT }

    // A slot that ran out with none of its item left to fill it and what it held.
    private record Want(int index, ItemStack stack, boolean sameEnchants) {
    }

    private static final int OFFHAND_INDEX = 9;
    // A box only goes down whilst you stand this still.
    private static final double STILL = 0.01;

    private final EnumSetting<Refills> refills = new EnumSetting<>("Refills",
        "Which slots are kept topped up.", Refills.EVERY_SLOT)
        .describe(Refills.EVERY_SLOT, "Every hotbar slot that runs low.")
        .describe(Refills.HELD_SLOT, "Only the slot you are holding.")
        .describe(Refills.ONE_SLOT, "Only the slot picked below.");
    private final NumberSetting slot = new NumberSetting("Slot",
        "The hotbar slot to keep filled. Ten is your offhand.", 1, 1, 10, 1).max(10)
        .under(refills, Refills.ONE_SLOT);
    private final RegistryListSetting<Item> pinned = new RegistryListSetting<>("Pinned items",
        "Items the chosen slot is filled with even whilst it holds something else. Leave it empty to keep whatever the slot had.",
        BuiltInRegistries.ITEM, List.of())
        .under(refills, Refills.HELD_SLOT, Refills.ONE_SLOT);
    private final NumberSetting threshold = new NumberSetting("Threshold",
        "Refills a stack once it drops to this many items.", 8, 1, 63, 1).max(64);
    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks to wait between refills.", 1, 0, 20, 1, " ticks");
    private final BoolSetting offhand = new BoolSetting("Offhand",
        "Also refills your offhand.", true);
    private final BoolSetting unstackable = new BoolSetting("Unstackables",
        "Replaces a used up single item like a totem or a pearl.", true);
    private final BoolSetting sameEnchants = new BoolSetting("Same enchantments",
        "Only replaces a used up single item with one carrying exactly the same enchantments.", true)
        .under(unstackable);
    private final BoolSetting fromHotbar = new BoolSetting("Search hotbar",
        "Pulls from other hotbar slots when the inventory has none.", false);
    private final BoolSetting searchShulkers = new BoolSetting("Search shulkers",
        "Sets down a shulker box you carry to take out the item a slot ran out of and mines the box back. It waits"
            + " until you stand still on the ground.", false);
    private final BoolSetting searchEnderChest = new BoolSetting("Search ender chest",
        "Sets down your ender chest for the item a slot ran out of. A shulker box kept in it that holds the item is"
            + " opened beside you and then put back. It needs a Silk Touch pickaxe in the hotbar to bring the chest"
            + " back.", false);
    private final RegistryListSetting<Item> excluded = new RegistryListSetting<>("Excluded",
        "Items that are never refilled. Click to pick them.", BuiltInRegistries.ITEM, List.of());
    private final NumberSetting repairAt = new NumberSetting("Swap worn tools",
        "Swaps a nearly spent tool for a fresher one of its kind once it has this many uses left."
            + " Zero leaves it in place.",
        0, 0, 100, 1, " uses");
    private final BoolSetting warnSpent = new BoolSetting("Warn when spent",
        "Says in chat when a worn tool has no fresher spare left.", true)
        .under(repairAt, () -> repairAt.getInt() > 0);

    // What each hotbar slot and the offhand held last tick.
    private final ItemStack[] previous = new ItemStack[10];
    private final ChatWarning warning = new ChatWarning();
    private final InventoryUtil.SlotSwap boxSlots = new InventoryUtil.SlotSwap();
    private final RestockErrand containers = new RestockErrand(boxSlots, "AutoReplenish", "you switch it on again");
    private Want want;
    // True from the first container of an errand until nothing is left to search.
    private boolean errand;
    // How many of the wanted item the inventory held as the errand began and the slots that held
    // any of it one bit each.
    private int fitsBefore;
    private long slotsBefore;
    private int timer;
    private boolean hadScreen;

    public AutoReplenish() {
        super("AutoReplenish", "Refills your hotbar stacks from your inventory.", Category.PLAYER);
        addSettings(refills, slot, pinned, threshold, delay, offhand, unstackable, sameEnchants,
            fromHotbar, searchShulkers, searchEnderChest, excluded, repairAt, warnSpent);
        searchTags("refill", "restock", "hotbar", "anti break", "shulker", "ender chest");
    }

    @Override
    protected void onEnable() {
        timer = 0;
        warning.clear();
        want = null;
        errand = false;
        containers.reset();
        hadScreen = mc.gui.screen() != null;
        if (inGame()) {
            snapshot();
        } else {
            for (int i = 0; i < previous.length; i++) {
                previous[i] = ItemStack.EMPTY;
            }
        }
    }

    @Override
    protected void onDisable() {
        containers.endErrand();
        boxSlots.restoreIfMine();
        want = null;
        errand = false;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (Hands.tied()) {
            return;
        }
        // The containers of an errand are worked with their own screens open. A meal or a fight
        // or ShulkerPacker holds it where it is.
        if (errand) {
            if (!Hands.taken() && !containers.runErrand(true)) {
                errand = false;
                finishErrand();
            }
            hadScreen = true;
            return;
        }
        boolean screen = mc.gui.screen() != null;
        // Items get moved around whilst a screen is open.
        if (hadScreen && !screen) {
            snapshot();
        }
        hadScreen = screen;
        if (!InventoryUtil.inventoryFree()) {
            return;
        }
        // A feeder shuffles stacks between the inventory and the hotbar. ShulkerPacker empties the
        // offhand and the slot its box leaves. A slot they emptied looks exactly like one that ran out.
        if (Hands.slotsTaken()) {
            snapshot();
            return;
        }
        if (want != null && fetchWant()) {
            snapshot();
            return;
        }
        if (timer > 0) {
            timer--;
            snapshot();
            return;
        }

        boolean offhandKept = Modules.keepsOffhand();
        boolean moved = false;
        int only = trackedSlot();
        if (only >= 0) {
            if (only != OFFHAND_INDEX || !offhandKept) {
                moved = check(only, stackAt(only));
            }
        } else {
            for (int i = 0; i < InventoryUtil.HOTBAR_SIZE && !moved; i++) {
                moved = check(i, mc.player.getInventory().getItem(i));
            }
            if (!moved && offhand.isOn() && !offhandKept) {
                moved = check(OFFHAND_INDEX, mc.player.getOffhandItem());
            }
        }
        snapshot();
        if (moved) {
            timer = delay.getInt();
        }
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
            if (!topUp) {
                noteWant(index, lookFor, matchEnchants);
            }
            return false;
        }
        if (hotbarLocked(source, index)) {
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
            if (hotbarLocked(source, index)) {
                continue;
            }
            return move(source, index);
        }
        if (now.isEmpty()) {
            noteWant(index, new ItemStack(pinned.chosen().getFirst()), false);
        }
        return false;
    }

    // A slot that ran out is filled from a container whilst a search may look for its item.
    private void noteWant(int index, ItemStack stack, boolean sameEnchants) {
        if ((searchShulkers.isOn() || searchEnderChest.isOn()) && want == null) {
            want = new Want(index, stack.copy(), sameEnchants);
        }
    }

    // True for a stack that fills the slot the errand is for.
    private boolean fills(ItemStack stack) {
        return !stack.isEmpty() && stack.is(want.stack().getItem()) && !tooWorn(stack)
            && (!want.sameEnchants() || stack.getEnchantments().equals(want.stack().getEnchantments()));
    }

    // Takes a stack for the slot that ran out out of a container once you stand still. One stack
    // is enough and the container is mined back as soon as it is in. True whilst the errand holds
    // the hotbar this tick.
    private boolean fetchWant() {
        if (!searchShulkers.isOn() && !searchEnderChest.isOn() || !stackAt(want.index()).isEmpty()) {
            want = null;
            return false;
        }
        // A stack that turned up since in a slot a refill may take from fills it.
        int source = refillSource();
        if (source != -1) {
            move(source, want.index());
            warning.clear();
            want = null;
            return true;
        }
        if (!mc.player.onGround() || mc.player.getDeltaMovement().horizontalDistanceSqr() > STILL * STILL
            || Hands.taken() || containers.busyElsewhere()) {
            return false;
        }
        // One more stack is enough.
        int before = fitsCarried();
        Predicate<ItemStack> fetch = stack -> fills(stack) && fitsCarried() <= before;
        if (!containers.couldRun(fetch, searchShulkers.isOn(), searchEnderChest.isOn())) {
            warning.say(searchEnderChest.isOn() && RestockErrand.needsPickaxe(fetch)
                ? "AutoReplenish needs a Silk Touch pickaxe in the hotbar to bring your ender chest back."
                : notFound());
            want = null;
            return false;
        }
        if (RestockErrand.errandSpot() == null) {
            warning.say("AutoReplenish has no room beside you to set a box down.");
            return false;
        }
        fitsBefore = before;
        slotsBefore = fittingSlots();
        containers.startErrand(fetch, searchShulkers.isOn(), searchEnderChest.isOn());
        errand = containers.runErrand(true);
        return errand;
    }

    private String notFound() {
        String where = !searchEnderChest.isOn() ? "shulker boxes"
            : searchShulkers.isOn() ? "shulker boxes or ender chest" : "ender chest";
        return "AutoReplenish found no " + ChatUtil.words(want.stack().getItem()) + " in your " + where + ".";
    }

    // The errand is over. A stack it brought goes into the slot that ran out from wherever it
    // landed. One that merged into a stack you already had stays there.
    private void finishErrand() {
        boxSlots.restoreIfMine();
        if (want != null && stackAt(want.index()).isEmpty()) {
            int brought = broughtSlot();
            if (brought != -1) {
                move(brought, want.index());
                warning.clear();
            } else if (fitsCarried() <= fitsBefore) {
                warning.say(notFound());
            }
        }
        want = null;
    }

    // A slot a refill may take from whose stack fills the slot that ran out. The hotbar counts
    // only with Search hotbar on. Minus one when none does.
    private int refillSource() {
        int lowest = fromHotbar.isOn() ? 0 : InventoryUtil.MAIN_START;
        for (int i = lowest; i < InventoryUtil.WHOLE_INVENTORY; i++) {
            if (i != ownIndex() && !hotbarLocked(i, want.index()) && fills(mc.player.getInventory().getItem(i))) {
                return i;
            }
        }
        return -1;
    }

    // A slot that holds a stack filling the slot that ran out and held none as the errand began.
    // Minus one when none does.
    private int broughtSlot() {
        for (int i = 0; i < InventoryUtil.WHOLE_INVENTORY; i++) {
            if (i != ownIndex() && (slotsBefore & 1L << i) == 0 && fills(mc.player.getInventory().getItem(i))) {
                return i;
            }
        }
        return -1;
    }

    // The slots holding a stack that fills the slot that ran out one bit each.
    private long fittingSlots() {
        long slots = 0;
        for (int i = 0; i < InventoryUtil.WHOLE_INVENTORY; i++) {
            if (fills(mc.player.getInventory().getItem(i))) {
                slots |= 1L << i;
            }
        }
        return slots;
    }

    private int fitsCarried() {
        return InventoryUtil.count(this::fills, InventoryUtil.WHOLE_INVENTORY);
    }

    // The inventory index of the slot that ran out. The offhand has none.
    private int ownIndex() {
        return want.index() == OFFHAND_INDEX ? -1 : want.index();
    }

    // A tool close to snapping trades places with the freshest spare of its kind. With no spare
    // it goes into an empty slot before it breaks and the slot it held stays empty.
    private boolean swapWorn(int index, ItemStack now) {
        if (!tooWorn(now)) {
            return false;
        }
        int spare = freshestSpare(index, now);
        if (spare != -1) {
            warning.clear();
            return move(spare, index);
        }
        if (warnSpent.isOn() && !carriesSpare(now)) {
            warning.say("Your " + ChatUtil.words(now.getItem()) + " is nearly spent and you carry no fresher one.");
        }
        for (int i = InventoryUtil.MAIN_START; i < InventoryUtil.WHOLE_INVENTORY; i++) {
            if (mc.player.getInventory().getItem(i).isEmpty()) {
                return InventoryUtil.swap(networkSlot(index), InventoryUtil.networkSlot(i))
                    != InventoryUtil.Swap.REFUSED;
            }
        }
        return false;
    }

    // The spare with the most uses left that is not itself worn out. The same item wins over
    // another of its kind. Minus one when there is none.
    private int freshestSpare(int index, ItemStack worn) {
        // The offhand is not part of the inventory index space.
        int own = index == OFFHAND_INDEX ? -1 : index;
        int best = -1;
        boolean bestSame = false;
        int bestUses = 0;
        int lowest = fromHotbar.isOn() ? 0 : InventoryUtil.MAIN_START;
        for (int i = lowest; i < InventoryUtil.WHOLE_INVENTORY; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (i == own || hotbarLocked(i, index) || !spareFor(worn, stack)) {
                continue;
            }
            boolean same = stack.is(worn.getItem());
            int uses = ItemUtil.usesLeft(stack);
            if (best == -1 || beats(same, uses, bestSame, bestUses)) {
                best = i;
                bestSame = same;
                bestUses = uses;
            }
        }
        return best;
    }

    // True when a fresher spare sits anywhere you carry it. Search hotbar only limits where a
    // swap may take one from.
    private boolean carriesSpare(ItemStack worn) {
        return InventoryUtil.findSlot(stack -> spareFor(worn, stack), InventoryUtil.WHOLE_INVENTORY) != -1
            || spareFor(worn, mc.player.getOffhandItem());
    }

    // A tool of the same kind that is not worn out itself.
    private boolean spareFor(ItemStack worn, ItemStack stack) {
        return GearKind.alike(worn, stack) && stack.isDamageableItem() && !tooWorn(stack);
    }

    // The same item beats another of its kind and then more uses win.
    private static boolean beats(boolean same, int uses, boolean bestSame, int bestUses) {
        return same != bestSame ? same : uses > bestUses;
    }

    // Only the held slot and the offhand may take from the hotbar.
    private static boolean hotbarLocked(int source, int index) {
        return source < InventoryUtil.HOTBAR_SIZE && index < InventoryUtil.HOTBAR_SIZE
            && index != InventoryUtil.selectedSlot();
    }

    private boolean tooWorn(ItemStack stack) {
        int limit = repairAt.getInt();
        return limit > 0 && ItemUtil.nearlyBroken(stack, limit);
    }

    private boolean move(int source, int index) {
        return InventoryUtil.swap(InventoryUtil.networkSlot(source), networkSlot(index))
            != InventoryUtil.Swap.REFUSED;
    }

    // The network slot of a hotbar index or the offhand.
    private static int networkSlot(int index) {
        return index == OFFHAND_INDEX ? InventoryUtil.OFFHAND_SLOT : InventoryUtil.networkSlot(index);
    }

    // Only exact matches merge into a partial stack. An emptied slot accepts
    // the same item with any data unless the enchantments have to match.
    private int findSource(ItemStack lookFor, int excludedIndex, boolean mustMerge,
                           boolean matchEnchants) {
        int best = -1;
        int bestCount = 0;
        int lowest = fromHotbar.isOn() ? 0 : InventoryUtil.MAIN_START;
        int selected = InventoryUtil.selectedSlot();
        for (int i = InventoryUtil.WHOLE_INVENTORY - 1; i >= lowest; i--) {
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
