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
import com.jellypudding.offlineclient.util.Hands;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.ItemUtil;
import com.jellypudding.offlineclient.util.MenuClicks;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.MouseButtonEvent;
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
        ON_KEY,
        MIDDLE_CLICK
    }

    public enum DumpFilter {
        WHITELIST,
        BLACKLIST,
        UNLISTED,
        MATCHING,
        EVERYTHING
    }

    private enum Part { NONE, YOURS, CONTAINER }

    private final BoolSetting sort = new BoolSetting("Sort",
        "Keeps the inventory in a stable order.", true);
    private final BoolSetting sortHotbar = new BoolSetting("Sort hotbar",
        "Includes the hotbar in the sorting.", false)
        .under(sort);
    private final EnumSetting<SortWhen> sortWhen = new EnumSetting<>("Sort when",
        "When the sorting runs.", SortWhen.ALWAYS)
        .describe(SortWhen.ALWAYS, "Sorts whenever a screen is open.")
        .describe(SortWhen.ON_KEY, "Sorts only whilst the sort key is held.")
        .describe(SortWhen.MIDDLE_CLICK, "Sorts the part of the screen you middle click.")
        .under(sort);
    private final BoolSetting sortContainer = new BoolSetting("Sort containers",
        "Also sorts the chest or barrel you have open.", false)
        .under(sort, () -> sort.isOn() && !sortWhen.is(SortWhen.MIDDLE_CLICK));
    private final KeybindSetting sortKey = new KeybindSetting("Sort key",
        "The key that runs the sorting.", KeybindSetting.UNBOUND)
        .under(sortWhen, SortWhen.ON_KEY);
    private final BoolSetting skipCreative = new BoolSetting("Skip creative",
        "The sorting does nothing in creative mode.", true)
        .under(sort);
    private final BoolSetting merge = new BoolSetting("Merge",
        "Pushes split stacks of the same item back together.", true);
    private final BoolSetting dump = new BoolSetting("Dump junk",
        "Moves junk into a container the moment you open one.", false);
    private final EnumSetting<DumpFilter> dumpFilter = new EnumSetting<>("Dump filter",
        "What Dump junk and the Dump button of ChestStealer and the Store and Both modes of ChestAura put into"
            + " a container.", DumpFilter.WHITELIST)
        .describe(DumpFilter.WHITELIST, "Dumps only the listed items.")
        .describe(DumpFilter.BLACKLIST, "Dumps everything except the listed items.")
        .describe(DumpFilter.UNLISTED, "Dumps everything the ChestStealer list or limits turn away.")
        .describe(DumpFilter.MATCHING, "Dumps only items the open container already holds.")
        .describe(DumpFilter.EVERYTHING, "Dumps the whole inventory.")
        .under(dump, () -> true);
    private final RegistryListSetting<Item> junk = new RegistryListSetting<>("Junk",
        "Items the dump filter applies to. Click to pick them.", BuiltInRegistries.ITEM,
        ItemUtil.JUNK)
        .under(dump, () -> dumpFilter.isAny(DumpFilter.WHITELIST, DumpFilter.BLACKLIST));
    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks between clicks.", 2, 0, 20, 1, " ticks");
    private final BoolSetting dragMove = new BoolSetting("Drag move",
        "Holding shift and dragging the left button moves every stack the cursor passes.", true);
    private final BoolSetting craftingCarry = new BoolSetting("Crafting carry",
        "Keeps items in your own crafting grid as four extra slots.", true);
    private final BoolSetting bundleScrolling = new BoolSetting("Bundle scrolling",
        "Scrolling over a bundle can reach every item inside it.", true);

    private int timer;
    private int dumpSlot = -1;
    private int dumpCount = -1;
    private boolean heldClose;
    private Part clicked = Part.NONE;

    public InventoryTweaks() {
        super("InventoryTweaks", "Sorts and merges your inventory whilst it is open.",
            Category.PLAYER);
        addSettings(sort, sortHotbar, sortContainer, sortWhen, sortKey, skipCreative, merge,
            dump, dumpFilter, junk, delay, dragMove, craftingCarry, bundleScrolling);
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

    // False leaves the click to the game.
    public boolean middleClicked(MouseButtonEvent event, AbstractContainerMenu menu, Slot slot) {
        if (!isEnabled() || slot == null || !InputUtil.isMiddle(event.button()) || !sortWhen.is(SortWhen.MIDDLE_CLICK)
            || !sortsHere()) {
            return false;
        }
        if (MenuClicks.isInventorySlot(slot)) {
            clicked = Part.YOURS;
            return true;
        }
        if (MenuClicks.isContainerSlot(slot) && InventoryUtil.isStorage(menu)) {
            clicked = Part.CONTAINER;
            return true;
        }
        return false;
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
        if (!inGame() || Hands.tied()) {
            return;
        }
        if (!craftingCarry.isOn()) {
            releaseClose();
        }
        if (Modules.packing()) {
            return;
        }
        Screen screen = mc.gui.screen();
        boolean own = screen instanceof InventoryScreen;
        boolean container = !own && InventoryUtil.isStorage(screen);
        if (!own && !container) {
            dumpSlot = -1;
            dumpCount = -1;
            clicked = Part.NONE;
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
        if (clicked != Part.NONE) {
            if (sortStep(menu, clicked == Part.YOURS ? mine : MenuClicks.containerSlots(menu))) {
                timer = delay.getInt();
            } else {
                clicked = Part.NONE;
            }
            return;
        }
        if (!sorts()) {
            return;
        }
        if (sortStep(menu, mine)) {
            timer = delay.getInt();
            return;
        }
        if (container && sortContainer.isOn() && sortStep(menu, MenuClicks.containerSlots(menu))) {
            timer = delay.getInt();
        }
    }

    private boolean sorts() {
        if (!sortsHere()) {
            return false;
        }
        return switch (sortWhen.getValue()) {
            case ALWAYS -> true;
            case ON_KEY -> sortKey.isHeld();
            case MIDDLE_CLICK -> false;
        };
    }

    private boolean sortsHere() {
        return sort.isOn() && !(skipCreative.isOn() && mc.player.isCreative());
    }

    private boolean stealerBusy() {
        ChestStealer stealer = Modules.get(ChestStealer.class);
        return stealer != null && stealer.busy();
    }

    private static boolean stealerTurnsAway(ItemStack stack) {
        ChestStealer stealer = Modules.get(ChestStealer.class);
        return stealer != null && stealer.turnsAway(stack);
    }

    private List<Integer> playerSlots(AbstractContainerMenu menu, boolean withHotbar) {
        List<Integer> found = new ArrayList<>();
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (MenuClicks.isInventorySlot(slot)
                && (withHotbar || slot.getContainerSlot() >= InventoryUtil.MAIN_START)) {
                found.add(i);
            }
        }
        return found;
    }

    public boolean dumps(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        return switch (dumpFilter.getValue()) {
            case WHITELIST -> junk.contains(stack.getItem());
            case BLACKLIST -> !junk.contains(stack.getItem());
            case UNLISTED -> stealerTurnsAway(stack);
            case MATCHING -> containerHolds(stack);
            case EVERYTHING -> true;
        };
    }

    private boolean containerHolds(ItemStack stack) {
        AbstractContainerMenu menu = mc.player.containerMenu;
        return !InventoryUtil.isStorage(menu)
            || MenuClicks.firstContainerSlot(menu, held -> held.is(stack.getItem())) != -1;
    }

    private boolean dumpJunk(AbstractContainerMenu menu) {
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (!MenuClicks.isInventorySlot(slot) || slot.getContainerSlot() < InventoryUtil.MAIN_START) {
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
            MenuClicks.quickMove(menu, i);
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
                MenuClicks.click(menu, region.get(b), 0, ContainerInput.PICKUP);
                MenuClicks.click(menu, region.get(a), 0, ContainerInput.PICKUP);
                if (!menu.getCarried().isEmpty()) {
                    MenuClicks.click(menu, region.get(b), 0, ContainerInput.PICKUP);
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
            MenuClicks.swap(menu, region.get(i), region.get(best));
            return true;
        }
        return false;
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
