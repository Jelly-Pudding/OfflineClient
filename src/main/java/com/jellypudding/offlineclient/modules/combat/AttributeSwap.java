package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.AttackEntityEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.ExclusivityGroup;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import com.jellypudding.offlineclient.util.ItemUtil;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

public final class AttributeSwap extends Module {

    private final NumberSetting minGain = new NumberSetting("Min gain",
        "The other item must beat what you hold by this much damage.", 0.5, 0, 10, 0.5);
    private final BoolSetting swapBack = new BoolSetting("Swap back",
        "Return to the slot you had once the hit has landed.", true);
    private final NumberSetting swapBackDelay = new NumberSetting("Swap back delay",
        "Ticks to hold the swapped item before returning.", 2, 0, 20, 1, " ticks")
        .under(swapBack);
    private final BoolSetting antiBreak = new BoolSetting("Anti break",
        "Skip items that are about to break.", true);

    private final SlotSwap slots = new SlotSwap();
    private int timer;

    public AttributeSwap() {
        super("AttributeSwap", "Swaps to your hardest hitting item the moment you attack.",
            Category.COMBAT);
        addSettings(minGain, swapBack, swapBackDelay, antiBreak);
        searchTags("attribute", "damage swap", "hotbar");
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
        slots.restore();
    }

    @Subscribe
    private void onAttack(AttackEntityEvent event) {
        if (!inGame() || mc.player.isSpectator() || slots.isHolding()) {
            return;
        }
        if (!(event.getTarget() instanceof LivingEntity target)) {
            return;
        }
        int selected = mc.player.getInventory().getSelectedSlot();
        int best = bestSlot(target, selected);
        if (best == -1) {
            return;
        }
        // The slot packet leaves before the attack packet.
        slots.select(best);
        timer = swapBackDelay.getInt();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || !slots.isHolding()) {
            return;
        }
        if (!swapBack.isOn()) {
            slots.forget();
            return;
        }
        if (timer > 0) {
            timer--;
            return;
        }
        slots.restore();
    }

    private int bestSlot(LivingEntity target, int selected) {
        double bestDamage = AutoWeapon.weaponDamage(
            mc.player.getInventory().getItem(selected), target) + minGain.getValue();
        int best = -1;
        for (int i = 0; i < 9; i++) {
            if (i == selected) {
                continue;
            }
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (antiBreak.isOn() && ItemUtil.nearlyBroken(stack)) {
                continue;
            }
            double damage = AutoWeapon.weaponDamage(stack, target);
            if (damage > bestDamage) {
                bestDamage = damage;
                best = i;
            }
        }
        return best;
    }
}
