package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.ListMode;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.FaceMode;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.MenuClicks;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.RotationPriority;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public final class ChestStealer extends Module {

    private final NumberSetting delay = new NumberSetting("Delay",
        "Milliseconds between each item grab.", 50, 0, 500, 10, "ms").min(0);
    private final NumberSetting initialDelay = new NumberSetting("Initial delay",
        "Milliseconds to wait before the first grab of a container.", 50, 0, 1000, 10, "ms")
        .min(0);
    private final NumberSetting jitter = new NumberSetting("Jitter",
        "Adds up to this many milliseconds at random to each grab.", 50, 0, 500, 10, "ms")
        .min(0);
    private final EnumSetting<ListMode> listMode = ListMode.setting("List mode", ListMode.BLACKLIST,
        "Takes only the listed items.", "Takes everything except the listed items.");
    private final RegistryListSetting<Item> items = new RegistryListSetting<>("Items",
        "The items the list applies to. Click to pick them.", BuiltInRegistries.ITEM,
        List.of());
    private final RegistryListSetting<MenuType<?>> screens = new RegistryListSetting<>("Screens",
        "Which container screens are stolen from. Click to pick them.", BuiltInRegistries.MENU,
        List.of(MenuType.GENERIC_9x3, MenuType.GENERIC_9x6, MenuType.SHULKER_BOX,
            MenuType.HOPPER, MenuType.GENERIC_3x3));
    private final BoolSetting throwOut = new BoolSetting("Throw out",
        "Throws each item on the ground instead of into your inventory.", false);
    private final BoolSetting backwards = new BoolSetting("Throw backwards",
        "Throws the pile behind you.", false)
        .under(throwOut);
    private final BoolSetting buttons = new BoolSetting("Buttons",
        "Draws Steal and Dump buttons above every container screen.", true);
    private final BoolSetting close = new BoolSetting("Close when done",
        "Close the container once everything is taken.", false);

    // How one step over the open container went.
    public enum Step {
        // A stack moved and the next one waits out the delay.
        MOVED,
        // The delay has not run out yet.
        WAITING,
        // Nothing is left that the rules want moved.
        DONE,
        // The side the items go to has no room.
        FULL
    }

    private long nextClick;
    private int lastSlot = -1;
    private int lastCount = -1;
    private boolean inventoryFull;
    private boolean open;
    // Set by the dump button until every stack the dump wants has gone in.
    private boolean dumping;
    // What a dump put in the container is left there for the rest of the visit.
    private boolean dumped;
    // Set whilst ChestAura runs a visit. The module's own tick stands back meanwhile.
    private boolean driven;

    public ChestStealer() {
        super("ChestStealer", "Takes everything out of containers for you.", Category.PLAYER);
        addSettings(delay, initialDelay, jitter, listMode, items, screens, throwOut, backwards,
            buttons, close);
        searchTags("loot", "chest", "filter", "steal", "dump");
    }

    @Override
    public String getSuffix() {
        return items.size() == 0 ? null : listMode.getValueString();
    }

    public boolean showsButtons() {
        return isEnabled() && buttons.isOn();
    }

    // A button press makes the next grab due at once.
    public void stealNow() {
        nextClick = 0;
    }

    public void dumpNow() {
        dumping = true;
        dumped = true;
        lastSlot = -1;
        lastCount = -1;
        nextClick = 0;
    }

    // The screens the module works in. A menu built without a type is skipped.
    public boolean handles(Screen screen) {
        if (!(screen instanceof AbstractContainerScreen<?> container)) {
            return false;
        }
        MenuType<?> type = container.getMenu().menuType;
        return type != null && handles(type);
    }

    public boolean handles(MenuType<?> type) {
        return screens.contains(type);
    }

    // True whilst the stealer is working a container of its own or one ChestAura opened.
    public boolean busy() {
        return isEnabled() || driven;
    }

    // ChestAura opens a container and runs its visit through take or store.
    public void startVisit() {
        forget();
        driven = true;
    }

    public void endVisit() {
        forget();
        driven = false;
    }

    public Step take() {
        return step(false);
    }

    public Step store() {
        return step(true);
    }

    // A visit can take something. There is a free slot or the items are thrown out.
    public boolean roomToTake() {
        return throwOut.isOn() || mc.player.getInventory().getFreeSlot() != -1;
    }

    // Something in the inventory is waiting to be stored.
    public boolean somethingToStore() {
        return InventoryUtil.findSlot(ChestStealer::dumps, InventoryUtil.WHOLE_INVENTORY) != -1;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || driven) {
            return;
        }
        if (!handles(mc.gui.screen())) {
            forget();
            return;
        }
        if (dumping) {
            Step step = store();
            dumping = step == Step.MOVED || step == Step.WAITING;
            return;
        }
        if (take() == Step.DONE && close.isOn()) {
            mc.player.closeContainer();
        }
    }

    private Step step(boolean store) {
        long now = System.currentTimeMillis();
        if (!open) {
            open = true;
            nextClick = now + initialDelay.getInt();
        }
        AbstractContainerMenu menu = mc.player.containerMenu;
        int containerSlots = menu.slots.size() - InventoryUtil.WHOLE_INVENTORY;
        if (containerSlots <= 0) {
            return Step.DONE;
        }
        if (store) {
            return now < nextClick ? Step.WAITING : storeStep(menu, containerSlots);
        }
        return takeStep(menu, containerSlots, now);
    }

    private Step takeStep(AbstractContainerMenu menu, int containerSlots, long now) {
        if (inventoryFull && !throwOut.isOn()) {
            if (mc.player.getInventory().getFreeSlot() == -1) {
                return Step.FULL;
            }
            // The refused slot has to look new again or the next pass skips it.
            lastSlot = -1;
            lastCount = -1;
            inventoryFull = false;
        }
        if (now < nextClick) {
            return Step.WAITING;
        }
        for (int i = 0; i < containerSlots; i++) {
            Slot slot = menu.slots.get(i);
            if (!slot.hasItem() || !wanted(slot.getItem())) {
                continue;
            }
            // A click that moved nothing means the inventory is full.
            if (i == lastSlot && slot.getItem().getCount() == lastCount) {
                inventoryFull = true;
                return Step.FULL;
            }
            lastSlot = i;
            lastCount = slot.getItem().getCount();
            takeSlot(menu, i);
            waitAgain();
            return Step.MOVED;
        }
        return Step.DONE;
    }

    private void takeSlot(AbstractContainerMenu menu, int slot) {
        if (!throwOut.isOn()) {
            MenuClicks.quickMove(menu, slot);
            return;
        }
        // The look has to reach the server before the throw does.
        if (backwards.isOn()) {
            FaceMode.SPAM.faceExact(mc.player.getYRot() + 180f, mc.player.getXRot(), RotationPriority.IDLE);
        }
        // Button one throws the whole stack straight onto the ground.
        MenuClicks.click(menu, slot, 1, ContainerInput.THROW);
    }

    // Moves one stack the InventoryTweaks dump filter wants into the container.
    private Step storeStep(AbstractContainerMenu menu, int containerSlots) {
        for (int i = containerSlots; i < menu.slots.size(); i++) {
            ItemStack stack = menu.slots.get(i).getItem();
            if (!dumps(stack)) {
                continue;
            }
            // The same stack coming back means the container has no room.
            if (i == lastSlot && stack.getCount() == lastCount) {
                return Step.FULL;
            }
            lastSlot = i;
            lastCount = stack.getCount();
            MenuClicks.quickMove(menu, i);
            waitAgain();
            return Step.MOVED;
        }
        return Step.DONE;
    }

    private static boolean dumps(ItemStack stack) {
        InventoryTweaks tweaks = Modules.get(InventoryTweaks.class);
        return tweaks == null ? !stack.isEmpty() : tweaks.dumps(stack);
    }

    private void waitAgain() {
        int wait = delay.getInt();
        if (jitter.getInt() > 0) {
            wait += ThreadLocalRandom.current().nextInt(jitter.getInt() + 1);
        }
        nextClick = System.currentTimeMillis() + wait;
    }

    private void forget() {
        nextClick = 0;
        lastSlot = -1;
        lastCount = -1;
        inventoryFull = false;
        open = false;
        dumping = false;
        dumped = false;
    }

    // An empty blacklist leaves every item wanted.
    private boolean wanted(ItemStack stack) {
        return listMode.getValue().admits(items.contains(stack.getItem())) && !(dumped && dumps(stack));
    }
}
