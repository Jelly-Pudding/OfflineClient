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
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.UseHold;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;

import java.util.List;

// Eats when hunger or health drops low. Food anywhere in the inventory
// counts and is swapped into the hotbar and back.
public final class AutoEat extends Module {

    // Ticks to wait after a meal whilst the food and the healing land.
    private static final int SETTLE_TICKS = 10;

    // A golden apple takes a moment to land. Rechecking too early eats a second one.
    private static final int HEAL_SETTLE_TICKS = 30;

    // The first slot of the backpack. Empty bowls are gathered there.
    private static final int BOWL_SLOT = 9;

    public enum Priority { BEST_HUNGER, BEST_SATURATION, BEST_OVERALL }

    public enum TakeFrom { HANDS, HOTBAR, INVENTORY }

    public enum Trigger { HUNGER, HEALTH, EITHER, BOTH }

    private final EnumSetting<Trigger> trigger = new EnumSetting<>("Trigger",
        "What starts a meal.", Trigger.EITHER)
        .describe(Trigger.HUNGER, "Eats when your food drops to the Hunger value.")
        .describe(Trigger.HEALTH, "Eats when your health drops to the Health value.")
        .describe(Trigger.EITHER, "Eats when your food or your health drops to its value.")
        .describe(Trigger.BOTH, "Eats only when your food and your health have both dropped to their values.");
    private final NumberSetting hunger = new NumberSetting("Hunger",
        "Start eating at or below this many food points. Full is 20.", 14, 1, 19, 1)
        .under(trigger, Trigger.HUNGER, Trigger.EITHER, Trigger.BOTH);
    private final NumberSetting injuredHunger = new NumberSetting("Injured hunger",
        "Start eating at or below this many food points whilst you are hurt. "
            + "Regeneration needs a nearly full bar.", 19, 1, 20, 1)
        .under(trigger, Trigger.HUNGER, Trigger.EITHER, Trigger.BOTH);
    private final NumberSetting injuryThreshold = new NumberSetting("Injury threshold",
        "How much health you must be missing before Injured hunger takes over.",
        1.5, 0.5, 10, 0.5, " hearts").min(0.5)
        .under(trigger, Trigger.HUNGER, Trigger.EITHER, Trigger.BOTH);
    private final NumberSetting targetHunger = new NumberSetting("Target hunger",
        "Fills your bar up to this many food points and picks food that wastes none.",
        20, 1, 20, 1);
    private final NumberSetting health = new NumberSetting("Health",
        "Start eating at or below this many hearts. A golden apple is reached for first and other food after. "
            + "Zero turns it off.",
        5, 0, 10, 0.5, " hearts").min(0).max(20)
        .under(trigger, Trigger.HEALTH, Trigger.EITHER, Trigger.BOTH);
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
    private final BoolSetting preferSoup = new BoolSetting("Prefer soup",
        "Reaches for stew and soup before other food whilst healing.", false);
    private final BoolSetting tidyBowls = new BoolSetting("Tidy bowls",
        "Gathers empty bowls into the first backpack slot so they are easy to refill.", false);
    private final BoolSetting avoidClicks = new BoolSetting("Avoid clicks",
        "Never eat whilst your crosshair is on something a right click would open.", true);
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
        addSettings(trigger, hunger, injuredHunger, injuryThreshold, targetHunger, health,
            priority, avoid, takeFrom, saveGapples, preferSoup, tidyBowls, avoidClicks,
            offhand, whileMoving, pauseCombat, whileBusy, noSlowdown, pauseOnFire);
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
        moveBowl();
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
        if (potionBusy() || clickInTheWay()) {
            return;
        }
        if (!whileMoving.isOn() && mc.player.getDeltaMovement().horizontalDistanceSqr() > 1.0E-4) {
            return;
        }

        boolean healthLow = healthLow();
        if (!triggered(healthLow, wantsFood())) {
            return;
        }
        int limit = slotLimit();
        int slot = healthLow && !healingWasted() ? findHealing(limit) : -1;
        healing = slot != -1;
        if (slot == -1 && healthLow && preferSoup.isOn()) {
            slot = findSoup(limit);
        }
        if (slot == -1) {
            slot = findFood(limit);
        }
        if (slot == -1) {
            return;
        }
        beginEating(slot);
    }

    // The Health value only counts in the modes that show it.
    private boolean healthLow() {
        return !trigger.is(Trigger.HUNGER) && EntityUtil.healthAtOrBelow(health.getValue());
    }

    private boolean triggered(boolean healthLow, boolean hungerLow) {
        return switch (trigger.getValue()) {
            case HUNGER -> hungerLow;
            case HEALTH -> healthLow;
            case EITHER -> healthLow || hungerLow;
            case BOTH -> healthLow && hungerLow;
        };
    }

    // Hands mode searches no slots. Hotbar mode or an open screen searches the
    // hotbar. Anything else searches the whole inventory.
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
        return food <= (injured() ? injuredHunger.getInt() : hunger.getInt());
    }

    // True whilst enough health is missing for the injured threshold to take over.
    private boolean injured() {
        return mc.player.getMaxHealth() - mc.player.getHealth() >= injuryThreshold.getValue() * 2;
    }

    // A right click through a meal would open a chest or start a trade.
    private boolean clickInTheWay() {
        if (!avoidClicks.isOn() || mc.hitResult == null) {
            return false;
        }
        if (mc.hitResult instanceof EntityHitResult entityHit) {
            Entity entity = entityHit.getEntity();
            return entity instanceof AbstractVillager || entity instanceof TamableAnimal;
        }
        return mc.hitResult instanceof BlockHitResult blockHit
            && BlockUtil.opensOnClick(BlockUtil.state(blockHit.getBlockPos()));
    }

    // Soup servers want the empties in one place for a refill.
    private void moveBowl() {
        if (!tidyBowls.isOn() || eating || !InventoryUtil.inventoryFree()) {
            return;
        }
        if (mc.player.getInventory().getItem(BOWL_SLOT).is(Items.BOWL)) {
            return;
        }
        int from = InventoryUtil.findSlot(Items.BOWL, InventoryUtil.WHOLE_INVENTORY);
        if (from == -1 || from == BOWL_SLOT) {
            return;
        }
        InventoryUtil.swap(InventoryUtil.networkSlot(from), InventoryUtil.networkSlot(BOWL_SLOT));
    }

    // Honey bottles and golden apples work at full hunger. Other food does not.
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
        // A screen stops the game reading the use key so the meal is started by hand.
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

    // The use key hits the main hand first. Food there fires instead of the offhand.
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

    // Food that wastes no points first. Anything edible after that. A golden
    // apple only when nothing is left and it is not saved.
    private int findFood(int limit) {
        int best = pickFood(limit, true);
        if (best == -1) {
            best = pickFood(limit, false);
        }
        if (best == -1 && !saveGapples.isOn()) {
            return findHealing(limit);
        }
        return best;
    }

    private int pickFood(int limit, boolean noWaste) {
        if (offhandUsable()) {
            ItemStack held = mc.player.getOffhandItem();
            if (isEdible(held) && !isHealing(held) && edibleNow(held) && (!noWaste || fits(held))) {
                return Inventory.SLOT_OFFHAND;
            }
        }
        int best = -1;
        double bestScore = 0;
        for (int i = 0; i < InventoryUtil.WHOLE_INVENTORY; i++) {
            if (!searchable(i, limit)) {
                continue;
            }
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!isEdible(stack) || isHealing(stack) || !edibleNow(stack)) {
                continue;
            }
            if (noWaste && !fits(stack)) {
                continue;
            }
            double score = score(stack);
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    // True when the food would not push the bar past the target.
    private boolean fits(ItemStack stack) {
        FoodProperties food = stack.get(DataComponents.FOOD);
        if (food == null || food.canAlwaysEat()) {
            return true;
        }
        return mc.player.getFoodData().getFoodLevel() + food.nutrition() <= targetHunger.getInt();
    }

    // Stew and soup heal on soup servers where a golden apple is never allowed.
    private int findSoup(int limit) {
        if (offhandUsable() && isSoup(mc.player.getOffhandItem())) {
            return Inventory.SLOT_OFFHAND;
        }
        for (int i = 0; i < InventoryUtil.WHOLE_INVENTORY; i++) {
            if (searchable(i, limit) && isSoup(mc.player.getInventory().getItem(i))) {
                return i;
            }
        }
        return -1;
    }

    private boolean isSoup(ItemStack stack) {
        if (!isEdible(stack) || !edibleNow(stack)) {
            return false;
        }
        return stack.is(Items.MUSHROOM_STEW) || stack.is(Items.RABBIT_STEW)
            || stack.is(Items.BEETROOT_SOUP);
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
