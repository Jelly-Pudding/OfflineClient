package com.jellypudding.offlineclient.module;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.Cooldowns;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.Hands;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

// Shared by modules that use the item in hand on the mobs in reach. The nearest one that is
// wanted gets a click each tick and is then left alone for as long as the subclass says.
public abstract class EntityUseAura extends Module {

    // A mob that turned the click down is tried again after this long.
    private static final int RETRY_TICKS = 20;

    protected final NumberSetting range = new NumberSetting("Range",
        "How close a mob has to be.", 4.5, 1, 6, 0.1, " blocks");
    protected final EnumSetting<InteractionHand> hand = new EnumSetting<>("Hand",
        "Which hand holds the item.", InteractionHand.MAIN_HAND);
    protected final BoolSetting rotate = new BoolSetting("Rotate",
        "Turns towards the mob on the server side.", true);

    // Mobs clicked lately by entity id. One comes back once its wait is over.
    private final Cooldowns<Integer> clicked = new Cooldowns<>();
    private int count;

    protected EntityUseAura(String name, String description) {
        super(name, description, Category.WORLD);
    }

    // Whether the item in hand should be used on the entity.
    protected abstract boolean wanted(Entity entity, ItemStack held);

    // Ticks before a mob that took the click gets another.
    protected abstract int restTicks(Entity entity);

    // How many clicks were taken since the module went on.
    protected final int count() {
        return count;
    }

    @Override
    protected void onEnable() {
        clicked.clear();
        count = 0;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (mc.gui.screen() != null || Hands.taken()) {
            return;
        }
        clicked.tick();
        InteractionHand useHand = hand.getValue();
        ItemStack held = mc.player.getItemInHand(useHand);
        if (held.isEmpty()) {
            return;
        }
        Entity target = EntityUtil.nearest(range.getValue(),
            entity -> entity.isAlive() && !clicked.contains(entity.getId()) && wanted(entity, held));
        if (target == null) {
            return;
        }
        boolean taken = EntityUtil.interact(target, useHand, rotate.isOn());
        if (taken) {
            count++;
        }
        clicked.put(target.getId(), taken ? restTicks(target) : RETRY_TICKS);
    }
}
