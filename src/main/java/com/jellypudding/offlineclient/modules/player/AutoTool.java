package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.BlockBreakEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.modules.combat.AutoWeapon;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.ItemUtil;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

public final class AutoTool extends Module {

    // Fraction of the total durability that counts as nearly broken.
    private static final double LOW_DURABILITY = 0.05;

    private final BoolSetting switchBack = new BoolSetting("Switch back",
        "Returns to the slot you had once you stop mining.", true);
    private final BoolSetting antiBreak = new BoolSetting("Anti break",
        "Never picks a nearly broken tool and drops one that wears out mid swing.", true);

    private final InventoryUtil.SlotSwap slots = new InventoryUtil.SlotSwap();

    private boolean wasDestroying;

    public AutoTool() {
        super("AutoTool", "Switches to your best tool when you mine something.", Category.PLAYER);
        addSettings(switchBack, antiBreak);
        searchTags("tool", "pickaxe", "best tool");
    }

    @Override
    protected void onDisable() {
        restore();
        wasDestroying = false;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.gameMode == null || mc.player.isDeadOrDying()) {
            // Respawn hands out a fresh inventory.
            slots.forget();
            wasDestroying = false;
            return;
        }
        boolean destroying = mc.gameMode.isDestroying();
        if (wasDestroying && !destroying) {
            restore();
        }
        wasDestroying = destroying;
    }

    @Subscribe
    private void onBlockBreak(BlockBreakEvent event) {
        if (!inGame() || mc.player.isSpectator()) {
            return;
        }
        // AutoWeapon drives the same hand.
        if (weaponBusy()) {
            return;
        }
        BlockState state = mc.level.getBlockState(event.getPos());
        int selected = mc.player.getInventory().getSelectedSlot();
        ItemStack held = mc.player.getInventory().getItem(selected);

        // A tool about to snap is worth leaving even for a slower one.
        boolean heldWornOut = antiBreak.isOn() && isNearlyBroken(held);
        // A fast enchanted tool beats a plain better one.
        float heldSpeed = heldWornOut ? -1 : ItemUtil.miningSpeed(held, state);
        int best = ItemUtil.bestToolSlot(state, heldSpeed,
            stack -> !antiBreak.isOn() || !isNearlyBroken(stack));

        if (best == -1 || best == selected) {
            return;
        }
        slots.select(best);
    }

    private boolean weaponBusy() {
        AutoWeapon weapon = Modules.get(AutoWeapon.class);
        return weapon != null && weapon.isHoldingWeapon();
    }

    private void restore() {
        if (!switchBack.isOn()) {
            slots.forget();
            return;
        }
        slots.restoreIfMine();
    }

    private boolean isNearlyBroken(ItemStack stack) {
        if (!stack.isDamageableItem()) {
            return false;
        }
        int max = stack.getMaxDamage();
        return max - stack.getDamageValue() <= max * LOW_DURABILITY;
    }
}
