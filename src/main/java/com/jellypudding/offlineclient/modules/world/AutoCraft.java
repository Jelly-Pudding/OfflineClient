package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.module.StationModule;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.MenuClicks;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Crafts the chosen items over and over through the recipe book the way a shift click on a
// recipe does. A recipe that takes its own result such as dyeing a shulker box goes in by hand
// because the recipe book might pick a box that already has the colour.
public final class AutoCraft extends StationModule<AbstractCraftingMenu> {

    // Ticks between two rounds of clicks.
    private static final int PACE = 2;

    // Ticks between two recipe book requests. Paper kicks a player who keeps sending them faster
    // than one a tick. The gap leaves room for a server set stricter than that.
    private static final int PLACE_GAP = 6;

    // A known recipe for a chosen item.
    private record Craft(RecipeDisplayEntry entry, ItemStack result, List<Ingredient> ingredients) {

        // The recipe book may place the result itself as an ingredient and craft nothing.
        boolean byHand() {
            return ingredients.stream().anyMatch(ingredient -> ingredient.acceptsItem(result.typeHolder()));
        }

        String name() {
            return result.getHoverName().getString();
        }
    }

    private final RegistryListSetting<Item> items = new RegistryListSetting<>("Items",
        "The items to craft. Each one is crafted until its ingredients run out.",
        BuiltInRegistries.ITEM, List.of()).onChange(this::forgetRecipes);

    private List<Craft> crafts;
    private Craft placed;
    private boolean refusedOnce;
    private int lastRequest = -PLACE_GAP;
    private int crafted;
    private final Takings takings = new Takings();

    public AutoCraft() {
        super("AutoCraft", "Crafts the items you pick whilst a crafting table or your inventory is open.",
            AbstractCraftingMenu.class, PACE);
        addSettings(items);
        searchTags("crafting table", "recipe book", "dye shulker");
    }

    @Override
    public String getSuffix() {
        return count(crafted, "crafted");
    }

    @Override
    protected void onEnable() {
        super.onEnable();
        crafted = 0;
    }

    @Override
    protected void closed() {
        crafts = null;
        placed = null;
        refusedOnce = false;
        takings.forget();
    }

    private void forgetRecipes() {
        crafts = null;
    }

    @Override
    protected void work(AbstractCraftingMenu menu) {
        crafted += takings.settle();
        Slot result = menu.getResultSlot();
        boolean filled = gridFilled(menu);
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
                return;
            }
            disable("The server crafted nothing from the recipe for " + placed.name() + ".");
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
    private void take(AbstractCraftingMenu menu) {
        Slot result = menu.getResultSlot();
        placed = null;
        refusedOnce = false;
        takings.start(result.getItem().getItem());
        if (moveOut(menu, result.index, "for what was crafted.")) {
            awaitServer(menu);
        }
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
        if (items.resolved().isEmpty()) {
            disable("Pick the items to craft first.");
            return;
        }
        StackedItemContents contents = new StackedItemContents();
        mc.player.getInventory().fillStackedContents(contents);
        boolean fits = false;
        for (Craft craft : crafts()) {
            int[] cells = gridCells(craft, menu);
            if (cells == null) {
                continue;
            }
            fits = true;
            if (craft.byHand()) {
                int[] sources = handSources(menu, craft);
                if (sources != null) {
                    placeByHand(menu, cells, sources);
                    placed(craft);
                    return;
                }
            } else if (craft.entry().canCraft(contents)) {
                MenuClicks.placeRecipe(menu, craft.entry().id());
                lastRequest = ticks();
                placed(craft);
                return;
            }
        }
        disable(fits ? "Out of ingredients for the chosen items."
            : "Your recipe book has no recipe for the chosen items that fits this grid.");
    }

    private void placed(Craft craft) {
        placed = craft;
        startClock();
    }

    // Recipe book entries for the chosen items in the order they were picked.
    private List<Craft> crafts() {
        if (crafts != null) {
            return crafts;
        }
        ContextMap context = SlotDisplayContext.fromLevel(mc.level);
        Map<Item, List<Craft>> byResult = new HashMap<>();
        for (RecipeCollection collection : mc.player.getRecipeBook().getCollections()) {
            for (RecipeDisplayEntry entry : collection.getRecipes()) {
                RecipeDisplay display = entry.display();
                boolean crafting = display instanceof ShapedCraftingRecipeDisplay
                    || display instanceof ShapelessCraftingRecipeDisplay;
                if (!crafting || entry.craftingRequirements().isEmpty()) {
                    continue;
                }
                ItemStack result = display.result().resolveForFirstStack(context);
                if (!result.isEmpty() && items.contains(result.getItem())) {
                    byResult.computeIfAbsent(result.getItem(), item -> new ArrayList<>())
                        .add(new Craft(entry, result, entry.craftingRequirements().get()));
                }
            }
        }
        crafts = new ArrayList<>();
        for (Item item : items.resolved()) {
            crafts.addAll(byResult.getOrDefault(item, List.of()));
        }
        return crafts;
    }

    // The grid slot of each ingredient in the order the recipe lists them.
    // Null when the recipe needs a bigger grid than this one.
    private static int[] gridCells(Craft craft, AbstractCraftingMenu menu) {
        int gridWidth = menu.getGridWidth();
        int count = craft.ingredients().size();
        int[] cells = new int[count];
        if (craft.entry().display() instanceof ShapedCraftingRecipeDisplay shaped) {
            if (shaped.width() > gridWidth || shaped.height() > menu.getGridHeight()) {
                return null;
            }
            int next = 0;
            for (int i = 0; i < shaped.ingredients().size() && next < count; i++) {
                if (!(shaped.ingredients().get(i) instanceof SlotDisplay.Empty)) {
                    cells[next++] = i / shaped.width() * gridWidth + i % shaped.width();
                }
            }
            return next == count ? cells : null;
        }
        if (count > gridWidth * menu.getGridHeight()) {
            return null;
        }
        for (int i = 0; i < count; i++) {
            cells[i] = i;
        }
        return cells;
    }

    // The inventory slot each ingredient comes from or null when one is missing.
    // Named stacks are fine here but worn or enchanted ones never go in.
    private int[] handSources(AbstractCraftingMenu menu, Craft craft) {
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

    private static boolean gridFilled(AbstractCraftingMenu menu) {
        return menu.getInputGridSlots().stream().anyMatch(Slot::hasItem);
    }
}
