package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.NumberSetting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Set;

public final class AutoEat extends Module {

    private static final Set<net.minecraft.world.item.Item> BLACKLIST = Set.of(
        Items.ROTTEN_FLESH, Items.SPIDER_EYE, Items.POISONOUS_POTATO,
        Items.PUFFERFISH, Items.CHICKEN, Items.SUSPICIOUS_STEW, Items.CHORUS_FRUIT
    );

    private final NumberSetting hunger = new NumberSetting("Hunger",
        "Start eating at or below this many food points. Full is 20.", 14, 1, 19, 1);

    private boolean eating;
    private int previousSlot = -1;

    public AutoEat() {
        super("AutoEat", "Eats food from your hotbar when you get hungry.", Category.PLAYER);
        addSettings(hunger);
    }

    @Override
    public String getSuffix() {
        return eating ? "eating" : null;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            stopEating();
            return;
        }

        if (!eating) {
            int food = mc.player.getFoodData().getFoodLevel();
            // The game refuses food on a full hunger bar.
            if (food > hunger.getInt() || food >= 20) {
                return;
            }
            int foodSlot = findFood();
            if (foodSlot == -1) {
                return;
            }
            previousSlot = mc.player.getInventory().getSelectedSlot();
            mc.player.getInventory().setSelectedSlot(foodSlot);
            eating = true;
        }

        boolean full = mc.player.getFoodData().getFoodLevel() >= 20;
        boolean holdingFood = isGoodFood(mc.player.getInventory().getSelectedItem());
        if (full || !holdingFood) {
            stopEating();
            return;
        }
        mc.options.keyUse.setDown(true);
    }

    private void stopEating() {
        if (!eating) {
            return;
        }
        eating = false;
        // Give the key back without forcing it up.
        boolean physicallyHeld = com.mojang.blaze3d.platform.InputConstants.isKeyDown(
            mc.getWindow(), mc.options.keyUse.key.getValue());
        mc.options.keyUse.setDown(physicallyHeld);
        if (previousSlot != -1 && mc.player != null) {
            mc.player.getInventory().setSelectedSlot(previousSlot);
        }
        previousSlot = -1;
    }

    @Override
    protected void onDisable() {
        stopEating();
    }

    private int findFood() {
        for (int i = 0; i < 9; i++) {
            if (isGoodFood(mc.player.getInventory().getItem(i))) {
                return i;
            }
        }
        return -1;
    }

    private boolean isGoodFood(ItemStack stack) {
        return stack.has(DataComponents.FOOD) && !BLACKLIST.contains(stack.getItem());
    }
}
