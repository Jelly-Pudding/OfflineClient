package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.ItemUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import org.lwjgl.glfw.GLFW;

// Swaps the chest slot between an elytra and a chestplate on one key.
// AutoArmor never takes an elytra off.
public final class ChestSwap extends Module {

    public enum Choice { STRONGEST, FIRST_FOUND, DIAMOND, NETHERITE, PREFER_DIAMOND, PREFER_NETHERITE }

    // Container slot the chest piece sits in on the survival inventory.
    private static final int CHEST_SLOT = 6;

    private final EnumSetting<Choice> chestplate = new EnumSetting<>("Chestplate",
        "Which chestplate goes on.", Choice.STRONGEST)
        .describe(Choice.STRONGEST, "The chestplate with the most armour.")
        .describe(Choice.FIRST_FOUND, "The first chestplate in your inventory.")
        .describe(Choice.DIAMOND, "A diamond chestplate and nothing else.")
        .describe(Choice.NETHERITE, "A netherite chestplate and nothing else.")
        .describe(Choice.PREFER_DIAMOND, "Diamond when you have one and otherwise netherite.")
        .describe(Choice.PREFER_NETHERITE, "Netherite when you have one and otherwise diamond.");
    private final BoolSetting countProtection = new BoolSetting("Count protection",
        "A well enchanted piece can beat a higher tier plain one.", true)
        .under(chestplate, Choice.STRONGEST);
    private final BoolSetting closeInventory = new BoolSetting("Close inventory",
        "Tells the server the inventory was closed after the swap as the vanilla screen does.",
        true);
    private final BoolSetting stayOn = new BoolSetting("Stay on",
        "The module stays on after the swap and swaps back when turned off.", false);

    public ChestSwap() {
        super("ChestSwap", "Swaps an elytra and a chestplate on one key.",
            Category.PLAYER, GLFW.GLFW_KEY_G);
        addSettings(chestplate, countProtection, closeInventory, stayOn);
        searchTags("elytra swap", "chestplate", "wings");
    }

    // The bind does the swap on its own unless the module is asked to stay on.
    @Override
    public boolean isTogglable() {
        return stayOn.isOn();
    }

    @Override
    public boolean savesEnabledState() {
        return false;
    }

    @Override
    public void onKeybind() {
        if (stayOn.isOn()) {
            toggle();
        } else {
            swap();
        }
    }

    @Override
    protected void onEnable() {
        swap();
    }

    @Override
    protected void onDisable() {
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
        int slot = InventoryUtil.findSlot(this::isElytra, InventoryUtil.WHOLE_INVENTORY);
        if (slot == -1) {
            return false;
        }
        wear(slot);
        return true;
    }

    private boolean equipChestplate() {
        int found = -1;
        double bestScore = 0;
        for (int i = 0; i < InventoryUtil.WHOLE_INVENTORY; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!isChestplate(stack)) {
                continue;
            }
            double score = score(stack);
            if (score <= 0) {
                continue;
            }
            if (found == -1 || score > bestScore) {
                found = i;
                bestScore = score;
            }
            if (settles(score)) {
                break;
            }
        }
        if (found == -1) {
            return false;
        }
        wear(found);
        return true;
    }

    // True when no later piece can beat this one.
    private boolean settles(double score) {
        return switch (chestplate.getValue()) {
            case STRONGEST -> false;
            case FIRST_FOUND -> true;
            default -> score >= 2;
        };
    }

    private void wear(int inventorySlot) {
        InventoryUtil.swap(InventoryUtil.networkSlot(inventorySlot), CHEST_SLOT);
        if (closeInventory.isOn()) {
            mc.player.connection.send(new ServerboundContainerClosePacket(0));
        }
    }

    private boolean isElytra(ItemStack stack) {
        return !stack.isEmpty() && stack.has(DataComponents.GLIDER);
    }

    private boolean isChestplate(ItemStack stack) {
        return !stack.isEmpty() && !isElytra(stack)
            && ItemUtil.equipSlot(stack) == EquipmentSlot.CHEST;
    }

    // Zero for a piece the choice refuses. The tier choices score a wanted tier
    // two and a fallback tier one so the first wanted piece wins outright.
    private double score(ItemStack stack) {
        boolean diamond = stack.is(Items.DIAMOND_CHESTPLATE);
        boolean netherite = stack.is(Items.NETHERITE_CHESTPLATE);
        return switch (chestplate.getValue()) {
            case STRONGEST -> strength(stack);
            case FIRST_FOUND -> 1;
            case DIAMOND -> diamond ? 2 : 0;
            case NETHERITE -> netherite ? 2 : 0;
            case PREFER_DIAMOND -> diamond ? 2 : netherite ? 1 : 0;
            case PREFER_NETHERITE -> netherite ? 2 : diamond ? 1 : 0;
        };
    }

    // Armour points weigh the most. Then Protection. Then toughness.
    private double strength(ItemStack stack) {
        double armor = ItemUtil.attributeValue(stack, Attributes.ARMOR, EquipmentSlot.CHEST);
        double toughness = ItemUtil.attributeValue(stack, Attributes.ARMOR_TOUGHNESS,
            EquipmentSlot.CHEST);
        double protection = countProtection.isOn()
            ? ItemUtil.enchantLevel(Enchantments.PROTECTION, stack) : 0;
        return armor * 5 + protection * 3 + toughness;
    }
}
