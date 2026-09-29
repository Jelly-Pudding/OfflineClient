package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.RankSetting;
import com.jellypudding.offlineclient.setting.Setting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.phys.BlockHitResult;

import java.util.function.Predicate;

// Flint and steel and fire charges. The modules that set blocks or sulfur cubes alight share
// the order the two are tried in and the rule that spares a flint and steel about to snap.
public final class Ignition {

    private static final Minecraft MC = OfflineClient.MC;

    public enum Flame {
        FLINT_AND_STEEL(Items.FLINT_AND_STEEL),
        FIRE_CHARGE(Items.FIRE_CHARGE);

        private final Item item;

        Flame(Item item) {
            this.item = item;
        }

        public ItemStack icon() {
            return new ItemStack(item);
        }
    }

    private final RankSetting<Flame> order = new RankSetting<>("Light with",
        "Which lighter to use. The first one on the list in your offhand or hotbar is used.",
        Flame.class, Flame::icon);
    private final BoolSetting antiBreak = new BoolSetting("Anti break",
        "Leaves a flint and steel that is about to snap alone.", false);

    public Setting<?>[] settings() {
        return new Setting<?>[] {order, antiBreak};
    }

    // Shows both rows only whilst the parent holds the value.
    public <E extends Enum<E>> Ignition under(EnumSetting<E> parent, E value) {
        order.under(parent, value);
        antiBreak.under(parent, value);
        return this;
    }

    public Ignition under(BoolSetting parent) {
        order.under(parent);
        antiBreak.under(parent);
        return this;
    }

    // True for a lighter the list allows that is not about to snap.
    public boolean usable(ItemStack stack) {
        for (Flame flame : order.ranked()) {
            if (stack.is(flame.item)) {
                return intact(stack);
            }
        }
        return false;
    }

    public static final String NO_LIGHTER = "Nothing in your offhand or hotbar can start a fire.";

    // True whilst the offhand or the hotbar holds a lighter that may be used.
    public boolean atHand() {
        return usable(MC.player.getOffhandItem()) || InventoryUtil.hotbarSlot(this::usable) != -1;
    }

    // Puts the favourite lighter to hand and runs the click with the hand that holds it. One in
    // the offhand needs no swap. A hotbar slot stays selected for the caller to give back.
    // False when no lighter is at hand or the click was refused.
    public boolean use(InventoryUtil.SlotSwap slots, Predicate<InteractionHand> click) {
        for (Flame flame : order.ranked()) {
            Predicate<ItemStack> fits = stack -> stack.is(flame.item) && intact(stack);
            if (fits.test(MC.player.getOffhandItem())) {
                return click.test(InteractionHand.OFF_HAND);
            }
            int slot = InventoryUtil.hotbarSlot(fits);
            if (slot != -1) {
                slots.select(slot);
                return click.test(InteractionHand.MAIN_HAND);
            }
        }
        return false;
    }

    private boolean intact(ItemStack stack) {
        return !antiBreak.isOn() || !ItemUtil.nearlyBroken(stack);
    }

    // True when fire would take in the cell. It has to be empty with something under it or
    // beside it that holds fire.
    public static boolean fireFits(BlockPos cell) {
        return MC.level.isInWorldBounds(cell)
            && BaseFireBlock.canBePlacedAt(MC.level, cell, MC.player.getDirection());
    }

    // The click that puts fire into the empty cell from the block on the given side of it.
    // A lighter sets its fire in front of the face it clicks.
    public static BlockHitResult fireClick(BlockPos cell, Direction from) {
        BlockPos against = cell.relative(from);
        Direction face = from.getOpposite();
        return new BlockHitResult(BlockUtil.hitPoint(against, face), face, against, false);
    }

    // Clicks with the lighter in the hand whilst sneaking. A chest or a door or TNT under the
    // click stays shut or unlit and the fire goes down. True when the game took the click.
    public static boolean strike(BlockHitResult hit, InteractionHand hand, boolean rotate) {
        if (rotate) {
            BlockUtil.faceVector(hit.getLocation());
        }
        boolean used = InputUtil.whileSneaking(() -> MC.gameMode.useItemOn(MC.player, hand, hit).consumesAction());
        if (used) {
            SwingMode.swingArm(hand);
        }
        return used;
    }

    // Fire only goes into an empty space. Grass or a flower in the cell is broken first and the
    // fire follows on a later tick. True whilst the cell is being cleared.
    public static boolean clear(BlockPos cell) {
        if (!BlockUtil.clearsInOneHit(cell)) {
            return false;
        }
        MC.gameMode.startDestroyBlock(cell, Direction.UP);
        return true;
    }

    public static boolean isFire(BlockPos cell) {
        return BlockUtil.state(cell).getBlock() instanceof BaseFireBlock;
    }

    // Punches out the fire in the cell. One hit puts it out. False when the cell holds none.
    public static boolean putOut(BlockPos cell) {
        if (!isFire(cell)) {
            return false;
        }
        MC.gameMode.startDestroyBlock(cell, Direction.UP);
        return true;
    }
}
