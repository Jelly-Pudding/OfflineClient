package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ChoiceListSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.ItemUtil;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.DispensibleContainerItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

public final class AutoDrop extends Module {

    public enum Mode { JUNK, KEEP, HANDS, SLOTS }

    public enum Order { ALL, TURNS }

    private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
        "What gets thrown away.", Mode.JUNK)
        .describe(Mode.JUNK, "Throws the listed junk as it arrives.")
        .describe(Mode.KEEP, "Throws everything that is not on your keep list. An empty list throws nothing.")
        .describe(Mode.HANDS, "Throws whatever you hold.")
        .describe(Mode.SLOTS, "Throws the hotbar slots you pick.");
    private final RegistryListSetting<Item> items = new RegistryListSetting<>("Items",
        "The items to throw away. Click to pick them. Blocks that Scaffold is building with stay.",
        BuiltInRegistries.ITEM, ItemUtil.JUNK)
        .under(mode, Mode.JUNK);
    private final RegistryListSetting<Item> keep = new RegistryListSetting<>("Keep",
        "The items that stay. Click to pick them.", BuiltInRegistries.ITEM, List.of())
        .under(mode, Mode.KEEP);
    private final BoolSetting hotbar = new BoolSetting("Hotbar too",
        "Also throws what lands in your hotbar.", true)
        .under(mode, Mode.JUNK, Mode.KEEP);
    private final BoolSetting worn = new BoolSetting("Worn too",
        "Also throws what you are wearing or holding in the offhand.", false)
        .under(mode, Mode.JUNK, Mode.KEEP);
    private final BoolSetting fullStacksOnly = new BoolSetting("Full stacks only",
        "Only throws a stack once it has filled up.", false)
        .under(mode, Mode.JUNK, Mode.KEEP);
    private final BoolSetting mainHand = new BoolSetting("Main hand",
        "Throws the stack in your main hand.", true)
        .under(mode, Mode.HANDS);
    private final BoolSetting offhand = new BoolSetting("Offhand",
        "Throws the stack in your offhand whilst no container is open.", false)
        .under(mode, Mode.HANDS);
    private final ChoiceListSetting slots = new ChoiceListSetting("Slots",
        "The hotbar slots to throw numbered from the left. Click to pick them.", () -> InventoryUtil.HOTBAR_NAMES)
        .under(mode, Mode.SLOTS);
    private final EnumSetting<Order> order = new EnumSetting<>("Order",
        "How the picked slots take their turn.", Order.ALL)
        .describe(Order.ALL, "Throws every picked slot each time.")
        .describe(Order.TURNS, "Throws the picked slots one after another.")
        .under(mode, Mode.SLOTS);
    private final NumberSetting perTurn = new NumberSetting("Slots each time",
        "How many of the picked slots are thrown each time.", 1, 1, 9, 1)
        .under(order, Order.TURNS);
    private final BoolSetting keepTools = new BoolSetting("Keep tools",
        "Never throws a bucket or anything that wears out like tools and armour.", true)
        .under(mode, Mode.KEEP, Mode.HANDS, Mode.SLOTS);
    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks between each throw.", 2, 0, 20, 1, " ticks");

    private int timer;
    // Where among the picked slots the next turn starts.
    private int turn;

    public AutoDrop() {
        super("AutoDrop", "Throws away junk or all but what you keep or whatever you hold or chosen hotbar slots.",
            Category.PLAYER);
        addSettings(mode, items, keep, hotbar, worn, fullStacksOnly, mainHand, offhand, slots, order, perTurn,
            keepTools, delay);
        searchTags("inventory cleaner", "junk", "throw", "drop hand", "drop slots");
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (mc.player.isSpectator() || Modules.packing()) {
            return;
        }
        if (timer > 0) {
            timer--;
            return;
        }
        boolean threw = switch (mode.getValue()) {
            case JUNK, KEEP -> throwUnwanted();
            case HANDS -> throwHands();
            case SLOTS -> throwSlots();
        };
        if (threw) {
            timer = delay.getInt();
        }
    }

    private boolean throwUnwanted() {
        if (!InventoryUtil.cursorFree() || mode.is(Mode.KEEP) && keep.getValue().isEmpty()) {
            return false;
        }
        List<Slot> menuSlots = mc.player.inventoryMenu.slots;
        for (int netSlot = 0; netSlot < menuSlots.size(); netSlot++) {
            if (!scans(netSlot)) {
                continue;
            }
            ItemStack stack = menuSlots.get(netSlot).getItem();
            if (!unwanted(stack)) {
                continue;
            }
            if (fullStacksOnly.isOn() && stack.getCount() < stack.getMaxStackSize()) {
                continue;
            }
            InventoryUtil.throwStack(netSlot);
            return true;
        }
        return false;
    }

    // Junk is what the list names. With a keep list it is whatever the list leaves out. The
    // blocks Scaffold is building with are never junk.
    private boolean unwanted(ItemStack stack) {
        if (Modules.scaffoldBuildsWith(stack)) {
            return false;
        }
        if (mode.is(Mode.KEEP)) {
            return throwable(stack) && !keep.contains(stack.getItem());
        }
        return !stack.isEmpty() && items.contains(stack.getItem()) && !Modules.keepsFromThrow(stack);
    }

    private boolean scans(int netSlot) {
        if (netSlot >= InventoryUtil.MAIN_START && netSlot < InventoryUtil.HOTBAR_START) {
            return true;
        }
        if (netSlot >= InventoryUtil.HOTBAR_START && netSlot < InventoryUtil.OFFHAND_SLOT) {
            return hotbar.isOn();
        }
        boolean armour = netSlot >= InventoryUtil.ARMOR_START && netSlot < InventoryUtil.MAIN_START;
        return worn.isOn() && (armour || netSlot == InventoryUtil.OFFHAND_SLOT);
    }

    // The main hand goes by the throw key's whole stack action. Paper only limits single items
    // thrown that way. An empty hand is skipped because the game would still send the packet.
    private boolean throwHands() {
        boolean threw = false;
        if (mainHand.isOn() && throwable(mc.player.getMainHandItem())) {
            mc.gameMode.dropItem(mc.player, true);
            threw = true;
        }
        if (offhand.isOn() && throwable(mc.player.getOffhandItem())) {
            threw |= InventoryUtil.throwFrom(Inventory.SLOT_OFFHAND);
        }
        return threw;
    }

    // An open container shows the hotbar too and takes the throw in its own menu.
    private boolean throwSlots() {
        List<Integer> picked = pickedSlots();
        int most = order.is(Order.ALL) ? picked.size() : perTurn.getInt();
        int thrown = 0;
        int step = 0;
        for (; step < picked.size() && thrown < most; step++) {
            int slot = picked.get((turn + step) % picked.size());
            if (throwable(mc.player.getInventory().getItem(slot)) && InventoryUtil.throwFrom(slot)) {
                thrown++;
            }
        }
        if (!picked.isEmpty()) {
            turn = (turn + step) % picked.size();
        }
        return thrown > 0;
    }

    // Hotbar indexes counted from nought on the left.
    private List<Integer> pickedSlots() {
        List<Integer> picked = new ArrayList<>();
        for (int i = 0; i < InventoryUtil.HOTBAR_SIZE; i++) {
            if (slots.contains(InventoryUtil.HOTBAR_NAMES.get(i))) {
                picked.add(i);
            }
        }
        return picked;
    }

    private boolean throwable(ItemStack stack) {
        return !stack.isEmpty() && !Modules.keepsFromThrow(stack) && !(keepTools.isOn() && keeps(stack));
    }

    // Tools and weapons and armour all wear out. Every bucket is a container.
    private static boolean keeps(ItemStack stack) {
        return stack.has(DataComponents.MAX_DAMAGE) || stack.has(DataComponents.TOOL)
            || stack.has(DataComponents.WEAPON) || stack.getItem() instanceof DispensibleContainerItem
            || stack.is(Items.MILK_BUCKET);
    }
}
