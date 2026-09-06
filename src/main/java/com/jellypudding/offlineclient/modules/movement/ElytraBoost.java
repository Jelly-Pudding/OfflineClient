package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.core.component.DataComponents;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Fireworks;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

// A client side rocket boosts you the same way a real one does and the server
// only ever sees the movement. A real rocket is used when that is switched off.
public final class ElytraBoost extends Module {

    private static final double TICKS_PER_SECOND = 20;

    private static final int MIN_GAP_TICKS = 10;

    private static final float LAUNCH_VOLUME = 3;

    private final BoolSetting antiConsume = new BoolSetting("Anti consume",
        "Boosts with a client side rocket so none of yours is spent. Works with no rockets at all.", true);
    private final NumberSetting flightDuration = new NumberSetting("Flight duration",
        "The flight duration of the client side rocket. Zero is the shortest boost.",
        0, 0, 255, 1, "").min(0).max(255).under(antiConsume);
    private final BoolSetting playSound = new BoolSetting("Play sound",
        "Plays the launch sound for a client side rocket.", true).under(antiConsume);
    private final BoolSetting auto = new BoolSetting("Auto",
        "Keeps firing on its own whilst you glide.", false);
    private final NumberSetting interval = new NumberSetting("Interval",
        "Seconds between rockets whilst Auto is on.", 3, 0.5, 15, 0.5, "s")
        .min(MIN_GAP_TICKS / TICKS_PER_SECOND).under(auto);
    private final BoolSetting takeOff = new BoolSetting("Take off",
        "Opens the elytra when you press the bind midair.", true);
    private final BoolSetting swapBack = new BoolSetting("Swap back",
        "Returns to the slot you had after every rocket.", true);
    private final BoolSetting fromInventory = new BoolSetting("Take from inventory",
        "Borrows rockets from the rest of the inventory when the hotbar has none.", true)
        .unless(antiConsume);

    private int lastFireTick = Integer.MIN_VALUE / 2;
    private boolean warned;
    private final InventoryUtil.HotbarLoan loan = new InventoryUtil.HotbarLoan();
    private final InventoryUtil.SlotSwap slots = new InventoryUtil.SlotSwap();
    // The client side rockets alive right now. FireworkRocketEntityMixin asks after them.
    private final Set<FireworkRocketEntity> fakes = new HashSet<>();

    public ElytraBoost() {
        super("ElytraBoost", "Press the bind whilst gliding to fire a firework rocket.", Category.MOVEMENT);
        addSettings(antiConsume, flightDuration, playSound, auto, interval, takeOff, swapBack,
            fromInventory);
        searchTags("elytra", "firework", "rocket", "boost");
    }

    @Override
    public String getSuffix() {
        if (!inGame()) {
            return null;
        }
        if (antiConsume.isOn()) {
            return "free";
        }
        int rockets = countRockets();
        return rockets == 0 ? "no rockets" : rockets + " rockets";
    }

    @Override
    protected void onEnable() {
        warned = false;
    }

    @Override
    protected void onDisable() {
        loan.giveBack();
        fakes.forEach(Entity::discard);
        fakes.clear();
    }

    // True for a client side rocket this module launched.
    public boolean isFake(FireworkRocketEntity rocket) {
        return isEnabled() && fakes.contains(rocket);
    }

    // MultiPlayerGameModeMixin asks before a right click uses an item.
    // A real rocket about to be spent whilst gliding is replaced by a client side one.
    public boolean interceptsUse(ItemStack held) {
        if (!antiConsume.isOn() || !held.is(Items.FIREWORK_ROCKET) || !inGame()
            || !mc.player.isFallFlying()) {
            return false;
        }
        launchFake();
        return true;
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
        if (takeOff.isOn() && !Modules.enabled(ElytraFly.class) && canGlide()) {
            ElytraFly.sendStartGlide();
            return;
        }
        toggle();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        fakes.removeIf(Entity::isRemoved);
        if (inGame() && !mc.player.isFallFlying()) {
            // Borrowed rockets go home once the glide is over.
            loan.giveBack();
        }
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

    // The player is rebuilt on a respawn or a dimension change.
    // Its tick counter starts over.
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

    // Also used by ElytraFly whilst it cruises on rockets.
    public void fire() {
        if (now() - lastFireTick < MIN_GAP_TICKS) {
            return;
        }
        if (antiConsume.isOn()) {
            launchFake();
            lastFireTick = mc.player.tickCount;
            return;
        }
        if (mc.player.getOffhandItem().is(Items.FIREWORK_ROCKET)) {
            use(InteractionHand.OFF_HAND);
            return;
        }
        int slot = InventoryUtil.hotbarSlot(stack -> stack.is(Items.FIREWORK_ROCKET));
        if (slot == -1 && fromInventory.isOn()) {
            slot = borrowRockets();
        }
        if (slot == -1) {
            if (!warned) {
                warned = true;
                ChatUtil.error("You have no firework rockets" + (fromInventory.isOn() ? "." : " in your hotbar."));
            }
            return;
        }
        warned = false;
        slots.select(slot);
        use(InteractionHand.MAIN_HAND);
        if (swapBack.isOn()) {
            slots.restoreIfMine();
        } else {
            slots.forget();
        }
    }

    // A rocket that exists on this client alone. It rides the player the way a
    // real one does and the vanilla entity tick supplies the push.
    private void launchFake() {
        if (!mc.player.isFallFlying() || mc.gui.screen() != null) {
            return;
        }
        ItemStack stack = Items.FIREWORK_ROCKET.getDefaultInstance();
        stack.set(DataComponents.FIREWORKS, new Fireworks(flightDuration.getInt(), List.of()));
        FireworkRocketEntity rocket = new FireworkRocketEntity(mc.level, stack, mc.player);
        fakes.add(rocket);
        if (playSound.isOn()) {
            mc.level.playSound(mc.player, rocket, SoundEvents.FIREWORK_ROCKET_LAUNCH,
                SoundSource.AMBIENT, LAUNCH_VOLUME, 1);
        }
        mc.level.addEntity(rocket);
    }

    // Moves a stack of rockets into the hotbar. The slot it landed in or minus one.
    private int borrowRockets() {
        for (int i = InventoryUtil.HOTBAR_SIZE; i < InventoryUtil.WHOLE_INVENTORY; i++) {
            if (!mc.player.getInventory().getItem(i).is(Items.FIREWORK_ROCKET)) {
                continue;
            }
            int before = InventoryUtil.selectedSlot();
            if (!loan.select(i)) {
                return -1;
            }
            int slot = InventoryUtil.selectedSlot();
            // The loan selected the slot for us. The caller decides what to hold.
            mc.player.getInventory().setSelectedSlot(before);
            return slot;
        }
        return -1;
    }

    private void use(InteractionHand hand) {
        if (mc.gameMode.useItem(mc.player, hand).consumesAction()) {
            mc.player.swing(hand);
            lastFireTick = mc.player.tickCount;
        }
    }

    private int countRockets() {
        int total = InventoryUtil.count(Items.FIREWORK_ROCKET, InventoryUtil.HOTBAR_SIZE);
        ItemStack offhand = mc.player.getOffhandItem();
        if (offhand.is(Items.FIREWORK_ROCKET)) {
            total += offhand.getCount();
        }
        return total;
    }

    // ElytraFly holds its own speed whilst cruising. A rocket only breaks the cycle.
    private boolean cruising() {
        ElytraFly elytraFly = Modules.get(ElytraFly.class);
        return elytraFly != null && elytraFly.isEnabled() && elytraFly.inCruiseMode();
    }
}
