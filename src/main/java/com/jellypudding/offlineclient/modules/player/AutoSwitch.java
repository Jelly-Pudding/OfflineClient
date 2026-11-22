package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.BlockPlaceEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ChoiceListSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.Hands;
import com.jellypudding.offlineclient.util.InventoryUtil;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public final class AutoSwitch extends Module {

    public enum When { TIMER, PLACED }

    public enum Order { IN_TURN, RANDOM }

    private final EnumSetting<When> when = new EnumSetting<>("Switch",
        "When the hand moves on to another slot.", When.TIMER)
        .describe(When.TIMER, "Every few ticks.")
        .describe(When.PLACED, "After each block you place whilst you hold one of the picked slots. Only slots"
            + " that hold a block take part.");
    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks the hand rests on each slot.", 1, 1, 20, 1, " ticks")
        .under(when, When.TIMER);
    private final EnumSetting<Order> order = new EnumSetting<>("Order",
        "Which picked slot comes next.", Order.IN_TURN)
        .describe(Order.IN_TURN, "The next picked slot along the hotbar.")
        .describe(Order.RANDOM, "Any picked slot at random.");
    private final BoolSetting noRepeat = new BoolSetting("Never the same",
        "A random pick never lands on the slot you already hold.", true)
        .under(order, Order.RANDOM);
    private final ChoiceListSetting slots = new ChoiceListSetting("Slots",
        "The hotbar slots that take part numbered from the left. Click to pick them.",
        () -> InventoryUtil.HOTBAR_NAMES, InventoryUtil.HOTBAR_NAMES);

    private int timer;
    private boolean placed;

    public AutoSwitch() {
        super("AutoSwitch", "Switches your hotbar slot on its own on a timer or after each placement.",
            Category.PLAYER);
        addSettings(when, delay, order, noRepeat, slots);
        searchTags("auto switch", "hotbar cycle", "troll", "random block", "texture", "palette");
    }

    @Override
    protected void onEnable() {
        timer = 0;
        placed = false;
    }

    @Subscribe
    private void onBlockPlace(BlockPlaceEvent event) {
        placed = when.is(When.PLACED);
    }

    @Subscribe
    private void onTick(TickEvent event) {
        boolean due = when.is(When.TIMER) ? ++timer >= delay.getInt() : placed;
        if (!due || Hands.taken()) {
            return;
        }
        timer = 0;
        placed = false;
        List<Integer> open = takingPart();
        Inventory inventory = mc.player.getInventory();
        int held = inventory.getSelectedSlot();
        if (when.is(When.PLACED) && !open.contains(held)) {
            return;
        }
        int next = order.is(Order.RANDOM) ? randomPick(open, held) : nextAlong(open, held);
        if (next != -1) {
            inventory.setSelectedSlot(next);
        }
    }

    private List<Integer> takingPart() {
        List<Integer> open = new ArrayList<>();
        for (int slot = 0; slot < InventoryUtil.HOTBAR_SIZE; slot++) {
            if (slots.contains(InventoryUtil.HOTBAR_NAMES.get(slot)) && (when.is(When.TIMER)
                || mc.player.getInventory().getItem(slot).getItem() instanceof BlockItem)) {
                open.add(slot);
            }
        }
        return open;
    }

    private static int nextAlong(List<Integer> open, int held) {
        for (int slot : open) {
            if (slot > held) {
                return slot;
            }
        }
        return open.isEmpty() ? -1 : open.getFirst();
    }

    private int randomPick(List<Integer> open, int held) {
        List<Integer> choices = new ArrayList<>(open);
        if (noRepeat.isOn() && choices.size() > 1) {
            choices.remove(Integer.valueOf(held));
        }
        return choices.isEmpty() ? -1 : choices.get(ThreadLocalRandom.current().nextInt(choices.size()));
    }
}
