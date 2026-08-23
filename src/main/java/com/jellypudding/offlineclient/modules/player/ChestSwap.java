package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.ItemUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;
import org.lwjgl.glfw.GLFW;

/**
 * Swaps the chest slot between an elytra and a chestplate on one key.
 * AutoArmor never takes an elytra off.
 */
public final class ChestSwap extends Module {

    // Container slot the chest piece sits in on the survival inventory.
    private static final int CHEST_SLOT = 6;

    private final BoolSetting best = new BoolSetting("Best chestplate",
        "Reach for the strongest chestplate instead of the first one found.", true);
    private final BoolSetting countProtection = new BoolSetting("Count Protection",
        "A well enchanted piece can beat a higher tier plain one.", true)
        .visibleWhen(best::isOn);

    public ChestSwap() {
        super("ChestSwap", "Swaps an elytra and a chestplate on one key.",
            Category.PLAYER, GLFW.GLFW_KEY_G);
        addSettings(best, countProtection);
        searchTags("elytra swap", "chestplate", "wings");
    }

    // Nothing to leave running. The bind does the swap.
    @Override
    public boolean isTogglable() {
        return false;
    }

    @Override
    public void onKeybind() {
        swap();
    }

    public void swap() {
        if (!inGame() || mc.player.isSpectator() || mc.gameMode == null) {
            return;
        }
        // Container clicks need the survival inventory with a clear cursor.
        if (mc.player.containerMenu.containerId != 0
            || !mc.player.containerMenu.getCarried().isEmpty()) {
            return;
        }

        ItemStack worn = mc.player.getItemBySlot(EquipmentSlot.CHEST);
        if (isElytra(worn)) {
            equipChestplate();
        } else if (isChestplate(worn)) {
            equipElytra();
        } else if (!equipChestplate()) {
            equipElytra();
        }
    }

    private boolean equipElytra() {
        for (int i = 0; i < 36; i++) {
            if (isElytra(mc.player.getInventory().getItem(i))) {
                wear(i);
                return true;
            }
        }
        return false;
    }

    private boolean equipChestplate() {
        int found = -1;
        double bestScore = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!isChestplate(stack)) {
                continue;
            }
            if (!best.isOn()) {
                wear(i);
                return true;
            }
            double score = score(stack);
            if (found == -1 || score > bestScore) {
                found = i;
                bestScore = score;
            }
        }
        if (found == -1) {
            return false;
        }
        wear(found);
        return true;
    }

    private void wear(int inventorySlot) {
        InventoryUtil.swap(InventoryUtil.networkSlot(inventorySlot), CHEST_SLOT);
    }

    private boolean isElytra(ItemStack stack) {
        return !stack.isEmpty() && stack.has(DataComponents.GLIDER);
    }

    private boolean isChestplate(ItemStack stack) {
        return !stack.isEmpty() && !isElytra(stack)
            && ItemUtil.equipSlot(stack) == EquipmentSlot.CHEST;
    }

    // Armour points weigh the most. Then Protection. Then toughness.
    private double score(ItemStack stack) {
        double armor = ItemUtil.attributeValue(stack, Attributes.ARMOR, EquipmentSlot.CHEST);
        double toughness = ItemUtil.attributeValue(stack, Attributes.ARMOR_TOUGHNESS,
            EquipmentSlot.CHEST);
        double protection = countProtection.isOn()
            ? ItemUtil.enchantLevel(Enchantments.PROTECTION, stack) : 0;
        return armor * 5 + protection * 3 + toughness;
    }
}
