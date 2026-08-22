package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.ItemUtil;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;

public final class AutoArmor extends Module {

    private static final EquipmentSlot[] SLOTS = {
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks between each swap.", 2, 0, 20, 1, " ticks");
    private final BoolSetting useEnchantments = new BoolSetting("Count Protection",
        "A well enchanted piece can beat a higher tier plain one.", true);

    private int timer;

    public AutoArmor() {
        super("AutoArmor", "Automatically wears the best armor you have.", Category.COMBAT);
        addSettings(delay, useEnchantments);
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            return;
        }
        if (mc.gui.screen() instanceof AbstractContainerScreen
            && !(mc.gui.screen() instanceof InventoryScreen
                || mc.gui.screen() instanceof CreativeModeInventoryScreen)) {
            return;
        }
        if (timer > 0) {
            timer--;
            return;
        }

        for (int slotIndex = 0; slotIndex < SLOTS.length; slotIndex++) {
            EquipmentSlot slot = SLOTS[slotIndex];

            // Never swap off an equipped elytra.
            if (slot == EquipmentSlot.CHEST
                && mc.player.getItemBySlot(slot).is(Items.ELYTRA)) {
                continue;
            }

            double equippedScore = score(mc.player.getItemBySlot(slot), slot);

            int bestInvSlot = -1;
            double bestScore = equippedScore;
            for (int i = 0; i < 36; i++) {
                ItemStack stack = mc.player.getInventory().getItem(i);
                double stackScore = score(stack, slot);
                if (stackScore > bestScore) {
                    bestScore = stackScore;
                    bestInvSlot = i;
                }
            }

            if (bestInvSlot == -1) {
                continue;
            }

            int networkSlot = bestInvSlot < 9 ? 36 + bestInvSlot : bestInvSlot;
            int armorNetworkSlot = 5 + slotIndex;
            boolean slotWasEmpty = mc.player.getItemBySlot(slot).isEmpty();

            click(networkSlot);
            click(armorNetworkSlot);
            if (!slotWasEmpty) {
                click(networkSlot);
            }
            timer = delay.getInt();
            return;
        }
    }

    /**
     * Real protective value of a piece. Armor points weigh the most. Then
     * the Protection enchantment. Then toughness.
     */
    private double score(ItemStack stack, EquipmentSlot slot) {
        if (stack.isEmpty() || stack.is(Items.ELYTRA) || ItemUtil.equipSlot(stack) != slot) {
            return -1;
        }
        double armor = ItemUtil.attributeValue(stack, Attributes.ARMOR, slot);
        double toughness = ItemUtil.attributeValue(stack, Attributes.ARMOR_TOUGHNESS, slot);
        double protection = useEnchantments.isOn()
            ? ItemUtil.enchantLevel(Enchantments.PROTECTION, stack) : 0;
        return armor * 5 + protection * 3 + toughness;
    }

    private void click(int networkSlot) {
        mc.gameMode.handleContainerInput(0, networkSlot, 0, ContainerInput.PICKUP, mc.player);
    }
}
