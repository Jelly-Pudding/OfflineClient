package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.Swap;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

// Steps aside completely whilst AutoTotem is enabled.
public final class Offhand extends Module {

    public enum Choice {
        CRYSTAL("Crystal", Items.END_CRYSTAL),
        TOTEM("Totem", Items.TOTEM_OF_UNDYING),
        GAPPLE("Golden apple", Items.GOLDEN_APPLE),
        EGAPPLE("Enchanted apple", Items.ENCHANTED_GOLDEN_APPLE),
        SHIELD("Shield", Items.SHIELD);

        private final String label;
        private final Item item;

        Choice(String label, Item item) {
            this.label = label;
            this.item = item;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private final EnumSetting<Choice> item = new EnumSetting<>("Item",
        "What to keep in your offhand.", Choice.CRYSTAL);
    private final NumberSetting totemHealth = new NumberSetting("Totem health",
        "Swaps to a totem once you drop to this many hearts. Zero never swaps.",
        7, 0, 10, 0.5, " hearts");
    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks to wait between swaps.", 0, 0, 20, 1, " ticks");

    private final InventoryUtil.StrandedStack cursor = new InventoryUtil.StrandedStack();
    private int timer;

    public Offhand() {
        super("Offhand", "Keeps a chosen item in your offhand.", Category.COMBAT);
        addSettings(item, totemHealth, delay);
        searchTags("totem", "crystal", "gapple");
    }

    @Override
    public String getSuffix() {
        return item.getValueString();
    }

    @Override
    protected void onEnable() {
        timer = 0;
    }

    @Override
    protected void onDisable() {
        if (inGame()) {
            cursor.giveBack();
        }
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.player.isSpectator()) {
            return;
        }
        if (!cursor.recover()) {
            return;
        }

        // AutoTotem owns the offhand whilst it is on.
        if (Modules.enabled(AutoTotem.class)) {
            return;
        }

        Item wanted = wantedItem();
        if (mc.player.getOffhandItem().is(wanted)) {
            return;
        }

        if (!InventoryUtil.canClick()) {
            return;
        }
        if (!InventoryUtil.carried().isEmpty()) {
            return;
        }
        if (timer > 0) {
            timer--;
            return;
        }

        int slot = findSlot(wanted);
        if (slot == -1) {
            return;
        }

        Swap result = InventoryUtil.swap(slot, InventoryUtil.OFFHAND_SLOT);
        if (result == Swap.REFUSED) {
            return;
        }
        if (result == Swap.STRANDED) {
            // Whatever the new item displaced had nowhere to go.
            cursor.hold(slot);
        }
        timer = delay.getInt();
    }

    private Item wantedItem() {
        // The totem already in the offhand counts.
        boolean haveTotem = mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING)
            || findSlot(Items.TOTEM_OF_UNDYING) != -1;
        if (totemHealth.getFloat() > 0 && haveTotem && EntityUtil.healthAtOrBelow(totemHealth.getValue())) {
            return Items.TOTEM_OF_UNDYING;
        }
        return item.getValue().item;
    }

    // Network slot of the first stack of the item. Minus one when absent.
    private int findSlot(Item wanted) {
        int slot = InventoryUtil.findSlot(wanted, InventoryUtil.WHOLE_INVENTORY);
        return slot == -1 ? -1 : InventoryUtil.networkSlot(slot);
    }
}
