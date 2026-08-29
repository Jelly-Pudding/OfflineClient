package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.Swap;
import com.jellypudding.offlineclient.util.ItemUtil;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;

public final class AutoArmor extends Module {

    private static final EquipmentSlot[] SLOTS = {
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks between each swap.", 2, 0, 20, 1, " ticks");
    private final BoolSetting useEnchantments = new BoolSetting("Count Protection",
        "Weigh the Protection enchantment when comparing pieces.", true);

    private int timer;

    public AutoArmor() {
        super("AutoArmor", "Automatically wears the best armour you have.", Category.COMBAT);
        addSettings(delay, useEnchantments);
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.player.isSpectator()) {
            return;
        }
        // Clicks are thrown away unless the survival inventory is the open one.
        if (!InventoryUtil.canClick()) {
            return;
        }
        // Anything already on the cursor belongs to the player.
        if (!InventoryUtil.carried().isEmpty()) {
            return;
        }
        if (timer > 0) {
            timer--;
            return;
        }

        for (int slotIndex = 0; slotIndex < SLOTS.length; slotIndex++) {
            EquipmentSlot slot = SLOTS[slotIndex];

            if (slot == EquipmentSlot.CHEST
                && mc.player.getItemBySlot(slot).is(Items.ELYTRA)) {
                continue;
            }

            int upgrade = bestUpgrade(slot);
            if (upgrade == -1) {
                continue;
            }

            // The armour slots sit right after the crafting grid.
            int armorNetworkSlot = 5 + slotIndex;
            if (InventoryUtil.swap(InventoryUtil.networkSlot(upgrade), armorNetworkSlot) != Swap.REFUSED) {
                timer = delay.getInt();
            }
            // One piece a tick.
            return;
        }
    }

    private int bestUpgrade(EquipmentSlot slot) {
        ItemStack worn = mc.player.getItemBySlot(slot);
        // A cursed piece never comes off. Clicking at it every tick gets nowhere.
        if (EnchantmentHelper.has(worn, EnchantmentEffectComponents.PREVENT_ARMOR_CHANGE)) {
            return -1;
        }
        int best = -1;
        double bestScore = score(worn, slot);
        for (int i = 0; i < 36; i++) {
            double stackScore = score(mc.player.getInventory().getItem(i), slot);
            if (stackScore > bestScore) {
                bestScore = stackScore;
                best = i;
            }
        }
        return best;
    }

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
}
