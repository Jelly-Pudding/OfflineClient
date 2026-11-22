package com.jellypudding.offlineclient.modules.movement;

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
import com.jellypudding.offlineclient.util.CrashGuard;
import com.jellypudding.offlineclient.util.DamageUtil;
import com.jellypudding.offlineclient.util.GlideSwitch;
import com.jellypudding.offlineclient.util.HeldKey;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.ItemUtil;
import com.jellypudding.offlineclient.util.Lagback;
import com.jellypudding.offlineclient.util.LoadedChunks;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.MovementUtil;

import net.minecraft.SharedConstants;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

// Control nudges the vanilla glide and Cruise flies a dive and climb cycle on its own.
// Packet flies like creative inside an open glide and Bounce hops along the ground.
public final class ElytraFly extends Module {

    public enum Mode { CONTROL, CRUISE, PACKET, BOUNCE }
    public enum Descent { SINK, HOVER, DROP }
    public enum Altitude { TAKE_OFF, BAND, FREE }
    public enum ChestSwapMode { NEVER, ALWAYS, WAIT_FOR_GROUND }

    // Why the glide is shut. It decides what may open it again.
    private enum Shut {
        // Not open since the player last stood on the ground. Auto take off opens it.
        LANDED,
        // Shut by the game in midair. Keep gliding opens it.
        CANCELLED,
        // Shut by sneak that is still held. Nothing opens it.
        DROPPED,
        // Shut by sneak that has been let go. It opens whatever the take off settings say.
        LET_GO,
        // Shut to fall the last of the way down. Nothing opens it before the ground.
        LANDING
    }

    // Around forty degrees down is where a glide trades the most height for speed.
    private static final float DIVE_PITCH = 40;
    private static final float CLIMB_PITCH = -30;

    private static final double RAMP_UNIT = 0.1;

    private static final double STALL_FRACTION = 0.5;

    private static final double LOW_DURABILITY = 0.05;
    // Twice the guard leaves an elytra at least twenty seconds of help to come down with.
    private static final double WEARING_OUT = LOW_DURABILITY * 2;

    // Close to the ground a glide stutters between landing and gliding. A glide that
    // was open a moment ago may start again nearer to it.
    private static final double TAKE_OFF_CLEARANCE = 3;
    private static final double RESTART_CLEARANCE = 1.5;

    // A Packet descent the crash guard stops this close over the ground is finished as a
    // fall. The server may hold a block of fall against the glide and three in all cost nothing.
    private static final double LANDING_DROP = DamageUtil.SAFE_FALL - 1;

    private static final double CHUNK_BRAKE = 0.6;

    private static final int TAKE_OFF_WINDOW = 4;

    private static final float HOLD_PITCH_LIMIT = 25;

    private static final double HOLD_GAIN = 0.3;
    private static final double HOLD_STEP = 0.1;
    private static final double HOLD_LEARN = 0.02;
    private static final double HOLD_BIAS_LIMIT = 0.05;

    private static final double HOVER_APPROACH = 2;
    private static final double HOVER_GAP = 0.4;
    private static final double HOVER_SINK = 0.1;

    private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
        "Who does the flying.", Mode.CONTROL)
        .describe(Mode.CONTROL, "You steer. The keys push you along and up and down.")
        .describe(Mode.CRUISE, "Flies a dive and climb cycle on its own for long trips.")
        .describe(Mode.PACKET, "Flies like creative mode whilst the server sees a glide.")
        .describe(Mode.BOUNCE, "Hops along the ground with the wings open. Fast on the nether roof.");
    private final NumberSetting speed = new NumberSetting("Horizontal speed",
        "How hard the movement keys push you along.", 1, 0.2, 5, 0.1, "x")
        .min(0.1).under(mode, Mode.CONTROL, Mode.PACKET);
    private final NumberSetting climbSpeed = new NumberSetting("Vertical speed",
        "How hard jump and sneak push you up and down.", 1, 0.2, 5, 0.1, "x")
        .min(0.1).under(mode, Mode.CONTROL, Mode.PACKET);
    private final BoolSetting holdHeight = new BoolSetting("Hold height",
        "Holds you at your current height whilst neither jump nor sneak is held.", true)
        .under(mode, Mode.CONTROL);
    private final BoolSetting lookToDive = new BoolSetting("Look to dive",
        "Looking down steeply lets the glide dive as it would without the hold.", false)
        .under(holdHeight);
    private final EnumSetting<Descent> onSneak = new EnumSetting<>("On sneak",
        "What holding sneak does whilst you glide.", Descent.SINK)
        .describe(Descent.SINK, "Sneak pushes you down by the vertical speed.")
        .describe(Descent.HOVER, "Sneak sinks you slowly and stops just above the ground instead of landing.")
        .describe(Descent.DROP, "Sneak ends the glide and lets you fall. The wings open again when you let go.")
        .under(mode, Mode.CONTROL);
    private final BoolSetting instantStop = new BoolSetting("Instant stop",
        "Kills your momentum when you release the keys.", false)
        .under(mode, Mode.CONTROL);
    private final NumberSetting fallMultiplier = new NumberSetting("Fall multiplier",
        "Scales the natural sink whilst Hold height is off. Lower values sink slower.",
        1, 0, 1, 0.01, "x")
        .under(holdHeight, () -> mode.is(Mode.CONTROL) && !holdHeight.isOn());
    private final BoolSetting acceleration = new BoolSetting("Acceleration",
        "Builds the horizontal speed up over time instead of applying it at once.", false)
        .under(mode, Mode.CONTROL, Mode.PACKET);
    private final NumberSetting accelerationStart = new NumberSetting("Acceleration start",
        "The speed the ramp begins from.", 0, 0, 5, 0.1, "x")
        .under(acceleration);
    private final NumberSetting accelerationStep = new NumberSetting("Acceleration step",
        "How quickly the ramp climbs towards the horizontal speed.", 1, 0.1, 5, 0.1, "")
        .under(acceleration);
    private final BoolSetting autoPilot = new BoolSetting("Auto pilot",
        "Holds forward for you whilst you glide above the minimum height.", false)
        .under(mode, Mode.CONTROL, Mode.PACKET);
    private final NumberSetting autoPilotHeight = new NumberSetting("Minimum height",
        "Auto pilot only pushes forward above this height.", 120, -64, 320, 1, "")
        .min(-128)
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
        .min(-128)
        .under(altitude, Altitude.BAND);
    private final NumberSetting upperHeight = new NumberSetting("Upper height",
        "Starts diving once you rise to this height.", 220, -64, 320, 1, "")
        .min(-128)
        .under(altitude, Altitude.BAND);
    private final NumberSetting pitchUpSpeed = new NumberSetting("Pitch up speed",
        "Degrees a tick the nose comes up when a climb starts.", 6, 0.5, 20, 0.05, " degrees")
        .under(mode, Mode.CRUISE);
    private final NumberSetting pitchDownSpeed = new NumberSetting("Pitch down speed",
        "Degrees a tick the nose goes down when a dive starts.", 6, 0.5, 20, 0.05, " degrees")
        .under(mode, Mode.CRUISE);
    private final BoolSetting rockets = new BoolSetting("Rockets",
        "Fires a rocket whenever the cycle runs out of speed. The ElytraBoost settings choose the rocket.", false)
        .under(mode, Mode.CRUISE);
    private final NumberSetting rocketDelay = new NumberSetting("Rocket delay",
        "Seconds between rockets.", 4, 1, 20, 0.5, "s").min(0.5)
        .under(rockets);
    private final BoolSetting lockYaw = new BoolSetting("Lock heading",
        "Keeps the heading you set off with.", true)
        .under(mode, Mode.CRUISE);
    private final ElytraBounce bounce = new ElytraBounce().under(mode, Mode.BOUNCE);
    private final BoolSetting instantDrop = new BoolSetting("Instant drop",
        "Turning the module off midair ends the glide and drops you at once.", false)
        .under(mode, Mode.CONTROL, Mode.CRUISE, Mode.PACKET);
    private final BoolSetting autoTakeOff = new BoolSetting("Auto take off",
        "Opens the elytra for you as soon as you fall.", true)
        .visibleWhen(this::fliesFree);
    private final BoolSetting groundStart = new BoolSetting("Ground start",
        "Jumps straight into a glide from standing.", true)
        .visibleWhen(this::fliesFree);
    private final BoolSetting keepGliding = new BoolSetting("Keep gliding",
        "Restarts a glide the game cancels midair.", true)
        .visibleWhen(this::fliesFree);
    private final BoolSetting stopInWater = new BoolSetting("Stop in water",
        "Does nothing whilst you are in water.", true);
    private final BoolSetting chunkGuard = new BoolSetting("Chunk guard",
        "Brakes before you fly into ground the client has not loaded.", true)
        .visibleWhen(this::fliesFree);
    private final NumberSetting chunkLookahead = new NumberSetting("Look ahead",
        "How far in front to check for loaded ground.", 24, 8, 64, 1, " blocks").min(1)
        .under(chunkGuard);
    private final CrashGuard crashGuard = new CrashGuard().shownWhen(this::fliesFree);
    private final BoolSetting replaceElytra = new BoolSetting("Replace elytra",
        "Swaps in a fresher elytra from your inventory once the worn one runs low.", false);
    private final NumberSetting replaceAt = new NumberSetting("Replace at",
        "Swaps once the worn elytra has this much durability left. Below five percent Durability guard"
            + " stops helping first.", 10, 1, 50, 1, "%").max(99)
        .under(replaceElytra);
    private final BoolSetting durabilityGuard = new BoolSetting("Durability guard",
        "Stops helping and warns you when the elytra is nearly broken.", true);
    private final EnumSetting<ChestSwapMode> chestSwap = new EnumSetting<>("Chest swap",
        "Uses ChestSwap to wear the elytra whilst the module is on.", ChestSwapMode.NEVER)
        .describe(ChestSwapMode.NEVER, "Leaves your chest slot alone.")
        .describe(ChestSwapMode.ALWAYS, "Puts the chestplate back as soon as the module is off.")
        .describe(ChestSwapMode.WAIT_FOR_GROUND, "Puts the chestplate back once you land after the module is off.");

    private Mode applied;

    private double ramp;
    // The forward key Auto pilot holds. Bounce holds its own and the two never run together.
    private final HeldKey forward = new HeldKey(options -> options.keyUp);

    private float steerYaw;
    private double steerHeight;
    private int steerTick = Integer.MIN_VALUE;
    private int stillTick = Integer.MIN_VALUE;

    // Runs on after the module is off until the player has landed.
    private final Object landingWatcher = new Object() {
        @Subscribe
        private void onTick(TickEvent event) {
            if (isEnabled()) {
                unwatch(this);
                return;
            }
            ChestSwap swapper = Modules.get(ChestSwap.class);
            if (swapper == null) {
                unwatch(this);
            } else if (mc.player.onGround() && swapper.canSwap()) {
                unwatch(this);
                swapper.takeOffElytra();
            }
        }
    };

    private ChestSwapMode chestSwapSeen;
    private boolean elytraWanted;

    private final GlideSwitch glide = GlideSwitch.get();
    private Shut shut = Shut.LANDED;
    // Set by the tick that wants the wings open. They open once its position packet has left.
    private boolean opening;
    private int takeOffWindow;
    private boolean warned;
    private boolean packetFlying;

    // NaN whilst the keys have the height.
    private double heldY = Double.NaN;
    private double holdBias;

    private double cruiseY;
    private float cruiseYaw;
    private boolean diving;
    private float forcedPitch;
    private boolean cruising;
    private int rocketTimer;

    private final Lagback.Watcher lagback = new Lagback.Watcher();

    public ElytraFly() {
        super("ElytraFly", "Full elytra control without firework rockets.", Category.MOVEMENT);
        addSettings(mode, speed, climbSpeed, holdHeight, lookToDive, fallMultiplier, onSneak,
            instantStop, acceleration, accelerationStart, accelerationStep,
            autoPilot, autoPilotHeight,
            cruiseSpeed, altitude, lowerHeight, upperHeight, pitchUpSpeed, pitchDownSpeed,
            rockets, rocketDelay, lockYaw);
        addSettings(bounce.settings());
        addSettings(instantDrop, autoTakeOff, groundStart, keepGliding, stopInWater, chunkGuard, chunkLookahead);
        addSettings(crashGuard.settings());
        addSettings(replaceElytra, replaceAt, durabilityGuard, chestSwap);
        searchTags("elytra", "glide", "fly", "cruise", "bounce", "packet fly", "pitch 40");
    }

    public boolean inCruiseMode() {
        return mode.is(Mode.CRUISE);
    }

    private boolean fliesFree() {
        return !mode.is(Mode.BOUNCE);
    }

    // The autopilot calls this each tick it flies you. Cruise takes the heading and the height
    // and Bounce takes the heading. The other modes go by the keys the autopilot holds.
    public void steer(float yaw, double height) {
        steerYaw = yaw;
        steerHeight = height;
        steerTick = mc.player.tickCount;
    }

    // The autopilot leaves jump and sneak alone whilst this is true.
    public boolean keepsOwnHeight() {
        return mode.isAny(Mode.CRUISE, Mode.BOUNCE);
    }

    // The autopilot only turns the view whilst this is true.
    public boolean holdsOwnKeys() {
        return mode.is(Mode.BOUNCE);
    }

    // The autopilot puts a block under you to stand on whilst this is true.
    public boolean hovers() {
        return mode.is(Mode.PACKET);
    }

    // The autopilot calls this each tick it wants a bounce to come to a halt and stand.
    public void holdStill() {
        stillTick = mc.player.tickCount;
    }

    // Why a bounce stands short of what blocks its way in words that follow "stopped bouncing as".
    // Null whilst it does not.
    public String bounceStoppedFor() {
        return mode.is(Mode.BOUNCE) ? bounce.stoppedFor() : null;
    }

    public boolean swapsGliderOn() {
        return !chestSwap.is(ChestSwapMode.NEVER) && InventoryUtil.findSlot(
            stack -> LivingEntity.canGlideUsing(stack, EquipmentSlot.CHEST), InventoryUtil.WHOLE_INVENTORY) != -1;
    }

    // True once the worn elytra nears the durability guard with no spare to put on. A container
    // that stays open takes every click and holds the swap up.
    public boolean wearingOut() {
        ItemStack worn = mc.player.getItemBySlot(EquipmentSlot.CHEST);
        if (!worn.is(Items.ELYTRA) || !ItemUtil.wornBelow(worn, WEARING_OUT)) {
            return false;
        }
        boolean swapsFirst = replaceElytra.isOn() && InventoryUtil.canClick()
            && (!durabilityGuard.isOn() || replaceShare() >= LOW_DURABILITY);
        int spare = swapsFirst ? fresherElytra(worn) : -1;
        return spare == -1 || ItemUtil.wornBelow(mc.player.getInventory().getItem(spare), WEARING_OUT);
    }

    // True on the tick the autopilot steers and the one after.
    private boolean steered() {
        return lately(steerTick);
    }

    private boolean lately(int tick) {
        int since = mc.player.tickCount - tick;
        return since >= 0 && since <= 1;
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
        shut = Shut.LANDED;
        opening = false;
        takeOffWindow = 0;
        cruising = false;
        warned = false;
        rocketTimer = 0;
        bounce.start();
        lagback.sync();
        heldY = Double.NaN;
        holdBias = 0;
        ramp = 0;
        chestSwapSeen = ChestSwapMode.NEVER;
    }

    @Override
    protected void onDisable() {
        letGo();
        bounce.reset();
        if (mc.player == null) {
            return;
        }
        if (instantDrop.isOn() && applied != Mode.BOUNCE && mc.player.isFallFlying()) {
            glide.close();
            mc.player.setDeltaMovement(Vec3.ZERO);
        }
        ChestSwap swapper = Modules.get(ChestSwap.class);
        if (swapper == null) {
            return;
        }
        if (chestSwap.is(ChestSwapMode.ALWAYS)) {
            swapper.takeOffElytra();
        } else if (chestSwap.is(ChestSwapMode.WAIT_FOR_GROUND)) {
            watch(landingWatcher);
        }
    }

    private void wearElytra() {
        if (chestSwap.getValue() != chestSwapSeen) {
            chestSwapSeen = chestSwap.getValue();
            elytraWanted = chestSwapSeen != ChestSwapMode.NEVER;
        }
        ChestSwap swapper = Modules.get(ChestSwap.class);
        if (elytraWanted && swapper != null && swapper.canSwap()) {
            elytraWanted = false;
            swapper.wearElytra();
        }
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (mc.player.isSpectator()) {
            bounce.idle();
            return;
        }
        if (lagback.happened()) {
            ramp = 0;
            bounce.pulledBack(mode.is(Mode.BOUNCE));
        }
        // A mode change lets go of whatever the last mode was holding.
        if (applied != mode.getValue()) {
            letGo();
            bounce.reset();
            applied = mode.getValue();
        }
        if (rocketTimer > 0) {
            rocketTimer--;
        }
        wearElytra();
        if (stopInWater.isOn() && (mc.player.isInWater() || mc.player.isUnderWater())) {
            shut = Shut.LANDED;
            ramp = 0;
            letGo();
            return;
        }
        if (replaceElytra.isOn()) {
            replaceWornElytra();
        }
        if (durabilityGuard.isOn() && checkDurability()) {
            letGo();
            return;
        }
        if (mode.is(Mode.BOUNCE)) {
            if (bounce.tick(steered(), lately(stillTick))) {
                opening = true;
            }
            return;
        }

        if (takeOffWindow > 0) {
            takeOffWindow--;
        }
        if (!mc.player.isFallFlying()) {
            takeOffTick();
            return;
        }
        takeOffWindow = 0;
        pilotTick();
        if (sneakDrops()) {
            glide.close();
            shut = Shut.DROPPED;
            return;
        }
        // A glide found shut on a later tick was shut by the game.
        shut = Shut.CANCELLED;

        switch (mode.getValue()) {
            case CRUISE -> cruiseTick();
            case PACKET -> packetTick();
            default -> controlTick();
        }

        if (chunkGuard.isOn() && aheadIsUnloaded()) {
            brakeForChunks();
        }
        boolean sinking = mc.player.getDeltaMovement().y < 0;
        if (crashGuard.brake(mc.player) && sinking && landsShort()) {
            fallToGround();
        }
    }

    private boolean landsShort() {
        return packetFlying && glide.closesAtOnce()
            && !mc.level.noCollision(mc.player, mc.player.getBoundingBox().expandTowards(0, -LANDING_DROP, 0));
    }

    // The crash guard has stopped a descent. It holds a landing clear of a rise the player
    // faces and where it cannot move them back it would hold it for good. Out of a glide the
    // server charges no wall. The glide is shut ahead of this tick's position and the server
    // never sees it any lower.
    private void fallToGround() {
        glide.close();
        endPacketFlight();
        mc.player.setDeltaMovement(Vec3.ZERO);
        shut = Shut.LANDING;
    }

    private void takeOffTick() {
        cruising = false;
        heldY = Double.NaN;
        ramp = 0;
        forward.letGo();
        endPacketFlight();
        boolean held = sneakDrops();
        if (mc.player.onGround()) {
            shut = Shut.LANDED;
        } else if (shut == Shut.DROPPED && !held) {
            shut = Shut.LET_GO;
        }
        if (!held) {
            jumpOff();
            startGlide();
        }
    }

    // The autopilot holds sneak to come down and never drops.
    private boolean sneakDrops() {
        return mode.is(Mode.CONTROL) && onSneak.is(Descent.DROP) && mc.player.input.keyPresses.shift()
            && !steered();
    }

    private void replaceWornElytra() {
        ItemStack worn = mc.player.getItemBySlot(EquipmentSlot.CHEST);
        if (!worn.is(Items.ELYTRA) || !ItemUtil.wornBelow(worn, replaceShare()) || !InventoryUtil.cursorFree()) {
            return;
        }
        int best = fresherElytra(worn);
        if (best != -1) {
            InventoryUtil.swap(InventoryUtil.networkSlot(best), InventoryUtil.CHEST_SLOT);
        }
    }

    private double replaceShare() {
        return replaceAt.getValue() / 100;
    }

    public static int fresherElytra(ItemStack worn) {
        int best = -1;
        int bestLeft = ItemUtil.usesLeft(worn);
        for (int i = 0; i < InventoryUtil.WHOLE_INVENTORY; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!stack.is(Items.ELYTRA)) {
                continue;
            }
            int left = ItemUtil.usesLeft(stack);
            if (left > bestLeft) {
                bestLeft = left;
                best = i;
            }
        }
        return best;
    }

    private void pilotTick() {
        forward.set(autoPilot.isOn() && mode.isAny(Mode.CONTROL, Mode.PACKET) && !steered()
            && mc.player.getY() > autoPilotHeight.getValue());
    }

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

    // The client sees empty air where the server has terrain that has not loaded.
    private boolean aheadIsUnloaded() {
        Vec3 velocity = mc.player.getDeltaMovement();
        if (velocity.horizontalDistance() < 0.1) {
            return false;
        }
        Vec3 ahead = mc.player.position().add(new Vec3(velocity.x, 0, velocity.z).normalize()
            .scale(chunkLookahead.getValue()));
        return !LoadedChunks.has(ahead.x, ahead.z);
    }

    // Packet takes its speed from the keys afresh every tick and stops dead instead.
    private void brakeForChunks() {
        double kept = mode.is(Mode.PACKET) ? 0 : CHUNK_BRAKE;
        Vec3 velocity = mc.player.getDeltaMovement();
        mc.player.setDeltaMovement(velocity.x * kept, Math.max(velocity.y, -0.1), velocity.z * kept);
    }

    // Additive nudges. The glide itself stays vanilla.
    private void controlTick() {
        Input keys = mc.player.input.keyPresses;
        Vec3 velocity = mc.player.getDeltaMovement();
        boolean steering = keys.forward() || keys.backward() || keys.left() || keys.right();
        Vec3 push = MovementUtil.inputDirection().scale(MovementUtil.GRAVITY * rampedSpeed(steering));
        double vx = velocity.x + push.x;
        double vy = velocity.y;
        double vz = velocity.z + push.z;

        double climb = climbSpeed.getValue();
        float pitch = mc.player.getXRot();
        boolean holding = false;
        if (keys.jump()) {
            vy += MovementUtil.GRAVITY * climb;
        } else if (keys.shift()) {
            vy = onSneak.is(Descent.HOVER) && !steered() ? hover(vy, climb) : vy - MovementUtil.GRAVITY / 2 * climb;
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

    private static double glideSink(float pitch) {
        double cos = Math.cos(Math.toRadians(pitch));
        return MovementUtil.GRAVITY * (1 - 0.75 * cos * cos);
    }

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

    private double hover(double vy, double climb) {
        double gap = groundGap(HOVER_APPROACH);
        if (gap < 0) {
            return vy - MovementUtil.GRAVITY / 2 * climb;
        }
        if (gap <= HOVER_GAP) {
            return Math.max(vy, glideSink(mc.player.getXRot()));
        }
        return Math.max(vy, -HOVER_SINK);
    }

    // Minus one for more air than the reach.
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

        // The autopilot sets the heading and the height the cycle turns around.
        Altitude heights = steered() ? Altitude.TAKE_OFF : altitude.getValue();
        Input keys = mc.player.input.keyPresses;
        if (steered()) {
            cruiseYaw = steerYaw;
            cruiseY = steerHeight;
        } else if (heights == Altitude.TAKE_OFF) {
            if (keys.jump()) {
                cruiseY += 1;
            } else if (keys.shift()) {
                cruiseY -= 1;
            }
        }

        Vec3 velocity = mc.player.getDeltaMovement();
        double bps = velocity.horizontalDistance() * SharedConstants.TICKS_PER_SECOND;
        double target = cruiseSpeed.getValue();
        double y = mc.player.getY();

        if (heights == Altitude.BAND) {
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
            diving = heights == Altitude.FREE || y >= cruiseY;
        }

        float wanted = diving ? DIVE_PITCH : CLIMB_PITCH;
        float step = diving ? pitchDownSpeed.getFloat() : pitchUpSpeed.getFloat();
        forcedPitch = forcedPitch + Mth.clamp(wanted - forcedPitch, -step, step);
    }

    @Subscribe
    private void onPostMotion(PostMotionEvent event) {
        if (!inGame()) {
            return;
        }
        // The wings open behind the position packet of the tick. NoFall still claims ground
        // on that one and the glide starts with no fall held against it.
        if (opening) {
            opening = false;
            glide.open();
        }
        // The cruise angle goes on after the entity tick has saved the angle of the last tick.
        // The camera then eases between the two over one frame instead of jumping the whole step.
        if (cruising) {
            mc.player.setXRot(forcedPitch);
            if (lockYaw.isOn() || steered()) {
                mc.player.setYRot(cruiseYaw);
            }
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
        rocketTimer = (int) Math.round(rocketDelay.getValue() * SharedConstants.TICKS_PER_SECOND);
    }

    // Creative style flight inside a glide the server holds open. An open glide is never
    // kicked for flying. LivingEntityMixin keeps the glide physics off the flight.
    private void packetTick() {
        // The landing packet shuts the glide on the server. The flight ends with it and the
        // ground is left to the player.
        if (mc.player.onGround()) {
            glide.land();
            endPacketFlight();
            return;
        }
        packetFlying = true;
        MovementUtil.flyDirect(MovementUtil.FLY_HORIZONTAL * rampedSpeed(MovementUtil.hasInput()),
            MovementUtil.FLY_VERTICAL * climbSpeed.getValue());
    }

    // A packet flight left on with no fly speed would strand you midair.
    private void endPacketFlight() {
        if (packetFlying) {
            packetFlying = false;
            MovementUtil.endFlight();
        }
    }

    // Read by LivingEntityMixin.
    public boolean flyingInGlide() {
        return packetFlying && mc.player != null && mc.player.getAbilities().flying;
    }

    // Read by CameraMixin. The saved options stay as the player set them.
    public boolean holdsFovStill() {
        return bounce.holdsFovStill();
    }

    private void letGo() {
        cruising = false;
        bounce.letGo();
        forward.letGo();
        endPacketFlight();
    }

    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        if (mode.is(Mode.BOUNCE)) {
            bounce.onPacket(event.getPacket());
        }
    }

    private void jumpOff() {
        // The autopilot takes off whatever Ground start says.
        if (!groundStart.isOn() && !steered() || takeOffWindow > 0) {
            return;
        }
        if (!mc.player.onGround() || !mc.player.input.keyPresses.jump()
            || mc.player.isPassenger() || mc.player.getAbilities().flying
            || !MovementUtil.wearsGlider()) {
            return;
        }
        mc.player.jumpFromGround();
        // The glide has to open whilst still rising. The usual falling test is skipped.
        takeOffWindow = TAKE_OFF_WINDOW;
    }

    private void startGlide() {
        if (takeOffWindow > 0) {
            opening = true;
            return;
        }
        boolean allowed = switch (shut) {
            case LANDED -> autoTakeOff.isOn();
            case CANCELLED -> keepGliding.isOn();
            case DROPPED, LANDING -> false;
            case LET_GO -> true;
        };
        if (!allowed || mc.player.onGround() || mc.player.getAbilities().flying
            || mc.player.getDeltaMovement().y >= 0) {
            return;
        }
        double clearance = shut == Shut.LANDED ? TAKE_OFF_CLEARANCE : RESTART_CLEARANCE;
        opening = mc.level.noCollision(mc.player, mc.player.getBoundingBox().expandTowards(0, -clearance, 0));
    }

    private boolean checkDurability() {
        ItemStack chest = mc.player.getItemBySlot(EquipmentSlot.CHEST);
        if (!chest.is(Items.ELYTRA) || !ItemUtil.wornBelow(chest, LOW_DURABILITY)) {
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
