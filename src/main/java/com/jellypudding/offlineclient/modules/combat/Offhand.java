package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.DamageUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.Swap;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

// Steps aside whilst AutoTotem has claimed the offhand for a totem.
public final class Offhand extends Module {

    private static final double HEART = 2;
    private static final int HOTBAR_SIZE = InventoryUtil.HOTBAR_SIZE;

    public enum Choice {
        CRYSTAL("Crystal", Items.END_CRYSTAL),
        TOTEM("Totem", Items.TOTEM_OF_UNDYING),
        GAPPLE("Golden apple", Items.GOLDEN_APPLE),
        EGAPPLE("Enchanted apple", Items.ENCHANTED_GOLDEN_APPLE),
        SHIELD("Shield", Items.SHIELD),
        POTION("Potion", Items.POTION);

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

    public enum Quick {
        NONE, CLICK_APPLE, SWORD_CLICK_APPLE, SWORD_APPLE, SWORD_CLICK_POTION, SWORD_POTION
    }

    private final EnumSetting<Choice> item = new EnumSetting<>("Item",
        "What to keep in your offhand.", Choice.CRYSTAL);
    private final BoolSetting hotbar = new BoolSetting("Hotbar",
        "Takes items from the hotbar too. Off leaves your hotbar stacks alone.", false);
    private final EnumSetting<Quick> quick = new EnumSetting<>("Quick item",
        "Brings a different item across in some situations.", Quick.NONE)
        .describe(Quick.NONE, "Nothing extra.")
        .describe(Quick.CLICK_APPLE,
            "An enchanted apple whilst you hold use with anything but a bow or crossbow "
                + "or trident or food.")
        .describe(Quick.SWORD_CLICK_APPLE, "An enchanted apple whilst you hold use with a sword.")
        .describe(Quick.SWORD_APPLE, "An enchanted apple whenever you hold a sword or an axe.")
        .describe(Quick.SWORD_CLICK_POTION, "A potion whilst you hold use with a sword.")
        .describe(Quick.SWORD_POTION, "A potion whenever you hold a sword or an axe.");
    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks to wait between swaps.", 0, 0, 20, 1, " ticks");

    private final NumberSetting totemHealth = new NumberSetting("Totem health",
        "Swaps to a totem once you drop to this many hearts. Zero never swaps.",
        7, 0, 10, 0.5, " hearts");
    private final BoolSetting elytra = new BoolSetting("Elytra",
        "Holds a totem whilst you glide.", false).visibleWhen(this::totemWanted);
    private final BoolSetting falling = new BoolSetting("Falling",
        "Counts the fall you are in against the totem health.", false)
        .visibleWhen(this::totemWanted);
    private final BoolSetting explosion = new BoolSetting("Explosion",
        "Counts a nearby crystal or bed or anchor against the totem health.", true)
        .visibleWhen(this::totemWanted);

    private final InventoryUtil.StrandedStack cursor = new InventoryUtil.StrandedStack();
    private int timer;

    public Offhand() {
        super("Offhand", "Keeps a chosen item in your offhand.", Category.COMBAT);
        addSettings(item, hotbar, quick, delay, totemHealth, elytra, falling, explosion);
        searchTags("totem", "crystal", "gapple", "potion");
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

    private boolean totemWanted() {
        return totemHealth.getValue() > 0;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.player.isSpectator()) {
            return;
        }
        if (!cursor.recover()) {
            return;
        }
        if (autoTotemLocked()) {
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

    private static boolean autoTotemLocked() {
        AutoTotem autoTotem = Modules.get(AutoTotem.class);
        return autoTotem != null && autoTotem.isLocked();
    }

    private Item wantedItem() {
        if (totemNeeded() && haveTotem()) {
            return Items.TOTEM_OF_UNDYING;
        }
        Item extra = quickItem();
        return extra != null ? extra : item.getValue().item;
    }

    private boolean haveTotem() {
        return mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING)
            || findSlot(Items.TOTEM_OF_UNDYING) != -1;
    }

    // True once the health line is crossed after the damage already on its way.
    private boolean totemNeeded() {
        if (!totemWanted()) {
            return false;
        }
        if (elytra.isOn() && mc.player.isFallFlying()) {
            return true;
        }
        float incoming = DamageUtil.possibleIncoming(DamageUtil.BLAST_RANGE,
            explosion.isOn(), false, falling.isOn());
        return EntityUtil.totalHealth(mc.player) - incoming <= totemHealth.getValue() * HEART;
    }

    // The item the quick rule asks for right now. Null when it asks for nothing.
    private Item quickItem() {
        ItemStack held = mc.player.getInventory().getSelectedItem();
        boolean sword = held.is(ItemTags.SWORDS);
        boolean swordOrAxe = sword || held.is(ItemTags.AXES);
        boolean clicking = mc.gui.screen() == null && InputUtil.physicallyHeld(mc.options.keyUse);
        return switch (quick.getValue()) {
            case NONE -> null;
            case CLICK_APPLE -> clicking && !usable(held) ? apple() : null;
            case SWORD_CLICK_APPLE -> clicking && sword ? apple() : null;
            case SWORD_APPLE -> swordOrAxe ? apple() : null;
            case SWORD_CLICK_POTION -> clicking && sword ? Items.POTION : null;
            case SWORD_POTION -> swordOrAxe ? Items.POTION : null;
        };
    }

    // Right clicking these uses them in the main hand so the offhand never fires.
    private static boolean usable(ItemStack held) {
        return held.is(Items.BOW) || held.is(Items.CROSSBOW) || held.is(Items.TRIDENT)
            || held.has(DataComponents.FOOD);
    }

    // A golden apple stands in whilst there is no enchanted one.
    private Item apple() {
        if (mc.player.getOffhandItem().is(Items.ENCHANTED_GOLDEN_APPLE)
            || findSlot(Items.ENCHANTED_GOLDEN_APPLE) != -1) {
            return Items.ENCHANTED_GOLDEN_APPLE;
        }
        return findSlot(Items.GOLDEN_APPLE) == -1 ? null : Items.GOLDEN_APPLE;
    }

    // Network slot of the first stack of the item. Minus one when absent.
    private int findSlot(Item wanted) {
        int first = hotbar.isOn() ? 0 : HOTBAR_SIZE;
        for (int i = first; i < InventoryUtil.WHOLE_INVENTORY; i++) {
            if (mc.player.getInventory().getItem(i).is(wanted)) {
                return InventoryUtil.networkSlot(i);
            }
        }
        return -1;
    }
}
