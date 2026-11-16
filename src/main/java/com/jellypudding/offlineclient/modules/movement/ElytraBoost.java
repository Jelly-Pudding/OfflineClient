package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.KeyPressEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.KeybindSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.ChatWarning;
import com.jellypudding.offlineclient.util.CrashGuard;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.GlideSwitch;
import com.jellypudding.offlineclient.util.Hands;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.MovementUtil;
import com.jellypudding.offlineclient.util.RotationManager;
import com.jellypudding.offlineclient.util.RotationPriority;
import com.jellypudding.offlineclient.util.SwingMode;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
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

    private static final int MIN_GAP_TICKS = 10;

    private static final float LAUNCH_VOLUME = 3;

    private enum Jump { NONE, OPENING, LIFTING }

    // The chest piece a rocket jump took off and the inventory slot it went to. Swapping the
    // same two slots again puts it back.
    private record ChestLoan(int slot, ItemStack piece) {
    }

    // A rocket jump asks for its look before the modules that fight ask for theirs.
    private static final int JUMP_FIRST = 100;

    // Ticks the glide may take to open after the jump before the leap is given up.
    private static final int OPEN_TICKS = 10;

    // Ticks a rocket may take to push once the glide is open before the leap is given up. A real
    // rocket only pushes once the server has sent it back.
    private static final int PUSH_TICKS = 10;

    private static final float STRAIGHT_UP = -RotationManager.STRAIGHT_DOWN;

    private final BoolSetting antiConsume = new BoolSetting("Anti consume",
        "Boosts with a rocket that exists only on your screen. Needs no rockets at all.", true);
    private final NumberSetting flightDuration = new NumberSetting("Flight duration",
        "The flight duration of the client side rocket. Zero is the shortest boost.",
        0, 0, 255, 1, "").max(255).under(antiConsume);
    private final BoolSetting playSound = new BoolSetting("Play sound",
        "Plays the launch sound for a client side rocket.", true).under(antiConsume);
    private final BoolSetting auto = new BoolSetting("Auto",
        "Keeps firing on its own whilst you glide.", false);
    private final NumberSetting interval = new NumberSetting("Interval",
        "Seconds between rockets whilst Auto is on.", 3, 0.5, 15, 0.5, "s")
        .min((double) MIN_GAP_TICKS / SharedConstants.TICKS_PER_SECOND).under(auto);
    private final BoolSetting takeOff = new BoolSetting("Take off",
        "Opens the elytra when you press the bind midair.", true);
    private final BoolSetting swapBack = new BoolSetting("Swap back",
        "Returns to the slot you had after every rocket.", true);
    private final BoolSetting fromInventory = new BoolSetting("Take from inventory",
        "Borrows rockets from the rest of the inventory when the hotbar has none.", true)
        .unless(antiConsume);
    private final KeybindSetting jumpKey = new KeybindSetting("Rocket jump key",
        "Puts your elytra on and leaps straight up on a rocket when pressed whilst the module is on. Your"
            + " chestplate goes back on as you rise.", KeybindSetting.UNBOUND);
    private final NumberSetting jumpHeight = new NumberSetting("Jump height",
        "How high a rocket jump takes you. The chestplate goes back on once the rest of the climb carries you"
            + " this far. A rocket that burns out sooner leaves you lower.", 20, 5, 60, 1, " blocks")
        .min(1).under(jumpKey, jumpKey::isBound);
    private final CrashGuard crashGuard = new CrashGuard();

    private int lastFireTick = Integer.MIN_VALUE / 2;
    private boolean warned;

    private Jump jump = Jump.NONE;
    private boolean jumpAsked;
    // Owed from the jump that took the chest piece off until it is back on. Null whilst nothing is owed.
    private ChestLoan chestLoan;
    private double jumpFrom;
    private float pitchBefore;
    private int jumpTicks;
    // The tick of the jump the glide opened on and whether a rocket has sped the climb since.
    private int openedAt;
    private boolean pushed;
    private double lastRise;
    private final ChatWarning fallWarning = new ChatWarning();
    private final InventoryUtil.HotbarLoan loan = new InventoryUtil.HotbarLoan();
    private final InventoryUtil.SlotSwap slots = new InventoryUtil.SlotSwap();
    // The client side rockets still alive. FireworkRocketEntityMixin asks after them.
    private final Set<FireworkRocketEntity> fakes = new HashSet<>();

    // Runs on whilst the module is off and ElytraFly fires through it. A spent rocket still
    // goes and borrowed rockets and a chest piece a rocket jump took off still find their way home.
    private final Object tidier = new Object() {
        @Subscribe
        private void onTick(TickEvent event) {
            if (isEnabled()) {
                unwatch(this);
                return;
            }
            boolean gliding = tidy();
            settleChest();
            if (!gliding && fakes.isEmpty() && !loan.isLent() && chestLoan == null) {
                unwatch(this);
            }
        }
    };

    public ElytraBoost() {
        super("ElytraBoost", "Its bind fires a firework rocket whilst you glide.", Category.MOVEMENT);
        addSettings(antiConsume, flightDuration, playSound, auto, interval, takeOff, swapBack,
            fromInventory, jumpKey, jumpHeight);
        addSettings(crashGuard.settings());
        searchTags("elytra", "firework", "rocket", "boost", "rocket jump", "leap");
    }

    @Override
    public String getSuffix() {
        if (!inGame()) {
            return null;
        }
        if (antiConsume.isOn()) {
            return "free";
        }
        int rockets = InventoryUtil.countWithOffhand(Items.FIREWORK_ROCKET, rocketReach());
        return rockets == 0 ? "no rockets" : rockets + " rockets";
    }

    @Override
    protected void onEnable() {
        warned = false;
        jumpAsked = false;
        fallWarning.clear();
    }

    @Override
    protected void onDisable() {
        endJump();
        settleChest();
        if (chestLoan != null) {
            watch(tidier);
        }
        loan.giveBack();
        fakes.forEach(Entity::discard);
        fakes.clear();
    }

    // True from the press of the rocket jump key until the chestplate is back on.
    public boolean jumping() {
        return jump != Jump.NONE || chestLoan != null;
    }

    // True for a client side rocket this module launched.
    public boolean isFake(FireworkRocketEntity rocket) {
        return fakes.contains(rocket);
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
        // ElytraFly opens the elytra itself. A take off the server has just refused is not
        // asked for again at once and the bind does nothing then.
        if (takeOff.isOn() && !Modules.enabled(ElytraFly.class) && couldGlide()) {
            GlideSwitch.get().open();
            return;
        }
        toggle();
    }

    // In the air with a glider on. The bind is a take off then and never the module switch.
    private boolean couldGlide() {
        return !mc.player.onGround() && !mc.player.isPassenger() && MovementUtil.wearsGlider();
    }

    @Subscribe
    private void onKeyPress(KeyPressEvent event) {
        if (event.presses(jumpKey) && inGame() && mc.gui.screen() == null) {
            jumpAsked = true;
        }
    }

    @Subscribe(priority = JUMP_FIRST)
    private void onJumpTick(TickEvent event) {
        if (jumpAsked) {
            jumpAsked = false;
            startJump();
        }
        if (jump != Jump.NONE) {
            stepJump();
        }
        if (jump == Jump.NONE) {
            settleChest();
        }
    }

    // The elytra goes on and the jump goes off. The glide opens on a later tick once the server has
    // heard that you left the ground.
    private void startJump() {
        if (jump != Jump.NONE || !inGame() || Hands.tied()) {
            return;
        }
        String problem = jumpProblem();
        if (problem != null) {
            ChatUtil.error(problem);
            return;
        }
        if (!MovementUtil.wearsGlider() && !wearGlider()) {
            return;
        }
        if (!Modules.fallsFree()) {
            fallWarning.say("Nothing takes the fall out of a rocket jump. NoFall or water at the bottom will.");
        }
        jumpFrom = mc.player.getY();
        pitchBefore = mc.player.getXRot();
        jumpTicks = 0;
        jump = Jump.OPENING;
        if (mc.player.onGround()) {
            mc.player.jumpFromGround();
        }
    }

    // Swaps the first elytra that can open into the chest slot. A chest piece that comes off is
    // owed back. False with the reason told when no elytra went on.
    private boolean wearGlider() {
        int glider = InventoryUtil.findSlot(stack -> LivingEntity.canGlideUsing(stack, EquipmentSlot.CHEST),
            InventoryUtil.WHOLE_INVENTORY);
        if (glider == -1) {
            ChatUtil.error("You have no elytra that can open.");
            return false;
        }
        ItemStack piece = mc.player.getItemBySlot(EquipmentSlot.CHEST).copy();
        InventoryUtil.swap(InventoryUtil.networkSlot(glider), InventoryUtil.CHEST_SLOT);
        if (!MovementUtil.wearsGlider()) {
            ChatUtil.error("Your chest piece would not come off for the elytra.");
            return false;
        }
        if (!piece.isEmpty()) {
            chestLoan = new ChestLoan(glider, piece);
        }
        return true;
    }

    // Why a jump cannot start now. Null when it can.
    private String jumpProblem() {
        if (mc.player.isFallFlying()) {
            return "You are already gliding.";
        }
        if (mc.player.isPassenger() || mc.player.isInWater()) {
            return "An elytra does not open whilst you ride or swim.";
        }
        if (Modules.enabled(ElytraFly.class)) {
            return "Rocket jump does not work whilst ElytraFly is on.";
        }
        if (Hands.slotsTaken() || mc.player.isUsingItem()) {
            return "Rocket jump needs your hands free.";
        }
        if (!MovementUtil.wearsGlider() && !InventoryUtil.cursorFree()) {
            return "Rocket jump cannot reach your armour whilst a container is open.";
        }
        if (!antiConsume.isOn() && !hasRockets()) {
            return "You have no firework rockets" + (fromInventory.isOn() ? "." : " in your hotbar.");
        }
        if (now() - lastFireTick < MIN_GAP_TICKS) {
            return "A rocket has only just gone. Press again in a moment.";
        }
        return null;
    }

    private void stepJump() {
        if (!inGame() || mc.player.isDeadOrDying() || mc.player.isPassenger() || mc.player.isInWater()) {
            endJump();
            return;
        }
        jumpTicks++;
        if (jump == Jump.OPENING) {
            openGlide();
            return;
        }
        // Only a rocket speeds a climb straight up. The climb is over once the push is spent or a
        // ceiling stops it.
        double rise = mc.player.getDeltaMovement().y;
        pushed |= rise > lastRise;
        lastRise = rise;
        if (!mc.player.isFallFlying() || pushed && rise <= 0 || climbLeft() >= jumpHeight.getValue()) {
            endJump();
            return;
        }
        if (!pushed && jumpTicks - openedAt > PUSH_TICKS) {
            ChatUtil.error("The rocket did not lift you and the rocket jump is off.");
            endJump();
            return;
        }
        lookUp();
    }

    // The glide opens once you are off the ground and the rocket goes as it opens.
    private void openGlide() {
        if (!mc.player.isFallFlying() && !mc.player.onGround()) {
            GlideSwitch.get().open();
        }
        if (!mc.player.isFallFlying()) {
            if (jumpTicks > OPEN_TICKS) {
                ChatUtil.error("The elytra did not open and the rocket jump is off.");
                endJump();
            }
            return;
        }
        jump = Jump.LIFTING;
        openedAt = jumpTicks;
        pushed = false;
        lastRise = mc.player.getDeltaMovement().y;
        lookUp();
        fire();
    }

    // How far above the start the climb carries you once the push stops. With the glide shut it runs on
    // as plain air where gravity comes off first and then the drag.
    private double climbLeft() {
        double top = mc.player.getY();
        double speed = mc.player.getDeltaMovement().y;
        while (speed > 0) {
            top += speed;
            speed = (speed - mc.player.getGravity()) * LivingEntity.BASE_VERTICAL_AIR_DRAG;
        }
        return top - jumpFrom;
    }

    // The view and the look the server holds point straight up. The rocket pushes along both.
    private void lookUp() {
        mc.player.setXRot(STRAIGHT_UP);
        RotationManager.requestExact(mc.player.getYRot(), STRAIGHT_UP, RotationPriority.AURA);
    }

    // The view drops back to where it was. The chest piece goes back on straight after.
    private void endJump() {
        if (jump == Jump.NONE) {
            return;
        }
        boolean lifted = jump == Jump.LIFTING;
        jump = Jump.NONE;
        if (lifted && mc.player != null) {
            mc.player.setXRot(pitchBefore);
        }
    }

    // Swaps the chest piece back once the jump is over and the server shuts the glide by itself as
    // it does on a landing. NoFall takes the glide's fall back on the next packet and the drop after
    // it as usual. The swap waits whilst clicks would be thrown away and is let go once a death or
    // ElytraFly or anything that moved either piece has made it wrong.
    private void settleChest() {
        if (chestLoan == null || jump != Jump.NONE || mc.player == null) {
            return;
        }
        ItemStack stored = mc.player.getInventory().getItem(chestLoan.slot());
        if (mc.player.isDeadOrDying() || Modules.enabled(ElytraFly.class)
            || !mc.player.getItemBySlot(EquipmentSlot.CHEST).has(DataComponents.GLIDER)
            || !ItemStack.matches(stored, chestLoan.piece())) {
            chestLoan = null;
            return;
        }
        if (!InventoryUtil.cursorFree()) {
            return;
        }
        InventoryUtil.swap(InventoryUtil.networkSlot(chestLoan.slot()), InventoryUtil.CHEST_SLOT);
        chestLoan = null;
        if (mc.player.isFallFlying() && !MovementUtil.wearsGlider()) {
            GlideSwitch.get().land();
        }
    }

    private boolean hasRockets() {
        return mc.player.getOffhandItem().is(Items.FIREWORK_ROCKET)
            || InventoryUtil.findSlot(Items.FIREWORK_ROCKET, rocketReach()) != -1;
    }

    // The slots a rocket is taken from. Take from inventory reaches past the hotbar.
    private int rocketReach() {
        return fromInventory.isOn() ? InventoryUtil.WHOLE_INVENTORY : InventoryUtil.HOTBAR_SIZE;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!tidy()) {
            return;
        }
        crashGuard.brake(mc.player);
        if (!auto.isOn() || !usable() || cruising()) {
            return;
        }
        int gap = (int) Math.round(interval.getValue() * SharedConstants.TICKS_PER_SECOND);
        if (now() - lastFireTick < Math.max(gap, MIN_GAP_TICKS)) {
            return;
        }
        fire();
    }

    // Clears away what the rockets left behind. True whilst the player glides.
    private boolean tidy() {
        // A rocket from a world since left is never marked as removed.
        fakes.removeIf(rocket -> rocket.isRemoved() || rocket.level() != mc.level);
        if (!inGame()) {
            return false;
        }
        if (!mc.player.isFallFlying()) {
            // Borrowed rockets go home once the glide is over.
            loan.giveBack();
            return false;
        }
        return true;
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
        return inGame() && !Hands.tied() && !mc.player.isUsingItem()
            && !mc.player.isInWater();
    }

    // Also used by ElytraFly whilst it cruises on rockets. It need not be switched on for that.
    public void fire() {
        if (now() - lastFireTick < MIN_GAP_TICKS) {
            return;
        }
        if (!isEnabled()) {
            watch(tidier);
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
        rocket.setId(EntityUtil.nextLocalId());
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
            if (mc.player.getInventory().getItem(i).is(Items.FIREWORK_ROCKET)) {
                return loan.borrow(i);
            }
        }
        return -1;
    }

    private void use(InteractionHand hand) {
        if (mc.gameMode.useItem(mc.player, hand).consumesAction()) {
            SwingMode.swingUse(hand);
            lastFireTick = mc.player.tickCount;
        }
    }

    // ElytraFly holds its own speed whilst cruising. A rocket only breaks the cycle.
    private boolean cruising() {
        ElytraFly elytraFly = Modules.active(ElytraFly.class);
        return elytraFly != null && elytraFly.inCruiseMode();
    }

    // The key does more than switch the module and cannot be a held one.
    @Override
    public boolean keySwitches() {
        return false;
    }
}
