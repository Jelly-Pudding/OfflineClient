package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.module.StationModule;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.MenuClicks;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.SelectableRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;

import java.util.List;

// Cuts whole stacks at an open stonecutter into the chosen results. The recipe list is known
// on this side but only the server fills the result slot. A shift click on it cuts the
// whole input stack.
public final class AutoMason extends StationModule<StonecutterMenu> {

    // Ticks between two rounds of clicks.
    private static final int PACE = 4;

    private final RegistryListSetting<Item> results = new RegistryListSetting<>("Cut into",
        "The shapes to make such as stairs or slabs. Any stack that cuts into one of them is used"
            + " unless it is on this list too.", BuiltInRegistries.ITEM, List.of());

    private final Takings takings = new Takings();
    private int cut;

    public AutoMason() {
        super("AutoMason", "Cuts stone into the shapes you pick whilst a stonecutter is open.",
            StonecutterMenu.class, PACE);
        addSettings(results);
        searchTags("stonecutter", "stairs", "slabs");
    }

    @Override
    public String getSuffix() {
        return count(cut, "cut");
    }

    @Override
    protected void onEnable() {
        super.onEnable();
        cut = 0;
    }

    @Override
    protected void closed() {
        takings.forget();
    }

    @Override
    protected void work(StonecutterMenu menu) {
        cut += takings.settle();
        ItemStack input = menu.getSlot(StonecutterMenu.INPUT_SLOT).getItem();
        ItemStack result = menu.getSlot(StonecutterMenu.RESULT_SLOT).getItem();
        if (!input.isEmpty() && results.contains(result.getItem())) {
            takings.start(result.getItem());
            if (moveOut(menu, StonecutterMenu.RESULT_SLOT, "for what was cut.")) {
                startClock();
                awaitServer(menu);
            }
            return;
        }
        if (input.isEmpty()) {
            putNext(menu);
            return;
        }
        int wanted = wantedRecipe(menu.getVisibleRecipes(), context());
        if (wanted == -1) {
            moveOut(menu, StonecutterMenu.INPUT_SLOT, "to empty the stonecutter.");
            return;
        }
        if (menu.getSelectedRecipeIndex() != wanted) {
            MenuClicks.pressButton(menu, wanted);
            startClock();
            awaitServer(menu);
            return;
        }
        if (stalled()) {
            disable("The server will not cut " + input.getHoverName().getString() + ".");
        }
    }

    private void putNext(StonecutterMenu menu) {
        ContextMap context = context();
        var recipes = mc.level.recipeAccess().stonecutterRecipes();
        int slot = MenuClicks.firstInventorySlot(menu, stack -> !stack.isEmpty()
            && !results.contains(stack.getItem()) && wantedRecipe(recipes.selectByInput(stack), context) != -1);
        if (slot == -1) {
            disable("Nothing left to cut.");
            return;
        }
        // The whole stack goes in. Choosing the recipe waits for the next round.
        MenuClicks.quickMove(menu, slot);
    }

    // The button of the first recipe that makes a chosen result. Minus one when none does.
    private int wantedRecipe(SelectableRecipe.SingleInputSet<StonecutterRecipe> recipes, ContextMap context) {
        List<SelectableRecipe.SingleInputEntry<StonecutterRecipe>> entries = recipes.entries();
        for (int i = 0; i < entries.size(); i++) {
            ItemStack shown = entries.get(i).recipe().optionDisplay().resolveForFirstStack(context);
            if (!shown.isEmpty() && results.contains(shown.getItem())) {
                return i;
            }
        }
        return -1;
    }

    private ContextMap context() {
        return SlotDisplayContext.fromLevel(mc.level);
    }
}
