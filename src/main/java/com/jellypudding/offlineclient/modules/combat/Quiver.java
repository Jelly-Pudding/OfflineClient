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
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
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

        // An arrow in the offhand beats everything else the game looks at.
        if (isArrow(mc.player.getOffhandItem())) {
            if (!ItemStack.isSameItemSameComponents(mc.player.getOffhandItem(),
                mc.player.getInventory().getItem(want))) {
                move(InventoryUtil.networkSlot(want), InventoryUtil.OFFHAND_SLOT);
            }
            return;
        }

        int first = firstArrow();
        if (first == -1 || first == want) {
            return;
        }
        move(InventoryUtil.networkSlot(want), InventoryUtil.networkSlot(first));
    }

    private void move(int from, int to) {
        Swap result = InventoryUtil.swap(from, to);
        if (result == Swap.REFUSED) {
            return;
        }
        if (result == Swap.STRANDED) {
            // Whatever the arrow displaced had nowhere to go.
            cursor.hold(from);
        }
        timer = delay.getInt();
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

    private int firstArrow() {
        for (int i = 0; i < 36; i++) {
            if (isArrow(mc.player.getInventory().getItem(i))) {
                return i;
            }
        }
        return -1;
    }

    // Inventory index of the arrow that should be fired next.
    private int findWanted() {
        for (Identifier id : effects.getValue()) {
            if (!BuiltInRegistries.MOB_EFFECT.containsKey(id)) {
                continue;
            }
            MobEffect effect = BuiltInRegistries.MOB_EFFECT.getValue(id);
            for (int i = 0; i < 36; i++) {
                ItemStack stack = mc.player.getInventory().getItem(i);
                if (stack.is(Items.TIPPED_ARROW) && hasEffect(stack, effect)) {
                    return i;
                }
            }
        }
        if (!plainFallback.isOn()) {
            return -1;
        }
        for (int i = 0; i < 36; i++) {
            if (mc.player.getInventory().getItem(i).is(Items.ARROW)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean hasEffect(ItemStack stack, MobEffect effect) {
        PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
        if (contents == null) {
            return false;
        }
        for (MobEffectInstance instance : contents.getAllEffects()) {
            if (instance.getEffect().value() == effect) {
                return true;
            }
        }
        return false;
    }

    private static boolean isArrow(ItemStack stack) {
        return stack.is(Items.ARROW) || stack.is(Items.TIPPED_ARROW)
            || stack.is(Items.SPECTRAL_ARROW);
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
