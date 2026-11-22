package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.module.StationModule;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.BlockMiner;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.FaceMode;
import com.jellypudding.offlineclient.util.Holes;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.MenuClicks;
import com.jellypudding.offlineclient.util.Recipes;
import com.jellypudding.offlineclient.util.SwingMode;
import com.jellypudding.offlineclient.util.ThrowOut;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.saveddata.maps.MapId;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

// Crafts through the recipe book the way a shift click on a recipe does. A recipe that takes its
// own result such as dyeing a shulker box goes in by hand because the recipe book might pick a box
// that already has the colour. A map and an empty map go in by hand as well and come out as the map
// and its copy.
public final class AutoCraft extends StationModule<AbstractCraftingMenu> {

    private static final int PACE = 2;

    // Paper kicks a player who keeps sending recipe book requests faster than one a tick. The gap
    // leaves room for a server set stricter than that.
    private static final int PLACE_GAP = 6;

    private static final int TABLE_WAIT = 10;

    // A second click opens a second screen on a slow server.
    private static final int OPEN_WAIT = 40;

    private static final int MOST_TRIES = 4;

    private static final int MINE_LIMIT = 200;

    private static final int RUN_GAP = 20;

    private final RegistryListSetting<Item> items = new RegistryListSetting<>("Items",
        "The items to craft. Each one is crafted until its ingredients run out.",
        BuiltInRegistries.ITEM, List.of()).onChange(this::forgetRecipes);
    private final ThrowOut throwOut = new ThrowOut(
        "Throws what is crafted in a grid you opened on the ground instead of into your inventory.");
    private final BoolSetting copyMaps = new BoolSetting("Copy maps",
        "Makes one copy of every filled map you carry whilst you carry empty maps."
            + " It runs before the items and works in your own inventory too.", false);
    private final BoolSetting restock = new BoolSetting("Restock by itself",
        "When you carry none of a chosen item it opens a crafting table in reach and crafts as many"
            + " as your ingredients allow and closes it again.", false);
    private final BoolSetting placeTable = new BoolSetting("Place a table",
        "Puts down a crafting table you carry when none is in reach.", true)
        .under(restock);
    private final BoolSetting onlyInHole = new BoolSetting("Only in a hole",
        "Restocks only whilst you stand in a hole of blast proof blocks.", false)
        .under(restock);
    private final NumberSetting aboveHealth = new NumberSetting("Above health",
        "Restocks only whilst you have more hearts than this. Nought never holds it back.",
        0, 0, 20, 0.5, " hearts")
        .under(restock);
    private final NumberSetting roomNeeded = new NumberSetting("Free slots",
        "Restocks only whilst at least this many inventory slots are empty.", 1, 1, 36, 1)
        .max(InventoryUtil.WHOLE_INVENTORY)
        .under(restock);
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Turns towards the table on the server side.", true)
        .under(restock);

    private List<Recipes.Craft> crafts;
    // The book makes a new list each time a recipe is learnt.
    private List<RecipeCollection> bookRead;
    private String placed;
    private boolean copying;
    private final Set<MapId> copied = new HashSet<>();
    private boolean refusedOnce;
    private int lastRequest = -PLACE_GAP;
    private int crafted;
    private final Takings takings = new Takings();
    private final InventoryUtil.HotbarLoan tableLoan = new InventoryUtil.HotbarLoan();
    // Every crafting table that opens whilst a run of the module's own is under way belongs to the
    // run.
    private boolean serving;
    private boolean arrived;
    private int tries;
    private int resumeAt;
    private boolean idle;
    private Predicate<Recipes.Craft> only;
    private BlockPos ownTable;
    private boolean wrappingUp;
    private int minedFor;
    private int settled;

    public AutoCraft() {
        super("AutoCraft", "Crafts the items you pick whilst a crafting table or your inventory is open.",
            AbstractCraftingMenu.class, PACE);
        addSettings(items);
        addSettings(throwOut.settings());
        addSettings(copyMaps, restock, placeTable, onlyInHole, aboveHealth, roomNeeded, rotate);
        searchTags("crafting table", "recipe book", "dye shulker", "copy maps", "clone map", "craft beds");
    }

    @Override
    public String getSuffix() {
        return count(crafted, "crafted");
    }

    @Override
    protected void onEnable() {
        super.onEnable();
        crafted = 0;
        resumeAt = 0;
        serving = false;
        arrived = false;
        tries = 0;
        copied.clear();
    }

    @Override
    protected void onDisable() {
        // A table the module opened for itself is not left open.
        if (serving && inGame() && mc.player.containerMenu instanceof CraftingMenu) {
            mc.player.closeContainer();
        }
        serving = false;
        tableLoan.release();
        if (ownTable != null) {
            BlockMiner.release(ownTable);
        }
        askedBy(null);
        ownTable = null;
    }

    // A table the run put down is mined back. False when no run started.
    public boolean craftOnly(Predicate<Recipes.Craft> wanted) {
        if (isEnabled() || !inGame()) {
            return false;
        }
        askedBy(wanted);
        wrappingUp = false;
        minedFor = 0;
        settled = 0;
        if (craftable()) {
            if (nearestTable() != null || carriedTable() != -1) {
                setEnabled(true);
            } else {
                ChatUtil.error("AutoCraft has no crafting table in reach or in your inventory to craft at.");
            }
        }
        if (!isEnabled()) {
            askedBy(null);
        }
        return isEnabled();
    }

    private void askedBy(Predicate<Recipes.Craft> test) {
        only = test;
        crafts = null;
    }

    @Override
    protected void closed() {
        crafts = null;
        placed = null;
        copying = false;
        refusedOnce = false;
        idle = false;
        takings.forget();
    }

    @Override
    protected void away() {
        boolean asked = only != null;
        if (!asked && !restock.isOn()) {
            return;
        }
        if (serving && arrived) {
            // The table of the run was shut by somebody else.
            endRun();
        }
        if (ticks() < resumeAt || mc.rightClickDelay > 0 || !handsFree(true)) {
            return;
        }
        if (asked && (wrappingUp || !craftable())) {
            wrappingUp = true;
            packUp();
            return;
        }
        if (!asked && !shouldRestock()) {
            serving = false;
            tries = 0;
            return;
        }
        if (tries >= MOST_TRIES) {
            giveUp("AutoCraft could not put down or open a crafting table.");
            return;
        }
        reachOut(asked);
    }

    private boolean craftable() {
        StackedItemContents contents = Recipes.carried();
        return crafts().stream().anyMatch(craft -> !craft.byHand() && craft.entry().canCraft(contents));
    }

    private void reachOut(boolean asked) {
        FaceMode face = rotate.isOn() ? FaceMode.SERVER : FaceMode.OFF;
        BlockPos table = nearestTable();
        if (table != null) {
            BlockUtil.interact(table, face);
            mc.rightClickDelay = InputUtil.USE_DELAY;
            tried(OPEN_WAIT);
            return;
        }
        if (!asked && !placeTable.isOn()) {
            return;
        }
        int carried = carriedTable();
        double reach = BlockUtil.serverBlockReach();
        BlockPos spot = carried == -1 ? null : tableSpot(reach);
        if (spot == null) {
            if (asked) {
                giveUp(carried == -1 ? "AutoCraft has no crafting table left to craft at."
                    : "AutoCraft has no room in reach to put a crafting table down.");
            }
            return;
        }
        if (tableLoan.select(carried)) {
            if (BlockUtil.reachablePlacement(spot, reach, false).place(face, SwingMode.BOTH) && asked) {
                ownTable = spot;
            }
            tableLoan.giveBack();
        }
        tried(TABLE_WAIT);
    }

    private void tried(int wait) {
        serving = true;
        tries++;
        resumeAt = ticks() + wait;
    }

    private void giveUp(String reason) {
        serving = false;
        tries = 0;
        if (only == null) {
            disable(reason);
            return;
        }
        ChatUtil.error(reason);
        wrappingUp = true;
    }

    private void endRun() {
        serving = false;
        arrived = false;
        tries = 0;
        if (only == null) {
            resumeAt = ticks() + RUN_GAP;
        } else {
            wrappingUp = true;
        }
    }

    private void packUp() {
        boolean standing = ownTable != null && BlockUtil.state(ownTable).is(Blocks.CRAFTING_TABLE);
        if (standing && ++minedFor < MINE_LIMIT) {
            BlockMiner.mine(ownTable, rotate.isOn());
            return;
        }
        if (ownTable == null || standing || ++settled >= TABLE_WAIT) {
            setEnabled(false);
        }
    }

    private static BlockPos nearestTable() {
        return BlockUtil.nearestWithin(BlockUtil.serverBlockReach(), AutoCraft::isTableInReach);
    }

    private static boolean isTableInReach(BlockPos pos) {
        return BlockUtil.state(pos).is(Blocks.CRAFTING_TABLE) && BlockUtil.serverReaches(pos);
    }

    private static int carriedTable() {
        return InventoryUtil.findSlot(Items.CRAFTING_TABLE, InventoryUtil.WHOLE_INVENTORY);
    }

    private boolean shouldRestock() {
        if (onlyInHole.isOn() && !Holes.playerInHole() || EntityUtil.healthAtOrBelow(aboveHealth.getValue())
            || InventoryUtil.emptySlots() < roomNeeded.getInt()) {
            return false;
        }
        StackedItemContents contents = Recipes.carried();
        for (Recipes.Craft craft : crafts()) {
            Item made = craft.result().getItem();
            if (!craft.byHand() && !mc.player.getOffhandItem().is(made)
                && InventoryUtil.count(made, InventoryUtil.WHOLE_INVENTORY) == 0
                && craft.entry().canCraft(contents)) {
                return true;
            }
        }
        return false;
    }

    // A click into the empty spot itself comes last because some servers refuse one.
    private static BlockPos tableSpot(double reach) {
        BlockPos leaning = BlockUtil.nearestWithin(reach, pos -> takesTable(pos, reach, true));
        return leaning != null ? leaning : BlockUtil.nearestWithin(reach, pos -> takesTable(pos, reach, false));
    }

    private static boolean takesTable(BlockPos pos, double reach, boolean leaning) {
        if (!BlockUtil.blockFits(pos)) {
            return false;
        }
        BlockUtil.Placement click = BlockUtil.reachablePlacement(pos, reach, false);
        return click != null && (!leaning || click.support() != null);
    }

    private void stop(String reason, boolean trouble) {
        boolean own = serving;
        if (own) {
            mc.player.closeContainer();
            endRun();
        }
        if (only != null) {
            if (trouble) {
                ChatUtil.error(reason);
            }
        } else if (trouble || !restock.isOn()) {
            disable(reason);
        } else if (!own) {
            idle = true;
        }
    }

    @Override
    protected void outOfRoom(String reason) {
        stop(reason, false);
    }

    private void forgetRecipes() {
        crafts = null;
    }

    @Override
    protected void work(AbstractCraftingMenu menu) {
        boolean own = serving && menu instanceof CraftingMenu;
        if (own && !arrived) {
            arrived = true;
            tries = 0;
        }
        crafted += takings.settle();
        // A run another module asked for only works a table of its own.
        if (idle || only != null && !own) {
            return;
        }
        Slot result = menu.getResultSlot();
        boolean filled = Recipes.gridFilled(menu);
        if (placed != null) {
            // The server sends the result first and the grid a moment later.
            if (result.hasItem() && filled) {
                take(menu);
                return;
            }
            if (!stalled()) {
                return;
            }
            // An inventory that had not caught up may have asked for too much. One retry settles it.
            if (!refusedOnce) {
                refusedOnce = true;
                placed = null;
                copying = false;
                return;
            }
            stop("The server crafted nothing from the recipe for " + placed + ".", true);
            return;
        }
        if (result.hasItem() && filled && items.contains(result.getItem().getItem())) {
            take(menu);
            return;
        }
        if (filled) {
            clearGrid(menu);
            return;
        }
        if (ticks() - lastRequest >= PLACE_GAP) {
            craftNext(menu);
        }
    }

    // The server crafts again and again until an ingredient runs out or the inventory fills.
    // Thrown out nothing fills and every set in the grid is crafted.
    private void take(AbstractCraftingMenu menu) {
        Slot result = menu.getResultSlot();
        placed = null;
        refusedOnce = false;
        if (copying) {
            // The map you had comes back with its copy and both stay with you.
            copying = false;
            if (moveOut(menu, result.index, "for the copied map.")) {
                crafted++;
                awaitServer(menu);
            }
            return;
        }
        if (throwing()) {
            crafted += sets(menu) * result.getItem().getCount();
        } else {
            takings.start(result.getItem().getItem());
        }
        if (takeCrafted(menu, result.index)) {
            awaitServer(menu);
        }
    }

    private boolean takeCrafted(AbstractCraftingMenu menu, int slot) {
        String what = "for what was crafted.";
        return throwing() ? takeFinished(throwOut, menu, slot, what) : moveOut(menu, slot, what);
    }

    // A run of the module's own keeps what it makes. Throw out only works a grid you opened.
    private boolean throwing() {
        return !serving && throwOut.isOn();
    }

    private static int sets(AbstractCraftingMenu menu) {
        int sets = 0;
        for (Slot slot : menu.getInputGridSlots()) {
            int count = slot.getItem().getCount();
            if (count > 0 && (sets == 0 || count < sets)) {
                sets = count;
            }
        }
        return sets;
    }

    private void clearGrid(AbstractCraftingMenu menu) {
        for (Slot slot : menu.getInputGridSlots()) {
            if (slot.hasItem() && !moveOut(menu, slot.index, "to clear the crafting grid.")) {
                return;
            }
        }
        awaitServer(menu);
    }

    private void craftNext(AbstractCraftingMenu menu) {
        if (only == null && copyMaps.isOn() && copyNextMap(menu)) {
            return;
        }
        if (only == null && items.resolved().isEmpty()) {
            if (copyMaps.isOn()) {
                stop(whyNoCopy(menu), false);
            } else {
                stop("Pick the items to craft first.", true);
            }
            return;
        }
        StackedItemContents contents = Recipes.carried();
        boolean fits = false;
        for (Recipes.Craft craft : crafts()) {
            int[] cells = Recipes.gridCells(craft, menu);
            if (cells == null) {
                continue;
            }
            fits = true;
            if (craft.byHand()) {
                int[] sources = handSources(menu, craft);
                if (sources != null) {
                    placeByHand(menu, cells, sources);
                    placed(craft.name());
                    return;
                }
            } else if (craft.entry().canCraft(contents)) {
                MenuClicks.placeRecipe(menu, craft.entry().id());
                lastRequest = ticks();
                placed(craft.name());
                return;
            }
        }
        stop(fits ? "Out of ingredients for the chosen items."
            : "Your recipe book has no recipe for the chosen items that fits this grid.", false);
    }

    private boolean copyNextMap(AbstractCraftingMenu menu) {
        int map = MenuClicks.firstInventorySlot(menu, this::uncopied);
        int blank = MenuClicks.firstInventorySlot(menu, stack -> stack.is(Items.MAP));
        if (map == -1 || blank == -1) {
            return false;
        }
        ItemStack original = menu.getSlot(map).getItem();
        copied.add(original.get(DataComponents.MAP_ID));
        List<Slot> grid = menu.getInputGridSlots();
        MenuClicks.moveSome(menu, map, grid.get(0).index, 1);
        MenuClicks.moveSome(menu, blank, grid.get(1).index, 1);
        copying = true;
        placed(original.getHoverName().getString());
        return true;
    }

    private boolean uncopied(ItemStack stack) {
        MapId id = stack.get(DataComponents.MAP_ID);
        return id != null && stack.is(ItemTags.CLONABLE_MAPS) && !copied.contains(id);
    }

    private String whyNoCopy(AbstractCraftingMenu menu) {
        if (MenuClicks.firstInventorySlot(menu, this::uncopied) != -1) {
            return "Out of empty maps.";
        }
        return copied.isEmpty() ? "You carry no map to copy." : "Every map you carry has its copy.";
    }

    private void placed(String what) {
        placed = what;
        startClock();
    }

    private List<Recipes.Craft> crafts() {
        List<RecipeCollection> book = mc.player.getRecipeBook().getCollections();
        if (crafts != null && book == bookRead) {
            return crafts;
        }
        bookRead = book;
        crafts = new ArrayList<>();
        if (only != null) {
            Recipes.known(item -> true).values().forEach(known -> known.stream().filter(only).forEach(crafts::add));
            return crafts;
        }
        Map<Item, List<Recipes.Craft>> byResult = Recipes.known(items::contains);
        for (Item item : items.resolved()) {
            crafts.addAll(byResult.getOrDefault(item, List.of()));
        }
        return crafts;
    }

    // Named stacks are fine here but worn or enchanted ones never go in.
    private int[] handSources(AbstractCraftingMenu menu, Recipes.Craft craft) {
        Map<Integer, Integer> taken = new HashMap<>();
        int[] sources = new int[craft.ingredients().size()];
        for (int i = 0; i < sources.length; i++) {
            Ingredient ingredient = craft.ingredients().get(i);
            int source = -1;
            for (Slot slot : menu.slots) {
                ItemStack stack = slot.getItem();
                if (MenuClicks.isInventorySlot(slot) && taken.getOrDefault(slot.index, 0) < stack.getCount()
                    && !stack.is(craft.result().getItem()) && !stack.isDamaged() && !stack.isEnchanted()
                    && ingredient.test(stack)) {
                    source = slot.index;
                    break;
                }
            }
            if (source == -1) {
                return null;
            }
            taken.merge(source, 1, Integer::sum);
            sources[i] = source;
        }
        return sources;
    }

    private static void placeByHand(AbstractCraftingMenu menu, int[] cells, int[] sources) {
        List<Slot> grid = menu.getInputGridSlots();
        for (int i = 0; i < cells.length; i++) {
            MenuClicks.moveSome(menu, sources[i], grid.get(cells[i]).index, 1);
        }
    }
}
