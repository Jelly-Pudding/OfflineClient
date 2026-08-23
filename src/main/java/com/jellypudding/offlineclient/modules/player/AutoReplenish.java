package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.module.ModuleManager;
import com.jellypudding.offlineclient.modules.combat.AutoTotem;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.InventoryUtil;
import net.minecraft.world.item.ItemStack;

// Tops up hotbar stacks by watching what each slot held last tick.
public final class AutoReplenish extends Module {

    private static final int OFFHAND_INDEX = 9;

    private final NumberSetting threshold = new NumberSetting("Threshold",
        "Refill a stack once it drops to this many items.", 8, 1, 63, 1).max(64);
    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks to wait between refills.", 1, 0, 20, 1, " ticks");
    private final BoolSetting offhand = new BoolSetting("Offhand",
        "Also refill your offhand.", true);
    private final BoolSetting unstackable = new BoolSetting("Unstackables",
        "Replace a used up single item like a totem or a pearl.", true);
    private final BoolSetting fromHotbar = new BoolSetting("Search hotbar",
        "Pull from other hotbar slots when the inventory has none.", false);

    // What each hotbar slot and the offhand held last tick.
    private final ItemStack[] previous = new ItemStack[10];
    private int timer;
    private boolean hadScreen;

    private AutoTotem autoTotem;
    private AutoEat autoEat;
    private AutoPotion autoPotion;

    public AutoReplenish() {
        super("AutoReplenish", "Refills your hotbar stacks from your inventory.", Category.PLAYER);
        addSettings(threshold, delay, offhand, unstackable, fromHotbar);
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
        boolean neighbours = findNeighbours();
        // AutoEat and AutoPotion shuffle stacks between the inventory and the
        // hotbar. A slot they emptied looks exactly like one that ran out.
        if (neighbours && (autoEat.isBusy() || autoPotion.isDrinking())) {
            snapshot();
            return;
        }
        if (timer > 0) {
            timer--;
            snapshot();
            return;
        }

        boolean moved = false;
        for (int i = 0; i < 9 && !moved; i++) {
            moved = check(i, mc.player.getInventory().getItem(i));
        }
        boolean totemBusy = neighbours && autoTotem.isEnabled();
        if (!moved && offhand.isOn() && !totemBusy) {
            moved = check(OFFHAND_INDEX, mc.player.getOffhandItem());
        }
        snapshot();
        if (moved) {
            timer = delay.getInt();
        }
    }

    // Cached. Each lookup walks every registered module.
    private boolean findNeighbours() {
        if (autoTotem != null) {
            return true;
        }
        ModuleManager modules = OfflineClient.INSTANCE.getModuleManager();
        if (modules == null) {
            return false;
        }
        autoTotem = modules.get(AutoTotem.class);
        autoEat = modules.get(AutoEat.class);
        autoPotion = modules.get(AutoPotion.class);
        return true;
    }

    private boolean check(int index, ItemStack now) {
        ItemStack before = previous[index] == null ? ItemStack.EMPTY : previous[index];
        int wanted = threshold.getInt();

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
        int source = findSource(lookFor, index == OFFHAND_INDEX ? -1 : index, topUp);
        if (source == -1) {
            return false;
        }
        // Only the held slot and the offhand may take from the hotbar.
        if (source < 9 && index < 9 && index != mc.player.getInventory().getSelectedSlot()) {
            return false;
        }

        int target = index == OFFHAND_INDEX
            ? InventoryUtil.OFFHAND_SLOT : InventoryUtil.networkSlot(index);
        int from = InventoryUtil.networkSlot(source);
        InventoryUtil.click(from);
        if (mc.player.containerMenu.getCarried().isEmpty()) {
            // The pickup was refused.
            return false;
        }
        InventoryUtil.click(target);
        if (!mc.player.containerMenu.getCarried().isEmpty()) {
            InventoryUtil.click(from);
        }
        return true;
    }

    /**
     * Only exact matches merge into a partial stack. An emptied slot accepts the
     * same item with any data.
     */
    private int findSource(ItemStack lookFor, int excludedIndex, boolean mustMerge) {
        int best = -1;
        int bestCount = 0;
        int lowest = fromHotbar.isOn() ? 0 : 9;
        int selected = mc.player.getInventory().getSelectedSlot();
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
            if (stack.getCount() > bestCount) {
                bestCount = stack.getCount();
                best = i;
            }
        }
        return best;
    }

    private void snapshot() {
        for (int i = 0; i < 9; i++) {
            previous[i] = mc.player.getInventory().getItem(i).copy();
        }
        previous[OFFHAND_INDEX] = mc.player.getOffhandItem().copy();
    }
}
