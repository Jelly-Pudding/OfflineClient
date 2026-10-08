package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.ItemUtil;
import com.jellypudding.offlineclient.util.MenuClicks;
import com.jellypudding.offlineclient.util.PlacedBox;
import com.jellypudding.offlineclient.util.Tally;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// One switch on packs and then the module switches itself off. With From container on and a
// container open it first takes that container's stacks and packs only those.
public final class ShulkerPacker extends Module {

    public enum Pack { EVERYTHING, LISTED, UNLISTED, STACKING, SINGLE }

    private enum Stage { TAKE, PLACE, FILL, PICKUP }

    // A kind of item the container gave up and how many of it are still to go into a box.
    private static final class Taken {

        // Only its item and components count.
        private final ItemStack kind;
        private int left;

        private Taken(ItemStack kind, int left) {
            this.kind = kind;
            this.left = left;
        }
    }

    // Ticks the box gets to land back in your inventory once it breaks.
    private static final int PICKUP_WAIT = 40;
    private static final String NO_BOX = "You carry no shulker box with room in it.";
    private static final String NO_SPOT = "There is no room beside you to set a shulker box down.";
    private static final String NOT_DOWN = "The shulker box would not go down.";

    private final EnumSetting<Pack> pack = new EnumSetting<>("Pack", "What goes into the box.", Pack.EVERYTHING)
        .describe(Pack.EVERYTHING, "Everything you carry.")
        .describe(Pack.LISTED, "Only the items picked below.")
        .describe(Pack.UNLISTED, "Everything except the items picked below.")
        .describe(Pack.STACKING, "Only items that stack such as blocks and food.")
        .describe(Pack.SINGLE, "Only items that never stack such as tools and armour.");
    private final RegistryListSetting<Item> items = new RegistryListSetting<>("Items",
        "The items the pack choice applies to. Click to pick them.", BuiltInRegistries.ITEM, List.of())
        .under(pack, Pack.LISTED, Pack.UNLISTED);
    private final BoolSetting skipHotbar = new BoolSetting("Skip hotbar",
        "Leaves your hotbar out of the pack.", true);
    private final BoolSetting fromContainer = new BoolSetting("From container",
        "With a container open it packs what the container holds instead of your own items.", true);
    private final BoolSetting moreBoxes = new BoolSetting("More boxes",
        "Fills the next shulker box you carry once one is full.", false);
    private final BoolSetting screenButton = new BoolSetting("Screen button",
        "Adds a Pack button to your inventory and to open containers.", true);
    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks between clicks.", 1, 0, 10, 1, " ticks").min(0);

    private final InventoryUtil.SlotSwap slots = new InventoryUtil.SlotSwap();
    private final InventoryUtil.HotbarLoan loan = new InventoryUtil.HotbarLoan();
    private final PlacedBox box = new PlacedBox(slots);
    private final MenuClicks.Clicked clicked = new MenuClicks.Clicked();

    private Stage stage = Stage.PLACE;
    private int timer;
    private int waited;
    // What your inventory held before the container gave up its stacks.
    private ItemStack[] before;
    // What the container gave up kind by kind. Null whilst your own items are packed.
    private List<Taken> taken;
    // Indexes whose stacks never go in such as the pickaxe and a stack moved aside for the box.
    private final Set<Integer> kept = new HashSet<>();
    private BlockPos placedAt;
    // Shulker boxes carried before one went down. The box is back once there are as many again.
    private int boxesHeld;
    private int packed;

    public ShulkerPacker() {
        super("ShulkerPacker", "Packs the items you pick into a shulker box and picks the box back up.",
            Category.PLAYER);
        addSettings(pack, items, skipHotbar, fromContainer, moreBoxes, screenButton, delay);
        searchTags("quick shulker", "pack", "shulker", "stash", "tidy");
    }

    // A run halfway through is no use after a restart.
    @Override
    public boolean savesEnabledState() {
        return false;
    }

    @Override
    public String getSuffix() {
        return count(packed, "packed");
    }

    public boolean showsButton(Screen screen) {
        return screenButton.isOn() && (screen instanceof InventoryScreen || InventoryUtil.isStorage(screen));
    }

    @Override
    protected void onEnable() {
        if (!inGame()) {
            setEnabled(false);
            return;
        }
        clicked.clear();
        kept.clear();
        taken = null;
        packed = 0;
        timer = 0;
        int pickaxe = ItemUtil.bestToolSlot(Blocks.SHULKER_BOX.defaultBlockState(), 1, stack -> true,
            InventoryUtil.WHOLE_INVENTORY);
        if (pickaxe != -1) {
            kept.add(pickaxe);
        }
        if (!fromContainer.isOn() || openStorage() == null) {
            stage = Stage.PLACE;
            return;
        }
        // Nothing leaves the container before a box is known to go down.
        if (boxSlot() == -1) {
            disable(NO_BOX);
            return;
        }
        if (PlacedBox.spot() == null) {
            disable(NO_SPOT);
            return;
        }
        before = new ItemStack[InventoryUtil.WHOLE_INVENTORY];
        for (int i = 0; i < before.length; i++) {
            before[i] = mc.player.getInventory().getItem(i).copy();
        }
        stage = Stage.TAKE;
    }

    @Override
    protected void onDisable() {
        box.end();
        slots.restoreIfMine();
        loan.giveBack();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            return;
        }
        switch (stage) {
            case TAKE -> take();
            case PLACE -> place();
            case FILL -> {
                if (!box.tick(this::fill, true, true)) {
                    afterBox();
                }
            }
            case PICKUP -> pickUp();
        }
    }

    // The storage container you have open other than your own inventory. Null when there is none.
    private AbstractContainerMenu openStorage() {
        return mc.gui.screen() instanceof AbstractContainerScreen<?> screen && !(screen instanceof InventoryScreen)
            && InventoryUtil.isStorage(screen) ? screen.getMenu() : null;
    }

    // Takes the container's stacks that the pack choice wants until nothing more comes. Then the
    // container shuts and what your inventory gained is what gets packed.
    private void take() {
        AbstractContainerMenu menu = openStorage();
        boolean full = false;
        if (menu != null) {
            if (waiting()) {
                return;
            }
            List<Integer> mine = MenuClicks.inventorySlots(menu);
            for (int slot : MenuClicks.containerSlots(menu)) {
                ItemStack stack = menu.slots.get(slot).getItem();
                if (!packs(stack) || clicked.thrownBack(slot, stack)) {
                    continue;
                }
                if (MenuClicks.room(menu, mine, stack) == 0) {
                    full = true;
                    continue;
                }
                move(menu, slot, mine, stack.getCount());
                return;
            }
            mc.player.closeContainer();
        }
        taken = gained();
        clicked.clear();
        if (taken.isEmpty() && full) {
            disable("Your inventory has no room for what the container holds.");
            return;
        }
        stage = Stage.PLACE;
    }

    // Sets the next box with room down beside you. A box out of the hotbar is lent into it first.
    // A container screen shuts first and any other screen is waited out.
    private void place() {
        if (mc.gui.screen() instanceof AbstractContainerScreen<?>) {
            mc.player.closeContainer();
            return;
        }
        if (mc.gui.screen() != null) {
            return;
        }
        if (nothingLeft()) {
            if (packed == 0) {
                disable(taken == null ? "Nothing you carry fits the pack choice."
                    : "Nothing in the container fits the pack choice.");
            } else {
                finish();
            }
            return;
        }
        int boxSlot = boxSlot();
        if (boxSlot == -1) {
            disable(packed == 0 ? NO_BOX : "Every shulker box you carry is full.");
            return;
        }
        if (boxSlot >= InventoryUtil.HOTBAR_SIZE) {
            // Your own stack the box pushes out of the hotbar stays out of the pack. What a
            // container gave is counted by kind and goes in wherever it lies.
            if (loan.select(boxSlot) && taken == null && !mc.player.getInventory().getItem(boxSlot).isEmpty()) {
                kept.add(boxSlot);
            }
            return;
        }
        placedAt = PlacedBox.spot();
        if (placedAt == null) {
            disable(NO_SPOT);
            return;
        }
        boxesHeld = boxCount();
        if (!box.setDown(boxSlot, placedAt, true)) {
            disable(NOT_DOWN);
            return;
        }
        clicked.clear();
        stage = Stage.FILL;
    }

    // Moves one stack into the open box each time the delay allows. False once no more goes in.
    private boolean fill(AbstractContainerMenu menu) {
        if (menu.menuType != MenuType.SHULKER_BOX) {
            return false;
        }
        if (waiting()) {
            return true;
        }
        List<Integer> boxSlots = MenuClicks.containerSlots(menu);
        for (Slot slot : menu.slots) {
            ItemStack stack = slot.getItem();
            if (MenuClicks.isInventorySlot(slot) && wanted(slot.getContainerSlot(), stack)
                && !clicked.thrownBack(slot.index, stack) && MenuClicks.room(menu, boxSlots, stack) > 0) {
                pack(menu, slot.index, boxSlots);
                packed++;
                return true;
            }
        }
        return false;
    }

    // A stack the container's items merged into gives up only as many as the container gave.
    private void pack(AbstractContainerMenu menu, int slot, List<Integer> boxSlots) {
        ItemStack stack = menu.slots.get(slot).getItem();
        int had = stack.getCount();
        Taken kind = takenOf(stack);
        move(menu, slot, boxSlots, kind == null ? had : Math.min(had, kind.left));
        if (kind != null) {
            ItemStack now = menu.slots.get(slot).getItem();
            kind.left -= had - (ItemStack.isSameItemSameComponents(now, kind.kind) ? now.getCount() : 0);
        }
    }

    // A box still standing could not be mined. One gone before it ever opened was refused.
    private void afterBox() {
        if (BlockUtil.state(placedAt).getBlock() instanceof ShulkerBoxBlock) {
            disable("The shulker box could not be mined and stands where it went down.");
        } else if (box.wouldNotOpen()) {
            disable("The shulker box would not open and was mined back.");
        } else if (!box.opened()) {
            disable(NOT_DOWN);
        } else {
            stage = Stage.PICKUP;
            waited = 0;
        }
    }

    private void pickUp() {
        if (boxCount() < boxesHeld) {
            if (++waited > PICKUP_WAIT) {
                disable("The shulker box did not come back to your inventory.");
            }
            return;
        }
        slots.restoreIfMine();
        loan.giveBack();
        if (moreBoxes.isOn() && !nothingLeft()) {
            stage = Stage.PLACE;
        } else {
            finish();
        }
    }

    private void finish() {
        ChatUtil.message("§bShulkerPacker §7packed §f" + Tally.counted(packed, "stack") + "§7.");
        setEnabled(false);
    }

    private boolean waiting() {
        if (timer > 0) {
            timer--;
            return true;
        }
        return false;
    }

    private void move(AbstractContainerMenu menu, int slot, List<Integer> into, int count) {
        clicked.note(slot, menu.slots.get(slot).getItem());
        MenuClicks.move(menu, slot, into, count);
        timer = delay.getInt();
    }

    // How many more of each kind your inventory holds than before the container gave up its stacks.
    private List<Taken> gained() {
        List<Taken> kinds = new ArrayList<>();
        for (int i = 0; i < before.length; i++) {
            tally(kinds, mc.player.getInventory().getItem(i), 1);
            tally(kinds, before[i], -1);
        }
        kinds.removeIf(kind -> kind.left <= 0);
        return kinds;
    }

    private static void tally(List<Taken> kinds, ItemStack stack, int sign) {
        if (stack.isEmpty()) {
            return;
        }
        Taken kind = find(kinds, stack);
        if (kind == null) {
            kinds.add(new Taken(stack.copyWithCount(1), sign * stack.getCount()));
        } else {
            kind.left += sign * stack.getCount();
        }
    }

    // What the container gave of the stack's kind. Null whilst your own items are packed and for
    // a kind it never gave.
    private Taken takenOf(ItemStack stack) {
        return taken == null ? null : find(taken, stack);
    }

    private static Taken find(List<Taken> kinds, ItemStack stack) {
        for (Taken kind : kinds) {
            if (ItemStack.isSameItemSameComponents(kind.kind, stack)) {
                return kind;
            }
        }
        return null;
    }

    private boolean nothingLeft() {
        for (int i = 0; i < InventoryUtil.WHOLE_INVENTORY; i++) {
            if (wanted(i, mc.player.getInventory().getItem(i))) {
                return false;
            }
        }
        return true;
    }

    // True for the stack at an inventory index that this run packs.
    private boolean wanted(int index, ItemStack stack) {
        if (kept.contains(index) || !packs(stack)) {
            return false;
        }
        if (taken != null) {
            Taken kind = takenOf(stack);
            return kind != null && kind.left > 0;
        }
        return !skipHotbar.isOn() || index >= InventoryUtil.HOTBAR_SIZE;
    }

    // True for a stack the pack choice takes. A shulker box never goes into another.
    private boolean packs(ItemStack stack) {
        if (stack.isEmpty() || !stack.getItem().canFitInsideContainerItems()) {
            return false;
        }
        return switch (pack.getValue()) {
            case EVERYTHING -> true;
            case LISTED -> items.contains(stack.getItem());
            case UNLISTED -> !items.contains(stack.getItem());
            case STACKING -> stack.isStackable();
            case SINGLE -> !stack.isStackable();
        };
    }

    private static int boxSlot() {
        return InventoryUtil.findSlot(ShulkerPacker::hasRoom, InventoryUtil.WHOLE_INVENTORY);
    }

    private static boolean hasRoom(ItemStack stack) {
        if (!ItemUtil.isShulkerBox(stack.getItem())) {
            return false;
        }
        ItemContainerContents inside = stack.get(DataComponents.CONTAINER);
        return inside == null || inside.nonEmptyItemCopyStream().count() < ShulkerBoxBlockEntity.CONTAINER_SIZE;
    }

    private static int boxCount() {
        return InventoryUtil.count(stack -> ItemUtil.isShulkerBox(stack.getItem()), InventoryUtil.WHOLE_INVENTORY);
    }
}
