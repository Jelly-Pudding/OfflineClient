package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.DamageUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.Hands;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.MenuClicks;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.Totems;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class AutoTotem extends Module {

    // Gliding into a wall kills outright. A totem is always worth holding.
    private static final double ELYTRA_TRIGGER_SPEED = 0.5;

    private final NumberSetting health = new NumberSetting("Health",
        "Puts a totem in your offhand once you drop to this many hearts. Zero keeps one there at all times.",
        0, 0, 10, 0.5, " hearts");
    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks to wait before equipping the next totem.", 0, 0, 20, 1, " ticks");
    private final BoolSetting explosions = new BoolSetting("Explosions",
        "Equips early when a nearby crystal or bed or anchor could drop you to the health line.", true);
    private final NumberSetting blastRange = new NumberSetting("Blast range",
        "How far away a charge is counted as a threat.", 8, 2, 16, 0.5, " blocks")
        .under(explosions);
    private final BoolSetting melee = new BoolSetting("Melee",
        "Equips early when an enemy within five blocks holds a weapon that could do it.", true);
    private final BoolSetting fall = new BoolSetting("Fall",
        "Equips early when the fall you are in would do it.", true);
    private final BoolSetting elytra = new BoolSetting("Elytra",
        "Always holds a totem whilst you glide at speed.", true);
    private final BoolSetting enderChest = new BoolSetting("Kept ender chest",
        "Takes a totem from the ender chest ChestLink keeps open once your inventory has none.", true);

    private final InventoryUtil.StrandedStack cursor = new InventoryUtil.StrandedStack();
    private int totems;
    private int timer;
    private boolean hadTotem;
    private boolean locked;

    private final BoolSetting counter = new BoolSetting("Totem counter",
        "Shows how many totems you have left next to the name.", true);

    public AutoTotem() {
        super("AutoTotem", "Keeps a totem of undying in your offhand.", Category.COMBAT);
        addSettings(health, delay, explosions, blastRange, melee, fall, elytra, enderChest, counter);
        searchTags("totem", "pop");
    }

    @Override
    public String getSuffix() {
        return counter.isOn() ? totems + " left" : null;
    }

    // Offhand steps aside whilst the offhand is claimed for a totem.
    public boolean isLocked() {
        return isEnabled() && locked;
    }

    @Override
    protected void onEnable() {
        timer = 0;
        hadTotem = false;
        locked = false;
    }

    @Override
    protected void onDisable() {
        if (inGame()) {
            cursor.giveBack();
        }
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (Hands.tied()) {
            return;
        }
        if (!cursor.recover()) {
            return;
        }
        AbstractContainerMenu chest = enderChest.isOn() ? Modules.keptEnderChest() : null;
        totems = Totems.carried() + (chest == null ? 0 : chestTotems(chest));
        float minHealth = health.getFloat();
        locked = totems > 0 && (minHealth <= 0 || threatened(minHealth));

        if (mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) {
            hadTotem = true;
            return;
        }
        if (hadTotem) {
            timer = delay.getInt();
            hadTotem = false;
        }
        int totemSlot = InventoryUtil.findSlot(Items.TOTEM_OF_UNDYING, InventoryUtil.WHOLE_INVENTORY);
        int chestSlot = totemSlot == -1 && chest != null
            ? MenuClicks.firstContainerSlot(chest, stack -> stack.is(Items.TOTEM_OF_UNDYING)) : -1;
        if (totemSlot == -1 && chestSlot == -1 || !locked) {
            return;
        }

        if (timer > 0) {
            timer--;
            return;
        }
        if (totemSlot != -1) {
            InventoryUtil.toOffhand(totemSlot, cursor);
        } else if (chest.getCarried().isEmpty()) {
            MenuClicks.click(chest, chestSlot, Inventory.SLOT_OFFHAND, ContainerInput.SWAP);
            // Whatever the offhand held goes on into your inventory when it has room.
            if (!chest.slots.get(chestSlot).getItem().isEmpty()) {
                MenuClicks.quickMove(chest, chestSlot);
            }
        }
    }

    private static int chestTotems(AbstractContainerMenu chest) {
        int count = 0;
        for (int slot : MenuClicks.containerSlots(chest)) {
            ItemStack stack = chest.slots.get(slot).getItem();
            if (stack.is(Items.TOTEM_OF_UNDYING)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private boolean threatened(float minHealth) {
        if (elytra.isOn() && mc.player.isFallFlying()
            && mc.player.getDeltaMovement().length() > ELYTRA_TRIGGER_SPEED) {
            return true;
        }
        return DamageUtil.healthAfterIncoming(blastRange.getValue(), explosions.isOn(), melee.isOn(),
            fall.isOn()) <= minHealth * EntityUtil.HEART;
    }
}
