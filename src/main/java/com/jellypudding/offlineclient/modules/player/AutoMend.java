package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.ItemUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;

import java.util.List;

// Repairs mending gear. Bottles only reach the pieces that are worn or held
// so the offhand mode is the way to mend items that sit in the inventory.
public final class AutoMend extends Module {

    // Straight down. The throw direction rides on the use packet.
    private static final float DOWN_PITCH = 90;

    private static final EquipmentSlot[] REPAIRABLE = {
        EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND,
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    public enum Mode { BOTTLES, ALL_BOTTLES, OFFHAND }

    private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
        "How the gear is repaired.", Mode.BOTTLES)
        .describe(Mode.BOTTLES, "Throws experience bottles down whilst worn or held mending gear is damaged.")
        .describe(Mode.ALL_BOTTLES,
            "Throws every experience bottle in the hotbar whatever state your gear is in. "
                + "Good for levels or for gear kept in the inventory.")
        .describe(Mode.OFFHAND,
            "Moves damaged mending items from your inventory into the offhand one at a time "
                + "so any experience you pick up repairs them.");
    private final NumberSetting threshold = new NumberSetting("Threshold",
        "Start throwing once a piece drops below this much durability.", 99, 1, 100, 1, "%")
        .min(1).max(100)
        .under(mode, Mode.BOTTLES);
    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks between throws.", 4, 0, 20, 1, " ticks")
        .under(mode, Mode.BOTTLES, Mode.ALL_BOTTLES);
    private final BoolSetting groundOnly = new BoolSetting("Ground only",
        "Only throw whilst standing on the ground.", true)
        .under(mode, Mode.BOTTLES, Mode.ALL_BOTTLES);
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
    private int timer;
    private int bottles;
    // Minus one whilst nothing worn or held carries Mending.
    private int worstPercent = -1;
    // True once the offhand mode has put something in the offhand.
    private boolean moved;
    // Set for the one packet that carries the downward throw.
    // Read from the packet thread.
    private volatile boolean throwing;

    public AutoMend() {
        super("AutoMend", "Repairs your mending gear with experience.", Category.PLAYER);
        addSettings(mode, threshold, delay, groundOnly, skip, force, autoDisable);
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
        moved = false;
        throwing = false;
    }

    @Override
    protected void onDisable() {
        slots.restoreIfMine();
        throwing = false;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.player.isSpectator()) {
            return;
        }
        if (mode.is(Mode.OFFHAND)) {
            slots.restoreIfMine();
            tickOffhand();
            return;
        }
        worstPercent = lowestDurability();
        bottles = countBottles();
        if (mode.is(Mode.ALL_BOTTLES) ? bottles == 0 : worstPercent < 0) {
            slots.restoreIfMine();
            if (mode.is(Mode.ALL_BOTTLES) && autoDisable.isOn()) {
                setEnabled(false);
            }
            return;
        }
        if (mode.is(Mode.BOTTLES) && worstPercent >= threshold.getInt()) {
            slots.restoreIfMine();
            if (autoDisable.isOn()) {
                if (bottles > 0) {
                    ChatUtil.message("§aEverything is repaired.");
                }
                setEnabled(false);
            }
            return;
        }
        if (!InventoryUtil.inventoryFree()) {
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

    // A bottle already in the offhand is thrown from there with no swap.
    private void throwBottle() {
        InteractionHand hand = InteractionHand.OFF_HAND;
        if (!mc.player.getOffhandItem().is(Items.EXPERIENCE_BOTTLE)) {
            int slot = findBottle();
            if (slot == -1) {
                slots.restore();
                return;
            }
            slots.select(slot);
            hand = InteractionHand.MAIN_HAND;
        }
        throwing = true;
        try {
            mc.gameMode.useItem(mc.player, hand);
        } finally {
            throwing = false;
        }
        timer = delay.getInt();
    }

    // The use packet carries the rotation the server throws along.
    @Subscribe(priority = 100)
    private void onPacketSend(PacketSendEvent event) {
        if (!throwing || !(event.getPacket() instanceof ServerboundUseItemPacket packet)) {
            return;
        }
        event.setPacket(new ServerboundUseItemPacket(packet.getHand(), packet.getSequence(),
            packet.getYRot(), DOWN_PITCH));
    }

    // Keeps one damaged mending item in the offhand until it is whole and
    // then brings in the next. The last one goes home once nothing is left.
    private void tickOffhand() {
        ItemStack offhand = mc.player.getOffhandItem();
        worstPercent = isMending(offhand) ? percentLeft(offhand) : -1;
        if (!InventoryUtil.inventoryFree()) {
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

    // The first damaged mending item in the inventory that is not skipped.
    private int findDamaged() {
        return InventoryUtil.findSlot(stack -> isMending(stack) && stack.getDamageValue() > 0
            && !skip.contains(stack.getItem()), InventoryUtil.WHOLE_INVENTORY);
    }

    // Minus one when no equipped piece carries Mending.
    private int lowestDurability() {
        int lowest = -1;
        for (EquipmentSlot slot : REPAIRABLE) {
            ItemStack stack = slot == EquipmentSlot.MAINHAND ? heldTool() : mc.player.getItemBySlot(slot);
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
        return (int) (100 - stack.getDamageValue() * 100.0 / stack.getMaxDamage());
    }

    // The tool stays in the slot the bottle swap came from.
    private ItemStack heldTool() {
        int home = slots.previousSlot();
        return home == -1 ? mc.player.getMainHandItem() : mc.player.getInventory().getItem(home);
    }

    // Hotbar bottles plus any in the offhand.
    private int countBottles() {
        int count = InventoryUtil.count(Items.EXPERIENCE_BOTTLE, InventoryUtil.HOTBAR_SIZE);
        ItemStack offhand = mc.player.getOffhandItem();
        return offhand.is(Items.EXPERIENCE_BOTTLE) ? count + offhand.getCount() : count;
    }

    private int findBottle() {
        return InventoryUtil.findSlot(Items.EXPERIENCE_BOTTLE, InventoryUtil.HOTBAR_SIZE);
    }
}
