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
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

// One switch on packs and then the module switches itself off. With From container on and a
// container open it first takes that container's stacks and packs only those. Your own items go
// in from the backpack and from every other part of your inventory that is switched on.
public final class ShulkerPacker extends Module {

    public enum Pack { EVERYTHING, LISTED, UNLISTED, STACKING, SINGLE }

    private enum Stage { TAKE, UNEQUIP, PLACE, FILL, PICKUP, EQUIP }

    // A part of your own inventory and the indexes it spans. A shulker box screen shows the
    // backpack and the hotbar. Armour and the offhand have to come into one of those first.
    private enum Part {
        BACKPACK("backpack", InventoryUtil.MAIN_START, InventoryUtil.WHOLE_INVENTORY, true),
        HOTBAR("hotbar", 0, InventoryUtil.HOTBAR_SIZE, true),
        ARMOUR("armour", InventoryUtil.WHOLE_INVENTORY, Inventory.SLOT_OFFHAND, false),
        OFFHAND("offhand", Inventory.SLOT_OFFHAND, Inventory.SLOT_OFFHAND + 1, false);

        private final String word;
        private final int from;
        private final int to;
        // True where a shulker box screen shows the slots.
        private final boolean reached;

        Part(String word, int from, int to, boolean reached) {
            this.word = word;
            this.from = from;
            this.to = to;
            this.reached = reached;
        }

        private IntStream indexes() {
            return IntStream.range(from, to);
        }

        private boolean holds(int index) {
            return index >= from && index < to;
        }
    }

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

    // A worn piece that came into reach of the box with the index it left and the one it lies at.
    private record Brought(int from, int into, ItemStack kind) {
    }

    // What a holder took of the stacks poured into it and what found no room.
    private record Poured(List<ItemStack> in, List<ItemStack> over) {
    }

    // Ticks the box gets to land back in your inventory once it breaks.
    private static final int PICKUP_WAIT = 40;
    private static final String NO_BOX = "You carry no shulker box with room in it.";
    private static final String NO_SPOT = "There is no room beside you to set a shulker box down.";
    private static final String NOT_DOWN = "The shulker box would not go down.";
    private static final String NOTHING_TAKEN = "Nothing came out of the container.";
    private static final String NO_CARRY = "Your inventory has no room for what the container holds.";

    private final EnumSetting<Pack> pack = new EnumSetting<>("Pack", "What goes into the box.", Pack.EVERYTHING)
        .describe(Pack.EVERYTHING, "Everything a shulker box can hold.")
        .describe(Pack.LISTED, "Only the items picked below.")
        .describe(Pack.UNLISTED, "Everything except the items picked below.")
        .describe(Pack.STACKING, "Only items that stack such as blocks and food.")
        .describe(Pack.SINGLE, "Only items that never stack such as tools and armour.");
    private final RegistryListSetting<Item> items = new RegistryListSetting<>("Items",
        "The items the pack choice applies to. Click to pick them.", BuiltInRegistries.ITEM, List.of())
        .under(pack, Pack.LISTED, Pack.UNLISTED);
    private final BoolSetting hotbar = new BoolSetting("Hotbar",
        "Also packs your hotbar whenever your own items are packed.", false)
        .under(pack, () -> true);
    private final BoolSetting armour = new BoolSetting("Armour",
        "Also packs the armour you wear whenever your own items are packed.", false)
        .under(pack, () -> true);
    private final BoolSetting offhand = new BoolSetting("Offhand",
        "Also packs what your offhand holds whenever your own items are packed.", false)
        .under(pack, () -> true);
    private final BoolSetting fromContainer = new BoolSetting("From container",
        "With a container open it packs what the container holds instead of your own items.", true);
    private final BoolSetting moreBoxes = new BoolSetting("More boxes",
        "Fills the next shulker box you carry once one is full.", false);
    private final BoolSetting screenButton = new BoolSetting("Screen button",
        "Adds a Pack button to your inventory and to open containers. Hover it to read what a press packs.", true);
    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks between clicks.", 1, 0, 10, 1, " ticks");

    // The switch that adds each part to the pack. The backpack has none and always goes in.
    private final Map<Part, BoolSetting> switches = Map.of(Part.HOTBAR, hotbar, Part.ARMOUR, armour,
        Part.OFFHAND, offhand);

    private final InventoryUtil.SlotSwap slots = new InventoryUtil.SlotSwap();
    private final InventoryUtil.HotbarLoan loan = new InventoryUtil.HotbarLoan();
    private final PlacedBox box = new PlacedBox(slots);
    private final MenuClicks.Clicked clicked = new MenuClicks.Clicked();

    private Stage stage = Stage.UNEQUIP;
    private int timer;
    private int waited;
    // What your inventory held before the container gave up its stacks.
    private ItemStack[] before;
    // What the container gave up kind by kind. Null whilst your own items are packed.
    private List<Taken> taken;
    // Indexes whose stacks never go in such as the pickaxe and a stack moved aside for the box. A
    // worn piece joins them once it has had its go and comes off only the once.
    private final Set<Integer> kept = new HashSet<>();
    // Where the pickaxe that stays with you lies. Minus one without one.
    private int pickaxe = -1;
    // Worn pieces that came off. One that never goes in is put back on however the run ends. Only
    // the put back takes a piece off the list and a fresh run inherits what an earlier one left.
    private final List<Brought> brought = new ArrayList<>();
    // How many of them went back on.
    private int restored;
    private BlockPos placedAt;
    // Shulker boxes carried before one went down. The box is back once there are as many again.
    private int boxesHeld;
    // True when the box that came back last still had an empty slot.
    private boolean roomLeft;
    private int packed;
    // What a run another module asked for packs in place of the pack choice and the words for
    // it. Null for your own.
    private Predicate<ItemStack> only;
    private String onlyKind;

    // Runs on after a run stopped by hand until what came off and never went in is back on. A
    // fresh run takes the pieces over. No tick comes whilst you are out of a world. The next join
    // carries on with what still lies where it came in and drops the rest.
    private final Object restorer = new Object() {
        @Subscribe
        private void onTick(TickEvent event) {
            if (isEnabled() || putBack()) {
                unwatch(this);
            }
        }
    };

    public ShulkerPacker() {
        super("ShulkerPacker",
            "Packs your backpack or an open container into a shulker box and picks the box back up.",
            Category.PLAYER);
        addSettings(pack, items, hotbar, armour, offhand, fromContainer, moreBoxes, screenButton, delay);
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

    // Packs the stacks of your own items that pass the test whatever the pack choice says. The
    // kind names them in what the player is told such as mined item. InfinityMiner sends what
    // it mined this way. False when no run started.
    public boolean packOnly(Predicate<ItemStack> wanted, String kind) {
        if (isEnabled()) {
            return false;
        }
        only = wanted;
        onlyKind = kind;
        setEnabled(true);
        if (!isEnabled()) {
            only = null;
        }
        return isEnabled();
    }

    public boolean showsButton(Screen screen) {
        return screenButton.isOn() && (screen instanceof InventoryScreen || InventoryUtil.isStorage(screen));
    }

    // What a press would do as things stand. The Pack button shows it. Only what a pack keeps out
    // is worked out afresh. The click delay and the pieces still owed back are left alone.
    public String pressTip() {
        if (!inGame()) {
            return "";
        }
        if (isEnabled()) {
            return "A pack is under way.";
        }
        AbstractContainerMenu storage = openStorage();
        prepare();
        String refusal = refusal(storage);
        if (refusal != null) {
            return refusal;
        }
        if (storage == null) {
            return tip(own(), stacksAt(IntStream.concat(queued(true), queued(false)))) + staysWords();
        }
        // The container shuts once your inventory can carry no more and only that much is packed.
        List<ItemStack> carrying = stacksAt(IntStream.range(0, InventoryUtil.WHOLE_INVENTORY));
        carrying.removeIf(ItemStack::isEmpty);
        Poured carried = pour(packable(storage), carrying, InventoryUtil.WHOLE_INVENTORY);
        return tip("this container" + (carried.over.isEmpty() ? "" : " that your inventory has room to carry"),
            carried.in);
    }

    // The sentence for the stacks a press would send from a place to the box.
    private String tip(String where, List<ItemStack> stacks) {
        return "Every " + kind() + " in " + where + " goes into " + named(stackAt(boxSlot())) + ending(stacks) + ".";
    }

    // How the sentence ends when the first box cannot take every stack a press sends. The rest
    // goes on into the next boxes whilst More boxes is on and stays behind otherwise.
    private String ending(List<ItemStack> stacks) {
        int[] boxes = boxes().toArray();
        List<ItemStack> over = pourInto(boxes[0], stacks);
        if (over.isEmpty()) {
            return "";
        }
        if (!moreBoxes.isOn() || boxes.length == 1) {
            return " until it is full";
        }
        for (int next = 1; next < boxes.length; next++) {
            over = pourInto(boxes[next], over);
        }
        return " and then your next box with room" + (over.isEmpty() ? "" : " until every box is full");
    }

    @Override
    protected void onEnable() {
        if (!inGame()) {
            setEnabled(false);
            return;
        }
        AbstractContainerMenu storage = openStorage();
        reset();
        // Nothing moves before a box is known to go down with something to put in it.
        String refusal = refusal(storage);
        if (refusal != null) {
            disable(refusal);
            return;
        }
        // A put back an earlier run left unfinished ends here and this run takes its pieces over.
        tellPutBack();
        if (storage == null) {
            stage = Stage.UNEQUIP;
            return;
        }
        before = new ItemStack[InventoryUtil.WHOLE_INVENTORY];
        for (int i = 0; i < before.length; i++) {
            before[i] = stackAt(i).copy();
        }
        stage = Stage.TAKE;
    }

    @Override
    protected void onDisable() {
        only = null;
        release();
        // A run stopped by hand still owes back what came off and never went in.
        if (inGame() && !putBack()) {
            watch(restorer);
        }
    }

    @Subscribe
    private void onTick(TickEvent event) {
        switch (stage) {
            case TAKE -> take();
            case UNEQUIP -> unequip();
            case PLACE -> place();
            case FILL -> {
                if (!box.tick(this::fill, true, true)) {
                    afterBox();
                }
            }
            case PICKUP -> pickUp();
            case EQUIP -> equip();
        }
    }

    // Starts a run afresh. Pieces an earlier run still owes back stay listed and this run puts
    // them back at its end.
    private void reset() {
        prepare();
        clicked.clear();
        packed = 0;
        timer = 0;
        roomLeft = false;
    }

    // Works out what a pack of your own items keeps out. The best pickaxe you carry never goes in.
    private void prepare() {
        kept.clear();
        taken = null;
        pickaxe = ItemUtil.bestToolSlot(Blocks.SHULKER_BOX.defaultBlockState(), 1, stack -> true,
            InventoryUtil.WHOLE_INVENTORY);
        if (pickaxe != -1) {
            kept.add(pickaxe);
        }
    }

    // Why a press would pack nothing as things stand. Null when it would pack.
    private String refusal(AbstractContainerMenu storage) {
        if (boxSlot() == -1) {
            return NO_BOX;
        }
        if (storage != null) {
            String nothing = nothingFrom(storage);
            if (nothing != null) {
                return nothing;
            }
        } else if (!waits(true) && nextPiece() == -1) {
            return nothingOfYours();
        }
        return PlacedBox.spot() == null ? NO_SPOT : null;
    }

    // Why your own items give the box nothing. Either a worn piece waits for a free slot or nothing
    // fits where the pack looks. A part that is switched off may hold what does fit.
    private String nothingOfYours() {
        String worn = words(part -> !part.reached && part.indexes().anyMatch(index -> wanted(part, index)), " and ");
        if (!worn.isEmpty()) {
            return "Empty one slot in your " + words(part -> part.reached && packsFrom(part), " or ")
                + " and your " + worn + " can be packed.";
        }
        String off = Arrays.stream(Part.values())
            .filter(part -> !packsFrom(part) && part.indexes().anyMatch(index -> fits(part, index)))
            .map(part -> switches.get(part).getName())
            .collect(Collectors.joining(" or "));
        return "There is no " + kind() + " to pack in " + own() + "."
            + (off.isEmpty() ? "" : " Turn on " + inSettings(off) + " to pack what is there.") + staysWords();
    }

    // Why an open container gives the box nothing. Null when a stack of it can come across. One
    // that holds nothing for the box may be open over items of yours that would go in.
    private String nothingFrom(AbstractContainerMenu storage) {
        List<ItemStack> stacks = packable(storage);
        if (stacks.isEmpty()) {
            return "There is no " + kind() + " to pack in this container." + (refusal(null) != null ? ""
                : " Turn off " + inSettings(fromContainer.getName()) + " to pack your own items instead.");
        }
        List<Integer> mine = MenuClicks.inventorySlots(storage);
        return stacks.stream().anyMatch(stack -> MenuClicks.room(storage, mine, stack) > 0) ? null : NO_CARRY;
    }

    // The stacks of a container that the pack choice takes.
    private List<ItemStack> packable(AbstractContainerMenu storage) {
        return MenuClicks.containerSlots(storage).stream()
            .map(slot -> storage.slots.get(slot).getItem()).filter(this::packs).toList();
    }

    // The switches named and where the player finds them.
    private String inSettings(String names) {
        return names + " in the " + getName() + " settings";
    }

    // The pack choice as the kind of item it takes.
    private String kind() {
        if (only != null) {
            return onlyKind;
        }
        return switch (pack.getValue()) {
            case EVERYTHING -> "item";
            case LISTED -> "listed item";
            case UNLISTED -> "unlisted item";
            case STACKING -> "item that stacks";
            case SINGLE -> "item that never stacks";
        };
    }

    // Where your own items are packed from such as your backpack and hotbar.
    private String own() {
        return "your " + words(this::packsFrom, " and ");
    }

    private static String words(Predicate<Part> test, String joiner) {
        return Arrays.stream(Part.values()).filter(test).map(part -> part.word)
            .collect(Collectors.joining(joiner));
    }

    // Says what stays although it lies where the pack looks. It would seem forgotten.
    private String staysWords() {
        boolean pick = Arrays.stream(Part.values())
            .anyMatch(part -> part.holds(pickaxe) && packsFrom(part) && packs(stackAt(pickaxe)));
        boolean stuck = packsFrom(Part.ARMOUR) && Part.ARMOUR.indexes()
            .anyMatch(index -> packs(stackAt(index)) && InventoryUtil.stuckOn(stackAt(index)));
        return (pick ? " Your best pickaxe stays with you." : "")
            + (stuck ? " Armour you cannot take off stays on." : "");
    }

    // A box by the name its tooltip shows such as your Red Shulker Box or your box named Ores.
    private static String named(ItemStack box) {
        String name = box.getHoverName().getString();
        return box.getCustomName() == null ? "your " + name : "your box named " + name;
    }

    // The storage container whose stacks a press packs. Null when your own items go in.
    private AbstractContainerMenu openStorage() {
        return fromContainer.isOn() && mc.gui.screen() instanceof AbstractContainerScreen<?> screen
            && !(screen instanceof InventoryScreen) && InventoryUtil.isStorage(screen) ? screen.getMenu() : null;
    }

    // True once no screen is up and your own inventory takes clicks. A container shuts whether its
    // screen shows or not and any other screen is waited out.
    private boolean nothingOpen() {
        Screen screen = mc.gui.screen();
        if (screen instanceof AbstractContainerScreen<?> || (screen == null && InventoryUtil.menuKept())) {
            mc.player.closeContainer();
            return false;
        }
        return screen == null;
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
        if (taken.isEmpty()) {
            stop(full ? NO_CARRY : NOTHING_TAKEN);
            return;
        }
        stage = Stage.UNEQUIP;
    }

    // Brings one worn piece into a free slot the box screen shows each time the delay allows. The
    // game lets go of every piece that waits. One it still holds on to ends the run.
    private void unequip() {
        if (!nothingOpen()) {
            return;
        }
        int piece = nextPiece();
        if (piece == -1) {
            stage = Stage.PLACE;
            return;
        }
        if (!InventoryUtil.cursorFree() || waiting()) {
            return;
        }
        int into = freeSlot();
        ItemStack stack = stackAt(piece).copy();
        kept.add(piece);
        timer = delay.getInt();
        if (InventoryUtil.swap(menuSlot(piece), menuSlot(into)) == InventoryUtil.Swap.REFUSED) {
            stop("Your " + stack.getHoverName().getString() + " would not come off.");
        } else {
            brought.add(new Brought(piece, into, stack));
        }
    }

    // Sets the next box with room down beside you. A box out of the hotbar is lent into it first.
    private void place() {
        if (!nothingOpen()) {
            return;
        }
        if (!waits(true)) {
            nothingInReach();
            return;
        }
        int boxSlot = boxSlot();
        if (boxSlot == -1) {
            stop(packed == 0 ? NO_BOX : "Every shulker box you carry is full.");
            return;
        }
        if (boxSlot >= InventoryUtil.HOTBAR_SIZE) {
            // The pickaxe keeps its hotbar slot to mine the box back. Your own stack the box
            // pushes out of a hotbar that is not packed stays out. What a container gave is
            // counted by kind and goes in wherever it lies.
            if (loan.select(boxSlot, pickaxe) && taken == null && !hotbar.isOn() && !stackAt(boxSlot).isEmpty()) {
                kept.add(boxSlot);
            }
            return;
        }
        placedAt = PlacedBox.spot();
        if (placedAt == null) {
            stop(NO_SPOT);
            return;
        }
        boxesHeld = boxCount();
        if (!box.setDown(boxSlot, placedAt, true)) {
            stop(NOT_DOWN);
            return;
        }
        clicked.clear();
        roomLeft = false;
        stage = Stage.FILL;
    }

    // Nothing waits where the box screen shows it. A run that packed is done. One that packed
    // nothing says why as things stand and goes round again should a worn piece be free to come.
    private void nothingInReach() {
        if (packed > 0) {
            finish();
            return;
        }
        String why = taken == null ? refusal(null) : NOTHING_TAKEN;
        if (why == null) {
            stage = Stage.UNEQUIP;
        } else {
            stop(why);
        }
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
        for (int index : queued(true).toArray()) {
            int slot = MenuClicks.slotOf(menu, index);
            ItemStack stack = stackAt(index);
            if (slot != -1 && !clicked.thrownBack(slot, stack) && MenuClicks.room(menu, boxSlots, stack) > 0) {
                pack(menu, slot, boxSlots);
                packed++;
                return true;
            }
        }
        roomLeft = MenuClicks.firstContainerSlot(menu, ItemStack::isEmpty) != -1;
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
            stop("The shulker box could not be mined and stands where it went down.");
        } else if (box.wouldNotOpen()) {
            stop("The shulker box would not open and was mined back.");
        } else if (!box.opened()) {
            stop(NOT_DOWN);
        } else {
            stage = Stage.PICKUP;
            waited = 0;
        }
    }

    private void pickUp() {
        if (boxCount() < boxesHeld) {
            if (++waited > PICKUP_WAIT) {
                stop("The shulker box did not come back to your inventory.");
            }
            return;
        }
        release();
        if (again()) {
            stage = Stage.UNEQUIP;
        } else {
            finish();
        }
    }

    // More boxes carries on whilst anything can still go in. Without it only a worn piece that had
    // to stay on gets a go at the box that came back with room.
    private boolean again() {
        boolean piece = nextPiece() != -1;
        return moreBoxes.isOn() ? piece || waits(true) : piece && roomLeft;
    }

    private void finish() {
        tell("packed §f" + Tally.counted(packed, "stack") + "§7." + restWords());
        leave();
    }

    // Says that stacks stayed behind. A full box left them and a switch would have filled the next
    // one. A box that came back with room means the server refused the moves.
    private String restWords() {
        if (!waits(true) && !waits(false)) {
            return "";
        }
        if (roomLeft) {
            return " The rest would not go in.";
        }
        boolean next = !moreBoxes.isOn() && boxSlot() != -1;
        return " The box had no room for the rest."
            + (next ? " Turn on " + inSettings(moreBoxes.getName()) + " to fill your next box too." : "");
    }

    // Tells the player why in chat and ends the run.
    private void stop(String reason) {
        ChatUtil.error(reason);
        leave();
    }

    // The hotbar loan goes home first because it moves stacks about.
    private void leave() {
        release();
        stage = Stage.EQUIP;
        equip();
    }

    // The module stays on until what came off and never went in is back on.
    private void equip() {
        if (putBack()) {
            setEnabled(false);
        }
    }

    // Puts back on one piece that came off and never went in each time the delay allows. True once
    // none is left. Chat hears of it only when something went back.
    private boolean putBack() {
        brought.removeIf(piece -> !lies(piece));
        if (brought.isEmpty()) {
            tellPutBack();
            return true;
        }
        if (InventoryUtil.cursorFree() && !waiting()) {
            Brought piece = brought.removeFirst();
            if (InventoryUtil.swap(menuSlot(piece.into), menuSlot(piece.from)) != InventoryUtil.Swap.REFUSED) {
                restored++;
            }
            timer = delay.getInt();
        }
        return false;
    }

    // Says how many stacks went back on and starts the count afresh. Silent when none did.
    private void tellPutBack() {
        if (restored > 0) {
            tell("put back §f" + Tally.counted(restored, "stack")
                + "§7 that did not go in.");
            restored = 0;
        }
    }

    // True whilst a piece lies where it came in and the place it came off is still bare.
    private boolean lies(Brought piece) {
        return ItemStack.isSameItemSameComponents(stackAt(piece.into), piece.kind) && stackAt(piece.from).isEmpty();
    }

    // Lets go of the box and of the hotbar slots the run borrowed. A loan going home is a click
    // and the next one waits its turn.
    private void release() {
        box.end();
        slots.restoreIfMine();
        if (loan.isLent()) {
            timer = delay.getInt();
        }
        loan.giveBack();
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
            tally(kinds, stackAt(i), 1);
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

    // Inventory indexes whose stacks wait to go into a box. Either those the box screen shows or
    // the worn ones that have to come into reach first.
    private IntStream queued(boolean reached) {
        return Arrays.stream(Part.values()).filter(part -> part.reached == reached)
            .flatMapToInt(part -> part.indexes().filter(index -> wanted(part, index)));
    }

    private boolean waits(boolean reached) {
        return queued(reached).findAny().isPresent();
    }

    // The worn piece to bring into reach next. Minus one when none waits or none can come because
    // no slot is free or the box about to go down has no room left for it. Worn pieces go in last
    // and only come off when the box takes them on top of all that waits in reach of it.
    private int nextPiece() {
        int piece = queued(false).findFirst().orElse(-1);
        int box = boxSlot();
        if (piece == -1 || box == -1 || freeSlot() == -1) {
            return -1;
        }
        List<ItemStack> stacks = stacksAt(IntStream.concat(queued(true), IntStream.of(piece)));
        return pourInto(box, stacks).isEmpty() ? piece : -1;
    }

    // An empty slot the box screen shows in a part that is packed. Minus one when none is free.
    private int freeSlot() {
        return Arrays.stream(Part.values()).filter(part -> part.reached && packsFrom(part))
            .flatMapToInt(Part::indexes)
            .filter(index -> stackAt(index).isEmpty() && !kept.contains(index))
            .findFirst().orElse(-1);
    }

    // True for the stack at an index of a part that this run packs. What a container gave is
    // counted by kind wherever in reach of the box it lies.
    private boolean wanted(Part part, int index) {
        if (!fits(part, index)) {
            return false;
        }
        if (taken == null) {
            return packsFrom(part);
        }
        Taken kind = part.reached ? takenOf(stackAt(index)) : null;
        return kind != null && kind.left > 0;
    }

    private boolean packsFrom(Part part) {
        BoolSetting toggle = switches.get(part);
        return toggle == null || toggle.isOn();
    }

    // True for an index of a part whose stack the pack choice takes and nothing keeps out. Armour
    // the game will not let you take off stays on.
    private boolean fits(Part part, int index) {
        ItemStack stack = stackAt(index);
        return !kept.contains(index) && packs(stack) && !(part == Part.ARMOUR && InventoryUtil.stuckOn(stack));
    }

    // True for a stack the pack choice takes. A shulker box never goes into another.
    private boolean packs(ItemStack stack) {
        if (stack.isEmpty() || !stack.getItem().canFitInsideContainerItems()) {
            return false;
        }
        if (only != null) {
            return only.test(stack);
        }
        return switch (pack.getValue()) {
            case EVERYTHING -> true;
            case LISTED -> items.contains(stack.getItem());
            case UNLISTED -> !items.contains(stack.getItem());
            case STACKING -> stack.isStackable();
            case SINGLE -> !stack.isStackable();
        };
    }

    private static ItemStack stackAt(int index) {
        return mc.player.getInventory().getItem(index);
    }

    // Copies of the stacks at inventory indexes for a pour to work on.
    private static List<ItemStack> stacksAt(IntStream indexes) {
        return indexes.mapToObj(index -> stackAt(index).copy()).collect(Collectors.toCollection(ArrayList::new));
    }

    // The slot of your own inventory screen that shows an inventory index.
    private static int menuSlot(int index) {
        return MenuClicks.slotOf(mc.player.inventoryMenu, index);
    }

    private static int boxSlot() {
        return boxes().findFirst().orElse(-1);
    }

    // Where the shulker boxes with an empty slot lie in the order a run fills them.
    private static IntStream boxes() {
        return IntStream.range(0, InventoryUtil.WHOLE_INVENTORY).filter(index -> {
            ItemStack stack = stackAt(index);
            return ItemUtil.isShulkerBox(stack.getItem())
                && inside(stack).size() < ShulkerBoxBlockEntity.CONTAINER_SIZE;
        });
    }

    // Copies of the stacks a shulker box you carry holds.
    private static List<ItemStack> inside(ItemStack box) {
        ItemContainerContents contents = box.get(DataComponents.CONTAINER);
        return contents == null ? new ArrayList<>()
            : contents.nonEmptyItemCopyStream().collect(Collectors.toCollection(ArrayList::new));
    }

    // What the box at an inventory index would leave out of the stacks.
    private static List<ItemStack> pourInto(int box, List<ItemStack> stacks) {
        return pour(stacks, inside(stackAt(box)), ShulkerBoxBlockEntity.CONTAINER_SIZE).over;
    }

    // Pours stacks into a holder of a given number of slots the way shift clicks do. Each stack
    // tops up the stacks of its kind and what is left of it takes an empty slot. The holder ends
    // up holding what it would hold.
    private static Poured pour(List<ItemStack> stacks, List<ItemStack> holder, int slots) {
        List<ItemStack> in = new ArrayList<>();
        List<ItemStack> over = new ArrayList<>();
        for (ItemStack stack : stacks) {
            int left = stack.getCount();
            for (ItemStack there : holder) {
                if (ItemStack.isSameItemSameComponents(there, stack)) {
                    int topUp = Math.max(0, Math.min(left, there.getMaxStackSize() - there.getCount()));
                    there.grow(topUp);
                    left -= topUp;
                }
            }
            if (left > 0 && holder.size() < slots) {
                holder.add(stack.copyWithCount(left));
                left = 0;
            }
            if (left < stack.getCount()) {
                in.add(stack.copyWithCount(stack.getCount() - left));
            }
            if (left > 0) {
                over.add(stack.copyWithCount(left));
            }
        }
        return new Poured(in, over);
    }

    private static int boxCount() {
        return InventoryUtil.count(stack -> ItemUtil.isShulkerBox(stack.getItem()), InventoryUtil.WHOLE_INVENTORY);
    }
}
