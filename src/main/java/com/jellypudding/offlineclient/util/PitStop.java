package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.modules.movement.ElytraFly;
import com.jellypudding.offlineclient.modules.player.AutoMend;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Fallable;

import java.util.function.Predicate;

// The work a long flight on wings does standing between two legs. Rockets and an elytra and
// bottles come out of the shulker boxes carried or the ender chest. AutoMend throws bottles at
// the worn elytra and a fresher elytra goes on in place of one that stays worn.
public final class PitStop {

    // What a stop is asked to look after.
    public record Needs(boolean mend, boolean restock, double line, int keepRockets) {
    }

    private enum Step { RESTOCK, MEND, CHANGE, DONE }

    private static final Minecraft MC = OfflineClient.MC;

    // A mend that wins back no durability for this long is over. AutoMend throws a bottle every
    // few ticks and the orbs reach you within a second or two.
    private static final int MEND_STALL_TICKS = 10 * SharedConstants.TICKS_PER_SECOND;
    // Bottles taken out of a container stop at a stack.
    private static final int BOTTLES = 64;
    // The AutoMend rows a mend past Elytra line needs.
    private static final String MEND_ROWS = "Its Mode has to throw bottles and its Gear has to watch armour and its"
        + " Start below has to sit over Elytra line";

    private final InventoryUtil.SlotSwap slots = new InventoryUtil.SlotSwap();
    private final RestockErrand restock = new RestockErrand(slots, "Autopilot", "the next flight");
    // The hotbar slot a stack of bottles took and what it held.
    private final HotbarRoom room = new HotbarRoom();
    private Needs needs;
    private Step step = Step.DONE;
    // True once the mend has begun and whilst AutoMend is on because the stop switched it on.
    private boolean mending;
    private boolean mendSwitchedOn;
    // The least damage the elytra has shown whilst mending and the ticks since it last went down.
    private int leastDamage;
    private int stallTicks;
    // Why the elytra is still worn once the stop is over as a sentence for chat. Null when it is
    // fit to fly on.
    private String stillWorn;

    // Forgets a container that would not open. Each flight starts afresh.
    public void reset() {
        restock.reset();
    }

    // Why a flight needs a stop now in the words after "comes down". Null whilst it needs
    // nothing a stop could get it.
    public String due(Needs asked) {
        if (!asked.mend() && !asked.restock()) {
            return null;
        }
        if (wornBelow(asked.line())) {
            if (couldMend(asked)) {
                return "to mend the elytra";
            }
            if (fresherCarried() != -1 || couldFetch(asked, elytraWanted(asked))) {
                return "to change the elytra";
            }
        }
        if (asked.restock() && rocketsLow(asked) && couldFetch(asked, rocketsWanted(asked))) {
            return "for more rockets";
        }
        return null;
    }

    // Why no stop can help an elytra worn down to the line in the words after "as". Asked once due
    // finds nothing. Null whilst the elytra is not worn down that far.
    public String noHelp(Needs asked) {
        if (!asked.mend() && !asked.restock() || !wornBelow(asked.line())) {
            return null;
        }
        if (!asked.mend()) {
            return noSupply(asked, elytraWanted(asked), "fresher elytra");
        }
        AutoMend autoMend = Modules.get(AutoMend.class);
        if (!mendable()) {
            return "the elytra has no Mending";
        }
        if (autoMend == null || Modules.lockHolds(autoMend)) {
            return "ModuleLock keeps AutoMend off";
        }
        if (!autoMend.wouldMend(EquipmentSlot.CHEST, asked.line())) {
            return "AutoMend would not mend it. " + MEND_ROWS;
        }
        return noSupply(asked, bottlesWanted(asked), "experience bottles");
    }

    // Why no more of something can be had in the words after "as".
    private String noSupply(Needs asked, Predicate<ItemStack> wanted, String what) {
        if (!asked.restock()) {
            return "you carry no " + what;
        }
        if (MC.player.getInventory().getFreeSlot() == -1) {
            return "your inventory has no free slot to restock into";
        }
        if (RestockErrand.needsPickaxe(wanted)) {
            return "your ender chest only comes back to a Silk Touch pickaxe in the hotbar";
        }
        return "your inventory and your containers hold no " + what;
    }

    // Starts the work once you stand still.
    public void begin(Needs asked) {
        needs = asked;
        stillWorn = null;
        mending = false;
        mendSwitchedOn = false;
        step = asked.restock() ? Step.RESTOCK : Step.MEND;
        if (asked.restock()) {
            restock.startErrand(this::wanted, true, true);
        }
    }

    // One tick of the work. False once it is over. The hands wait whilst something else has them.
    public boolean tick() {
        if (step == Step.DONE) {
            return false;
        }
        if (Hands.taken()) {
            return true;
        }
        switch (step) {
            case RESTOCK -> {
                if (!restock.runErrand(true)) {
                    slots.restoreIfMine();
                    step = Step.MEND;
                }
            }
            case MEND -> mend();
            case CHANGE -> {
                // A container another owner set down beside you is seen to before the flight goes on.
                if (!restock.busyElsewhere()) {
                    change();
                    step = Step.DONE;
                }
            }
            case DONE -> {
            }
        }
        return step != Step.DONE;
    }

    // Why the elytra is still worn now the stop is over as a sentence for chat. Null when it is
    // fit to fly on.
    public String stillWorn() {
        return stillWorn;
    }

    // Lets go of whatever the stop holds. A container left down is named in chat.
    public void end() {
        restock.endErrand();
        slots.restoreIfMine();
        stopMending();
        room.giveBack();
        step = Step.DONE;
    }

    // AutoMend throws until the elytra is whole or stops winning back durability.
    private void mend() {
        AutoMend autoMend = Modules.get(AutoMend.class);
        ItemStack worn = MC.player.getItemBySlot(EquipmentSlot.CHEST);
        if (!mending) {
            if (!needs.mend() || autoMend == null || !wornBelow(needs.line()) || !mendable()) {
                step = Step.CHANGE;
                return;
            }
            if (!autoMend.wouldMend(EquipmentSlot.CHEST, needs.line())) {
                stillWorn = "AutoMend would not mend the elytra past Elytra line. " + MEND_ROWS + ".";
                step = Step.CHANGE;
                return;
            }
            mending = true;
            leastDamage = worn.getDamageValue();
            stallTicks = 0;
            if (!autoMend.isEnabled()) {
                autoMend.setEnabled(true);
                mendSwitchedOn = autoMend.isEnabled();
            }
        }
        bringBottlesToHand();
        if (worn.getDamageValue() < leastDamage) {
            leastDamage = worn.getDamageValue();
            stallTicks = 0;
        } else {
            stallTicks++;
        }
        boolean whole = !worn.isDamaged();
        boolean noBottles = carried(Items.EXPERIENCE_BOTTLE) == 0;
        if (!whole && autoMend.isEnabled() && !noBottles && stallTicks < MEND_STALL_TICKS) {
            return;
        }
        if (!whole && wornBelow(needs.line())) {
            stillWorn = noBottles ? "The bottles ran out before the elytra was mended."
                : "AutoMend stopped before the elytra passed Elytra line.";
        }
        stopMending();
        step = Step.CHANGE;
    }

    private void stopMending() {
        AutoMend autoMend = Modules.get(AutoMend.class);
        if (mendSwitchedOn && autoMend != null) {
            autoMend.setEnabled(false);
        }
        mending = false;
        mendSwitchedOn = false;
    }

    // An elytra that is still worn gives way to the freshest one carried.
    private void change() {
        if (!wornBelow(needs.line())) {
            stillWorn = null;
            return;
        }
        int fresher = fresherCarried();
        if (fresher != -1 && InventoryUtil.cursorFree()) {
            InventoryUtil.swap(InventoryUtil.networkSlot(fresher), InventoryUtil.CHEST_SLOT);
            stillWorn = wornBelow(needs.line()) ? "The freshest elytra you carry is worn as well." : null;
        } else if (stillWorn == null) {
            stillWorn = "Nothing could mend or change the elytra.";
        }
    }

    // AutoMend throws from the hotbar. A stack of bottles in the backpack comes up whenever the
    // hotbar runs dry. A full hotbar lends a slot until the stop is over.
    private void bringBottlesToHand() {
        if (InventoryUtil.countWithOffhand(Items.EXPERIENCE_BOTTLE, InventoryUtil.HOTBAR_SIZE) > 0
            || !InventoryUtil.cursorFree()) {
            return;
        }
        int from = InventoryUtil.findSlot(Items.EXPERIENCE_BOTTLE, InventoryUtil.WHOLE_INVENTORY);
        if (from >= InventoryUtil.HOTBAR_SIZE) {
            room.bring(from, stack -> false);
        }
    }

    // What the errand takes out of a container. Rockets up to the count kept and a stack of
    // bottles to mend with and an elytra fresher than the one worn.
    private boolean wanted(ItemStack stack) {
        return rocketsWanted(needs).test(stack) || bottlesWanted(needs).test(stack)
            || elytraWanted(needs).test(stack);
    }

    private static Predicate<ItemStack> rocketsWanted(Needs asked) {
        return stack -> stack.is(Items.FIREWORK_ROCKET) && rocketsLow(asked);
    }

    private static Predicate<ItemStack> bottlesWanted(Needs asked) {
        return stack -> asked.mend() && stack.is(Items.EXPERIENCE_BOTTLE) && mendable()
            && carried(Items.EXPERIENCE_BOTTLE) < BOTTLES;
    }

    private static Predicate<ItemStack> elytraWanted(Needs asked) {
        return stack -> stack.is(Items.ELYTRA) && wornBelow(asked.line()) && fresherCarried() == -1
            && lastsLonger(stack, MC.player.getItemBySlot(EquipmentSlot.CHEST));
    }

    // True when a container the errand would set down holds a stack the test accepts.
    private boolean couldFetch(Needs asked, Predicate<ItemStack> wanted) {
        return asked.restock() && MC.player.getInventory().getFreeSlot() != -1
            && restock.couldRun(wanted, true, true);
    }

    // True when AutoMend would mend the worn elytra past the line with the bottles carried or ones
    // a restock fetches.
    private boolean couldMend(Needs asked) {
        AutoMend autoMend = Modules.get(AutoMend.class);
        if (!asked.mend() || autoMend == null || Modules.lockHolds(autoMend) || !mendable()
            || !autoMend.wouldMend(EquipmentSlot.CHEST, asked.line())) {
            return false;
        }
        return carried(Items.EXPERIENCE_BOTTLE) > 0 || couldFetch(asked, bottlesWanted(asked));
    }

    // Only an elytra with Mending takes experience.
    private static boolean mendable() {
        ItemStack worn = MC.player.getItemBySlot(EquipmentSlot.CHEST);
        return worn.is(Items.ELYTRA) && ItemUtil.enchantLevel(Enchantments.MENDING, worn) > 0;
    }

    private static boolean rocketsLow(Needs asked) {
        return asked.keepRockets() > 0 && carried(Items.FIREWORK_ROCKET) < asked.keepRockets();
    }

    private static boolean wornBelow(double line) {
        ItemStack worn = MC.player.getItemBySlot(EquipmentSlot.CHEST);
        return worn.is(Items.ELYTRA) && ItemUtil.wornBelow(worn, line);
    }

    // Every one of an item in the inventory and the offhand.
    private static int carried(Item item) {
        return InventoryUtil.countWithOffhand(item, InventoryUtil.WHOLE_INVENTORY);
    }

    // The elytra carried that ElytraFly would put on in place of the worn one. Minus one when
    // none lasts longer.
    private static int fresherCarried() {
        return ElytraFly.fresherElytra(MC.player.getItemBySlot(EquipmentSlot.CHEST));
    }

    private static boolean lastsLonger(ItemStack stack, ItemStack than) {
        return ItemUtil.usesLeft(stack) > ItemUtil.usesLeft(than);
    }

    // A block to stand on in mid air. A whole cube that stays where it is put and is no container
    // and does not hurt to stand on.
    public static int platformSlot() {
        return InventoryUtil.hotbarSlot(stack -> {
            Block block = Block.byItem(stack.getItem());
            return ItemUtil.isFullBlock(stack.getItem()) && !ItemUtil.isShulkerBox(stack.getItem())
                && !(block instanceof Fallable) && !BlockUtil.harmful(block.defaultBlockState());
        });
    }
}
