package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.BrewingStandMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Brews the chosen potion whilst a brewing stand is open. The ingredient
 * chain is worked out from the game's own brewing rules. Any potion a
 * server allows can be made.
 */
public final class AutoBrewer extends Module {

    public enum Form { DRINKABLE, SPLASH, LINGERING }

    // Stand slots. Everything after these is the player's inventory.
    private static final int BOTTLE_SLOTS = 3;
    private static final int INGREDIENT_SLOT = 3;
    private static final int FUEL_SLOT = 4;
    private static final int FIRST_PLAYER_SLOT = 5;

    // Ticks between two actions. The server gets time to answer the last one.
    private static final int SETTLE_TICKS = 5;

    // Water goes through at most three ingredients and then the two forms.
    private static final int MAX_STEPS = 6;

    private enum Stage { TAKE, FILL, BREW }

    private final RegistryListSetting<Potion> potion = new RegistryListSetting<>("Potion",
        "The potion to brew. Only the first one picked is used.",
        BuiltInRegistries.POTION, List.of(Potions.STRENGTH.value()));
    private final EnumSetting<Form> form = new EnumSetting<>("Form",
        "What the finished bottle turns into.", Form.DRINKABLE)
        .describe(Form.DRINKABLE, "A plain bottle to drink.")
        .describe(Form.SPLASH, "Gunpowder goes in last for a throwable bottle.")
        .describe(Form.LINGERING, "Dragon breath follows the gunpowder for a cloud bottle.");

    private Stage stage;
    private List<Item> plan;
    private int step;
    private int settle;
    private int brewed;

    public AutoBrewer() {
        super("AutoBrewer", "Brews the potion you pick whilst a brewing stand is open.", Category.WORLD);
        addSettings(potion, form);
        searchTags("potion", "brewing stand");
    }

    @Override
    public String getSuffix() {
        return brewed == 0 ? null : brewed + " brewed";
    }

    @Override
    protected void onEnable() {
        stage = null;
        plan = null;
        brewed = 0;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> screen)
            || !(screen.getMenu() instanceof BrewingStandMenu stand)) {
            stage = null;
            return;
        }
        if (stage == null) {
            stage = Stage.TAKE;
            step = 0;
            settle = 0;
            plan = buildPlan();
            if (plan == null) {
                ChatUtil.error("That potion cannot be brewed from water on this server.");
                setEnabled(false);
                return;
            }
        }
        if (settle > 0) {
            settle--;
            return;
        }
        if (stand.getBrewingTicks() != 0) {
            return;
        }
        switch (stage) {
            case TAKE -> takeBottles(stand);
            case FILL -> fillWater(stand);
            case BREW -> brew(stand);
        }
        settle = SETTLE_TICKS;
    }

    // Whatever sits in the bottle slots goes to the inventory first.
    private void takeBottles(BrewingStandMenu stand) {
        for (int i = 0; i < BOTTLE_SLOTS; i++) {
            if (stand.slots.get(i).getItem().isEmpty()) {
                continue;
            }
            if (!isWaterBottle(stand.slots.get(i).getItem())) {
                brewed++;
            }
            quickMove(stand, i);
            if (!stand.slots.get(i).getItem().isEmpty()) {
                ChatUtil.error("No room in the inventory for the finished potions.");
                setEnabled(false);
                return;
            }
        }
        stage = Stage.FILL;
    }

    private void fillWater(BrewingStandMenu stand) {
        for (int i = 0; i < BOTTLE_SLOTS; i++) {
            if (!stand.slots.get(i).getItem().isEmpty()) {
                continue;
            }
            int slot = findPlayerSlot(stand, AutoBrewer::isWaterBottle);
            if (slot == -1) {
                ChatUtil.error("Out of water bottles.");
                setEnabled(false);
                return;
            }
            moveOne(stand, slot, i);
        }
        step = 0;
        stage = Stage.BREW;
    }

    private void brew(BrewingStandMenu stand) {
        // The leftover of the last ingredient goes home before the next one.
        if (!stand.slots.get(INGREDIENT_SLOT).getItem().isEmpty()) {
            quickMove(stand, INGREDIENT_SLOT);
            if (!stand.slots.get(INGREDIENT_SLOT).getItem().isEmpty()) {
                ChatUtil.error("No room in the inventory for the leftover ingredient.");
                setEnabled(false);
            }
            return;
        }
        if (step >= plan.size()) {
            stage = Stage.TAKE;
            return;
        }
        if (stand.getFuel() == 0) {
            int fuel = findPlayerSlot(stand, stack -> stack.is(Items.BLAZE_POWDER));
            if (fuel == -1) {
                ChatUtil.error("Out of blaze powder.");
                setEnabled(false);
                return;
            }
            moveOne(stand, fuel, FUEL_SLOT);
            return;
        }
        Item ingredient = plan.get(step);
        int slot = findPlayerSlot(stand, stack -> stack.is(ingredient));
        if (slot == -1) {
            ChatUtil.error("Out of " + ingredient.getName(ingredient.getDefaultInstance()).getString() + ".");
            setEnabled(false);
            return;
        }
        moveOne(stand, slot, INGREDIENT_SLOT);
        step++;
    }

    /**
     * Breadth first from a water bottle through every ingredient the brewing
     * rules accept until the wanted potion appears. Null when no chain leads
     * there.
     */
    private List<Item> buildPlan() {
        Potion wanted = potion.resolved().stream().findFirst().orElse(null);
        if (wanted == null || mc.level == null) {
            return null;
        }
        PotionBrewing brewing = mc.level.potionBrewing();
        List<Item> ingredients = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            if (brewing.isPotionIngredient(item.getDefaultInstance())) {
                ingredients.add(item);
            }
        }

        record Path(ItemStack potion, List<Item> steps) {
        }
        Deque<Path> queue = new ArrayDeque<>();
        Set<Holder<Potion>> seen = new HashSet<>();
        queue.add(new Path(PotionContents.createItemStack(Items.POTION, Potions.WATER), List.of()));
        seen.add(Potions.WATER);
        while (!queue.isEmpty()) {
            Path path = queue.poll();
            if (contentsOf(path.potion()).is(BuiltInRegistries.POTION.wrapAsHolder(wanted))) {
                return withForm(path.steps());
            }
            if (path.steps().size() >= MAX_STEPS) {
                continue;
            }
            for (Item ingredient : ingredients) {
                ItemStack stack = ingredient.getDefaultInstance();
                if (!brewing.hasPotionMix(path.potion(), stack)) {
                    continue;
                }
                ItemStack next = brewing.mix(stack, path.potion());
                Holder<Potion> result = contentsOf(next).potion().orElse(null);
                if (result == null || !seen.add(result)) {
                    continue;
                }
                List<Item> steps = new ArrayList<>(path.steps());
                steps.add(ingredient);
                queue.add(new Path(next, steps));
            }
        }
        return null;
    }

    private List<Item> withForm(List<Item> steps) {
        List<Item> result = new ArrayList<>(steps);
        if (form.isAny(Form.SPLASH, Form.LINGERING)) {
            result.add(Items.GUNPOWDER);
        }
        if (form.is(Form.LINGERING)) {
            result.add(Items.DRAGON_BREATH);
        }
        return result;
    }

    private static PotionContents contentsOf(ItemStack stack) {
        return stack.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY);
    }

    private static boolean isWaterBottle(ItemStack stack) {
        return stack.is(Items.POTION) && contentsOf(stack).is(Potions.WATER);
    }

    private static int findPlayerSlot(BrewingStandMenu stand, Predicate<ItemStack> test) {
        for (int i = FIRST_PLAYER_SLOT; i < stand.slots.size(); i++) {
            if (test.test(stand.slots.get(i).getItem())) {
                return i;
            }
        }
        return -1;
    }

    private void quickMove(BrewingStandMenu stand, int slot) {
        mc.gameMode.handleContainerInput(stand.containerId, slot, 0, ContainerInput.QUICK_MOVE, mc.player);
    }

    // Picks the stack up and drops a single item into the target with a right click.
    private void moveOne(BrewingStandMenu stand, int from, int to) {
        if (!stand.getCarried().isEmpty()) {
            return;
        }
        mc.gameMode.handleContainerInput(stand.containerId, from, 0, ContainerInput.PICKUP, mc.player);
        mc.gameMode.handleContainerInput(stand.containerId, to, 1, ContainerInput.PICKUP, mc.player);
        if (!stand.getCarried().isEmpty()) {
            mc.gameMode.handleContainerInput(stand.containerId, from, 0, ContainerInput.PICKUP, mc.player);
        }
    }
}
