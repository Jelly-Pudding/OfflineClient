package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.PostMotionEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.modules.player.ChestSwap;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.MovementUtil;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

// Control nudges the vanilla glide and Cruise flies a dive and climb cycle on its own.
// Packet fakes the glide with packets and Bounce hops along the ground with wings open.
public final class ElytraFly extends Module {

    public enum Mode { CONTROL, CRUISE, PACKET, BOUNCE }
    public enum Altitude { TAKE_OFF, BAND, FREE }
    public enum Heading { FREE, SNAP, FIXED }
    public enum ChestSwapMode { NEVER, ALWAYS, WAIT_FOR_GROUND }

    // Around forty degrees down is where a glide trades the most height for speed.
    private static final float DIVE_PITCH = 40;
    private static final float CLIMB_PITCH = -30;

    // Each acceleration step adds this much speed a tick.
    private static final double RAMP_UNIT = 0.1;

    private static final double TICKS_PER_SECOND = 20;

    // Below this fraction of the target speed a climb would stall.
    private static final double STALL_FRACTION = 0.5;

    private static final double LOW_DURABILITY = 0.05;

    private static final double TAKE_OFF_CLEARANCE = 3;

    // Speed kept each tick whilst waiting for chunks to arrive.
    private static final double CHUNK_BRAKE = 0.6;

    // Ticks after a standing jump in which the glide is forced open.
    private static final int TAKE_OFF_WINDOW = 4;

    // Ticks to leave the server alone after asking for a glide.
    private static final int RESTART_COOLDOWN = 5;

    // Pitched further down than this is a dive and the height is let go when asked.
    private static final float HOLD_PITCH_LIMIT = 25;

    // Ticks of travel the crash check sweeps ahead at the least and at the most.
    private static final double CRASH_TICKS = 4;
    private static final int CRASH_MAX_TICKS = 40;

    // Share of the safe speed kept after a brake. Leaves room to add some back.
    private static final double CRASH_KEEP = 0.6;

    // How hard the held height pulls back per block of drift.
    // The most it may pull in a single tick.
    private static final double HOLD_GAIN = 0.3;
    private static final double HOLD_STEP = 0.1;
    // How quickly a steady drift is learned and cancelled outright.
    private static final double HOLD_LEARN = 0.02;
    private static final double HOLD_BIAS_LIMIT = 0.05;

    private static final double GRAVITY = 0.08;

    // Auto hover eases down inside this and stops inside the smaller gap.
    private static final double HOVER_APPROACH = 2;
    private static final double HOVER_GAP = 0.4;
    private static final double HOVER_SINK = 0.1;

    // Bounce turns to the nearest of these headings.
    private static final float YAW_SNAP = 45;

    private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
        "Who does the flying.", Mode.CONTROL)
        .describe(Mode.CONTROL, "You steer. The keys push you along and up and down.")
        .describe(Mode.CRUISE, "Flies a dive and climb cycle on its own for long trips.")
        .describe(Mode.PACKET, "Fakes a glide with packets whilst you fly like creative. Works on some servers.")
        .describe(Mode.BOUNCE, "Hops along the ground with the wings open. Fast on the nether roof.");
    private final NumberSetting speed = new NumberSetting("Horizontal speed",
        "How hard the movement keys push you along.", 1, 0.2, 5, 0.1, "x")
        .min(0.1).max(20).under(mode, Mode.CONTROL, Mode.PACKET);
    private final NumberSetting climbSpeed = new NumberSetting("Vertical speed",
        "How hard jump and sneak push you up and down.", 1, 0.2, 5, 0.1, "x")
        .min(0.1).max(20).under(mode, Mode.CONTROL, Mode.PACKET);
    private final BoolSetting holdHeight = new BoolSetting("Hold height",
        "Holds you at your current height whilst neither jump nor sneak is held.", true)
        .under(mode, Mode.CONTROL);
    private final BoolSetting lookToDive = new BoolSetting("Look to dive",
        "Looking down steeply lets the glide dive as it would without the hold.", false)
        .under(holdHeight);
    private final BoolSetting autoHover = new BoolSetting("Hover on sneak",
        "Sneaking sinks you slowly and stops a block above the ground instead of landing.", false)
        .under(mode, Mode.CONTROL);
    private final BoolSetting instantStop = new BoolSetting("Instant stop",
        "Kills your momentum when you release the keys.", false)
        .under(mode, Mode.CONTROL);
    private final BoolSetting sneakDrop = new BoolSetting("Sneak to drop",
        "Holding sneak ends the glide. Wins over Hover on sneak.", false)
        .under(mode, Mode.CONTROL);
    private final NumberSetting fallMultiplier = new NumberSetting("Fall multiplier",
        "Scales the natural sink whilst Hold height is off. Lower values sink slower.",
        1, 0, 1, 0.01, "x").min(0).max(1)
        .under(holdHeight, () -> mode.is(Mode.CONTROL) && !holdHeight.isOn());
    private final BoolSetting acceleration = new BoolSetting("Acceleration",
        "Builds the horizontal speed up over time instead of applying it at once.", false)
        .under(mode, Mode.CONTROL, Mode.PACKET);
    private final NumberSetting accelerationStart = new NumberSetting("Acceleration start",
        "The speed the ramp begins from.", 0, 0, 5, 0.1, "x").min(0).max(20)
        .under(acceleration);
    private final NumberSetting accelerationStep = new NumberSetting("Acceleration step",
        "How quickly the ramp climbs towards the horizontal speed.", 1, 0.1, 5, 0.1, "")
        .min(0.1).max(5)
        .under(acceleration);
    private final BoolSetting autoPilot = new BoolSetting("Auto pilot",
        "Holds forward for you whilst you glide above the minimum height.", false)
        .under(mode, Mode.CONTROL, Mode.PACKET);
    private final NumberSetting autoPilotHeight = new NumberSetting("Minimum height",
        "Auto pilot only pushes forward above this height.", 120, -64, 320, 1, "")
        .min(-128).max(2000)
        .under(autoPilot);
    private final NumberSetting cruiseSpeed = new NumberSetting("Cruise speed",
        "The speed the dive and climb cycle aims for.", 30, 10, 60, 1, " bps")
        .min(5).under(mode, Mode.CRUISE);
    private final EnumSetting<Altitude> altitude = new EnumSetting<>("Altitude",
        "How the cycle picks the heights it dives and climbs between.", Altitude.TAKE_OFF)
        .describe(Altitude.TAKE_OFF, "Cycles around the height you set off from. Jump and sneak nudge it.")
        .describe(Altitude.BAND, "Cycles between the two heights you set below.")
        .describe(Altitude.FREE, "Speed alone decides when to dive and when to climb.")
        .under(mode, Mode.CRUISE);
    private final NumberSetting lowerHeight = new NumberSetting("Lower height",
        "Starts climbing once you sink to this height.", 180, -64, 320, 1, "")
        .min(-128).max(2000)
        .under(altitude, Altitude.BAND);
    private final NumberSetting upperHeight = new NumberSetting("Upper height",
        "Starts diving once you rise to this height.", 220, -64, 320, 1, "")
        .min(-128).max(2000)
        .under(altitude, Altitude.BAND);
    private final NumberSetting pitchUpSpeed = new NumberSetting("Pitch up speed",
        "Degrees a tick the nose comes up when a climb starts.", 6, 0.5, 20, 0.05, " degrees")
        .min(0.5).max(90)
        .under(mode, Mode.CRUISE);
    private final NumberSetting pitchDownSpeed = new NumberSetting("Pitch down speed",
        "Degrees a tick the nose goes down when a dive starts.", 6, 0.5, 20, 0.05, " degrees")
        .min(0.5).max(90)
        .under(mode, Mode.CRUISE);
    private final BoolSetting rockets = new BoolSetting("Rockets",
        "Fires a rocket from your hotbar whenever the cycle runs out of speed.", false)
        .under(mode, Mode.CRUISE);
    private final NumberSetting rocketDelay = new NumberSetting("Rocket delay",
        "Seconds between rockets.", 4, 1, 20, 0.5, "s").min(0.5)
        .under(rockets);
    private final BoolSetting lockYaw = new BoolSetting("Lock heading",
        "Keeps the heading you set off with.", true)
        .under(mode, Mode.CRUISE);
    private final EnumSetting<Heading> bounceHeading = new EnumSetting<>("Heading",
        "How the bounce steers.", Heading.SNAP)
        .describe(Heading.FREE, "You steer with the mouse.")
        .describe(Heading.SNAP, "Snaps your heading to the nearest 45 degrees.")
        .describe(Heading.FIXED, "Holds the yaw you set below.")
        .under(mode, Mode.BOUNCE);
    private final NumberSetting bounceYaw = new NumberSetting("Yaw",
        "The heading held whilst bouncing.", 0, 0, 360, 1, " degrees").min(0).max(360)
        .under(bounceHeading, Heading.FIXED);
    private final BoolSetting lockPitch = new BoolSetting("Lock pitch",
        "Holds the pitch below whilst bouncing. Off leaves the pitch to you.", true)
        .under(mode, Mode.BOUNCE);
    private final NumberSetting bouncePitch = new NumberSetting("Pitch",
        "The pitch held whilst bouncing.", 0, -90, 90, 1, " degrees")
        .under(lockPitch);
    private final BoolSetting autoJump = new BoolSetting("Auto jump",
        "Holds jump for you. Off only holds forward and leaves the jumping to you.", true)
        .under(mode, Mode.BOUNCE);
    private final BoolSetting manualTakeOff = new BoolSetting("Manual take off",
        "Leaves opening the wings to you. The wings still reopen after a pull back.", false)
        .under(mode, Mode.BOUNCE);
    private final BoolSetting sprintAlways = new BoolSetting("Sprint always",
        "Keeps sprint on in the air too. Off sprints on the ground only and stops the view zoom flicker.", true)
        .under(mode, Mode.BOUNCE);
    private final BoolSetting restart = new BoolSetting("Restart",
        "Opens the wings again after the server pulls you back.", true)
        .under(mode, Mode.BOUNCE);
    private final NumberSetting restartDelay = new NumberSetting("Restart delay",
        "Ticks to wait after the server pulls you back before the wings open again.",
        20, 0, 100, 1, " ticks").min(0)
        .under(restart);
    private final BoolSetting instantDrop = new BoolSetting("Instant drop",
        "Turning the module off midair ends the glide and drops you at once.", false)
        .under(mode, Mode.CONTROL, Mode.CRUISE, Mode.PACKET);
    private final BoolSetting autoTakeOff = new BoolSetting("Auto take off",
        "Opens the elytra for you as soon as you fall.", true);
    private final BoolSetting groundStart = new BoolSetting("Ground start",
        "Jump straight into a glide from standing.", true);
    private final BoolSetting keepGliding = new BoolSetting("Keep gliding",
        "Restarts a glide the game cancels midair.", true);
    private final BoolSetting stopInWater = new BoolSetting("Stop in water",
        "Does nothing whilst you are in water.", true);
    private final BoolSetting chunkGuard = new BoolSetting("Chunk guard",
        "Slows you down before you fly into ground the client has not loaded.", true);
    private final NumberSetting chunkLookahead = new NumberSetting("Look ahead",
        "How far in front to check for loaded ground.", 24, 8, 64, 1, " blocks")
        .under(chunkGuard);
    private final BoolSetting noCrash = new BoolSetting("No crash",
        "Brakes before you fly into a wall or the ground. The faster you go the further ahead it looks.", false);
    private final NumberSetting crashLookAhead = new NumberSetting("Crash look ahead",
        "The least distance along your flight path to check.", 5, 1, 15, 1, " blocks").min(1).max(32)
        .under(noCrash);
    private final BoolSetting replaceElytra = new BoolSetting("Replace elytra",
        "Swaps in a fresher elytra from your inventory once the worn one runs low.", false);
    private final NumberSetting replaceAt = new NumberSetting("Replace at",
        "Durability points left that count as run low.", 10, 1, 100, 1).min(1)
        .under(replaceElytra);
    private final BoolSetting durabilityGuard = new BoolSetting("Durability guard",
        "Stops helping and warns you when the elytra is nearly broken.", true);
    private final EnumSetting<ChestSwapMode> chestSwap = new EnumSetting<>("Chest swap",
        "Runs ChestSwap when the module toggles.", ChestSwapMode.NEVER)
        .describe(ChestSwapMode.NEVER, "Leaves your chest slot alone.")
        .describe(ChestSwapMode.ALWAYS, "Puts the elytra on when enabled and the chestplate back when disabled.")
        .describe(ChestSwapMode.WAIT_FOR_GROUND, "Puts the elytra on when enabled and the chestplate back once you land.");
    private final BoolSetting replenishRockets = new BoolSetting("Replenish rockets",
        "Moves rockets from your inventory into a hotbar slot once the hotbar has none.", false);
    private final NumberSetting rocketSlot = new NumberSetting("Rocket slot",
        "The hotbar slot the rockets go to.", 9, 1, 9, 1, "").min(1).max(9)
        .under(replenishRockets);

    // The mode the last tick ran under.
    private Mode applied;

    // The ramped horizontal speed whilst Acceleration is on.
    private double ramp;
    private boolean pilotHeld;
    // The view zoom scale put aside whilst Bounce sprints on the ground only.
    private double savedFovScale = -1;
    // Set once a Bounce pull back has waited out its delay.
    private boolean reopen;

    // Runs on after the module is off until the glide has ended.
    private final Object dropWatcher = new Object() {
        @Subscribe
        private void onTick(TickEvent event) {
            if (isEnabled() || !inGame() || !mc.player.isFallFlying()) {
                unwatch(this);
                return;
            }
            mc.player.setDeltaMovement(Vec3.ZERO);
            mc.player.connection.send(new ServerboundMovePlayerPacket.StatusOnly(true,
                mc.player.horizontalCollision));
        }
    };

    // Runs on after the module is off until the player has landed.
    private final Object landingWatcher = new Object() {
        @Subscribe
        private void onTick(TickEvent event) {
            if (isEnabled() || !inGame()) {
                unwatch(this);
                return;
            }
            if (mc.player.onGround()) {
                unwatch(this);
                swapChest(true);
            }
        }
    };

    private int restartCooldown;
    // Ticks left to force the elytra open after a standing jump.
    private int takeOffWindow;
    private boolean wasGliding;
    private boolean warned;
    // True from a sneak drop until the sneak key is let go.
    // Keeps the glide from restarting.
    private boolean dropped;

    // The height held or NaN whilst the keys have it and the drift learned to date.
    private double heldY = Double.NaN;
    private double holdBias;

    private double cruiseY;
    private float cruiseYaw;
    private boolean diving;
    private float forcedPitch;
    private boolean cruising;
    private int rocketTimer;

    // Bounce keys held down by the module and the wait after a rubberband.
    private boolean bounceKeys;
    private int bounceRestart;
    // Set from the packet thread when the server sends the player back.
    private volatile boolean rubberbanded;

    public ElytraFly() {
        super("ElytraFly", "Full elytra control without firework rockets.", Category.MOVEMENT);
        addSettings(mode, speed, climbSpeed, holdHeight, lookToDive, fallMultiplier, autoHover,
            instantStop, sneakDrop, acceleration, accelerationStart, accelerationStep,
            autoPilot, autoPilotHeight,
            cruiseSpeed, altitude, lowerHeight, upperHeight, pitchUpSpeed, pitchDownSpeed,
            rockets, rocketDelay, lockYaw,
            bounceHeading, bounceYaw, lockPitch, bouncePitch, autoJump, manualTakeOff,
            sprintAlways, restart, restartDelay, instantDrop,
            autoTakeOff, groundStart, keepGliding, stopInWater, chunkGuard, chunkLookahead,
            noCrash, crashLookAhead, replaceElytra, replaceAt, durabilityGuard,
            chestSwap, replenishRockets, rocketSlot);
        searchTags("elytra", "glide", "fly", "cruise", "bounce", "packet fly", "pitch 40");
    }

    public boolean inCruiseMode() {
        return mode.is(Mode.CRUISE);
    }

    @Override
    public String getSuffix() {
        return switch (mode.getValue()) {
            case CRUISE -> cruising ? (diving ? "diving" : "climbing") : "cruise";
            case PACKET -> "packet";
            case BOUNCE -> "bounce";
            case CONTROL -> speed.getValueString() + " " + climbSpeed.getValueString();
        };
    }

    @Override
    protected void onEnable() {
        applied = mode.getValue();
        restartCooldown = 0;
        takeOffWindow = 0;
        wasGliding = false;
        cruising = false;
        warned = false;
        dropped = false;
        rocketTimer = 0;
        bounceRestart = 0;
        rubberbanded = false;
        heldY = Double.NaN;
        holdBias = 0;
        ramp = 0;
        pilotHeld = false;
        reopen = false;
        if (inGame() && chestSwap.getValue() != ChestSwapMode.NEVER
            && !mc.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)) {
            swapChest(false);
        }
    }

    @Override
    protected void onDisable() {
        cruising = false;
        releaseBounceKeys();
        releasePilot();
        restoreFovScale();
        if (mc.player == null) {
            return;
        }
        if (applied == Mode.PACKET) {
            endPacketFlight();
        }
        if (instantDrop.isOn() && applied != Mode.BOUNCE && mc.player.isFallFlying()) {
            watch(dropWatcher);
        }
        if (chestSwap.is(ChestSwapMode.ALWAYS)) {
            swapChest(true);
        } else if (chestSwap.is(ChestSwapMode.WAIT_FOR_GROUND)) {
            watch(landingWatcher);
        }
    }

    private static void watch(Object watcher) {
        OfflineClient.INSTANCE.getEventBus().register(watcher);
    }

    private static void unwatch(Object watcher) {
        OfflineClient.INSTANCE.getEventBus().unregister(watcher);
    }

    // ChestSwap decides which piece goes on. Taking the elytra off only makes
    // sense whilst one is worn.
    private void swapChest(boolean takeOff) {
        ChestSwap module = Modules.get(ChestSwap.class);
        if (module == null) {
            return;
        }
        if (takeOff && !mc.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)) {
            return;
        }
        module.swap();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.player.isSpectator()) {
            return;
        }
        // A mode change lets go of whatever the old mode was holding.
        if (applied != mode.getValue()) {
            releaseBounceKeys();
            releasePilot();
            restoreFovScale();
            if (applied == Mode.PACKET) {
                endPacketFlight();
            }
            applied = mode.getValue();
        }
        if (restartCooldown > 0) {
            restartCooldown--;
        }
        if (rocketTimer > 0) {
            rocketTimer--;
        }
        if (stopInWater.isOn() && (mc.player.isInWater() || mc.player.isUnderWater())) {
            cruising = false;
            wasGliding = false;
            ramp = 0;
            releaseBounceKeys();
            releasePilot();
            return;
        }
        if (replaceElytra.isOn()) {
            replaceWornElytra();
        }
        if (replenishRockets.isOn()) {
            replenishHotbarRockets();
        }
        if (durabilityGuard.isOn() && checkDurability()) {
            cruising = false;
            wasGliding = mc.player.isFallFlying();
            releaseBounceKeys();
            releasePilot();
            return;
        }
        if (dropped && !mc.player.input.keyPresses.shift()) {
            dropped = false;
        }

        if (mode.is(Mode.PACKET)) {
            pilotTick();
            packetTick();
            return;
        }
        if (mode.is(Mode.BOUNCE)) {
            bounceTick();
            return;
        }

        if (takeOffWindow > 0) {
            takeOffWindow--;
        }
        if (!mc.player.isFallFlying()) {
            cruising = false;
            heldY = Double.NaN;
            ramp = 0;
            releasePilot();
            if (!dropped) {
                jumpOff();
                startGlide();
            }
            wasGliding = false;
            return;
        }
        takeOffWindow = 0;
        wasGliding = true;
        pilotTick();
        if (mode.is(Mode.CONTROL) && sneakDrop.isOn() && mc.player.input.keyPresses.shift()) {
            // The same command that opens a glide closes one that is already open.
            sendStartGlide();
            dropped = true;
            return;
        }

        if (mode.is(Mode.CRUISE)) {
            cruiseTick();
        } else {
            cruising = false;
            controlTick();
        }

        if (chunkGuard.isOn() && aheadIsUnloaded()) {
            brakeForChunks();
        }
        if (noCrash.isOn()) {
            brakeForBlocks();
        }
    }

    // Sweeps the body along the flight path a tick at a time and brakes before
    // the first tick that would touch a block. A margin covers the glide's added speed.
    private void brakeForBlocks() {
        Vec3 velocity = mc.player.getDeltaMovement();
        double pace = velocity.length();
        if (pace < 0.01) {
            return;
        }
        int ticks = (int) Math.ceil(Math.max(crashLookAhead.getValue(), pace * CRASH_TICKS) / pace);
        ticks = Math.min(ticks, CRASH_MAX_TICKS);
        AABB box = mc.player.getBoundingBox();
        for (int step = 1; step <= ticks; step++) {
            if (mc.level.noCollision(mc.player, box.move(velocity.scale(step)))) {
                continue;
            }
            // Stop short of the tick that would hit.
            // Two ticks out means one tick of travel is left.
            double allowed = pace * Math.max(0, step - 1) / ticks * CRASH_KEEP;
            if (step <= 2) {
                allowed = 0;
            }
            if (pace > allowed) {
                mc.player.setDeltaMovement(velocity.scale(allowed / pace));
            }
            return;
        }
    }

    // A worn elytra at the threshold gives way to the fullest one in the bag.
    private void replaceWornElytra() {
        ItemStack worn = mc.player.getItemBySlot(EquipmentSlot.CHEST);
        if (!worn.is(Items.ELYTRA) || worn.getMaxDamage() - worn.getDamageValue() > replaceAt.getInt()) {
            return;
        }
        if (!InventoryUtil.inventoryFree()) {
            return;
        }
        int best = -1;
        int bestLeft = worn.getMaxDamage() - worn.getDamageValue();
        for (int i = 0; i < InventoryUtil.WHOLE_INVENTORY; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!stack.is(Items.ELYTRA)) {
                continue;
            }
            int left = stack.getMaxDamage() - stack.getDamageValue();
            if (left > bestLeft) {
                bestLeft = left;
                best = i;
            }
        }
        if (best != -1) {
            InventoryUtil.swap(InventoryUtil.networkSlot(best), InventoryUtil.CHEST_SLOT);
        }
    }

    // A stack from the bag fills the chosen slot once the hotbar has run dry.
    private void replenishHotbarRockets() {
        if (InventoryUtil.hotbarSlot(stack -> stack.is(Items.FIREWORK_ROCKET)) != -1
            || !InventoryUtil.inventoryFree()) {
            return;
        }
        int found = InventoryUtil.findSlot(Items.FIREWORK_ROCKET, InventoryUtil.WHOLE_INVENTORY);
        if (found == -1) {
            return;
        }
        InventoryUtil.swap(InventoryUtil.networkSlot(found),
            InventoryUtil.networkSlot(rocketSlot.getInt() - 1));
    }

    // Holds forward whilst gliding high enough and lets go below that.
    private void pilotTick() {
        boolean wanted = autoPilot.isOn() && mc.player.isFallFlying()
            && mc.player.getY() > autoPilotHeight.getValue();
        if (wanted) {
            InputUtil.hold(mc.options.keyUp);
            pilotHeld = true;
        } else {
            releasePilot();
        }
    }

    private void releasePilot() {
        if (!pilotHeld) {
            return;
        }
        pilotHeld = false;
        InputUtil.release(mc.options.keyUp);
    }

    // The speed the keys push with this tick. Acceleration ramps it up from a
    // start value and the ramp falls back to nothing whilst the keys are idle.
    private double rampedSpeed(boolean steering) {
        if (!acceleration.isOn()) {
            return speed.getValue();
        }
        if (!steering) {
            ramp = 0;
            return 0;
        }
        double from = Math.max(ramp, accelerationStart.getValue());
        ramp = Math.min(from + accelerationStep.getValue() * RAMP_UNIT, speed.getValue());
        return ramp;
    }

    // Sprinting only on the ground pumps the view zoom on and off every hop.
    // The zoom is switched off for the ride and put back after.
    private void applyFovScale() {
        if (sprintAlways.isOn() || savedFovScale >= 0) {
            return;
        }
        savedFovScale = mc.options.fovEffectScale().get();
        mc.options.fovEffectScale().set(0.0);
    }

    private void restoreFovScale() {
        if (savedFovScale < 0) {
            return;
        }
        mc.options.fovEffectScale().set(savedFovScale);
        savedFovScale = -1;
    }

    // True when the ground ahead has not loaded. The client sees empty air where
    // the server has terrain.
    private boolean aheadIsUnloaded() {
        Vec3 velocity = mc.player.getDeltaMovement();
        if (velocity.horizontalDistance() < 0.1) {
            return false;
        }
        Vec3 heading = new Vec3(velocity.x, 0, velocity.z).normalize()
            .scale(chunkLookahead.getValue());
        BlockPos ahead = BlockPos.containing(mc.player.position().add(heading));
        return !mc.level.isLoaded(ahead);
    }

    // Bleeds the speed off each tick. The glide stays controllable.
    private void brakeForChunks() {
        Vec3 velocity = mc.player.getDeltaMovement();
        mc.player.setDeltaMovement(velocity.x * CHUNK_BRAKE, Math.max(velocity.y, -0.1),
            velocity.z * CHUNK_BRAKE);
    }

    // Additive nudges. The glide itself stays vanilla.
    private void controlTick() {
        Input keys = mc.player.input.keyPresses;
        Vec3 velocity = mc.player.getDeltaMovement();
        boolean steering = keys.forward() || keys.backward() || keys.left() || keys.right();
        double accel = GRAVITY * rampedSpeed(steering);

        double vx = velocity.x;
        double vy = velocity.y;
        double vz = velocity.z;

        double yaw = Math.toRadians(mc.player.getYRot());
        double forwardX = -Math.sin(yaw);
        double forwardZ = Math.cos(yaw);
        double rightX = -Math.cos(yaw);
        double rightZ = -Math.sin(yaw);

        if (keys.forward()) {
            vx += forwardX * accel;
            vz += forwardZ * accel;
        }
        if (keys.backward()) {
            vx -= forwardX * accel;
            vz -= forwardZ * accel;
        }
        if (keys.right()) {
            vx += rightX * accel;
            vz += rightZ * accel;
        }
        if (keys.left()) {
            vx -= rightX * accel;
            vz -= rightZ * accel;
        }

        double climb = climbSpeed.getValue();
        float pitch = mc.player.getXRot();
        boolean holding = false;
        if (keys.jump()) {
            vy += GRAVITY * climb;
        } else if (keys.shift()) {
            vy = autoHover.isOn() ? hover(vy, climb) : vy - GRAVITY / 2 * climb;
        } else if (holdHeight.isOn() && pitch < 0 && steering) {
            // Looking up whilst moving climbs.
            // The lift only stops the sink underneath the climb.
            vy = Math.max(vy, glideSink(pitch));
        } else if (holdHeight.isOn() && (!lookToDive.isOn() || pitch < HOLD_PITCH_LIMIT)) {
            vy = holdHeight(vy);
            holding = true;
        } else if (!holdHeight.isOn() && vy < 0) {
            vy *= fallMultiplier.getValue();
        }
        if (!holding) {
            heldY = Double.NaN;
        }

        if (instantStop.isOn() && !steering) {
            vx = 0;
            vz = 0;
            // The lift that holds the height is not momentum.
            if (!keys.jump() && !holding && vy > 0) {
                vy = 0;
            }
        }

        mc.player.setDeltaMovement(vx, vy, vz);
    }

    // The glide loses this much height a tick at a pitch.
    // Zero at the pitch where lift matches gravity.
    private static double glideSink(float pitch) {
        double cos = Math.cos(Math.toRadians(pitch));
        return GRAVITY * (1 - 0.75 * cos * cos);
    }

    // Holds the height the player had when the vertical keys let go.
    // Any drift that creeps in is pulled back and slowly learned away.
    private double holdHeight(double vy) {
        double y = mc.player.getY();
        if (Double.isNaN(heldY)) {
            heldY = y;
            holdBias = 0;
        }
        double error = heldY - y;
        holdBias = Math.clamp(holdBias + error * HOLD_LEARN, -HOLD_BIAS_LIMIT, HOLD_BIAS_LIMIT);
        double correction = Math.clamp(error * HOLD_GAIN, -HOLD_STEP, HOLD_STEP);
        return glideSink(mc.player.getXRot()) + correction + holdBias;
    }

    // Eases down onto the ground and stops a small gap above it.
    private double hover(double vy, double climb) {
        double gap = groundGap(HOVER_APPROACH);
        if (gap < 0) {
            return vy - GRAVITY / 2 * climb;
        }
        if (gap <= HOVER_GAP) {
            return Math.max(vy, glideSink(mc.player.getXRot()));
        }
        return Math.max(vy, -HOVER_SINK);
    }

    // Blocks of air under the feet up to the reach or minus one for more than that.
    private double groundGap(double reach) {
        Vec3 feet = mc.player.position();
        HitResult hit = mc.level.clip(new ClipContext(feet, feet.subtract(0, reach, 0),
            ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, mc.player));
        return hit.getType() == HitResult.Type.MISS ? -1 : feet.y - hit.getLocation().y;
    }

    private void cruiseTick() {
        if (!cruising) {
            cruising = true;
            cruiseY = mc.player.getY();
            cruiseYaw = mc.player.getYRot();
            diving = true;
            forcedPitch = mc.player.getXRot();
        }

        Input keys = mc.player.input.keyPresses;
        if (altitude.is(Altitude.TAKE_OFF)) {
            if (keys.jump()) {
                cruiseY += 1;
            } else if (keys.shift()) {
                cruiseY -= 1;
            }
        }

        Vec3 velocity = mc.player.getDeltaMovement();
        double bps = velocity.horizontalDistance() * TICKS_PER_SECOND;
        double target = cruiseSpeed.getValue();
        double y = mc.player.getY();

        if (altitude.is(Altitude.BAND)) {
            // The two heights alone turn the cycle. A stall still earns a rocket.
            if (diving && y <= lowerHeight.getValue()) {
                diving = false;
            } else if (!diving && y >= upperHeight.getValue()) {
                diving = true;
            }
            if (bps < target * STALL_FRACTION) {
                fireRocket();
            }
        } else if (bps < target * STALL_FRACTION) {
            diving = true;
            fireRocket();
        } else if (bps >= target) {
            diving = false;
        } else {
            diving = altitude.is(Altitude.FREE) || y >= cruiseY;
        }

        float wanted = diving ? DIVE_PITCH : CLIMB_PITCH;
        float step = diving ? pitchDownSpeed.getFloat() : pitchUpSpeed.getFloat();
        forcedPitch = forcedPitch + Mth.clamp(wanted - forcedPitch, -step, step);
    }

    // The cruise angle goes on after the entity tick has saved the old one. The camera
    // then eases between the two over one frame instead of jumping the whole step.
    @Subscribe
    private void onPostMotion(PostMotionEvent event) {
        if (!cruising || !inGame()) {
            return;
        }
        mc.player.setXRot(forcedPitch);
        if (lockYaw.isOn()) {
            mc.player.setYRot(cruiseYaw);
        }
    }

    // ElytraBoost does the swapping and the firing.
    // Its own auto mode stands aside whilst cruising.
    private void fireRocket() {
        if (!rockets.isOn() || rocketTimer > 0) {
            return;
        }
        ElytraBoost boost = Modules.get(ElytraBoost.class);
        if (boost == null) {
            return;
        }
        boost.fire();
        rocketTimer = (int) Math.round(rocketDelay.getValue() * TICKS_PER_SECOND);
    }

    // Creative style flight with an elytra on.
    // The server hears a glide start and a ground flag each tick. Some take it as real.
    private void packetTick() {
        if (!mc.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)) {
            endPacketFlight();
            return;
        }
        Abilities abilities = mc.player.getAbilities();
        abilities.flying = true;
        abilities.setFlyingSpeed(0);

        Input keys = mc.player.input.keyPresses;
        double vertical = MovementUtil.FLY_VERTICAL * climbSpeed.getValue();
        double vy = 0;
        if (keys.jump()) {
            vy += vertical;
        }
        if (keys.shift()) {
            vy -= vertical;
        }
        Vec3 heading = MovementUtil.inputDirection();
        double horizontal = MovementUtil.FLY_HORIZONTAL * rampedSpeed(heading != Vec3.ZERO);
        mc.player.setDeltaMovement(heading.x * horizontal, vy, heading.z * horizontal);

        sendStartGlide();
        mc.player.connection.send(new ServerboundMovePlayerPacket.StatusOnly(true,
            mc.player.horizontalCollision));
    }

    private void endPacketFlight() {
        if (mc.player.isCreative() || mc.player.isSpectator()) {
            return;
        }
        mc.player.getAbilities().flying = false;
        mc.player.getAbilities().setFlyingSpeed(MovementUtil.VANILLA_FLY_SPEED);
    }

    // Holds forward and jump and reopens the wings every hop.
    // A rubberband closes them for a moment to let the server settle.
    private void bounceTick() {
        if (rubberbanded) {
            rubberbanded = false;
            mc.player.stopFallFlying();
            if (restart.isOn()) {
                bounceRestart = restartDelay.getInt();
                reopen = true;
            }
        }
        if (bounceRestart > 0) {
            bounceRestart--;
            releaseBounceKeys();
            return;
        }
        if (mc.player.isPassenger() || mc.player.getAbilities().flying
            || !mc.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)) {
            releaseBounceKeys();
            return;
        }
        applyFovScale();
        bounceKeys = true;
        mc.options.keyUp.setDown(true);
        if (autoJump.isOn()) {
            mc.options.keyJump.setDown(true);
        } else {
            InputUtil.release(mc.options.keyJump);
        }
        if (lockPitch.isOn()) {
            mc.player.setXRot(bouncePitch.getFloat());
        }
        switch (bounceHeading.getValue()) {
            case SNAP -> mc.player.setYRot(Math.round(mc.player.getYRot() / YAW_SNAP) * YAW_SNAP);
            case FIXED -> mc.player.setYRot(bounceYaw.getFloat());
            case FREE -> { }
        }
        // Sprinting in the air upsets some anti cheats.
        // Sprint always is for servers that do not mind it.
        mc.player.setSprinting(sprintAlways.isOn() || mc.player.onGround()
            || !mc.player.isFallFlying());
        if (mc.player.isFallFlying() || mc.player.onGround() || restartCooldown > 0) {
            return;
        }
        // A manual take off still reopens the wings once after a pull back.
        if (!manualTakeOff.isOn() || reopen) {
            reopen = false;
            openGlide();
        }
    }

    private void releaseBounceKeys() {
        if (!bounceKeys) {
            return;
        }
        bounceKeys = false;
        InputUtil.release(mc.options.keyUp);
        InputUtil.release(mc.options.keyJump);
    }

    // Fired on the netty thread.
    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        if (!(event.getPacket() instanceof ClientboundPlayerPositionPacket)) {
            return;
        }
        ramp = 0;
        if (mode.is(Mode.BOUNCE)) {
            rubberbanded = true;
        }
    }

    // Leaves the ground to give the glide something to start from.
    private void jumpOff() {
        if (!groundStart.isOn() || takeOffWindow > 0 || restartCooldown > 0) {
            return;
        }
        if (!mc.player.onGround() || !mc.player.input.keyPresses.jump()
            || mc.player.isPassenger() || mc.player.getAbilities().flying
            || !mc.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)) {
            return;
        }
        mc.player.jumpFromGround();
        // The glide has to open whilst still rising. The usual falling test is skipped.
        takeOffWindow = TAKE_OFF_WINDOW;
    }

    private void startGlide() {
        if (restartCooldown > 0) {
            return;
        }
        if (takeOffWindow > 0) {
            if (!mc.player.onGround()
                && mc.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)) {
                openGlide();
            }
            return;
        }
        boolean allowed = wasGliding ? keepGliding.isOn() : autoTakeOff.isOn();
        if (!allowed) {
            return;
        }
        if (mc.player.onGround() || mc.player.isPassenger() || mc.player.isInWater()
            || mc.player.getAbilities().flying
            || !mc.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)) {
            return;
        }
        if (mc.player.getDeltaMovement().y >= 0) {
            return;
        }
        // Close to the ground a glide stutters between landing and gliding.
        double clearance = wasGliding ? 1.5 : TAKE_OFF_CLEARANCE;
        if (!mc.level.noCollision(mc.player,
            mc.player.getBoundingBox().expandTowards(0, -clearance, 0))) {
            return;
        }
        openGlide();
    }

    private void openGlide() {
        sendStartGlide();
        restartCooldown = RESTART_COOLDOWN;
    }

    // The server opens the elytra on this packet alone.
    static void sendStartGlide() {
        mc.player.connection.send(new ServerboundPlayerCommandPacket(mc.player,
            ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
    }

    // Warns once.
    private boolean checkDurability() {
        ItemStack chest = mc.player.getItemBySlot(EquipmentSlot.CHEST);
        if (!chest.is(Items.ELYTRA) || !chest.isDamageableItem()) {
            warned = false;
            return false;
        }
        int max = chest.getMaxDamage();
        int left = max - chest.getDamageValue();
        if (left > max * LOW_DURABILITY) {
            warned = false;
            return false;
        }
        if (!warned) {
            warned = true;
            ChatUtil.error("Your elytra is nearly broken. ElytraFly has stopped helping.");
        }
        return true;
    }
}
