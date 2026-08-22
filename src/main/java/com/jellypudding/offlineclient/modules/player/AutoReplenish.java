package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.modules.combat.AutoTotem;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

/**
 * Tops up hotbar stacks from the rest of the inventory before they run
 * out. Watches what each slot held last tick.
 */
public final class AutoReplenish extends Module {

    private static final int OFFHAND_NETWORK_SLOT = 45;
    private static final int OFFHAND_INDEX = 9;

    private final NumberSetting threshold = new NumberSetting("Threshold",
        "Refill a stack once it drops to this many items.", 8, 1, 63, 1).max(64);
    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks to wait between refills.", 1, 0, 20, 1, " ticks");
    private final BoolSetting offhand = new BoolSetting("Offhand",
        "Also refill your offhand.", true);
    private final BoolSetting unstackable = new BoolSetting("Unstackables",
        "Replace a used up single item like a totem or a pearl with another one.", true);
    private final BoolSetting fromHotbar = new BoolSetting("Search hotbar",
        "Pull from other hotbar slots when the inventory has none.", false);

    /** What each hotbar slot and the offhand held last tick. */
    private final ItemStack[] previous = new ItemStack[10];
    private int timer;
    private boolean hadScreen;

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
        // Items get moved around while a screen is open. Start fresh once it closes.
        if (hadScreen && !screen) {
            snapshot();
        }
        hadScreen = screen;
        if (screen || mc.player.containerMenu.containerId != 0
            || !mc.player.containerMenu.getCarried().isEmpty()) {
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
        boolean totemBusy = OfflineClient.INSTANCE.getModuleManager().get(AutoTotem.class).isEnabled();
        if (!moved && offhand.isOn() && !totemBusy) {
            moved = check(OFFHAND_INDEX, mc.player.getOffhandItem());
        }
        snapshot();
        if (moved) {
            timer = delay.getInt();
        }
    }

    /** Refills one slot if it needs it. Returns true when a click was sent. */
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
            // The stack ran out completely since last tick.
            if (before.isStackable() || unstackable.isOn()) {
                lookFor = before;
            }
        }
        if (lookFor == null) {
            return false;
        }

        int source = findSource(lookFor, index, topUp);
        if (source == -1) {
            return false;
        }
        // Only the held slot and the offhand may take from the hotbar.
        if (source < 9 && index < 9 && index != mc.player.getInventory().getSelectedSlot()) {
            return false;
        }

        int target = index == OFFHAND_INDEX ? OFFHAND_NETWORK_SLOT : 36 + index;
        int from = source < 9 ? 36 + source : source;
        click(from);
        click(target);
        // Whatever did not fit goes back where it came from.
        if (!mc.player.containerMenu.getCarried().isEmpty()) {
            click(from);
        }
        return true;
    }

    /**
     * Inventory index of the best stack to pull from or minus one. Prefers the
     * biggest matching stack. Only exact matches can be merged into a
     * partial stack. An emptied slot accepts the same item with any data.
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

    private void click(int networkSlot) {
        mc.gameMode.handleContainerInput(0, networkSlot, 0, ContainerInput.PICKUP, mc.player);
    }

    private void snapshot() {
        for (int i = 0; i < 9; i++) {
            previous[i] = mc.player.getInventory().getItem(i).copy();
        }
        previous[OFFHAND_INDEX] = mc.player.getOffhandItem().copy();
    }
}
