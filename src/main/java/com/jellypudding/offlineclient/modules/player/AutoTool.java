package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.BlockBreakEvent;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.modules.combat.AutoWeapon;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.BreakSlots;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.ItemUtil;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Comparator;
import java.util.List;

public final class AutoTool extends Module {

    // Speed outranks every enchantment. The bonuses only break a tie.
    private static final double SPEED_WEIGHT = 1000;
    private static final double PREFERRED_WEIGHT = 10;

    // What a bare hand scores against any block.
    private static final double HAND_SCORE = SPEED_WEIGHT;

    // Items that never wear come first. Then the lower durability tiers and then fewer enchantments.
    private static final Comparator<ItemStack> CHEAPEST_FIRST = Comparator
        .comparingInt((ItemStack stack) -> ItemUtil.wears(stack) ? 1 : 0)
        .thenComparingInt(ItemStack::getMaxDamage)
        .thenComparingInt(stack -> stack.getEnchantments().size());

    public enum Prefer { NONE, FORTUNE, SILK_TOUCH }

    public enum Filter { OFF, ALLOW, BLOCK }

    public enum Spare { CHEAPEST, SLOT }

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
        "Never mines with a nearly broken tool. Hands over to a free slot or stops the break.", true);
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
    private final BoolSetting saveTools = new BoolSetting("Save tools",
        "Hands the hit that breaks each block to a cheaper hotbar item and spares your tool the wear.",
        false);
    private final EnumSetting<Spare> spare = new EnumSetting<>("Spare",
        "Which item makes the last hit.", Spare.CHEAPEST)
        .describe(Spare.CHEAPEST, "The cheapest item in the hotbar that still breaks the block in time.")
        .describe(Spare.SLOT, "The hotbar slot below whenever it breaks the block in time.")
        .under(saveTools);
    private final NumberSetting spareSlot = new NumberSetting("Spare slot",
        "The hotbar slot that makes the last hit.", 9, 1, 9, 1).min(1).max(InventoryUtil.HOTBAR_SIZE)
        .under(spare, Spare.SLOT);
    private final NumberSetting spareStays = new NumberSetting("Spare stays for",
        "Ticks the spare stays in hand after its hit to cover a block the server finishes a tick late.",
        1, 0, 5, 1, " ticks").min(0).under(saveTools);
    private final BoolSetting keepDrops = new BoolSetting("Keep drops",
        "Only makes the last hit with an item that still gets the drops.", true).under(saveTools);
    private final BoolSetting keepEnchants = new BoolSetting("Keep enchantments",
        "Only makes the last hit with the same Silk Touch and with as much Fortune on ores and crops.",
        true).under(saveTools);
    private final RegistryListSetting<Block> neverSave = new RegistryListSetting<>("Never save on",
        "Blocks your own tool always finishes. Click to pick them.", BuiltInRegistries.BLOCK, List.of())
        .under(saveTools);

    private final InventoryUtil.HotbarLoan loan = new InventoryUtil.HotbarLoan();
    // Holds the spare for the one packet that breaks the block.
    private final InventoryUtil.SlotSwap spareHit = new InventoryUtil.SlotSwap();
    private int spareTicksLeft;

    private boolean wasDestroying;

    // The block being mined as it stood before the game cleared it and the ticks it has taken.
    private BlockPos breaking;
    private BlockState breakingState;
    private int breakTicks;

    private boolean holdingBack;
    private ItemStack warnedAbout;

    public AutoTool() {
        super("AutoTool", "Switches to your best tool when you mine something.", Category.PLAYER);
        addSettings(fromInventory, prefer, fortune, silkTouch, switchBack, switchDelay, antiBreak,
            breakMargin, swords, hands, filter, tools, saveTools, spare, spareSlot, spareStays, keepDrops,
            keepEnchants, neverSave);
        searchTags("tool", "pickaxe", "best tool", "fortune", "silk touch", "spare tool",
            "tool saver", "durability");
    }

    // The spare goes home first. The loan then finds its own tool in hand and can go back too.
    @Override
    protected void onDisable() {
        spareHit.restoreIfMine();
        spareTicksLeft = 0;
        restore();
        wasDestroying = false;
        breaking = null;
        holdingBack = false;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.gameMode == null || mc.player.isDeadOrDying()) {
            // Respawn hands out a fresh inventory.
            loan.forget();
            spareHit.forget();
            wasDestroying = false;
            return;
        }
        // The spare only makes one hit. The game waits five ticks before the next block anyway.
        boolean spareOut = spareTicksLeft > 0;
        if (spareOut) {
            spareTicksLeft--;
        } else {
            spareHit.restoreIfMine();
        }
        boolean destroying = mc.gameMode.isDestroying();
        // A loan that could not go home earlier gets another go every tick. It waits whilst the
        // spare is out because the slot to go back to is the one the spare hands back.
        if (!destroying && !spareOut && (wasDestroying || loan.isLent())) {
            restore();
        }
        if (!destroying) {
            breaking = null;
            holdingBack = false;
        }
        wasDestroying = destroying || (wasDestroying && spareOut);
    }

    @Subscribe
    private void onBlockBreak(BlockBreakEvent event) {
        holdingBack = false;
        if (!inGame() || mc.player.isSpectator()) {
            return;
        }
        BlockPos pos = event.getPos();
        BlockState state = mc.level.getBlockState(pos);
        track(pos, state);
        ItemStack held = mc.player.getMainHandItem();
        boolean snaps = wouldSnap(held, state);
        // AutoWeapon drives the same hand and a spare still in hand stays for its ticks. A tool
        // about to snap never waits out the switch delay.
        boolean handBusy = weaponBusy() || spareTicksLeft > 0;
        if (!handBusy && (snaps || breakTicks >= switchDelay.getInt())) {
            int best = pick(state, fromInventory.isOn() ? InventoryUtil.WHOLE_INVENTORY : InventoryUtil.HOTBAR_SIZE);
            if (best != -1) {
                loan.select(best);
                return;
            }
        }
        holdingBack = snaps;
        if (holdingBack) {
            warnOnce(held);
        }
    }

    // Counts the ticks spent on one block and keeps the block as it stood.
    private void track(BlockPos pos, BlockState state) {
        if (!pos.equals(breaking)) {
            breaking = pos.immutable();
            breakTicks = 0;
        } else {
            breakTicks++;
        }
        breakingState = state;
    }

    // True whilst a break is stopped because the only tool to hand would snap on it.
    public boolean holdsBack() {
        return holdingBack;
    }

    // The slot among the first few AutoTool would mine the block with. Minus one keeps what is held.
    public int pick(BlockState state, int slots) {
        int selected = InventoryUtil.selectedSlot();
        ItemStack held = mc.player.getInventory().getItem(selected);
        int best = enchantedPick(state, slots);
        if (best == -1) {
            boolean heldWornOut = antiBreak.isOn() && isNearlyBroken(held);
            double floor = heldWornOut ? HAND_SCORE : score(held, state);
            best = bestSlot(state, floor, slots);
            // A tool about to snap is worth leaving even for a bare hand.
            if (best == -1 && heldWornOut) {
                best = handSlot();
            }
        }
        // Nothing better than a hand and the hand itself is better than what is held.
        if (best == -1 && hands.isOn() && ItemUtil.miningSpeed(held, state) <= 1 && !held.isEmpty()) {
            best = InventoryUtil.freeHotbarSlot();
        }
        return best == selected ? -1 : best;
    }

    // The same within the hotbar for the packet miners.
    public int hotbarPick(BlockState state) {
        return pick(state, InventoryUtil.HOTBAR_SIZE);
    }

    // A slot that mines like a bare hand and cannot wear out. Minus one on a hotbar full of tools.
    private static int handSlot() {
        int free = InventoryUtil.freeHotbarSlot();
        return free != -1 ? free : InventoryUtil.hotbarSlot(stack -> !stack.isDamageableItem());
    }

    private boolean wouldSnap(ItemStack held, BlockState state) {
        return antiBreak.isOn() && isNearlyBroken(held) && ItemUtil.wearsOn(held, state);
    }

    private void warnOnce(ItemStack held) {
        if (held == warnedAbout) {
            return;
        }
        warnedAbout = held;
        ChatUtil.error("AutoTool will not mine with your nearly broken " + held.getHoverName().getString() + ".");
    }

    // The enchantment a block deserves outranks raw speed. Ores and crops want
    // Fortune and an ender chest wants Silk Touch. Minus one when nothing carries it.
    private int enchantedPick(BlockState state, int slots) {
        ResourceKey<Enchantment> wanted = null;
        if (silkTouch.isOn() && state.is(Blocks.ENDER_CHEST)) {
            wanted = Enchantments.SILK_TOUCH;
        } else if (fortune.isOn() && fortuneCounts(state)) {
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

    // Ores and crops drop more with Fortune.
    private static boolean fortuneCounts(BlockState state) {
        return BlockUtil.isOre(state) || state.getBlock() instanceof CropBlock;
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

    // The game's own packet that breaks the block is where the spare steps in. The slot
    // change goes out first and the server breaks the block with the spare in hand.
    @Subscribe
    private void onPacketSend(PacketSendEvent event) {
        if (!saveTools.isOn() || !(event.getPacket() instanceof ServerboundPlayerActionPacket packet)
            || packet.getSequence() == 0 || !inGame()) {
            return;
        }
        BlockPos pos = packet.getPos();
        switch (packet.getAction()) {
            case START_DESTROY_BLOCK -> {
                if (oneHit(pos)) {
                    finishWithSpare(pos, 1);
                }
            }
            // The server counts from the start. One tick less allows for the two clocks.
            case STOP_DESTROY_BLOCK -> {
                if (BreakSlots.isTarget(pos)) {
                    finishWithSpare(pos, BlockUtil.SERVER_ACCEPTS / Math.max(1, BreakSlots.sinceStart()));
                }
            }
            default -> {
            }
        }
    }

    // A start only breaks the block when the tool in hand does it in one hit.
    private boolean oneHit(BlockPos pos) {
        return pos.equals(breaking) && breakingState != null
            && breakingState.getDestroyProgress(mc.player, mc.level, pos) >= 1;
    }

    private void finishWithSpare(BlockPos pos, float needed) {
        if (!pos.equals(breaking) || breakingState == null || mc.player.getAbilities().instabuild
            || weaponBusy()) {
            return;
        }
        ItemStack held = mc.player.getMainHandItem();
        if (neverSave.contains(breakingState.getBlock()) || !ItemUtil.wearsOn(held, breakingState)) {
            return;
        }
        int slot = spareFor(held, breakingState, pos, needed);
        if (slot != -1) {
            spareHit.select(slot);
            spareTicksLeft = spareStays.getInt();
        }
    }

    // Minus one when no spare can make the hit.
    private int spareFor(ItemStack held, BlockState state, BlockPos pos, float needed) {
        int selected = InventoryUtil.selectedSlot();
        if (spare.is(Spare.SLOT)) {
            int slot = spareSlot.getInt() - 1;
            return slot != selected && finishes(hotbar(slot), held, state, pos, needed) ? slot : -1;
        }
        int best = -1;
        for (int i = 0; i < InventoryUtil.HOTBAR_SIZE; i++) {
            ItemStack stack = hotbar(i);
            if (i == selected || CHEAPEST_FIRST.compare(stack, held) >= 0
                || !finishes(stack, held, state, pos, needed)) {
                continue;
            }
            if (best == -1 || CHEAPEST_FIRST.compare(stack, hotbar(best)) < 0) {
                best = i;
            }
        }
        return best;
    }

    // The spare breaks the block in time and loses nothing the tool in hand would have kept.
    private boolean finishes(ItemStack spareStack, ItemStack held, BlockState state, BlockPos pos,
                             float needed) {
        if (!allowed(spareStack)) {
            return false;
        }
        if (keepDrops.isOn() && ItemUtil.getsDrops(held, state) && !ItemUtil.getsDrops(spareStack, state)) {
            return false;
        }
        if (keepEnchants.isOn() && !sameLuck(spareStack, held, state)) {
            return false;
        }
        return BlockUtil.breakDelta(spareStack, state, pos) >= needed;
    }

    // Silk Touch changes what nearly every block drops. Fortune only counts on ores and crops.
    private static boolean sameLuck(ItemStack spareStack, ItemStack held, BlockState state) {
        if (ItemUtil.enchantLevel(Enchantments.SILK_TOUCH, spareStack)
            != ItemUtil.enchantLevel(Enchantments.SILK_TOUCH, held)) {
            return false;
        }
        return !fortuneCounts(state) || ItemUtil.enchantLevel(Enchantments.FORTUNE, spareStack)
            >= ItemUtil.enchantLevel(Enchantments.FORTUNE, held);
    }

    private ItemStack hotbar(int slot) {
        return mc.player.getInventory().getItem(slot);
    }

    private boolean weaponBusy() {
        AutoWeapon weapon = Modules.get(AutoWeapon.class);
        return weapon != null && weapon.isHoldingWeapon();
    }

    // The first slot only comes back whilst the player has not picked a different one.
    private void restore() {
        loan.giveBack(switchBack.isOn() && loan.stillMine());
    }

    private boolean isNearlyBroken(ItemStack stack) {
        return ItemUtil.wornBelow(stack, breakMargin.getValue() / 100);
    }
}
