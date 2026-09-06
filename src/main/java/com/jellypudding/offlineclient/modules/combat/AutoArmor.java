package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ChoiceListSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.Swap;
import com.jellypudding.offlineclient.util.ItemUtil;
import com.jellypudding.offlineclient.util.MovementUtil;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

public final class AutoArmor extends Module {

    public enum Protection {
        PROTECTION(Enchantments.PROTECTION),
        BLAST_PROTECTION(Enchantments.BLAST_PROTECTION),
        FIRE_PROTECTION(Enchantments.FIRE_PROTECTION),
        PROJECTILE_PROTECTION(Enchantments.PROJECTILE_PROTECTION);

        private final ResourceKey<Enchantment> enchantment;

        Protection(ResourceKey<Enchantment> enchantment) {
            this.enchantment = enchantment;
        }
    }

    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks between each swap.", 2, 0, 20, 1, " ticks");
    private final EnumSetting<Protection> prefer = new EnumSetting<>("Prefer",
        "The protection enchantment that counts for the most.", Protection.PROTECTION)
        .describe(Protection.PROTECTION, "Plain Protection against everything.")
        .describe(Protection.BLAST_PROTECTION, "Blast Protection against crystals and beds.")
        .describe(Protection.FIRE_PROTECTION, "Fire Protection against lava and fire.")
        .describe(Protection.PROJECTILE_PROTECTION, "Projectile Protection against arrows.");
    private final BoolSetting blastLeggings = new BoolSetting("Blast leggings",
        "Leggings always prefer Blast Protection whatever is picked above.", true);
    private final ChoiceListSetting avoided = new ChoiceListSetting("Avoided enchantments",
        "Never put on a piece carrying any of these and take one off when something else fits.",
        AutoArmor::enchantmentIds, List.of("minecraft:binding_curse", "minecraft:frost_walker"));
    private final BoolSetting keepElytra = new BoolSetting("Keep elytra",
        "Leave a worn elytra alone rather than swapping a chestplate over it.", true);
    private final BoolSetting whileMoving = new BoolSetting("While moving",
        "Keep swapping whilst you walk. Off waits until you stand still.", true);
    private final BoolSetting antiBreak = new BoolSetting("Anti break",
        "Takes a worn piece off before it shatters and never puts one on.", true);
    private final NumberSetting durability = new NumberSetting("Durability",
        "Uses left at which a piece counts as worn out.", 10, 1, 100, 1, " uses")
        .under(antiBreak);

    private int timer;

    public AutoArmor() {
        super("AutoArmor", "Automatically wears the best armour you have.", Category.COMBAT);
        addSettings(delay, prefer, blastLeggings, avoided, keepElytra, whileMoving, antiBreak, durability);
        searchTags("armour", "auto equip");
    }

    // Every enchantment the world knows. The list is empty until a world is loaded.
    private static Collection<String> enchantmentIds() {
        if (mc.level == null) {
            return List.of();
        }
        return mc.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).keySet().stream()
            .map(Object::toString).sorted().collect(Collectors.toList());
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
        if (!whileMoving.isOn() && MovementUtil.inputDirection().lengthSqr() > 0) {
            return;
        }
        if (!InventoryUtil.carried().isEmpty()) {
            return;
        }
        if (timer > 0) {
            timer--;
            return;
        }

        for (int slotIndex = 0; slotIndex < ItemUtil.ARMOR_SLOTS.size(); slotIndex++) {
            EquipmentSlot slot = ItemUtil.ARMOR_SLOTS.get(slotIndex);
            ItemStack worn = mc.player.getItemBySlot(slot);
            if (slot == EquipmentSlot.CHEST && worn.is(Items.ELYTRA) && keepElytra.isOn()) {
                continue;
            }
            // A cursed piece never comes off. Clicking at it every tick gets nowhere.
            if (EnchantmentHelper.has(worn, EnchantmentEffectComponents.PREVENT_ARMOR_CHANGE)) {
                continue;
            }

            int armorNetworkSlot = InventoryUtil.ARMOR_START + slotIndex;
            int upgrade = bestUpgrade(worn, slot);
            if (upgrade == -1) {
                // Nothing better to wear. A shattering piece still comes off.
                if (wornOut(worn) && stow(armorNetworkSlot)) {
                    return;
                }
                continue;
            }

            if (InventoryUtil.swap(InventoryUtil.networkSlot(upgrade), armorNetworkSlot) != Swap.REFUSED) {
                timer = delay.getInt();
            }
            return;
        }
    }

    // Moves a worn out piece into the first free inventory slot.
    private boolean stow(int armorNetworkSlot) {
        int free = InventoryUtil.findSlot(ItemStack::isEmpty, InventoryUtil.WHOLE_INVENTORY);
        if (free == -1) {
            return false;
        }
        if (InventoryUtil.swap(armorNetworkSlot, InventoryUtil.networkSlot(free)) == Swap.REFUSED) {
            return false;
        }
        timer = delay.getInt();
        return true;
    }

    private boolean wornOut(ItemStack stack) {
        return antiBreak.isOn() && ItemUtil.nearlyBroken(stack, durability.getInt());
    }

    // The inventory slot of the piece that beats the worn one or minus one.
    // An elytra or a worn out piece scores nothing so anything real replaces it.
    private int bestUpgrade(ItemStack worn, EquipmentSlot slot) {
        int best = -1;
        double bestScore = worn.is(Items.ELYTRA) || wornOut(worn) ? -1 : score(worn, slot);
        for (int i = 0; i < InventoryUtil.WHOLE_INVENTORY; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.isEmpty() || stack.is(Items.ELYTRA) || ItemUtil.equipSlot(stack) != slot
                || wornOut(stack) || carriesAvoided(stack)) {
                continue;
            }
            double stackScore = score(stack, slot);
            if (stackScore > bestScore) {
                bestScore = stackScore;
                best = i;
            }
        }
        return best;
    }

    // Armour points count double and the chosen protection triple. Every other
    // protection and Unbreaking count once and Mending twice. Avoided ones count against.
    private double score(ItemStack stack, EquipmentSlot slot) {
        double score = 2 * ItemUtil.attributeValue(stack, Attributes.ARMOR, slot)
            + ItemUtil.attributeValue(stack, Attributes.ARMOR_TOUGHNESS, slot);
        ResourceKey<Enchantment> preferred = slot == EquipmentSlot.LEGS && blastLeggings.isOn()
            ? Enchantments.BLAST_PROTECTION : prefer.getValue().enchantment;
        score += 3 * ItemUtil.enchantLevel(preferred, stack);
        for (Protection kind : Protection.values()) {
            score += ItemUtil.enchantLevel(kind.enchantment, stack);
        }
        score += ItemUtil.enchantLevel(Enchantments.UNBREAKING, stack);
        score += 2 * ItemUtil.enchantLevel(Enchantments.MENDING, stack);
        for (Object2IntMap.Entry<Holder<Enchantment>> entry
            : EnchantmentHelper.getEnchantmentsForCrafting(stack).entrySet()) {
            if (isAvoided(entry.getKey())) {
                score -= 2 * entry.getIntValue();
            }
        }
        return score;
    }

    private boolean carriesAvoided(ItemStack stack) {
        for (Holder<Enchantment> enchantment : EnchantmentHelper.getEnchantmentsForCrafting(stack).keySet()) {
            if (isAvoided(enchantment)) {
                return true;
            }
        }
        return false;
    }

    private boolean isAvoided(Holder<Enchantment> enchantment) {
        return enchantment.unwrapKey()
            .map(key -> avoided.contains(key.identifier().toString()))
            .orElse(false);
    }
}
