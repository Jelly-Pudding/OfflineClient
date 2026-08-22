package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.module.ModuleManager;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * Keeps a chosen item in the offhand and swaps to a totem at low health.
 * Steps aside completely whilst AutoTotem is enabled.
 */
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

    private static final int OFFHAND_SLOT = 45;

    private final EnumSetting<Choice> item = new EnumSetting<>("Item",
        "What to keep in your offhand.", Choice.CRYSTAL);
    private final NumberSetting totemHealth = new NumberSetting("Totem health",
        "Hold a totem instead at or below this many hearts. 0 = never.", 7, 0, 10, 0.5, " hearts");
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

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.player.isSpectator()) {
            return;
        }
        // AutoTotem owns the offhand while it is on.
        ModuleManager modules = OfflineClient.INSTANCE.getModuleManager();
        if (modules != null && modules.get(AutoTotem.class).isEnabled()) {
            return;
        }

        // Finish the swap that started last tick.
        if (returnSlot != -1) {
            click(returnSlot);
            returnSlot = -1;
        }

        Item wanted = wantedItem();
        if (mc.player.getOffhandItem().is(wanted)) {
            return;
        }

        // Don't touch slots while a chest or similar container is open.
        if (mc.gui.screen() instanceof AbstractContainerScreen
            && !(mc.gui.screen() instanceof InventoryScreen
                || mc.gui.screen() instanceof CreativeModeInventoryScreen)) {
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

        boolean offhandEmpty = mc.player.getOffhandItem().isEmpty();
        click(slot);
        click(OFFHAND_SLOT);
        if (!offhandEmpty) {
            returnSlot = slot;
        }
        timer = delay.getInt();
    }

    /** The chosen item or a totem when health is low enough. */
    private Item wantedItem() {
        float threshold = totemHealth.getFloat() * 2;
        if (threshold > 0
            && mc.player.getHealth() + mc.player.getAbsorptionAmount() <= threshold
            && findSlot(Items.TOTEM_OF_UNDYING) != -1) {
            return Items.TOTEM_OF_UNDYING;
        }
        return item.getValue().item;
    }

    /** Network slot of the first stack of the item. Minus one when absent. */
    private int findSlot(Item wanted) {
        for (int i = 0; i < 36; i++) {
            if (mc.player.getInventory().getItem(i).is(wanted)) {
                // A hotbar slot maps to network slot 36 plus its index.
                return i < 9 ? 36 + i : i;
            }
        }
        return -1;
    }

    private void click(int networkSlot) {
        mc.gameMode.handleContainerInput(0, networkSlot, 0, ContainerInput.PICKUP, mc.player);
    }
}
