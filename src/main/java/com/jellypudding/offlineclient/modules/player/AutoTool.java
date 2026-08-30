package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.BlockBreakEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.modules.combat.AutoWeapon;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.ItemUtil;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.Predicate;

public final class AutoTool extends Module {

    // Fraction of the total durability that counts as nearly broken.
    private static final double LOW_DURABILITY = 0.05;

    private final BoolSetting fromInventory = new BoolSetting("Search inventory",
        "Also borrows a better tool from the rest of your inventory and puts it back afterwards.",
        false);
    private final BoolSetting fortune = new BoolSetting("Fortune on ores",
        "Takes a Fortune tool for ores and crops even when a plain one is faster.", true);
    private final BoolSetting silkTouch = new BoolSetting("Silk touch on ender chests",
        "Takes a Silk Touch pickaxe for an ender chest. It then drops whole.", true);
    private final BoolSetting switchBack = new BoolSetting("Switch back",
        "Returns to the slot you had once you stop mining.", true);
    private final BoolSetting antiBreak = new BoolSetting("Anti break",
        "Never picks a nearly broken tool and drops one that wears out mid swing.", true);
    private final BoolSetting swords = new BoolSetting("Use swords",
        "A sword counts as a tool. Cobwebs and bamboo cut far faster with one.", false);
    private final BoolSetting hands = new BoolSetting("Use hands",
        "Switches to an empty slot when nothing beats a bare hand.", false);

    private final InventoryUtil.HotbarLoan loan = new InventoryUtil.HotbarLoan();

    private boolean wasDestroying;

    public AutoTool() {
        super("AutoTool", "Switches to your best tool when you mine something.", Category.PLAYER);
        addSettings(fromInventory, fortune, silkTouch, switchBack, antiBreak, swords, hands);
        searchTags("tool", "pickaxe", "best tool", "fortune", "silk touch");
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
            loan.forget();
            wasDestroying = false;
            return;
        }
        boolean destroying = mc.gameMode.isDestroying();
        // A loan that could not go home earlier gets another go every tick.
        if (!destroying && (wasDestroying || loan.isLent())) {
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
        int selected = InventoryUtil.selectedSlot();
        ItemStack held = mc.player.getInventory().getItem(selected);
        int slots = fromInventory.isOn() ? InventoryUtil.WHOLE_INVENTORY : InventoryUtil.HOTBAR_SIZE;
        Predicate<ItemStack> allowed = stack -> (!antiBreak.isOn() || !isNearlyBroken(stack))
            && (swords.isOn() || !(stack.is(ItemTags.SWORDS)));

        int best = enchantedPick(state, slots, allowed);
        if (best == -1) {
            boolean heldWornOut = antiBreak.isOn() && isNearlyBroken(held);
            float heldSpeed = heldWornOut ? 1 : ItemUtil.miningSpeed(held, state);
            best = ItemUtil.bestToolSlot(state, heldSpeed, allowed, slots);
            // A tool about to snap is worth leaving even for a bare hand.
            if (best == -1 && heldWornOut) {
                best = InventoryUtil.freeHotbarSlot();
            }
        }
        // Nothing better than a hand and the hand itself is better than what is held.
        if (best == -1 && hands.isOn() && ItemUtil.miningSpeed(held, state) <= 1
            && !held.isEmpty()) {
            best = InventoryUtil.freeHotbarSlot();
        }
        if (best == -1 || best == selected) {
            return;
        }
        loan.select(best);
    }

    /**
     * The enchantment a block deserves outranks raw speed. Ores and crops
     * want Fortune and an ender chest wants Silk Touch. Minus one when the
     * block wants neither or nothing carries it.
     */
    private int enchantedPick(BlockState state, int slots, Predicate<ItemStack> allowed) {
        ResourceKey<Enchantment> wanted = null;
        if (silkTouch.isOn() && state.is(Blocks.ENDER_CHEST)) {
            wanted = Enchantments.SILK_TOUCH;
        } else if (fortune.isOn() && (BlockUtil.isOre(state) || state.getBlock() instanceof CropBlock)) {
            wanted = Enchantments.FORTUNE;
        }
        if (wanted == null) {
            return -1;
        }
        int best = -1;
        int bestLevel = 0;
        float bestSpeed = 1;
        for (int i = 0; i < slots; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!allowed.test(stack)) {
                continue;
            }
            int level = ItemUtil.enchantLevel(wanted, stack);
            float speed = ItemUtil.miningSpeed(stack, state);
            // Only a real tool for the block counts. A level beats a speed and a speed breaks ties.
            if (level == 0 || speed <= 1) {
                continue;
            }
            if (level > bestLevel || (level == bestLevel && speed > bestSpeed)) {
                bestLevel = level;
                bestSpeed = speed;
                best = i;
            }
        }
        return best;
    }

    private boolean weaponBusy() {
        AutoWeapon weapon = Modules.get(AutoWeapon.class);
        return weapon != null && weapon.isHoldingWeapon();
    }

    // The old slot only comes back if the player has not picked another since.
    private void restore() {
        loan.giveBack(switchBack.isOn() && loan.stillMine());
    }

    private static boolean isNearlyBroken(ItemStack stack) {
        return ItemUtil.wornBelow(stack, LOW_DURABILITY);
    }
}
