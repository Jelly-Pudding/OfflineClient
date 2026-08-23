package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.BlockBreakEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.module.ModuleManager;
import com.jellypudding.offlineclient.modules.combat.AutoWeapon;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.util.ItemUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

public final class AutoTool extends Module {

    // Fraction of the total durability that counts as nearly broken.
    private static final double LOW_DURABILITY = 0.05;

    private final BoolSetting switchBack = new BoolSetting("Switch back",
        "Returns to the slot you had once you stop mining.", true);
    private final BoolSetting antiBreak = new BoolSetting("Anti break",
        "Never picks a nearly broken tool and drops one that wears out mid swing.", true);

    private int previousSlot = -1;

    // A manual slot change cancels the return.
    private int ourSlot = -1;

    private boolean wasDestroying;

    // Cached. The lookup walks every registered module.
    private AutoWeapon autoWeapon;

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
            previousSlot = -1;
            ourSlot = -1;
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
        int best = heldWornOut ? -1 : selected;
        // A fast enchanted tool beats a plain better one.
        float bestSpeed = heldWornOut ? -1 : ItemUtil.miningSpeed(held, state);

        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (antiBreak.isOn() && isNearlyBroken(stack)) {
                continue;
            }
            float speed = ItemUtil.miningSpeed(stack, state);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                best = i;
            }
        }

        if (best == -1 || best == selected) {
            return;
        }
        if (previousSlot == -1) {
            previousSlot = selected;
        }
        mc.player.getInventory().setSelectedSlot(best);
        ourSlot = best;
    }

    private boolean weaponBusy() {
        if (autoWeapon == null) {
            ModuleManager modules = OfflineClient.INSTANCE.getModuleManager();
            if (modules == null) {
                return false;
            }
            autoWeapon = modules.get(AutoWeapon.class);
        }
        return autoWeapon.isHoldingWeapon();
    }

    private void restore() {
        if (!switchBack.isOn() || previousSlot == -1 || mc.player == null) {
            previousSlot = -1;
            ourSlot = -1;
            return;
        }
        if (mc.player.getInventory().getSelectedSlot() == ourSlot) {
            mc.player.getInventory().setSelectedSlot(previousSlot);
        }
        previousSlot = -1;
        ourSlot = -1;
    }

    private boolean isNearlyBroken(ItemStack stack) {
        if (!stack.isDamageableItem()) {
            return false;
        }
        int max = stack.getMaxDamage();
        return max - stack.getDamageValue() <= max * LOW_DURABILITY;
    }
}
