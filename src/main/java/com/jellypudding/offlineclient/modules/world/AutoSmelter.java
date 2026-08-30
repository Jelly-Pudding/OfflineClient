package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.BlastFurnaceMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.SmokerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipePropertySet;

import java.util.List;
import java.util.function.Predicate;

// Keeps a furnace fed whilst its screen is open and takes what comes out.
public final class AutoSmelter extends Module {

    private static final int INPUT_SLOT = 0;
    private static final int FUEL_SLOT = 1;
    private static final int OUTPUT_SLOT = 2;
    private static final int FIRST_PLAYER_SLOT = 3;

    // Ticks between two rounds of clicks.
    private static final int INTERVAL = 10;

    private final RegistryListSetting<Item> fuels = new RegistryListSetting<>("Fuel",
        "Items that may be burnt.", BuiltInRegistries.ITEM,
        List.of(Items.COAL, Items.CHARCOAL, Items.COAL_BLOCK));
    private final NumberSetting fuelPerRefill = new NumberSetting("Fuel per refill",
        "How many fuel items go in each time the fire dies.", 8, 1, 64, 1).max(64);
    private final RegistryListSetting<Item> inputs = new RegistryListSetting<>("Smelt",
        "Items to smelt.", BuiltInRegistries.ITEM,
        List.of(Items.RAW_IRON, Items.RAW_COPPER, Items.RAW_GOLD, Items.IRON_ORE,
            Items.GOLD_ORE, Items.COPPER_ORE, Items.SAND, Items.COBBLESTONE));
    private final BoolSetting stopWhenEmpty = new BoolSetting("Stop when empty",
        "Turns off once you have nothing left to smelt or burn.", true);
    private final BoolSetting closeScreen = new BoolSetting("Close screen",
        "Closes the furnace after every round to let you carry on.", false);

    private int taken;

    public AutoSmelter() {
        super("AutoSmelter", "Feeds an open furnace from your inventory and empties it.", Category.WORLD);
        addSettings(fuels, fuelPerRefill, inputs, stopWhenEmpty, closeScreen);
        searchTags("furnace", "smelt", "blast furnace", "smoker");
    }

    @Override
    public String getSuffix() {
        return taken == 0 ? null : taken + " taken";
    }

    @Override
    protected void onEnable() {
        taken = 0;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> screen)
            || !(screen.getMenu() instanceof AbstractFurnaceMenu furnace)) {
            return;
        }
        if (mc.player.tickCount % INTERVAL != 0 || !furnace.getCarried().isEmpty()) {
            return;
        }
        takeOutput(furnace);
        if (!isEnabled()) {
            return;
        }
        refuel(furnace);
        if (!isEnabled()) {
            return;
        }
        feed(furnace);
        if (isEnabled() && closeScreen.isOn()) {
            mc.player.closeContainer();
        }
    }

    private void takeOutput(AbstractFurnaceMenu furnace) {
        ItemStack output = furnace.slots.get(OUTPUT_SLOT).getItem();
        if (output.isEmpty()) {
            return;
        }
        int count = output.getCount();
        quickMove(furnace, OUTPUT_SLOT);
        if (!furnace.slots.get(OUTPUT_SLOT).getItem().isEmpty()) {
            ChatUtil.error("No room in the inventory for what came out.");
            setEnabled(false);
            return;
        }
        taken += count;
    }

    private void refuel(AbstractFurnaceMenu furnace) {
        if (furnace.getLitProgress() > 0 || !furnace.slots.get(FUEL_SLOT).getItem().isEmpty()) {
            return;
        }
        int slot = findPlayerSlot(furnace, stack -> fuels.contains(stack.getItem())
            && mc.level.fuelValues().isFuel(stack));
        if (slot == -1) {
            giveUp("Out of fuel.");
            return;
        }
        ItemStack source = furnace.slots.get(slot).getItem();
        int count = Math.min(fuelPerRefill.getInt(), source.getCount());
        moveSome(furnace, slot, FUEL_SLOT, count);
    }

    private void feed(AbstractFurnaceMenu furnace) {
        if (!furnace.slots.get(INPUT_SLOT).getItem().isEmpty()) {
            return;
        }
        ResourceKey<RecipePropertySet> recipes = recipesFor(furnace);
        int slot = findPlayerSlot(furnace, stack -> inputs.contains(stack.getItem())
            && mc.level.recipeAccess().propertySet(recipes).test(stack));
        if (slot == -1) {
            // The last item may still be cooking.
            if (furnace.getBurnProgress() <= 0) {
                giveUp("Nothing left to smelt.");
            }
            return;
        }
        quickMove(furnace, slot);
    }

    // Each furnace type takes its own set of inputs.
    private static ResourceKey<RecipePropertySet> recipesFor(AbstractFurnaceMenu furnace) {
        if (furnace instanceof BlastFurnaceMenu) {
            return RecipePropertySet.BLAST_FURNACE_INPUT;
        }
        if (furnace instanceof SmokerMenu) {
            return RecipePropertySet.SMOKER_INPUT;
        }
        return RecipePropertySet.FURNACE_INPUT;
    }

    private void giveUp(String reason) {
        if (stopWhenEmpty.isOn()) {
            ChatUtil.error(reason);
            setEnabled(false);
        }
    }

    private static int findPlayerSlot(AbstractFurnaceMenu furnace, Predicate<ItemStack> test) {
        for (int i = FIRST_PLAYER_SLOT; i < furnace.slots.size(); i++) {
            if (test.test(furnace.slots.get(i).getItem())) {
                return i;
            }
        }
        return -1;
    }

    private void quickMove(AbstractFurnaceMenu furnace, int slot) {
        mc.gameMode.handleContainerInput(furnace.containerId, slot, 0, ContainerInput.QUICK_MOVE, mc.player);
    }

    // Picks the stack up and drops items one at a time with right clicks.
    private void moveSome(AbstractFurnaceMenu furnace, int from, int to, int count) {
        mc.gameMode.handleContainerInput(furnace.containerId, from, 0, ContainerInput.PICKUP, mc.player);
        for (int i = 0; i < count && !furnace.getCarried().isEmpty(); i++) {
            mc.gameMode.handleContainerInput(furnace.containerId, to, 1, ContainerInput.PICKUP, mc.player);
        }
        if (!furnace.getCarried().isEmpty()) {
            mc.gameMode.handleContainerInput(furnace.containerId, from, 0, ContainerInput.PICKUP, mc.player);
        }
    }
}
