package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.UseHold;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * Eats when hunger or health drops low. Food anywhere in the inventory counts
 * and is swapped into the hotbar and back.
 */
public final class AutoEat extends Module {

    // Ticks to wait after a meal whilst the food and the healing land.
    private static final int SETTLE_TICKS = 10;

    // A golden apple takes a moment to land. Rechecking too early eats a second one.
    private static final int HEAL_SETTLE_TICKS = 30;

    public enum Priority { BEST_HUNGER, BEST_SATURATION, BEST_OVERALL }

    public enum TakeFrom { HANDS, HOTBAR, INVENTORY }

    private final NumberSetting hunger = new NumberSetting("Hunger",
        "Start eating at or below this many food points. Full is 20.", 14, 1, 19, 1);
    private final NumberSetting health = new NumberSetting("Health",
        "Eat a golden apple at or below this many hearts. Zero turns it off.",
        0, 0, 10, 0.5, " hearts").min(0).max(20);
    private final EnumSetting<Priority> priority = new EnumSetting<>("Priority",
        "Which food to reach for first.", Priority.BEST_HUNGER)
        .describe(Priority.BEST_HUNGER, "Eats whatever restores the most food points.")
        .describe(Priority.BEST_SATURATION, "Eats whatever gives the most saturation.")
        .describe(Priority.BEST_OVERALL, "Eats whatever scores best on both together.");
    private final RegistryListSetting<Item> avoid = new RegistryListSetting<>("Avoid",
        "Food that never gets eaten. Click to pick it.", BuiltInRegistries.ITEM,
        List.of(Items.ROTTEN_FLESH, Items.SPIDER_EYE, Items.POISONOUS_POTATO,
            Items.PUFFERFISH, Items.CHICKEN, Items.SUSPICIOUS_STEW, Items.CHORUS_FRUIT));
    private final EnumSetting<TakeFrom> takeFrom = new EnumSetting<>("Take from",
        "Where food may be taken from.", TakeFrom.INVENTORY)
        .describe(TakeFrom.HANDS, "Only eats what is already in your hands.")
        .describe(TakeFrom.HOTBAR, "Picks food from the hotbar only.")
        .describe(TakeFrom.INVENTORY, "Borrows food from anywhere in the inventory.");
    private final BoolSetting saveGapples = new BoolSetting("Save golden apples",
        "Never eats a golden apple for hunger alone. They are kept for the Health check.", false);
    private final BoolSetting offhand = new BoolSetting("Use offhand",
        "Reach for food in your offhand first.", true);
    private final BoolSetting whileMoving = new BoolSetting("Eat whilst moving",
        "Eat on the move. Eating drops you to walking speed.", true);
    private final BoolSetting pauseCombat = new BoolSetting("Pause combat",
        "Holds the combat modules back whilst you eat.", true);
    private final BoolSetting whileBusy = new BoolSetting("Eat in screens",
        "Keeps eating whilst a chest or inventory is open.", false);
    private final BoolSetting noSlowdown = new BoolSetting("No slowdown",
        "Keeps your normal speed whilst eating. Meals you start yourself count too.", false);
    private final BoolSetting pauseOnFire = new BoolSetting("Pause on fire",
        "Stops eating whilst you are burning.", false);

    private boolean eating;
    private boolean healing;
    private final UseHold use = new UseHold();
    private int settle;
    private final InventoryUtil.HotbarLoan loan = new InventoryUtil.HotbarLoan();

    public AutoEat() {
        super("AutoEat", "Eats for you when you get hungry or hurt.", Category.PLAYER);
        addSettings(hunger, health, priority, avoid, takeFrom, saveGapples, offhand, whileMoving,
            pauseCombat, whileBusy, noSlowdown, pauseOnFire);
        searchTags("food", "golden apple", "gapple");
    }

    public boolean isEating() {
        return isEnabled() && eating && pauseCombat.isOn();
    }

    public boolean isBusy() {
        return isEnabled() && eating;
    }

    // Read by LocalPlayerMixin to keep a meal from slowing you down.
    public boolean suppressesSlowdown() {
        return isEnabled() && noSlowdown.isOn() && eatingFood();
    }

    // True whilst any food is going down. A meal started by hand counts.
    private boolean eatingFood() {
        return mc.player != null && mc.player.isUsingItem()
            && mc.player.getUseItem().has(DataComponents.FOOD);
    }

    @Override
    public String getSuffix() {
        return eating ? "eating" : null;
    }

    @Override
    protected void onDisable() {
        stopEating();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.player.isSpectator() || mc.player.getAbilities().instabuild) {
            stopEating();
            return;
        }
        if (mc.player.isDeadOrDying()) {
            // Respawn rebuilds the inventory.
            loan.forget();
            stopEating();
            settle = 0;
            return;
        }
        if (settle > 0) {
            settle--;
            return;
        }
        if (burning()) {
            stopEating();
            return;
        }

        if (eating) {
            continueEating();
            return;
        }
        if (Modules.feeding(this)) {
            return;
        }
        // A carried stack would be dropped by a slot swap.
        if (!mc.player.containerMenu.getCarried().isEmpty()) {
            return;
        }
        if (busy() && !whileBusy.isOn()) {
            return;
        }
        if (potionBusy()) {
            return;
        }
        if (!whileMoving.isOn() && mc.player.getDeltaMovement().horizontalDistanceSqr() > 1.0E-4) {
            return;
        }

        int limit = slotLimit();
        boolean wantsHealing = EntityUtil.healthAtOrBelow(health.getValue()) && !healingWasted();
        int slot = wantsHealing ? findHealing(limit) : -1;
        healing = slot != -1;
        if (slot == -1 && wantsFood()) {
            slot = findFood(limit);
        }
        if (slot == -1) {
            return;
        }
        beginEating(slot);
    }

    /**
     * How many inventory slots may be searched. A swap needs the survival
     * inventory. A screen limits the search to the hotbar. Hands means the
     * held slot alone and the offhand is checked separately.
     */
    private int slotLimit() {
        if (takeFrom.is(TakeFrom.HANDS)) {
            return 0;
        }
        if (takeFrom.is(TakeFrom.HOTBAR) || busy()) {
            return InventoryUtil.HOTBAR_SIZE;
        }
        return InventoryUtil.WHOLE_INVENTORY;
    }

    // The held slot always counts. The rest only up to the limit.
    private boolean searchable(int slot, int limit) {
        return slot < limit || slot == InventoryUtil.selectedSlot();
    }

    // A screen or a foreign container is open.
    private boolean busy() {
        return mc.gui.screen() != null || mc.player.containerMenu.containerId != 0;
    }

    // The absorption a golden apple grants does not stack. A second one is wasted.
    private boolean healingWasted() {
        return mc.player.getAbsorptionAmount() > 0;
    }

    // Fire resistance takes the burn damage away. A meal stays safe under it.
    private boolean burning() {
        return pauseOnFire.isOn() && mc.player.isOnFire()
            && !mc.player.hasEffect(MobEffects.FIRE_RESISTANCE);
    }

    private boolean potionBusy() {
        AutoPotion potion = Modules.get(AutoPotion.class);
        return potion != null && potion.isDrinking();
    }

    private boolean wantsFood() {
        int food = mc.player.getFoodData().getFoodLevel();
        return food <= hunger.getInt();
    }

    // Honey bottles and golden apples go down on a full hunger bar. Ordinary food does not.
    private boolean edibleNow(ItemStack stack) {
        FoodProperties food = stack.get(DataComponents.FOOD);
        if (food == null) {
            return false;
        }
        return mc.player.getFoodData().getFoodLevel() < 20 || food.canAlwaysEat();
    }

    private void beginEating(int slot) {
        // The offhand needs no swap at all.
        if (slot == Inventory.SLOT_OFFHAND) {
            eating = true;
            use.begin();
            return;
        }
        if (!loan.select(slot)) {
            return;
        }
        eating = true;
        use.begin();
    }

    private void continueEating() {
        if ((busy() && !whileBusy.isOn()) || !holdingFood()) {
            stopEating();
            return;
        }
        if (!use.tick()) {
            finishMeal();
            return;
        }
        // A screen stops the game reading the use key. The meal is started by hand.
        if (busy() && !mc.player.isUsingItem()) {
            mc.gameMode.useItem(mc.player, offhandUsable()
                ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
        }
    }

    // A golden apple needs longer before the health is worth reading again.
    private void finishMeal() {
        boolean wasHealing = healing;
        stopEating();
        settle = wasHealing ? HEAL_SETTLE_TICKS : SETTLE_TICKS;
    }

    // The use key hits the main hand first. Food there would fire instead of the offhand.
    private boolean offhandUsable() {
        return offhand.isOn()
            && !mc.player.getInventory().getSelectedItem().has(DataComponents.FOOD);
    }

    private boolean holdingFood() {
        if (isEdible(mc.player.getInventory().getSelectedItem())) {
            return true;
        }
        return offhand.isOn() && isEdible(mc.player.getOffhandItem());
    }

    private void stopEating() {
        if (eating) {
            eating = false;
            healing = false;
            use.release();
        }
        // A loan whose return was refused earlier gets another go.
        loan.giveBack();
    }

    // The plain apple wins over the enchanted one.
    private int findHealing(int limit) {
        if (offhandUsable()) {
            ItemStack held = mc.player.getOffhandItem();
            if ((held.is(Items.GOLDEN_APPLE) || held.is(Items.ENCHANTED_GOLDEN_APPLE))
                && !avoid.contains(held.getItem())) {
                return Inventory.SLOT_OFFHAND;
            }
        }
        int enchanted = -1;
        for (int i = 0; i < InventoryUtil.WHOLE_INVENTORY; i++) {
            if (!searchable(i, limit)) {
                continue;
            }
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.is(Items.GOLDEN_APPLE) && !avoid.contains(stack.getItem())) {
                return i;
            }
            if (enchanted == -1 && stack.is(Items.ENCHANTED_GOLDEN_APPLE)
                && !avoid.contains(stack.getItem())) {
                enchanted = i;
            }
        }
        return enchanted;
    }

    // Plain food first. A golden apple only when nothing else is left and it is not being saved.
    private int findFood(int limit) {
        int best = -1;
        double bestScore = 0;
        if (offhandUsable()) {
            ItemStack held = mc.player.getOffhandItem();
            if (isEdible(held) && !isHealing(held) && edibleNow(held)) {
                return Inventory.SLOT_OFFHAND;
            }
        }
        for (int i = 0; i < InventoryUtil.WHOLE_INVENTORY; i++) {
            if (!searchable(i, limit)) {
                continue;
            }
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!isEdible(stack) || isHealing(stack) || !edibleNow(stack)) {
                continue;
            }
            double score = score(stack);
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        if (best == -1 && !saveGapples.isOn()) {
            return findHealing(limit);
        }
        return best;
    }

    private double score(ItemStack stack) {
        FoodProperties food = stack.get(DataComponents.FOOD);
        if (food == null) {
            return 0;
        }
        // Saturation is the whole value and not a multiplier of the nutrition.
        if (priority.is(Priority.BEST_SATURATION)) {
            return food.saturation();
        }
        if (priority.is(Priority.BEST_OVERALL)) {
            return food.nutrition() + food.saturation();
        }
        return food.nutrition();
    }

    private boolean isEdible(ItemStack stack) {
        return stack.has(DataComponents.FOOD) && !avoid.contains(stack.getItem());
    }

    // Golden apples are kept back for the Health check.
    private boolean isHealing(ItemStack stack) {
        return stack.is(Items.GOLDEN_APPLE) || stack.is(Items.ENCHANTED_GOLDEN_APPLE);
    }
}
