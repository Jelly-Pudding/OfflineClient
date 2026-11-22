package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
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
import java.util.function.Predicate;

public final class Recipes {

    private static final Minecraft MC = OfflineClient.MC;

    public record Craft(RecipeDisplayEntry entry, ItemStack result, List<Ingredient> ingredients) {

        // The recipe book may place the result itself as an ingredient and craft nothing.
        public boolean byHand() {
            return ingredients.stream().anyMatch(ingredient -> ingredient.acceptsItem(result.typeHolder()));
        }

        public String name() {
            return result.getHoverName().getString();
        }
    }

    private Recipes() {
    }

    public static Map<Item, List<Craft>> known(Predicate<Item> wanted) {
        ContextMap context = SlotDisplayContext.fromLevel(MC.level);
        Map<Item, List<Craft>> byResult = new HashMap<>();
        for (RecipeCollection collection : MC.player.getRecipeBook().getCollections()) {
            for (RecipeDisplayEntry entry : collection.getRecipes()) {
                RecipeDisplay display = entry.display();
                boolean crafting = display instanceof ShapedCraftingRecipeDisplay
                    || display instanceof ShapelessCraftingRecipeDisplay;
                if (!crafting || entry.craftingRequirements().isEmpty()) {
                    continue;
                }
                ItemStack result = display.result().resolveForFirstStack(context);
                if (!result.isEmpty() && wanted.test(result.getItem())) {
                    byResult.computeIfAbsent(result.getItem(), item -> new ArrayList<>())
                        .add(new Craft(entry, result, entry.craftingRequirements().get()));
                }
            }
        }
        return byResult;
    }

    public static StackedItemContents carried() {
        StackedItemContents contents = new StackedItemContents();
        MC.player.getInventory().fillStackedContents(contents);
        return contents;
    }

    // Null when the recipe needs a bigger grid than this one.
    public static int[] gridCells(Craft craft, AbstractCraftingMenu menu) {
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

    public static Craft craftable(List<Craft> crafts, AbstractCraftingMenu menu, StackedItemContents contents) {
        for (Craft craft : crafts) {
            if (gridCells(craft, menu) != null && craft.entry().canCraft(contents)) {
                return craft;
            }
        }
        return null;
    }

    public static void placeOne(AbstractCraftingMenu menu, Craft craft) {
        MC.gameMode.handlePlaceRecipe(menu.containerId, craft.entry().id(), false);
    }

    public static boolean gridFilled(AbstractCraftingMenu menu) {
        return menu.getInputGridSlots().stream().anyMatch(Slot::hasItem);
    }
}
