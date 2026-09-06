package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.DamageUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.Swap;
import net.minecraft.world.item.Items;

public final class AutoTotem extends Module {

    // Gliding into a wall kills outright. A totem is always worth holding.
    private static final double ELYTRA_TRIGGER_SPEED = 0.5;

    // Health points in one heart.
    private static final float HEART = 2;

    private final NumberSetting health = new NumberSetting("Health",
        "Puts a totem in your offhand once you drop to this many hearts. Zero keeps one there at all times.",
        0, 0, 10, 0.5, " hearts");
    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks to wait before equipping the next totem.", 0, 0, 20, 1, " ticks");
    private final BoolSetting explosions = new BoolSetting("Explosions",
        "Equip early when a nearby crystal or bed or anchor could drop you to the health line.", true);
    private final NumberSetting blastRange = new NumberSetting("Blast range",
        "How far away a charge is counted as a threat.", 8, 2, 16, 0.5, " blocks")
        .under(explosions);
    private final BoolSetting melee = new BoolSetting("Melee",
        "Equip early when an enemy within five blocks holds a weapon that could do it.", true);
    private final BoolSetting fall = new BoolSetting("Fall",
        "Equip early when the fall you are in would do it.", true);
    private final BoolSetting elytra = new BoolSetting("Elytra",
        "Always hold a totem whilst gliding at speed.", true);

    private final InventoryUtil.StrandedStack cursor = new InventoryUtil.StrandedStack();
    private int totems;
    private int timer;
    private boolean hadTotem;
    private boolean locked;

    private final BoolSetting counter = new BoolSetting("Totem counter",
        "Show how many totems you have left next to the name.", true);

    public AutoTotem() {
        super("AutoTotem", "Keeps a totem of undying in your offhand.", Category.COMBAT);
        addSettings(health, delay, explosions, blastRange, melee, fall, elytra, counter);
        searchTags("totem", "pop");
    }

    @Override
    public String getSuffix() {
        return counter.isOn() ? totems + " left" : null;
    }

    // True whilst the offhand is claimed for a totem. Offhand steps aside for it.
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
        if (!inGame() || mc.player.isSpectator()) {
            return;
        }
        if (!cursor.recover()) {
            return;
        }
        totems = countTotems();
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
        int totemSlot = findTotem();
        if (totemSlot == -1 || !locked) {
            return;
        }

        if (!InventoryUtil.canClick()) {
            return;
        }
        if (!InventoryUtil.carried().isEmpty()) {
            return;
        }

        if (timer > 0) {
            timer--;
            return;
        }

        if (InventoryUtil.swap(totemSlot, InventoryUtil.OFFHAND_SLOT) == Swap.STRANDED) {
            // The item the totem replaced had nowhere to go.
            cursor.hold(totemSlot);
        }
    }

    // True when the health line is already crossed or something already in the world
    // would push the player under it before the next tick could react.
    private boolean threatened(float minHealth) {
        if (elytra.isOn() && mc.player.isFallFlying()
            && mc.player.getDeltaMovement().length() > ELYTRA_TRIGGER_SPEED) {
            return true;
        }
        float incoming = DamageUtil.possibleIncoming(blastRange.getValue(),
            explosions.isOn(), melee.isOn(), fall.isOn());
        return EntityUtil.totalHealth(mc.player) - incoming <= minHealth * HEART;
    }

    // Every totem the player owns. The one already equipped counts.
    private int countTotems() {
        int offhand = mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING)
            ? mc.player.getOffhandItem().getCount() : 0;
        return offhand + InventoryUtil.count(Items.TOTEM_OF_UNDYING, InventoryUtil.WHOLE_INVENTORY);
    }

    // Network slot of the first totem in the inventory. Minus one when there is none.
    private int findTotem() {
        int slot = InventoryUtil.findSlot(Items.TOTEM_OF_UNDYING, InventoryUtil.WHOLE_INVENTORY);
        return slot == -1 ? -1 : InventoryUtil.networkSlot(slot);
    }
}
