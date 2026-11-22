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

public abstract class EntityUseAura extends Module {

    private static final int RETRY_TICKS = 20;

    protected final NumberSetting range = new NumberSetting("Range",
        "How close a mob has to be.", 4.5, 1, 6, 0.1, " blocks");
    protected final EnumSetting<InteractionHand> hand = new EnumSetting<>("Hand",
        "Which hand holds the item.", InteractionHand.MAIN_HAND);
    protected final BoolSetting rotate = new BoolSetting("Rotate",
        "Turns towards the mob on the server side.", true);

    private final Cooldowns<Integer> clicked = new Cooldowns<>();
    private int count;

    protected EntityUseAura(String name, String description) {
        super(name, description, Category.WORLD);
    }

    protected abstract boolean wanted(Entity entity, ItemStack held);

    protected abstract int restTicks(Entity entity);

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
