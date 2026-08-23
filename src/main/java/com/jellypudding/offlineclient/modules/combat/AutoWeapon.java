package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.AttackEntityEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.ExclusivityGroup;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import com.jellypudding.offlineclient.util.ItemUtil;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;

public final class AutoWeapon extends Module {

    public enum Prefer {
        SWORD("Sword"),
        AXE("Axe");

        private final String name;

        Prefer(String name) {
            this.name = name;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private final EnumSetting<Prefer> prefer = new EnumSetting<>("Prefer",
        "Which kind of weapon wins when both are close in damage.", Prefer.SWORD);
    private final NumberSetting threshold = new NumberSetting("Threshold",
        "How much more damage the other kind must deal to win.", 2, 0, 10, 0.5);
    private final BoolSetting antiBreak = new BoolSetting("Anti break",
        "Skip weapons that are about to break.", true);
    private final BoolSetting switchBack = new BoolSetting("Switch back",
        "Return to the slot you had selected once you stop fighting.", false);
    private final NumberSetting releaseTime = new NumberSetting("Release time",
        "Ticks without an attack before switching back.", 20, 1, 100, 1, " ticks")
        .visibleWhen(switchBack::isOn);

    private final SlotSwap slots = new SlotSwap();
    private int timer;

    public AutoWeapon() {
        super("AutoWeapon", "Switches to your strongest sword or axe when you attack.", Category.COMBAT);
        addSettings(prefer, threshold, antiBreak, switchBack, releaseTime);
        searchTags("auto sword", "auto axe", "weapon switch");
    }

    @Override
    public ExclusivityGroup getExclusivityGroup() {
        return ExclusivityGroup.WEAPON_SWAP;
    }

    @Override
    protected void onEnable() {
        slots.forget();
        timer = 0;
    }

    @Override
    protected void onDisable() {
        if (switchBack.isOn() && inGame()) {
            slots.restore();
        } else {
            slots.forget();
        }
    }

    // AutoTool stands aside whilst this is true.
    public boolean isHoldingWeapon() {
        return isEnabled() && slots.isHolding();
    }

    @Subscribe
    private void onAttack(AttackEntityEvent event) {
        if (!inGame() || mc.player.isSpectator() || mc.player.isDeadOrDying()) {
            return;
        }
        if (!(event.getTarget() instanceof LivingEntity target)) {
            return;
        }

        int best = bestSlot(target);
        int selected = mc.player.getInventory().getSelectedSlot();
        if (best == -1 || best == selected) {
            timer = releaseTime.getInt();
            return;
        }

        slots.select(best);
        timer = releaseTime.getInt();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || !slots.isHolding()) {
            return;
        }
        if (mc.player.isDeadOrDying()) {
            // Respawn hands out a fresh inventory.
            slots.forget();
            timer = 0;
            return;
        }
        if (!switchBack.isOn()) {
            slots.forget();
            return;
        }
        if (timer > 0) {
            timer--;
            return;
        }
        slots.restore();
    }

    private int bestSlot(LivingEntity target) {
        return bestWeaponSlot(target, prefer.getValue() == Prefer.SWORD,
            threshold.getValue(), antiBreak.isOn());
    }

    public static int bestWeaponSlot(LivingEntity target) {
        return bestWeaponSlot(target, true, 2, true);
    }

    // The hotbar slot holding the strongest weapon against this target or minus one.
    public static int bestWeaponSlot(LivingEntity target, boolean preferSword,
                                     double margin, boolean skipBreaking) {
        int swordSlot = -1;
        int axeSlot = -1;
        double swordDamage = 0;
        double axeDamage = 0;

        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (skipBreaking && ItemUtil.nearlyBroken(stack)) {
                continue;
            }
            if (stack.is(ItemTags.SWORDS)) {
                double damage = weaponDamage(stack, target);
                if (damage > swordDamage) {
                    swordDamage = damage;
                    swordSlot = i;
                }
            } else if (stack.is(ItemTags.AXES)) {
                double damage = weaponDamage(stack, target);
                if (damage > axeDamage) {
                    axeDamage = damage;
                    axeSlot = i;
                }
            }
        }

        if (swordSlot == -1) {
            return axeSlot;
        }
        if (axeSlot == -1) {
            return swordSlot;
        }
        if (preferSword) {
            return axeDamage - swordDamage > margin ? axeSlot : swordSlot;
        }
        return swordDamage - axeDamage > margin ? swordSlot : axeSlot;
    }

    // Damage one full strength hit would deal to the target.
    public static double weaponDamage(ItemStack stack, Entity target) {
        double damage = ItemUtil.attributeValue(stack, Attributes.ATTACK_DAMAGE, EquipmentSlot.MAINHAND);

        int sharpness = ItemUtil.enchantLevel(Enchantments.SHARPNESS, stack);
        if (sharpness > 0) {
            damage += 0.5 * sharpness + 0.5;
        }
        if (EntityUtil.typeIs(target, EntityTypeTags.SENSITIVE_TO_SMITE)) {
            damage += 2.5 * ItemUtil.enchantLevel(Enchantments.SMITE, stack);
        }
        if (EntityUtil.typeIs(target, EntityTypeTags.SENSITIVE_TO_BANE_OF_ARTHROPODS)) {
            damage += 2.5 * ItemUtil.enchantLevel(Enchantments.BANE_OF_ARTHROPODS, stack);
        }
        return damage;
    }
}
