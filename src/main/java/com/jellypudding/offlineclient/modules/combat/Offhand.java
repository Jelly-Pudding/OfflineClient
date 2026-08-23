package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
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
        "Hold a totem instead at or below this many hearts. Zero turns it off.", 7, 0, 10, 0.5, " hearts");
    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks to wait between swaps.", 0, 0, 20, 1, " ticks");

    private int returnSlot = -1;
    private int timer;

    public Offhand() {
        super("Offhand", "Keeps a chosen item in your offhand.", Category.COMBAT);
        addSettings(item, totemHealth, delay);
        searchTags("totem", "crystal", "gapple");
    }

    @Override
    public String getSuffix() {
        return item.getValue().toString();
    }

    @Override
    protected void onEnable() {
        returnSlot = -1;
        timer = 0;
    }

    @Override
    protected void onDisable() {
        if (inGame() && returnSlot != -1 && InventoryUtil.canClick()
            && !InventoryUtil.carried().isEmpty()) {
            InventoryUtil.click(returnSlot);
        }
        returnSlot = -1;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.player.isSpectator()) {
            return;
        }
        boolean canClick = InventoryUtil.canClick();

        // A refused click can leave an item on the cursor.
        if (returnSlot != -1) {
            if (canClick && !InventoryUtil.carried().isEmpty()) {
                InventoryUtil.click(returnSlot);
            }
            if (!InventoryUtil.carried().isEmpty()) {
                return;
            }
            returnSlot = -1;
        }

        // AutoTotem owns the offhand whilst it is on.
        if (Modules.enabled(AutoTotem.class)) {
            return;
        }

        Item wanted = wantedItem();
        if (mc.player.getOffhandItem().is(wanted)) {
            return;
        }

        if (!canClick) {
            return;
        }
        // Anything already on the cursor belongs to the player.
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
            returnSlot = slot;
        }
        timer = delay.getInt();
    }

    private Item wantedItem() {
        float threshold = totemHealth.getFloat() * 2;
        // The totem already in the offhand counts.
        boolean haveTotem = mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING)
            || findSlot(Items.TOTEM_OF_UNDYING) != -1;
        if (threshold > 0
            && mc.player.getHealth() + mc.player.getAbsorptionAmount() <= threshold
            && haveTotem) {
            return Items.TOTEM_OF_UNDYING;
        }
        return item.getValue().item;
    }

    // Network slot of the first stack of the item. Minus one when absent.
    private int findSlot(Item wanted) {
        for (int i = 0; i < 36; i++) {
            if (mc.player.getInventory().getItem(i).is(wanted)) {
                return InventoryUtil.networkSlot(i);
            }
        }
        return -1;
    }
}
