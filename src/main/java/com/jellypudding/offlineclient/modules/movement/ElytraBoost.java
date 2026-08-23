package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.module.ModuleManager;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.Setting;
import com.jellypudding.offlineclient.util.ChatUtil;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class ElytraBoost extends Module {

    private static final double TICKS_PER_SECOND = 20;

    private static final int MIN_GAP_TICKS = 10;

    private final BoolSetting auto = new BoolSetting("Auto",
        "Keeps firing on its own whilst you glide.", false);
    private final NumberSetting interval = new NumberSetting("Interval",
        "Seconds between rockets whilst Auto is on.", 3, 0.5, 15, 0.5, "s")
        .min(MIN_GAP_TICKS / TICKS_PER_SECOND).visibleWhen(auto::isOn);
    private final BoolSetting takeOff = new BoolSetting("Take off",
        "Opens the elytra when you press the bind midair.", true);
    private final BoolSetting swapBack = new BoolSetting("Swap back",
        "Returns to the slot you had after every rocket.", true);

    private int lastFireTick = Integer.MIN_VALUE / 2;
    private boolean warned;

    public ElytraBoost() {
        super("ElytraBoost", "Press the bind whilst gliding to fire a firework rocket.", Category.MOVEMENT);
        addSettings(auto, interval, takeOff, swapBack);
        searchTags("elytra", "firework", "rocket", "boost");
    }

    @Override
    public String getSuffix() {
        if (!inGame()) {
            return null;
        }
        int rockets = countRockets();
        return rockets == 0 ? "no rockets" : rockets + " rockets";
    }

    @Override
    protected void onEnable() {
        warned = false;
    }

    @Override
    public void onKeybind() {
        if (!isEnabled() || !inGame() || mc.player.isSpectator()) {
            toggle();
            return;
        }
        if (mc.player.isFallFlying()) {
            fire();
            return;
        }
        // ElytraFly opens the elytra itself.
        if (takeOff.isOn() && !elytraFlyOn() && canGlide()) {
            startGlide();
            return;
        }
        toggle();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!auto.isOn() || !usable() || !mc.player.isFallFlying()) {
            return;
        }
        if (cruising()) {
            return;
        }
        int gap = (int) Math.round(interval.getValue() * TICKS_PER_SECOND);
        if (now() - lastFireTick < Math.max(gap, MIN_GAP_TICKS)) {
            return;
        }
        fire();
    }

    // The player is rebuilt on a respawn or a dimension change and its tick counter starts over.
    private int now() {
        int tick = mc.player.tickCount;
        if (tick < lastFireTick) {
            lastFireTick = Integer.MIN_VALUE / 2;
        }
        return tick;
    }

    private boolean usable() {
        return inGame() && !mc.player.isSpectator() && !mc.player.isUsingItem()
            && !mc.player.isInWater();
    }

    private boolean canGlide() {
        return !mc.player.onGround() && !mc.player.isPassenger()
            && mc.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA);
    }

    private void startGlide() {
        mc.player.connection.send(new ServerboundPlayerCommandPacket(mc.player,
            ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
    }

    private void fire() {
        if (now() - lastFireTick < MIN_GAP_TICKS) {
            return;
        }
        if (mc.player.getOffhandItem().is(Items.FIREWORK_ROCKET)) {
            use(InteractionHand.OFF_HAND);
            return;
        }
        int slot = findRocket();
        if (slot == -1) {
            if (!warned) {
                warned = true;
                ChatUtil.error("You have no firework rockets in your hotbar.");
            }
            return;
        }
        warned = false;
        int previous = mc.player.getInventory().getSelectedSlot();
        if (slot != previous) {
            mc.player.getInventory().setSelectedSlot(slot);
        }
        use(InteractionHand.MAIN_HAND);
        if (swapBack.isOn() && slot != previous) {
            mc.player.getInventory().setSelectedSlot(previous);
        }
    }

    private void use(InteractionHand hand) {
        if (mc.gameMode.useItem(mc.player, hand).consumesAction()) {
            mc.player.swing(hand);
            lastFireTick = mc.player.tickCount;
        }
    }

    private int findRocket() {
        int selected = mc.player.getInventory().getSelectedSlot();
        if (mc.player.getInventory().getItem(selected).is(Items.FIREWORK_ROCKET)) {
            return selected;
        }
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).is(Items.FIREWORK_ROCKET)) {
                return i;
            }
        }
        return -1;
    }

    private int countRockets() {
        int total = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.is(Items.FIREWORK_ROCKET)) {
                total += stack.getCount();
            }
        }
        ItemStack offhand = mc.player.getOffhandItem();
        if (offhand.is(Items.FIREWORK_ROCKET)) {
            total += offhand.getCount();
        }
        return total;
    }

    private boolean elytraFlyOn() {
        return elytraFly() != null;
    }

    private boolean cruising() {
        Module elytraFly = elytraFly();
        if (elytraFly == null) {
            return false;
        }
        Setting<?> mode = elytraFly.getSetting("Mode");
        return mode != null && ElytraFly.Mode.CRUISE.equals(mode.getValue());
    }

    private Module elytraFly() {
        ModuleManager modules = OfflineClient.INSTANCE.getModuleManager();
        if (modules == null) {
            return null;
        }
        Module module = modules.get("ElytraFly");
        return module != null && module.isEnabled() ? module : null;
    }
}
