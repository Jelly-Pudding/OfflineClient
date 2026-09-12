package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.KeybindSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.ItemUtil;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

public final class InventoryTweaks extends Module {

    public enum SortWhen {
        ALWAYS,
        ON_KEY
    }

    public enum DumpFilter {
        WHITELIST,
        BLACKLIST,
        EVERYTHING
    }

    // Inventory indices nine and up are the main grid.
    private static final int GRID_START = 9;
    private static final int GRID_END = 36;

    private final BoolSetting sort = new BoolSetting("Sort",
        "Keep the inventory in a stable order.", true);
    private final BoolSetting sortHotbar = new BoolSetting("Sort hotbar",
        "Include the hotbar in the sorting.", false)
        .under(sort);
    private final BoolSetting sortContainer = new BoolSetting("Sort containers",
        "Also sort the chest or barrel you have open.", false)
        .under(sort);
    private final EnumSetting<SortWhen> sortWhen = new EnumSetting<>("Sort when",
        "When the sorting runs.", SortWhen.ALWAYS)
        .describe(SortWhen.ALWAYS, "Sorts whenever a screen is open.")
        .describe(SortWhen.ON_KEY, "Sorts only whilst the sort key is held.")
        .under(sort);
    private final KeybindSetting sortKey = new KeybindSetting("Sort key",
        "The key that runs the sorting.", KeybindSetting.UNBOUND)
        .under(sortWhen, SortWhen.ON_KEY);
    private final BoolSetting skipCreative = new BoolSetting("Skip creative",
        "The sorting does nothing in creative mode.", true)
        .under(sort);
    private final BoolSetting merge = new BoolSetting("Merge",
        "Push split stacks of the same item back together.", true);
    private final BoolSetting dump = new BoolSetting("Dump junk",
        "Move junk into a container the moment you open one.", false);
    private final EnumSetting<DumpFilter> dumpFilter = new EnumSetting<>("Dump filter",
        "What the junk list means.", DumpFilter.WHITELIST)
        .describe(DumpFilter.WHITELIST, "Dumps only the listed items.")
        .describe(DumpFilter.BLACKLIST, "Dumps everything except the listed items.")
        .describe(DumpFilter.EVERYTHING, "Dumps the whole inventory.")
        .under(dump);
    private final RegistryListSetting<Item> junk = new RegistryListSetting<>("Junk",
        "Items the dump filter applies to. Click to pick them.", BuiltInRegistries.ITEM,
        ItemUtil.JUNK)
        .under(dump, () -> dump.isOn() && !dumpFilter.is(DumpFilter.EVERYTHING));
    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks between clicks.", 2, 0, 20, 1, " ticks").min(0);
    private final BoolSetting dragMove = new BoolSetting("Drag move",
        "Holding shift and dragging the left button moves every stack the cursor passes.", true);
    private final BoolSetting craftingCarry = new BoolSetting("Crafting carry",
        "Keeps items in your own crafting grid as four extra slots.", true);
    private final BoolSetting bundleScrolling = new BoolSetting("Bundle scrolling",
        "Scrolling over a bundle can reach every item inside it.", true);
    private final BoolSetting frameInput = new BoolSetting("Frame input",
        "Reads your keys and mining every drawn frame rather than every tick.", false);

    private int timer;
    // The last slot a dump click was sent for and what it held.
    private int dumpSlot = -1;
    private int dumpCount = -1;
    // True whilst a close packet for your own inventory has been held back.
    private boolean heldClose;

    public InventoryTweaks() {
        super("InventoryTweaks", "Sorts and merges your inventory whilst it is open.",
            Category.PLAYER);
        addSettings(sort, sortHotbar, sortContainer, sortWhen, sortKey, skipCreative, merge,
            dump, dumpFilter, junk, delay, dragMove, craftingCarry, bundleScrolling, frameInput);
        searchTags("sort", "tidy", "stack", "dump", "xcarry", "bundle");
    }

    @Override
    protected void onEnable() {
        timer = 0;
    }

    @Override
    protected void onDisable() {
        releaseClose();
    }

    public boolean dragsStacks() {
        return isEnabled() && dragMove.isOn();
    }

    public boolean uncapsBundles() {
        return isEnabled() && bundleScrolling.isOn();
    }

    public boolean frameInput() {
        return isEnabled() && frameInput.isOn();
    }

    // Your own inventory never really closes. The crafting grid keeps its items.
    @Subscribe
    private void onPacketSend(PacketSendEvent event) {
        if (!craftingCarry.isOn()) {
            return;
        }
        if (event.getPacket() instanceof ServerboundContainerClosePacket packet
            && packet.getContainerId() == 0) {
            heldClose = true;
            event.cancel();
        }
    }

    // The server is told the grid is closed once the setting stops holding it.
    private void releaseClose() {
        if (!heldClose) {
            return;
        }
        heldClose = false;
        if (mc.getConnection() != null) {
            mc.getConnection().send(new ServerboundContainerClosePacket(0));
        }
    }

    @Subscribe
    private void onClientTick(ClientTickEvent event) {
        if (!inGame() || mc.player.isSpectator()) {
            return;
        }
        if (!craftingCarry.isOn()) {
            releaseClose();
        }
        Screen screen = mc.gui.screen();
        boolean own = screen instanceof InventoryScreen;
        boolean container = !own && InventoryUtil.isStorage(screen);
        if (!own && !container) {
            dumpSlot = -1;
            dumpCount = -1;
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
        if (!sorts()) {
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

    private boolean sorts() {
        if (!sort.isOn() || (skipCreative.isOn() && mc.player.isCreative())) {
            return false;
        }
        return sortWhen.is(SortWhen.ALWAYS) || sortKey.isHeld();
    }

    private boolean stealerBusy() {
        return Modules.enabled(ChestStealer.class);
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

    // True when the dump filter wants this stack moved out.
    public boolean dumps(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        return switch (dumpFilter.getValue()) {
            case WHITELIST -> junk.contains(stack.getItem());
            case BLACKLIST -> !junk.contains(stack.getItem());
            case EVERYTHING -> true;
        };
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
            if (!dumps(stack)) {
                continue;
            }
            // The same stack coming back means the container had no room for it.
            if (i == dumpSlot && stack.getCount() == dumpCount) {
                return false;
            }
            dumpSlot = i;
            dumpCount = stack.getCount();
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
        int byId = BuiltInRegistries.ITEM.getKey(a.getItem())
            .compareTo(BuiltInRegistries.ITEM.getKey(b.getItem()));
        if (byId != 0) {
            return byId;
        }
        return Integer.compare(b.getCount(), a.getCount());
    }
}
