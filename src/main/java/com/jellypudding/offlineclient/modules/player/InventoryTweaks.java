package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.DispenserScreen;
import net.minecraft.client.gui.screens.inventory.HopperScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

public final class InventoryTweaks extends Module {

    // Inventory indices nine and up are the main grid.
    private static final int GRID_START = 9;
    private static final int GRID_END = 36;

    private final BoolSetting sort = new BoolSetting("Sort",
        "Keep the inventory in a stable order.", true);
    private final BoolSetting sortHotbar = new BoolSetting("Sort hotbar",
        "Include the hotbar in the sorting.", false);
    private final BoolSetting sortContainer = new BoolSetting("Sort containers",
        "Also sort the chest or barrel you have open.", false);
    private final BoolSetting merge = new BoolSetting("Merge",
        "Push split stacks of the same item back together.", true);
    private final BoolSetting dump = new BoolSetting("Dump junk",
        "Move junk into a container the moment you open one.", false);
    private final RegistryListSetting<Item> junk = new RegistryListSetting<>("Junk",
        "Items counted as junk. Click to pick them.", BuiltInRegistries.ITEM,
        List.of(Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.DIRT, Items.GRAVEL,
            Items.NETHERRACK, Items.ROTTEN_FLESH, Items.POISONOUS_POTATO));
    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks between clicks.", 2, 0, 20, 1, " ticks").min(0);

    private int timer;

    public InventoryTweaks() {
        super("InventoryTweaks", "Sorts and merges your inventory whilst it is open.",
            Category.PLAYER);
        addSettings(sort, sortHotbar, sortContainer, merge, dump, junk, delay);
        searchTags("sort", "tidy", "stack", "dump");
    }

    @Override
    protected void onEnable() {
        timer = 0;
    }

    @Subscribe
    private void onClientTick(ClientTickEvent event) {
        if (!inGame() || mc.player.isSpectator()) {
            return;
        }
        Screen screen = mc.gui.screen();
        boolean own = screen instanceof InventoryScreen;
        boolean container = !own && isStorage(screen);
        if (!own && !container) {
            return;
        }
        AbstractContainerMenu menu = mc.player.containerMenu;
        if (!menu.getCarried().isEmpty()) {
            return;
        }
        if (timer > 0) {
            timer--;
            return;
        }

        List<Integer> mine = playerSlots(menu, sortHotbar.isOn());
        if (mine.isEmpty()) {
            return;
        }

        if (container && dump.isOn() && !stealerBusy() && dumpJunk(menu)) {
            timer = delay.getInt();
            return;
        }
        if (merge.isOn() && mergeOne(menu, mine)) {
            timer = delay.getInt();
            return;
        }
        if (!sort.isOn()) {
            return;
        }
        if (sortStep(menu, mine)) {
            timer = delay.getInt();
            return;
        }
        if (container && sortContainer.isOn() && sortStep(menu, containerSlots(menu))) {
            timer = delay.getInt();
        }
    }

    /**
     * Plain storage only. Crafting and anvil and furnace screens refuse a click
     * into their own slots.
     */
    private static boolean isStorage(Screen screen) {
        return screen instanceof ContainerScreen
            || screen instanceof ShulkerBoxScreen
            || screen instanceof HopperScreen
            || screen instanceof DispenserScreen;
    }

    private boolean stealerBusy() {
        return OfflineClient.INSTANCE.getModuleManager().get(ChestStealer.class).isEnabled();
    }

    private List<Integer> playerSlots(AbstractContainerMenu menu, boolean withHotbar) {
        List<Integer> found = new ArrayList<>();
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (slot.container != mc.player.getInventory()) {
                continue;
            }
            int index = slot.getContainerSlot();
            if (index >= GRID_START && index < GRID_END) {
                found.add(i);
            } else if (withHotbar && index < GRID_START) {
                found.add(i);
            }
        }
        return found;
    }

    private List<Integer> containerSlots(AbstractContainerMenu menu) {
        List<Integer> found = new ArrayList<>();
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (slot.container != mc.player.getInventory() && !slot.isFake()) {
                found.add(i);
            }
        }
        return found;
    }

    private boolean dumpJunk(AbstractContainerMenu menu) {
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (slot.container != mc.player.getInventory()) {
                continue;
            }
            int index = slot.getContainerSlot();
            if (index < GRID_START || index >= GRID_END) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (stack.isEmpty() || !junk.contains(stack.getItem())) {
                continue;
            }
            click(menu, i, 0, ContainerInput.QUICK_MOVE);
            return true;
        }
        return false;
    }

    private boolean mergeOne(AbstractContainerMenu menu, List<Integer> region) {
        for (int a = 0; a < region.size(); a++) {
            ItemStack first = menu.getSlot(region.get(a)).getItem();
            if (first.isEmpty() || !first.isStackable()
                || first.getCount() >= first.getMaxStackSize()) {
                continue;
            }
            for (int b = a + 1; b < region.size(); b++) {
                ItemStack second = menu.getSlot(region.get(b)).getItem();
                if (second.isEmpty() || second.getCount() >= second.getMaxStackSize()) {
                    continue;
                }
                if (!ItemStack.isSameItemSameComponents(first, second)) {
                    continue;
                }
                click(menu, region.get(b), 0, ContainerInput.PICKUP);
                click(menu, region.get(a), 0, ContainerInput.PICKUP);
                if (!menu.getCarried().isEmpty()) {
                    click(menu, region.get(b), 0, ContainerInput.PICKUP);
                }
                return true;
            }
        }
        return false;
    }

    // One swap per call. Repeated calls settle the whole region.
    private boolean sortStep(AbstractContainerMenu menu, List<Integer> region) {
        for (int i = 0; i < region.size(); i++) {
            int best = i;
            for (int j = i + 1; j < region.size(); j++) {
                if (compare(menu.getSlot(region.get(j)).getItem(),
                    menu.getSlot(region.get(best)).getItem()) < 0) {
                    best = j;
                }
            }
            if (best == i) {
                continue;
            }
            swap(menu, region.get(i), region.get(best));
            return true;
        }
        return false;
    }

    // Trades two slots with three pickup clicks.
    private void swap(AbstractContainerMenu menu, int from, int to) {
        click(menu, from, 0, ContainerInput.PICKUP);
        click(menu, to, 0, ContainerInput.PICKUP);
        click(menu, from, 0, ContainerInput.PICKUP);
        // Never leave a stack stuck to the cursor.
        if (!menu.getCarried().isEmpty()) {
            click(menu, to, 0, ContainerInput.PICKUP);
        }
    }

    private void click(AbstractContainerMenu menu, int slot, int button, ContainerInput kind) {
        mc.gameMode.handleContainerInput(menu.containerId, slot, button, kind, mc.player);
    }

    // Empty slots sink to the end and everything else goes by id then size.
    private static int compare(ItemStack a, ItemStack b) {
        if (a.isEmpty() || b.isEmpty()) {
            return Boolean.compare(a.isEmpty(), b.isEmpty());
        }
        int byId = BuiltInRegistries.ITEM.getKey(a.getItem()).toString()
            .compareTo(BuiltInRegistries.ITEM.getKey(b.getItem()).toString());
        if (byId != 0) {
            return byId;
        }
        return Integer.compare(b.getCount(), a.getCount());
    }
}
