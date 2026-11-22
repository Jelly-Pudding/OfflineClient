package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.Hands;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.ItemUtil;
import com.jellypudding.offlineclient.util.RotationManager;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;

import java.util.List;

// Bottles only reach the pieces that are worn or held. The offhand mode is the way to mend items
// that sit in the inventory.
public final class AutoMend extends Module {

    public enum Mode { BOTTLES, ALL_BOTTLES, OFFHAND }

    public enum Gear {
        ARMOUR(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET),
        HANDS(EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND),
        BOTH(EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND,
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET);

        private final List<EquipmentSlot> slots;

        Gear(EquipmentSlot... slots) {
            this.slots = List.of(slots);
        }
    }

    private static final double PERCENT = 100;

    private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
        "How the gear is repaired.", Mode.BOTTLES)
        .describe(Mode.BOTTLES, "Throws experience bottles down whilst worn or held mending gear is damaged.")
        .describe(Mode.ALL_BOTTLES,
            "Throws every experience bottle in the hotbar whatever state your gear is in. "
                + "Good for levels or for gear kept in the inventory.")
        .describe(Mode.OFFHAND,
            "Moves damaged mending items into your offhand one at a time to soak up experience.");
    private final EnumSetting<Gear> gear = new EnumSetting<>("Gear",
        "Which mending pieces are watched.", Gear.BOTH)
        .describe(Gear.ARMOUR, "Only the armour you wear.")
        .describe(Gear.HANDS, "Only what you hold in either hand.")
        .describe(Gear.BOTH, "The armour you wear and what you hold.")
        .under(mode, Mode.BOTTLES);
    private final NumberSetting startBelow = new NumberSetting("Start below",
        "Throwing starts once a watched piece drops below this much durability.", 99, 1, 100, 1, "%")
        .max(100)
        .under(mode, Mode.BOTTLES);
    private final NumberSetting repairTo = new NumberSetting("Repair up to",
        "Throwing stops once every watched piece is back to this much durability or to Start below when"
            + " that is higher.", 99, 1, 100, 1, "%")
        .max(100)
        .under(mode, Mode.BOTTLES);
    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks between throws.", 4, 0, 20, 1, " ticks")
        .under(mode, Mode.BOTTLES, Mode.ALL_BOTTLES);
    private final BoolSetting groundOnly = new BoolSetting("Ground only",
        "Only throws whilst you stand on the ground.", true)
        .under(mode, Mode.BOTTLES, Mode.ALL_BOTTLES);
    private final BoolSetting fromInventory = new BoolSetting("Take from inventory",
        "Borrows bottles from the rest of the inventory when the hotbar and offhand have none.", true)
        .under(mode, Mode.BOTTLES);
    private final RegistryListSetting<Item> skip = new RegistryListSetting<>("Skip",
        "Items that never go into the offhand. Click to pick them.", BuiltInRegistries.ITEM, List.of())
        .under(mode, Mode.OFFHAND);
    private final BoolSetting force = new BoolSetting("Force",
        "Puts the damaged item in the offhand even when something else is already there. "
            + "Off waits until the offhand is empty or holds mending gear.", false)
        .under(mode, Mode.OFFHAND);
    private final BoolSetting autoDisable = new BoolSetting("Auto off",
        "Switches off once the gear is repaired or once no bottles are left to throw.", true);

    private final InventoryUtil.SlotSwap slots = new InventoryUtil.SlotSwap();
    private final InventoryUtil.HotbarLoan loan = new InventoryUtil.HotbarLoan();
    private int timer;
    private int bottles;
    // Minus one whilst nothing watched carries Mending.
    private int worstPercent = -1;
    private boolean mending;
    private boolean threw;
    private boolean moved;

    public AutoMend() {
        super("AutoMend", "Repairs your mending gear with experience.", Category.PLAYER);
        addSettings(mode, gear, startBelow, repairTo, delay, groundOnly, fromInventory, skip, force, autoDisable);
        searchTags("mending", "xp", "experience bottle", "repair", "exp thrower");
    }

    @Override
    public String getSuffix() {
        if (mode.is(Mode.OFFHAND)) {
            return worstPercent < 0 ? "nothing to mend" : worstPercent + "%";
        }
        if (mode.is(Mode.BOTTLES) && worstPercent < 0) {
            return "no mending gear";
        }
        if (bottles == 0) {
            return "no bottles";
        }
        return mode.is(Mode.ALL_BOTTLES) ? bottles + " left" : worstPercent + "% " + bottles + " left";
    }

    @Override
    protected void onEnable() {
        timer = 0;
        mending = false;
        threw = false;
        moved = false;
    }

    @Override
    protected void onDisable() {
        slots.restoreIfMine();
        loan.giveBack(false);
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (mc.player.isSpectator()) {
            return;
        }
        if (mc.player.isDeadOrDying()) {
            // Respawn hands out a fresh inventory.
            loan.forget();
            return;
        }
        if (!borrows()) {
            loan.giveBack(false);
        }
        if (mode.is(Mode.OFFHAND)) {
            tickOffhand();
            return;
        }
        worstPercent = lowestDurability();
        bottles = countBottles();
        if (mode.is(Mode.BOTTLES) && !needsMending()) {
            loan.giveBack(false);
            // With no mending gear on it waits for some to be put on.
            if (autoDisable.isOn() && worstPercent >= 0) {
                ChatUtil.message(threw ? "§aEverything is repaired." : "§aNothing needs mending.");
                setEnabled(false);
            }
            return;
        }
        if (bottles == 0) {
            loan.giveBack(false);
            if (autoDisable.isOn()) {
                ChatUtil.message("§cNo experience bottles are left to throw.");
                setEnabled(false);
            }
            return;
        }
        if (!handsFree()) {
            return;
        }
        if (groundOnly.isOn() && !mc.player.onGround()) {
            return;
        }
        if (timer > 0) {
            timer--;
            return;
        }
        throwBottle();
    }

    private boolean needsMending() {
        if (worstPercent < 0) {
            mending = false;
        } else if (worstPercent < startBelow.getInt()) {
            mending = true;
        } else if (worstPercent >= stopsAt()) {
            mending = false;
        }
        return mending;
    }

    private int stopsAt() {
        return Math.max(repairTo.getInt(), startBelow.getInt());
    }

    // The line is a share of the whole.
    public boolean wouldMend(EquipmentSlot slot, double line) {
        ItemStack worn = mc.player.getItemBySlot(slot);
        if (!isMending(worn)) {
            return false;
        }
        return switch (mode.getValue()) {
            case ALL_BOTTLES -> true;
            case BOTTLES -> gear.getValue().slots.contains(slot) && percentLeft(worn) < startBelow.getInt()
                && stopsAt() / PERCENT > line;
            case OFFHAND -> false;
        };
    }

    // A slot change would end the meal.
    private boolean handsFree() {
        return InventoryUtil.inventoryFree() && !Hands.slotsTaken() && !mc.player.isUsingItem();
    }

    // A hotbar bottle is held for the throw alone and the slot goes back in the same tick. The
    // server never sees the held item change between its ticks and a fight keeps its weapon.
    private void throwBottle() {
        boolean offhand = mc.player.getOffhandItem().is(Items.EXPERIENCE_BOTTLE);
        if (!offhand) {
            int slot = bottleSlot();
            if (slot == -1) {
                return;
            }
            slots.select(slot);
        }
        InteractionHand hand = offhand ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        try {
            RotationManager.whileFacing(mc.player.getYRot(), RotationManager.STRAIGHT_DOWN,
                () -> mc.gameMode.useItem(mc.player, hand).consumesAction());
        } finally {
            slots.restore();
        }
        threw = true;
        timer = delay.getInt();
    }

    private int bottleSlot() {
        int slot = InventoryUtil.hotbarSlot(stack -> stack.is(Items.EXPERIENCE_BOTTLE));
        if (slot != -1 || !borrows()) {
            return slot;
        }
        int stored = InventoryUtil.findSlot(Items.EXPERIENCE_BOTTLE, InventoryUtil.WHOLE_INVENTORY);
        return loan.borrow(stored, InventoryUtil.selectedSlot());
    }

    private void tickOffhand() {
        ItemStack offhand = mc.player.getOffhandItem();
        worstPercent = isMending(offhand) ? percentLeft(offhand) : -1;
        if (!handsFree()) {
            return;
        }
        if (!offhand.isEmpty()) {
            if (isMending(offhand)) {
                if (offhand.getDamageValue() > 0) {
                    return;
                }
            } else if (!force.isOn()) {
                return;
            }
        }
        int slot = findDamaged();
        if (slot != -1) {
            InventoryUtil.swap(InventoryUtil.networkSlot(slot), InventoryUtil.OFFHAND_SLOT);
            moved = true;
            return;
        }
        if (!autoDisable.isOn()) {
            return;
        }
        if (moved) {
            int empty = InventoryUtil.findSlot(ItemStack::isEmpty, InventoryUtil.WHOLE_INVENTORY);
            if (empty != -1) {
                InventoryUtil.swap(InventoryUtil.OFFHAND_SLOT, InventoryUtil.networkSlot(empty));
            }
        }
        ChatUtil.message("§aEverything is repaired.");
        setEnabled(false);
    }

    private int findDamaged() {
        return InventoryUtil.findSlot(stack -> isMending(stack) && stack.getDamageValue() > 0
            && !skip.contains(stack.getItem()), InventoryUtil.WHOLE_INVENTORY);
    }

    private int lowestDurability() {
        int lowest = -1;
        for (EquipmentSlot slot : gear.getValue().slots) {
            ItemStack stack = mc.player.getItemBySlot(slot);
            if (!isMending(stack)) {
                continue;
            }
            int percent = percentLeft(stack);
            lowest = lowest < 0 ? percent : Math.min(lowest, percent);
        }
        return lowest;
    }

    private static boolean isMending(ItemStack stack) {
        if (stack.isEmpty() || !stack.isDamageableItem() || stack.getMaxDamage() <= 0) {
            return false;
        }
        return ItemUtil.enchantLevel(Enchantments.MENDING, stack) > 0;
    }

    private static int percentLeft(ItemStack stack) {
        return (int) ItemUtil.durabilityPercent(stack);
    }

    private boolean borrows() {
        return mode.is(Mode.BOTTLES) && fromInventory.isOn();
    }

    private int countBottles() {
        return InventoryUtil.countWithOffhand(Items.EXPERIENCE_BOTTLE,
            borrows() ? InventoryUtil.WHOLE_INVENTORY : InventoryUtil.HOTBAR_SIZE);
    }
}
