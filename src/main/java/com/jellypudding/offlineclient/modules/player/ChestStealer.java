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
import com.jellypudding.offlineclient.util.ItemUtil;
import com.jellypudding.offlineclient.util.LootLimits;
import com.jellypudding.offlineclient.util.MenuClicks;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.RotationPriority;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.BrewingStandMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.FurnaceResultSlot;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

public final class ChestStealer extends Module {

    // A brewing stand holds its three bottles first. Its ingredient and fuel come after.
    private static final int BREWING_BOTTLES = 3;

    // Smallest stack is a share out of a hundred.
    private static final double PERCENT = 100;

    // The menus whose slots the stealer knows. A crafting or trade or anvil result would spend
    // your own items when taken.
    private static final Set<MenuType<?>> STEALABLE = Set.of(MenuType.GENERIC_9x1, MenuType.GENERIC_9x2,
        MenuType.GENERIC_9x3, MenuType.GENERIC_9x4, MenuType.GENERIC_9x5, MenuType.GENERIC_9x6,
        MenuType.SHULKER_BOX, MenuType.HOPPER, MenuType.GENERIC_3x3, MenuType.FURNACE, MenuType.BLAST_FURNACE,
        MenuType.SMOKER, MenuType.BREWING_STAND, MenuType.CRAFTER_3x3);

    // Better gear first and then fuller stacks.
    private static final Comparator<ItemStack> RANK = Comparator.comparingDouble(ItemUtil::gearScore)
        .thenComparingInt(ItemStack::getCount);

    private final NumberSetting delay = new NumberSetting("Delay",
        "Milliseconds between each item grab.", 50, 0, 500, 10, "ms").min(0);
    private final NumberSetting initialDelay = new NumberSetting("Initial delay",
        "Milliseconds to wait before the first grab of a container.", 50, 0, 1000, 10, "ms")
        .min(0);
    private final NumberSetting jitter = new NumberSetting("Jitter",
        "Adds up to this many milliseconds at random to each grab.", 50, 0, 500, 10, "ms")
        .min(0);
    private final NumberSetting perTick = new NumberSetting("Stacks per tick",
        "How many stacks move in one tick once the delay allows.", 1, 1, 27, 1).min(1);
    private final EnumSetting<ListMode> listMode = ListMode.setting("List mode", ListMode.BLACKLIST,
        "Takes only the listed items.", "Takes everything except the listed items.");
    private final RegistryListSetting<Item> items = new RegistryListSetting<>("Items",
        "The items the list applies to. Click to pick them.", BuiltInRegistries.ITEM,
        List.of());
    private final NumberSetting smallest = new NumberSetting("Smallest stack",
        "Container stacks below this share of a full stack are left behind.", 0, 0, 100, 5, " %")
        .min(0).max(PERCENT);
    private final BoolSetting furnaceInputs = new BoolSetting("Furnace inputs",
        "Also takes the fuel and what is cooking out of furnaces and brewing stands.", false);
    private final LootLimits limits = new LootLimits();
    private final RegistryListSetting<MenuType<?>> screens = new RegistryListSetting<>("Screens",
        "Which container screens are stolen from. Click to pick them.", BuiltInRegistries.MENU,
        List.of(MenuType.GENERIC_9x3, MenuType.GENERIC_9x6, MenuType.SHULKER_BOX,
            MenuType.HOPPER, MenuType.GENERIC_3x3, MenuType.FURNACE, MenuType.BLAST_FURNACE,
            MenuType.SMOKER, MenuType.BREWING_STAND, MenuType.CRAFTER_3x3))
        .only(STEALABLE::contains);
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
    private boolean open;
    // Set by the dump button until every stack the dump wants has gone in.
    private boolean dumping;
    // What a dump put in the container is left there for the rest of the visit.
    private boolean dumped;
    // Set whilst ChestAura runs a visit. The module's own tick stands back meanwhile.
    private boolean driven;
    // Set for a ChestAura visit that stores as well. Taking then leaves the dump filter's picks.
    private boolean storing;
    // What each slot held when it was last clicked. A slot that holds it still had its move
    // thrown back by the server and is passed over until its stack changes.
    private final Map<Integer, ItemStack> clickedAt = new HashMap<>();

    public ChestStealer() {
        super("ChestStealer", "Takes everything or only what you set limits for out of containers.",
            Category.PLAYER);
        addSettings(delay, initialDelay, jitter, perTick, listMode, items, smallest, furnaceInputs);
        addSettings(limits.settings());
        addSettings(screens, throwOut, backwards, buttons, close);
        limits.visibleWhen(() -> !throwOut.isOn());
        searchTags("loot", "chest", "filter", "steal", "dump", "limits");
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
        clickedAt.clear();
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
    public void startVisit(boolean alsoStores) {
        forget();
        driven = true;
        storing = alsoStores;
    }

    public void endVisit() {
        forget();
        driven = false;
        storing = false;
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

    // Something held goes past its limit and a visit would put it back.
    public boolean extrasToStore() {
        if (!limited() || !limits.storesExtras()) {
            return false;
        }
        for (Map.Entry<LootLimits.Rule, Integer> held : limits.holdings().entrySet()) {
            int limit = held.getKey().limit();
            if (limit > 0 && held.getValue() > limit) {
                return true;
            }
        }
        return false;
    }

    // A full inventory can still trade up for better gear or fuller stacks.
    public boolean mayUpgrade() {
        return limited() && limits.upgrades();
    }

    // True for a stack the list or the limits turn away. InventoryTweaks can dump every such stack.
    public boolean turnsAway(ItemStack stack) {
        return !listMode.getValue().admits(items.contains(stack.getItem()))
            || limited() && limits.ruleFor(stack) == null;
    }

    // Throwing items out keeps none and has no use for a limit.
    private boolean limited() {
        return limits.isOn() && !throwOut.isOn();
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

    // Up to the per tick count of moves once the delay allows. Every one is predicted on the
    // menu at once and the next move sees it.
    private Step step(boolean store) {
        long now = System.currentTimeMillis();
        if (!open) {
            open = true;
            nextClick = now + initialDelay.getInt();
        }
        if (now < nextClick) {
            return Step.WAITING;
        }
        AbstractContainerMenu menu = mc.player.containerMenu;
        Step step = Step.DONE;
        for (int moves = 0; moves < perTick.getInt(); moves++) {
            step = store ? storeOnce(menu) : takeOnce(menu);
            if (step != Step.MOVED) {
                if (moves > 0) {
                    waitAgain();
                }
                return step;
            }
        }
        waitAgain();
        return step;
    }

    // Extras go back first to make room. A trade comes once nothing more can be taken.
    private Step takeOnce(AbstractContainerMenu menu) {
        if (throwOut.isOn()) {
            return throwOnce(menu);
        }
        Map<LootLimits.Rule, Integer> held = limited() ? limits.holdings() : Map.of();
        if (limited() && limits.storesExtras() && storeExtra(menu, held)) {
            return Step.MOVED;
        }
        Step taken = takeWanted(menu, held);
        if (taken == Step.MOVED || !mayUpgrade()) {
            return taken;
        }
        return upgrade(menu, held) ? Step.MOVED : taken;
    }

    // The best stack still wanted goes first and only as many as its limit allows.
    private Step takeWanted(AbstractContainerMenu menu, Map<LootLimits.Rule, Integer> held) {
        List<Integer> mine = MenuClicks.inventorySlots(menu);
        boolean blocked = false;
        for (int slot : bestFirst(menu, MenuClicks.containerSlots(menu))) {
            ItemStack stack = menu.slots.get(slot).getItem();
            int count = wantedCount(menu, slot, stack, held);
            // A furnace result never takes back the rest of a split stack. It would stay on the
            // cursor and drop at your feet when the screen closes with no room left.
            if (count == 0 || count < stack.getCount() && !menu.slots.get(slot).mayPlace(stack)) {
                continue;
            }
            if (thrownBack(slot, stack) || MenuClicks.room(menu, mine, stack) == 0) {
                blocked = true;
                continue;
            }
            move(menu, slot, mine, count);
            return Step.MOVED;
        }
        return blocked ? Step.FULL : Step.DONE;
    }

    // How many of a container stack the rules still want. Nought leaves it where it is.
    private int wantedCount(AbstractContainerMenu menu, int slot, ItemStack stack,
                            Map<LootLimits.Rule, Integer> held) {
        if (!takeable(menu, slot, stack)) {
            return 0;
        }
        if (!limited()) {
            return stack.getCount();
        }
        LootLimits.Rule rule = limits.ruleFor(stack);
        return rule == null ? 0 : Math.clamp(rule.limit() - held.getOrDefault(rule, 0), 0, stack.getCount());
    }

    // The list lets it through and nothing keeps it in the container.
    private boolean takeable(AbstractContainerMenu menu, int slot, ItemStack stack) {
        return !stack.isEmpty() && !turnsAway(stack) && !((dumped || storing) && dumps(stack))
            && !keptInput(menu, slot) && stack.getCount() * PERCENT >= smallest.getValue() * stack.getMaxStackSize();
    }

    // A furnace keeps its fuel and what is cooking and a brewing stand its ingredient and fuel.
    private boolean keptInput(AbstractContainerMenu menu, int slot) {
        if (furnaceInputs.isOn()) {
            return false;
        }
        Slot target = menu.slots.get(slot);
        if (menu instanceof AbstractFurnaceMenu) {
            return !(target instanceof FurnaceResultSlot);
        }
        return menu instanceof BrewingStandMenu && target.getContainerSlot() >= BREWING_BOTTLES;
    }

    // Puts back a stack held past its limit. The worst goes first and never more than the extra.
    // Worn armour and the offhand count but cannot be put back. A piece better than the one
    // worn under the same limit stays.
    private boolean storeExtra(AbstractContainerMenu menu, Map<LootLimits.Rule, Integer> held) {
        if (!InventoryUtil.keeps(menu)) {
            return false;
        }
        Map<LootLimits.Rule, Double> worn = limits.bestWorn();
        List<Integer> theirs = MenuClicks.containerSlots(menu);
        for (int slot : worstFirst(menu, MenuClicks.inventorySlots(menu))) {
            ItemStack stack = menu.slots.get(slot).getItem();
            LootLimits.Rule rule = stack.isEmpty() ? null : limits.ruleFor(stack);
            if (rule == null || rule.limit() == 0
                || ItemUtil.gearScore(stack) > worn.getOrDefault(rule, Double.POSITIVE_INFINITY)) {
                continue;
            }
            int extra = held.getOrDefault(rule, 0) - rule.limit();
            if (extra <= 0 || thrownBack(slot, stack) || MenuClicks.room(menu, theirs, stack) == 0) {
                continue;
            }
            move(menu, slot, theirs, Math.min(extra, stack.getCount()));
            return true;
        }
        return false;
    }

    // Trades your worst stack under a limit for a better one in the container. Gear goes by its
    // score. A fuller stack only trades whilst every slot is taken and the limit still holds.
    // Only storage that keeps what it is given takes the trade.
    private boolean upgrade(AbstractContainerMenu menu, Map<LootLimits.Rule, Integer> held) {
        if (!InventoryUtil.keeps(menu)) {
            return false;
        }
        boolean full = mc.player.getInventory().getFreeSlot() == -1;
        List<Integer> mine = MenuClicks.inventorySlots(menu);
        for (int slot : bestFirst(menu, MenuClicks.containerSlots(menu))) {
            ItemStack offer = menu.slots.get(slot).getItem();
            LootLimits.Rule rule = takeable(menu, slot, offer) ? limits.ruleFor(offer) : null;
            if (rule == null || rule.limit() == 0 || thrownBack(slot, offer)) {
                continue;
            }
            int worst = worstUnder(menu, mine, rule);
            if (worst == -1) {
                continue;
            }
            ItemStack given = menu.slots.get(worst).getItem();
            if (!menu.slots.get(slot).mayPlace(given) || !beats(offer, given, rule, held, full)) {
                continue;
            }
            clickedAt.put(slot, offer.copy());
            clickedAt.put(worst, given.copy());
            MenuClicks.swap(menu, slot, worst);
            return true;
        }
        return false;
    }

    private static boolean beats(ItemStack offer, ItemStack given, LootLimits.Rule rule,
                                 Map<LootLimits.Rule, Integer> held, boolean full) {
        double gain = ItemUtil.gearScore(offer) - ItemUtil.gearScore(given);
        if (gain != 0) {
            return gain > 0;
        }
        int more = offer.getCount() - given.getCount();
        return full && more > 0 && held.getOrDefault(rule, 0) + more <= rule.limit();
    }

    // Your slot holding the worst stack under the rule. Minus one when you hold none of it.
    private int worstUnder(AbstractContainerMenu menu, List<Integer> slots, LootLimits.Rule rule) {
        int worst = -1;
        for (int slot : slots) {
            ItemStack stack = menu.slots.get(slot).getItem();
            if (!stack.isEmpty() && rule.equals(limits.ruleFor(stack))
                && (worst == -1 || RANK.compare(stack, menu.slots.get(worst).getItem()) < 0)) {
                worst = slot;
            }
        }
        return worst;
    }

    // Moves one stack the InventoryTweaks dump filter wants into the container. Only plain
    // storage takes a shift clicked stack. ChestAura only fills storage that keeps it whilst
    // the dump button fills any hopper or dropper you open.
    private Step storeOnce(AbstractContainerMenu menu) {
        if (!(driven ? InventoryUtil.keeps(menu) : InventoryUtil.isStorage(menu))) {
            return Step.DONE;
        }
        List<Integer> theirs = MenuClicks.containerSlots(menu);
        boolean blocked = false;
        for (int slot : MenuClicks.inventorySlots(menu)) {
            ItemStack stack = menu.slots.get(slot).getItem();
            if (!dumps(stack)) {
                continue;
            }
            if (thrownBack(slot, stack) || MenuClicks.room(menu, theirs, stack) == 0) {
                blocked = true;
                continue;
            }
            move(menu, slot, theirs, stack.getCount());
            return Step.MOVED;
        }
        return blocked ? Step.FULL : Step.DONE;
    }

    // Button one throws the whole stack onto the ground. The look has to reach the server first.
    private Step throwOnce(AbstractContainerMenu menu) {
        for (int slot : MenuClicks.containerSlots(menu)) {
            ItemStack stack = menu.slots.get(slot).getItem();
            if (!takeable(menu, slot, stack) || thrownBack(slot, stack)) {
                continue;
            }
            if (backwards.isOn()) {
                FaceMode.SPAM.faceExact(mc.player.getYRot() + 180f, mc.player.getXRot(), RotationPriority.IDLE);
            }
            clickedAt.put(slot, stack.copy());
            MenuClicks.click(menu, slot, 1, ContainerInput.THROW);
            return Step.MOVED;
        }
        return Step.DONE;
    }

    // A whole stack goes by shift click. Part of one is dropped into a single slot a right click
    // at a time and the rest goes back where it was.
    private void move(AbstractContainerMenu menu, int from, List<Integer> into, int count) {
        ItemStack stack = menu.slots.get(from).getItem();
        clickedAt.put(from, stack.copy());
        int target = count < stack.getCount() ? MenuClicks.landing(menu, into, stack) : -1;
        if (target == -1) {
            MenuClicks.quickMove(menu, from);
        } else {
            MenuClicks.moveSome(menu, from, target, count);
        }
    }

    private boolean thrownBack(int slot, ItemStack now) {
        ItemStack before = clickedAt.get(slot);
        return before != null && ItemStack.matches(before, now);
    }

    private static List<Integer> bestFirst(AbstractContainerMenu menu, List<Integer> slots) {
        return ranked(menu, slots, true);
    }

    private static List<Integer> worstFirst(AbstractContainerMenu menu, List<Integer> slots) {
        return ranked(menu, slots, false);
    }

    // Each stack is scored once. Scoring it on every comparison costs far more.
    private static List<Integer> ranked(AbstractContainerMenu menu, List<Integer> slots, boolean best) {
        Map<Integer, Double> scores = new HashMap<>();
        for (int slot : slots) {
            scores.put(slot, ItemUtil.gearScore(menu.slots.get(slot).getItem()));
        }
        Comparator<Integer> order = Comparator.<Integer>comparingDouble(scores::get)
            .thenComparingInt(slot -> menu.slots.get(slot).getItem().getCount());
        List<Integer> sorted = new ArrayList<>(slots);
        sorted.sort(best ? order.reversed() : order);
        return sorted;
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
        open = false;
        dumping = false;
        dumped = false;
        clickedAt.clear();
    }
}
