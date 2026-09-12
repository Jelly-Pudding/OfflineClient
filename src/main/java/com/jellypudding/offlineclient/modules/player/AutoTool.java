package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.BlockBreakEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.modules.combat.AutoWeapon;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.ItemUtil;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

public final class AutoTool extends Module {

    // Speed outranks every enchantment. The bonuses only break a tie.
    private static final double SPEED_WEIGHT = 1000;
    private static final double PREFERRED_WEIGHT = 10;

    // What a bare hand scores against any block.
    private static final double HAND_SCORE = SPEED_WEIGHT;

    public enum Prefer { NONE, FORTUNE, SILK_TOUCH }

    public enum Filter { OFF, ALLOW, BLOCK }

    private final BoolSetting fromInventory = new BoolSetting("Search inventory",
        "Also borrows a better tool from the rest of your inventory and puts it back afterwards.",
        false);
    private final EnumSetting<Prefer> prefer = new EnumSetting<>("Prefer",
        "Which enchantment wins between tools of the same speed on any block.", Prefer.FORTUNE)
        .describe(Prefer.NONE, "Speed alone decides.")
        .describe(Prefer.FORTUNE, "A Fortune tool wins a tie on any block.")
        .describe(Prefer.SILK_TOUCH, "A Silk Touch tool wins a tie on any block such as glass or ice.");
    private final BoolSetting fortune = new BoolSetting("Fortune on ores",
        "Takes a Fortune tool for ores and crops even when a plain one is faster.", true);
    private final BoolSetting silkTouch = new BoolSetting("Silk touch on ender chests",
        "Takes a Silk Touch pickaxe for an ender chest. It then drops whole.", true);
    private final BoolSetting switchBack = new BoolSetting("Switch back",
        "Returns to the slot you had once you stop mining.", true);
    private final NumberSetting switchDelay = new NumberSetting("Switch delay",
        "Ticks to keep mining with what you hold before the tool is switched.", 0, 0, 20, 1, " ticks")
        .min(0);
    private final BoolSetting antiBreak = new BoolSetting("Anti break",
        "Never picks a nearly broken tool and drops one that wears out mid swing.", true);
    private final NumberSetting breakMargin = new NumberSetting("Retire at",
        "A tool with this much durability or less counts as nearly broken.", 10, 1, 100, 1, "%")
        .min(1).max(100)
        .under(antiBreak);
    private final BoolSetting swords = new BoolSetting("Use swords",
        "A sword counts as a tool. Cobwebs and bamboo cut far faster with one.", false);
    private final BoolSetting hands = new BoolSetting("Use hands",
        "Switches to an empty slot when nothing beats a bare hand.", false);
    private final EnumSetting<Filter> filter = new EnumSetting<>("Tool filter",
        "Which tools may be picked.", Filter.OFF)
        .describe(Filter.OFF, "Every tool may be picked.")
        .describe(Filter.ALLOW, "Only the listed tools may be picked.")
        .describe(Filter.BLOCK, "The listed tools are never picked.");
    private final RegistryListSetting<Item> tools = new RegistryListSetting<>("Tools",
        "The tools the filter applies to. Click to pick them.", BuiltInRegistries.ITEM, List.of())
        .under(filter, Filter.ALLOW, Filter.BLOCK);

    private final InventoryUtil.HotbarLoan loan = new InventoryUtil.HotbarLoan();

    private boolean wasDestroying;

    // The block being mined and how many ticks it has taken up to now.
    private BlockPos breaking;
    private int breakTicks;

    public AutoTool() {
        super("AutoTool", "Switches to your best tool when you mine something.", Category.PLAYER);
        addSettings(fromInventory, prefer, fortune, silkTouch, switchBack, switchDelay, antiBreak,
            breakMargin, swords, hands, filter, tools);
        searchTags("tool", "pickaxe", "best tool", "fortune", "silk touch");
    }

    @Override
    protected void onDisable() {
        restore();
        wasDestroying = false;
        breaking = null;
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
        if (!destroying) {
            breaking = null;
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
        if (!countBreakTicks(event.getPos())) {
            return;
        }
        BlockState state = mc.level.getBlockState(event.getPos());
        int selected = InventoryUtil.selectedSlot();
        ItemStack held = mc.player.getInventory().getItem(selected);
        int slots = fromInventory.isOn() ? InventoryUtil.WHOLE_INVENTORY : InventoryUtil.HOTBAR_SIZE;

        int best = enchantedPick(state, slots);
        if (best == -1) {
            boolean heldWornOut = antiBreak.isOn() && isNearlyBroken(held);
            double floor = heldWornOut ? HAND_SCORE : score(held, state);
            best = bestSlot(state, floor, slots);
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

    // True once the block has been mined for the switch delay.
    private boolean countBreakTicks(BlockPos pos) {
        if (!pos.equals(breaking)) {
            breaking = pos.immutable();
            breakTicks = 0;
        } else {
            breakTicks++;
        }
        return breakTicks >= switchDelay.getInt();
    }

    // The enchantment a block deserves outranks raw speed. Ores and crops want
    // Fortune and an ender chest wants Silk Touch. Minus one when nothing carries it.
    private int enchantedPick(BlockState state, int slots) {
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
        double bestScore = HAND_SCORE;
        for (int i = 0; i < slots; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!allowed(stack)) {
                continue;
            }
            int level = ItemUtil.enchantLevel(wanted, stack);
            double score = score(stack, state);
            // Real tools for the block only. Level wins first and score breaks a tie.
            if (level == 0 || score <= HAND_SCORE) {
                continue;
            }
            if (level > bestLevel || (level == bestLevel && score > bestScore)) {
                bestLevel = level;
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    // The real tool that scores above the floor. Minus one when none does.
    private int bestSlot(BlockState state, double floor, int slots) {
        int best = -1;
        double bestScore = Math.max(floor, HAND_SCORE);
        for (int i = 0; i < slots; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!allowed(stack)) {
                continue;
            }
            double score = score(stack, state);
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    // Speed first. The preferred enchantment and then the durability
    // enchantments only separate tools that mine at the same speed.
    private double score(ItemStack stack, BlockState state) {
        double score = ItemUtil.miningSpeed(stack, state) * SPEED_WEIGHT;
        if (score <= HAND_SCORE) {
            return HAND_SCORE;
        }
        ResourceKey<Enchantment> preferred = switch (prefer.getValue()) {
            case NONE -> null;
            case FORTUNE -> Enchantments.FORTUNE;
            case SILK_TOUCH -> Enchantments.SILK_TOUCH;
        };
        if (preferred != null) {
            score += ItemUtil.enchantLevel(preferred, stack) * PREFERRED_WEIGHT;
        }
        return score + ItemUtil.enchantLevel(Enchantments.UNBREAKING, stack)
            + ItemUtil.enchantLevel(Enchantments.MENDING, stack);
    }

    private boolean allowed(ItemStack stack) {
        if (antiBreak.isOn() && isNearlyBroken(stack)) {
            return false;
        }
        if (!swords.isOn() && stack.is(ItemTags.SWORDS)) {
            return false;
        }
        return switch (filter.getValue()) {
            case OFF -> true;
            case ALLOW -> tools.contains(stack.getItem());
            case BLOCK -> !tools.contains(stack.getItem());
        };
    }

    private boolean weaponBusy() {
        AutoWeapon weapon = Modules.get(AutoWeapon.class);
        return weapon != null && weapon.isHoldingWeapon();
    }

    // The old slot only comes back if the player has not picked another since.
    private void restore() {
        loan.giveBack(switchBack.isOn() && loan.stillMine());
    }

    private boolean isNearlyBroken(ItemStack stack) {
        return ItemUtil.wornBelow(stack, breakMargin.getValue() / 100);
    }
}
