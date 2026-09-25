package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.Swap;
import com.jellypudding.offlineclient.util.ItemUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;

import java.util.List;

// The game fires the first arrow it finds.
public final class Quiver extends Module {

    private final RegistryListSetting<MobEffect> effects = new RegistryListSetting<>("Effects",
        "Tipped arrows to reach for in order of preference.",
        BuiltInRegistries.MOB_EFFECT,
        List.of(MobEffects.INSTANT_DAMAGE.value(), MobEffects.POISON.value(),
            MobEffects.SLOWNESS.value(), MobEffects.WEAKNESS.value()));
    private final BoolSetting plainFallback = new BoolSetting("Save tipped",
        "Move plain arrows to the front when none of the chosen ones are around.", false);
    private final BoolSetting drawingOnly = new BoolSetting("Whilst drawing",
        "Only sort the quiver whilst the bow is actually being drawn.", false);
    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks between moves.", 2, 0, 20, 1, " ticks");

    private final InventoryUtil.StrandedStack cursor = new InventoryUtil.StrandedStack();
    private int timer;
    private String chosen;

    public Quiver() {
        super("Quiver", "Picks which arrow your bow fires next.", Category.COMBAT);
        addSettings(effects, plainFallback, drawingOnly, delay);
        searchTags("tipped arrow", "arrow select", "bow");
    }

    @Override
    public String getSuffix() {
        return chosen;
    }

    @Override
    protected void onEnable() {
        timer = 0;
        chosen = null;
    }

    @Override
    protected void onDisable() {
        if (inGame()) {
            cursor.giveBack();
        }
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.player.isSpectator() || !holdsLauncher()) {
            chosen = null;
            return;
        }
        if (!cursor.recover()) {
            return;
        }
        if (drawingOnly.isOn() && !isDrawing()) {
            return;
        }
        if (!InventoryUtil.inventoryFree()) {
            return;
        }
        if (timer > 0) {
            timer--;
            return;
        }

        int want = findWanted();
        if (want == -1) {
            chosen = null;
            return;
        }
        chosen = label(mc.player.getInventory().getItem(want));

        int first = InventoryUtil.firstArrowSlot();
        int wanted = InventoryUtil.networkSlot(want);
        if (first == -1 || first == wanted || (first == InventoryUtil.OFFHAND_SLOT
            && ItemStack.isSameItemSameComponents(mc.player.getOffhandItem(),
                mc.player.getInventory().getItem(want)))) {
            return;
        }
        move(wanted, first);
    }

    private void move(int from, int to) {
        if (cursor.swap(from, to) != Swap.REFUSED) {
            timer = delay.getInt();
        }
    }

    private boolean holdsLauncher() {
        return isLauncher(mc.player.getMainHandItem()) || isLauncher(mc.player.getOffhandItem());
    }

    private static boolean isLauncher(ItemStack stack) {
        return stack.getItem() instanceof BowItem || stack.getItem() instanceof CrossbowItem;
    }

    private boolean isDrawing() {
        if (mc.player.isUsingItem() && isLauncher(mc.player.getUseItem())) {
            return true;
        }
        return isLauncher(mc.player.getMainHandItem()) && mc.options.keyUse.isDown();
    }

    // Inventory index of the arrow that should be fired next.
    private int findWanted() {
        for (Identifier id : effects.getValue()) {
            if (!BuiltInRegistries.MOB_EFFECT.containsKey(id)) {
                continue;
            }
            MobEffect effect = BuiltInRegistries.MOB_EFFECT.getValue(id);
            int slot = InventoryUtil.findSlot(stack -> stack.is(Items.TIPPED_ARROW)
                && ItemUtil.carriesEffect(stack, effect), InventoryUtil.WHOLE_INVENTORY);
            if (slot != -1) {
                return slot;
            }
        }
        if (!plainFallback.isOn()) {
            return -1;
        }
        return InventoryUtil.findSlot(Items.ARROW, InventoryUtil.WHOLE_INVENTORY);
    }


    private static String label(ItemStack stack) {
        if (!stack.is(Items.TIPPED_ARROW)) {
            return "plain";
        }
        PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
        if (contents == null) {
            return "plain";
        }
        var effects = contents.getAllEffects().iterator();
        return effects.hasNext()
            ? effects.next().getEffect().value().getDisplayName().getString() : "plain";
    }
}
