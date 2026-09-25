package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.MenuClicks;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.BrewingStandMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// Brews the chosen potion whilst a brewing stand is open using the game's own
// brewing rules. Any potion a server allows can be made.
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

    // The brewing rules live on the server and never reach the client.
    // These are the steps the game ships with. A server that changes them
    // refuses an ingredient and the module reports it.
    private record Step(Holder<Potion> from, Item reagent, Holder<Potion> to) {
    }

    private static final List<Step> STEPS = List.of(
        new Step(Potions.AWKWARD, Items.BLAZE_POWDER, Potions.STRENGTH),
        new Step(Potions.AWKWARD, Items.BREEZE_ROD, Potions.WIND_CHARGED),
        new Step(Potions.AWKWARD, Items.COBWEB, Potions.WEAVING),
        new Step(Potions.AWKWARD, Items.GHAST_TEAR, Potions.REGENERATION),
        new Step(Potions.AWKWARD, Items.GLISTERING_MELON_SLICE, Potions.HEALING),
        new Step(Potions.AWKWARD, Items.GOLDEN_CARROT, Potions.NIGHT_VISION),
        new Step(Potions.AWKWARD, Items.MAGMA_CREAM, Potions.FIRE_RESISTANCE),
        new Step(Potions.AWKWARD, Items.PHANTOM_MEMBRANE, Potions.SLOW_FALLING),
        new Step(Potions.AWKWARD, Items.PUFFERFISH, Potions.WATER_BREATHING),
        new Step(Potions.AWKWARD, Items.RABBIT_FOOT, Potions.LEAPING),
        new Step(Potions.AWKWARD, Items.SLIME_BLOCK, Potions.OOZING),
        new Step(Potions.AWKWARD, Items.SPIDER_EYE, Potions.POISON),
        new Step(Potions.AWKWARD, Items.STONE, Potions.INFESTED),
        new Step(Potions.AWKWARD, Items.SUGAR, Potions.SWIFTNESS),
        new Step(Potions.AWKWARD, Items.TURTLE_HELMET, Potions.TURTLE_MASTER),
        new Step(Potions.FIRE_RESISTANCE, Items.REDSTONE, Potions.LONG_FIRE_RESISTANCE),
        new Step(Potions.HARMING, Items.GLOWSTONE_DUST, Potions.STRONG_HARMING),
        new Step(Potions.HEALING, Items.FERMENTED_SPIDER_EYE, Potions.HARMING),
        new Step(Potions.HEALING, Items.GLOWSTONE_DUST, Potions.STRONG_HEALING),
        new Step(Potions.INVISIBILITY, Items.REDSTONE, Potions.LONG_INVISIBILITY),
        new Step(Potions.LEAPING, Items.FERMENTED_SPIDER_EYE, Potions.SLOWNESS),
        new Step(Potions.LEAPING, Items.GLOWSTONE_DUST, Potions.STRONG_LEAPING),
        new Step(Potions.LEAPING, Items.REDSTONE, Potions.LONG_LEAPING),
        new Step(Potions.LONG_LEAPING, Items.FERMENTED_SPIDER_EYE, Potions.LONG_SLOWNESS),
        new Step(Potions.LONG_NIGHT_VISION, Items.FERMENTED_SPIDER_EYE, Potions.LONG_INVISIBILITY),
        new Step(Potions.LONG_POISON, Items.FERMENTED_SPIDER_EYE, Potions.HARMING),
        new Step(Potions.LONG_SWIFTNESS, Items.FERMENTED_SPIDER_EYE, Potions.LONG_SLOWNESS),
        new Step(Potions.NIGHT_VISION, Items.FERMENTED_SPIDER_EYE, Potions.INVISIBILITY),
        new Step(Potions.NIGHT_VISION, Items.REDSTONE, Potions.LONG_NIGHT_VISION),
        new Step(Potions.POISON, Items.FERMENTED_SPIDER_EYE, Potions.HARMING),
        new Step(Potions.POISON, Items.GLOWSTONE_DUST, Potions.STRONG_POISON),
        new Step(Potions.POISON, Items.REDSTONE, Potions.LONG_POISON),
        new Step(Potions.REGENERATION, Items.GLOWSTONE_DUST, Potions.STRONG_REGENERATION),
        new Step(Potions.REGENERATION, Items.REDSTONE, Potions.LONG_REGENERATION),
        new Step(Potions.SLOW_FALLING, Items.REDSTONE, Potions.LONG_SLOW_FALLING),
        new Step(Potions.SLOWNESS, Items.GLOWSTONE_DUST, Potions.STRONG_SLOWNESS),
        new Step(Potions.SLOWNESS, Items.REDSTONE, Potions.LONG_SLOWNESS),
        new Step(Potions.STRENGTH, Items.GLOWSTONE_DUST, Potions.STRONG_STRENGTH),
        new Step(Potions.STRENGTH, Items.REDSTONE, Potions.LONG_STRENGTH),
        new Step(Potions.STRONG_HEALING, Items.FERMENTED_SPIDER_EYE, Potions.STRONG_HARMING),
        new Step(Potions.STRONG_POISON, Items.FERMENTED_SPIDER_EYE, Potions.STRONG_HARMING),
        new Step(Potions.SWIFTNESS, Items.FERMENTED_SPIDER_EYE, Potions.SLOWNESS),
        new Step(Potions.SWIFTNESS, Items.GLOWSTONE_DUST, Potions.STRONG_SWIFTNESS),
        new Step(Potions.SWIFTNESS, Items.REDSTONE, Potions.LONG_SWIFTNESS),
        new Step(Potions.TURTLE_MASTER, Items.GLOWSTONE_DUST, Potions.STRONG_TURTLE_MASTER),
        new Step(Potions.TURTLE_MASTER, Items.REDSTONE, Potions.LONG_TURTLE_MASTER),
        new Step(Potions.WATER, Items.BLAZE_POWDER, Potions.MUNDANE),
        new Step(Potions.WATER, Items.BREEZE_ROD, Potions.MUNDANE),
        new Step(Potions.WATER, Items.COBWEB, Potions.MUNDANE),
        new Step(Potions.WATER, Items.FERMENTED_SPIDER_EYE, Potions.WEAKNESS),
        new Step(Potions.WATER, Items.GHAST_TEAR, Potions.MUNDANE),
        new Step(Potions.WATER, Items.GLISTERING_MELON_SLICE, Potions.MUNDANE),
        new Step(Potions.WATER, Items.GLOWSTONE_DUST, Potions.THICK),
        new Step(Potions.WATER, Items.MAGMA_CREAM, Potions.MUNDANE),
        new Step(Potions.WATER, Items.NETHER_WART, Potions.AWKWARD),
        new Step(Potions.WATER, Items.RABBIT_FOOT, Potions.MUNDANE),
        new Step(Potions.WATER, Items.REDSTONE, Potions.MUNDANE),
        new Step(Potions.WATER, Items.SLIME_BLOCK, Potions.MUNDANE),
        new Step(Potions.WATER, Items.SPIDER_EYE, Potions.MUNDANE),
        new Step(Potions.WATER, Items.STONE, Potions.MUNDANE),
        new Step(Potions.WATER, Items.SUGAR, Potions.MUNDANE),
        new Step(Potions.WATER_BREATHING, Items.REDSTONE, Potions.LONG_WATER_BREATHING),
        new Step(Potions.WEAKNESS, Items.REDSTONE, Potions.LONG_WEAKNESS));

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
    private int planIndex;
    private int settle;
    private int brewed;

    public AutoBrewer() {
        super("AutoBrewer", "Brews the potion you pick whilst a brewing stand is open.", Category.WORLD);
        addSettings(potion, form);
        searchTags("potion", "brewing stand");
    }

    @Override
    public String getSuffix() {
        return count(brewed, "brewed");
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
            planIndex = 0;
            settle = 0;
            plan = buildPlan();
            if (plan == null) {
                disable("That potion cannot be brewed from water on this server.");
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
            if (!MenuClicks.quickMoved(stand, i)) {
                disable("No room in the inventory for the finished potions.");
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
            int slot = MenuClicks.firstSlot(stand, FIRST_PLAYER_SLOT, AutoBrewer::isWaterBottle);
            if (slot == -1) {
                disable("Out of water bottles.");
                return;
            }
            MenuClicks.moveSome(stand, slot, i, 1);
        }
        planIndex = 0;
        stage = Stage.BREW;
    }

    private void brew(BrewingStandMenu stand) {
        // The leftover of the last ingredient goes home before the next one.
        if (!stand.slots.get(INGREDIENT_SLOT).getItem().isEmpty()) {
            if (!MenuClicks.quickMoved(stand, INGREDIENT_SLOT)) {
                disable("No room in the inventory for the leftover ingredient.");
            }
            return;
        }
        if (planIndex >= plan.size()) {
            stage = Stage.TAKE;
            return;
        }
        if (stand.getFuel() == 0) {
            int fuel = MenuClicks.firstSlot(stand, FIRST_PLAYER_SLOT, stack -> stack.is(Items.BLAZE_POWDER));
            if (fuel == -1) {
                disable("Out of blaze powder.");
                return;
            }
            MenuClicks.moveSome(stand, fuel, FUEL_SLOT, 1);
            return;
        }
        Item ingredient = plan.get(planIndex);
        int slot = MenuClicks.firstSlot(stand, FIRST_PLAYER_SLOT, stack -> stack.is(ingredient));
        if (slot == -1) {
            disable("Out of " + ingredient.getName(ingredient.getDefaultInstance()).getString() + ".");
            return;
        }
        MenuClicks.moveSome(stand, slot, INGREDIENT_SLOT, 1);
        planIndex++;
    }

    // Breadth first from a water bottle. Null when no chain leads to the potion.
    private List<Item> buildPlan() {
        Potion wanted = potion.resolved().stream().findFirst().orElse(null);
        if (wanted == null || mc.level == null) {
            return null;
        }
        Holder<Potion> target = BuiltInRegistries.POTION.wrapAsHolder(wanted);

        record Path(Holder<Potion> potion, List<Item> steps) {
        }
        Deque<Path> queue = new ArrayDeque<>();
        Set<Holder<Potion>> seen = new HashSet<>();
        queue.add(new Path(Potions.WATER, List.of()));
        seen.add(Potions.WATER);
        while (!queue.isEmpty()) {
            Path path = queue.poll();
            if (path.potion().value() == target.value()) {
                return withForm(path.steps());
            }
            if (path.steps().size() >= MAX_STEPS) {
                continue;
            }
            for (Step step : STEPS) {
                if (step.from().value() != path.potion().value() || !seen.add(step.to())) {
                    continue;
                }
                List<Item> steps = new ArrayList<>(path.steps());
                steps.add(step.reagent());
                queue.add(new Path(step.to(), steps));
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
}
