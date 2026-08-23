package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.ItemUtil;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;

/**
 * Throws experience bottles down whilst worn or held mending gear is damaged.
 * Only equipped items soak up the experience.
 */
public final class AutoMend extends Module {

    // Straight down. The throw direction rides on the use packet.
    private static final float DOWN_PITCH = 90;

    private static final EquipmentSlot[] REPAIRABLE = {
        EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND,
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    private final NumberSetting threshold = new NumberSetting("Threshold",
        "Start throwing once a piece drops below this much durability.", 99, 1, 100, 1, "%")
        .min(1).max(100);
    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks between throws.", 4, 0, 20, 1, " ticks");
    private final BoolSetting groundOnly = new BoolSetting("Ground only",
        "Only throw whilst standing on the ground.", true);
    private final BoolSetting autoDisable = new BoolSetting("Auto off",
        "Switch off once everything is repaired.", true);

    private final InventoryUtil.SlotSwap slots = new InventoryUtil.SlotSwap();
    private int timer;
    private int bottles;
    private int worstPercent = 100;
    // Set for the one packet that carries the downward throw.
    private boolean throwing;

    public AutoMend() {
        super("AutoMend", "Repairs your mending gear with experience bottles.", Category.PLAYER);
        addSettings(threshold, delay, groundOnly, autoDisable);
        searchTags("mending", "xp", "experience bottle", "repair");
    }

    @Override
    public String getSuffix() {
        if (bottles == 0) {
            return "no bottles";
        }
        return worstPercent + "% " + bottles + " left";
    }

    @Override
    protected void onEnable() {
        timer = 0;
        throwing = false;
    }

    @Override
    protected void onDisable() {
        slots.restore();
        throwing = false;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.player.isSpectator()) {
            return;
        }
        worstPercent = lowestDurability();
        bottles = countBottles();

        if (worstPercent >= threshold.getInt()) {
            slots.restore();
            if (autoDisable.isOn()) {
                ChatUtil.message("§aEverything is repaired.");
                setEnabled(false);
            }
            return;
        }
        // Slot swaps and item use both need the survival inventory.
        if (mc.gui.screen() != null || mc.player.containerMenu.containerId != 0
            || !mc.player.containerMenu.getCarried().isEmpty()) {
            return;
        }
        if (groundOnly.isOn() && !mc.player.onGround()) {
            return;
        }
        if (timer > 0) {
            timer--;
            return;
        }

        int slot = findBottle();
        if (slot == -1) {
            slots.restore();
            return;
        }
        slots.select(slot);
        throwing = true;
        try {
            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
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

    private int lowestDurability() {
        int lowest = 100;
        for (EquipmentSlot slot : REPAIRABLE) {
            ItemStack stack = mc.player.getItemBySlot(slot);
            if (stack.isEmpty() || !stack.isDamageableItem() || stack.getMaxDamage() <= 0) {
                continue;
            }
            if (ItemUtil.enchantLevel(Enchantments.MENDING, stack) <= 0) {
                continue;
            }
            int percent = 100 - stack.getDamageValue() * 100 / stack.getMaxDamage();
            lowest = Math.min(lowest, percent);
        }
        return lowest;
    }

    private int countBottles() {
        int total = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.is(Items.EXPERIENCE_BOTTLE)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private int findBottle() {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).is(Items.EXPERIENCE_BOTTLE)) {
                return i;
            }
        }
        return -1;
    }
}
