package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.RightClickEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.modules.misc.Timer;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.AxisWalker;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.FaceMode;
import com.jellypudding.offlineclient.util.HeldKey;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.HotbarLoan;
import com.jellypudding.offlineclient.util.LavaReach;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.PlacementWatch;
import com.jellypudding.offlineclient.util.RotationManager;
import com.jellypudding.offlineclient.util.RotationPriority;
import com.jellypudding.offlineclient.util.UseBudget;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import net.minecraft.client.player.ClientInput;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

// Builds a staircase up or down. At a walking pace the next step goes down in front of you
// as you walk. At a rapid pace a step goes down and you stand on it every tick. The run
// remembers its lowest and highest step for the flow time command.
public final class Staircase extends Module {

    public enum Pace { WALK, RAPID }

    public enum Slope { UP, DOWN }

    public enum Movement { AUTO, MANUAL }

    // The lowest and highest step of a run and the ground under each.
    public record Run(BlockPos lowest, BlockPos highest, int groundUnderLowest, int groundUnderHighest) {

        private static Run from(BlockPos step) {
            int ground = LavaReach.groundBelow(step);
            return new Run(step, step, ground, ground);
        }

        private Run with(BlockPos step) {
            if (step.getY() < lowest.getY()) {
                return new Run(step, highest, LavaReach.groundBelow(step), groundUnderHighest);
            }
            if (step.getY() > highest.getY()) {
                return new Run(lowest, step, groundUnderLowest, LavaReach.groundBelow(step));
            }
            return this;
        }

        // Flow steps for lava poured on the top step.
        public int flowSteps() {
            return LavaReach.staircaseSteps(highest.getY(), lowest.getY(), groundUnderHighest, groundUnderLowest);
        }
    }

    // The movement keys as they are really held.
    private record Keys(boolean forward, boolean back, boolean left, boolean right, boolean jump) {

        private static final Keys NONE = new Keys(false, false, false, false, false);
    }

    private static final String TIMER_KEY = "Staircase";

    // A stair clicked in the lower half of its space lands the right way up.
    private static final double LOWER_QUARTER = 0.25;

    // Walking that gets less than this far across for the whole wait counts as stuck.
    // Jumping on the spot moves you up and down and never across.
    private static final double STUCK_DISTANCE = 0.5;
    private static final int STUCK_TICKS = 40;

    // Headings come in eighths of a turn counted from south as the yaw does.
    private static final float EIGHTH = 45f;
    private static final int EIGHTHS = 8;

    // Following the view a pitch steeper than this builds down. The keys look up or down
    // past it when they turn the stairs.
    private static final float STEEP_PITCH = 40f;
    private static final float LOOK_UP_PITCH = 30f;
    private static final float LOOK_DOWN_PITCH = 60f;

    // Steps that may wait on the server at once. It caps how far you get ahead of a slow
    // server and how far you drop if it refuses them all.
    private static final int MAX_UNANSWERED = 4;

    // Paper throws away the tenth click in 300 milliseconds and never answers it. A click
    // left unanswered this long was thrown away.
    private static final long ANSWER_MILLIS = 1000;

    // A step refused this many times will not stay.
    private static final int MAX_ATTEMPTS = 3;

    // The server refuses a single move of more than ten blocks. A rise of nine with a block
    // across is the most one step can take.
    private static final int MAX_GAP = 8;

    // A landing forgives a fall of three blocks. Each step down lands and pays for its own drop.
    private static final int SAFE_DROP = 3;

    // One tick of gravity after drag. It presses you onto the step as standing does in vanilla.
    // The move lands and every move packet claims the ground. The server never banks the descent.
    private static final Vec3 ONTO_STEP = new Vec3(0, -0.0784, 0);

    // Knocked further than this from the step you stand on ends the run. Closer is pulled back.
    private static final double LOST_DISTANCE = 2;
    private static final double DRIFT = 0.01;

    private final EnumSetting<Pace> pace = new EnumSetting<>("Pace",
        "How the stairs are built.", Pace.WALK)
        .describe(Pace.WALK, "The next step goes down in front of you as you walk.")
        .describe(Pace.RAPID, "A step goes down and you stand on it every tick.");
    private final EnumSetting<Slope> slope = new EnumSetting<>("Direction",
        "Which way the steps go.", Slope.UP)
        .describe(Slope.UP, "Each step is a block higher than the one you stand on.")
        .describe(Slope.DOWN, "Each step is a block lower. It builds over a drop in front of you.");
    private final BoolSetting walkForYou = new BoolSetting("Walk for you",
        "Walks you along the stairs the way you face and jumps each step up. Hold your back key to stand still.",
        false).under(pace, Pace.WALK);
    private final EnumSetting<Movement> movement = new EnumSetting<>("Movement",
        "When a step is taken.", Movement.AUTO)
        .describe(Movement.AUTO, "Every tick until a limit. The use key pauses and resumes it.")
        .describe(Movement.MANUAL, "Only whilst you hold forward or back.")
        .under(pace, Pace.RAPID);
    private final BoolSetting startPaused = new BoolSetting("Start paused",
        "Waits for the use key before the first step.", true)
        .under(movement, Movement.AUTO);
    private final BoolSetting followView = new BoolSetting("Follow view",
        "Builds the way you look and down once you look steeply down. Off keeps the heading you start with and your movement keys steer.",
        true).under(pace, Pace.RAPID);
    private final BoolSetting diagonal = new BoolSetting("Diagonal",
        "Lets the stairs run at forty five degrees when you face that way. Left and right then turn half as far.",
        false).under(pace, Pace.RAPID);
    private final NumberSetting diagonalRun = new NumberSetting("Diagonal run",
        "Steps taken to one side before the stairs turn to the other.", 1, 1, 8, 1, " steps").min(1)
        .under(diagonal);
    private final NumberSetting gap = new NumberSetting("Gap",
        "Empty blocks left between steps whilst you hold jump. Going down leaves at most two.",
        1, 0, 2, 1, " blocks").min(0).max(MAX_GAP)
        .under(pace, Pace.RAPID);
    private final NumberSetting stepDelay = new NumberSetting("Step delay",
        "Ticks between one step and the next.", 0, 0, 10, 1, " ticks").min(0)
        .under(pace, Pace.RAPID);
    private final BoolSetting thenCast = new BoolSetting("Then cast",
        "Climbs to the mountain height and then starts LavaCast down the stairs. The stairs only go up.", false)
        .under(pace, Pace.RAPID);
    private final NumberSetting mountainHeight = new NumberSetting("Mountain height",
        "How many blocks the stairs climb before the cast starts.", 30, 5, 128, 1, " blocks").min(1)
        .under(thenCast);
    private final NumberSetting speedUp = new NumberSetting("Speed up",
        "How many times faster the game runs whilst you build.", 1, 1, 10, 0.1, "x").min(1);
    private final BoolSetting topLimit = new BoolSetting("Top limit",
        "Stops the stairs climbing past a height.", false);
    private final NumberSetting topHeight = new NumberSetting("Top height",
        "The highest your feet go.", 200, -64, 320, 1)
        .min(DimensionType.MIN_Y).max(DimensionType.MAX_Y).under(topLimit);
    private final BoolSetting turnBackAtTop = new BoolSetting("Turn back at top",
        "Turns the stairs back down at the top height or a ceiling instead of stopping.", false);
    private final BoolSetting bottomLimit = new BoolSetting("Bottom limit",
        "Stops the stairs going below a height.", false);
    private final NumberSetting bottomHeight = new NumberSetting("Bottom height",
        "The lowest your feet go.", 64, -64, 320, 1)
        .min(DimensionType.MIN_Y).max(DimensionType.MAX_Y).under(bottomLimit);
    private final BoolSetting turnBackAtBottom = new BoolSetting("Turn back at bottom",
        "Turns the stairs back up at the bottom height or a floor instead of stopping.", false);
    private final RegistryListSetting<Block> blocks = new RegistryListSetting<>("Blocks",
        "The blocks and stairs the steps are made of. An empty list allows any.",
        BuiltInRegistries.BLOCK,
        List.of(Blocks.COBBLESTONE, Blocks.COBBLESTONE_STAIRS, Blocks.COBBLED_DEEPSLATE,
            Blocks.COBBLED_DEEPSLATE_STAIRS, Blocks.STONE, Blocks.STONE_STAIRS, Blocks.NETHERRACK,
            Blocks.DIRT));
    private final BoolSetting stairsFirst = new BoolSetting("Stairs first",
        "Uses stairs before full blocks. Stairs are walked up without a jump.", true);
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Sends a look packet towards each full block. Stairs always turn to set the way they face.", true);
    private final BoolSetting showNextStep = new BoolSetting("Show next step",
        "Draws where the next step goes and the top step of a climb with a height to reach.", true);
    private final BoxStyle nextStyle = new BoxStyle("Next", BoxStyle.Shape.BOTH, 278).under(showNextStep);
    private final BoolSetting showEnds = new BoolSetting("Show ends",
        "Draws the lowest and highest step of this run.", false);
    private final BoxStyle endsStyle = new BoxStyle("Ends", BoxStyle.Shape.LINES, 190).under(showEnds);

    private final HotbarLoan loan = new HotbarLoan();
    private final HeldKey forward = new HeldKey(options -> options.keyUp);
    private final AxisWalker walker = new AxisWalker();
    private final PlacementWatch placements = new PlacementWatch();
    private final ClientInput stillInput = new ClientInput();

    private Run run;

    // Walking pace. The heading Walk for you keeps and the progress that tells it is stuck.
    private Direction lineHeading;
    private Vec3 lastPos;
    private int stillTicks;

    // Set by a turn back until you stand on another step. A second end in a row stops
    // instead of turning back and forth against a wall.
    private boolean justTurned;
    private BlockPos lastStep;

    // Rapid pace.
    private boolean paused;
    private boolean useHeld;
    private Keys keys = Keys.NONE;
    private int heading;
    private int diagonalSteps;
    private boolean secondSide;
    private int stepHeading = -1;
    private Direction lastWay;
    private BlockPos standingOn;
    private double standY;
    private int delayLeft;
    private boolean endNoted;
    private long lastSettleAt;
    private ClientInput ownInput;

    public Staircase() {
        super("Staircase", "Builds a staircase up or down as you walk or at a rapid pace.", Category.WORLD);
        addSettings(pace, slope, walkForYou, movement, startPaused, followView, diagonal, diagonalRun, gap,
            stepDelay, thenCast, mountainHeight, speedUp, topLimit, topHeight, turnBackAtTop, bottomLimit,
            bottomHeight, turnBackAtBottom, blocks, stairsFirst, rotate, showNextStep);
        addSettings(nextStyle.settings());
        addSettings(showEnds);
        addSettings(endsStyle.settings());
        slope.visibleWhen(() -> pace.is(Pace.WALK) || !followView.isOn() && !thenCast.isOn());
        searchTags("stairs", "steps", "mountain", "climb");
    }

    // The last run since the game started. Null before the first.
    public Run lastRun() {
        return run;
    }

    @Override
    public boolean savesEnabledState() {
        return false;
    }

    @Override
    public String getSuffix() {
        if (pace.is(Pace.WALK)) {
            return null;
        }
        if (paused) {
            return "paused";
        }
        if (!thenCast.isOn() || run == null) {
            return null;
        }
        int climbed = mc.player.blockPosition().getY() - (run.lowest().getY() + 1);
        return climbed + "/" + mountainHeight.getInt();
    }

    @Override
    protected void onEnable() {
        run = null;
        standingOn = null;
        paused = false;
        justTurned = false;
        lastStep = null;
        keys = Keys.NONE;
        lastPos = null;
        stillTicks = 0;
        delayLeft = 0;
        endNoted = false;
        loan.forget();
        placements.clear();
        if (!inGame()) {
            setEnabled(false);
            return;
        }
        if (Modules.enabled(LavaCast.class)) {
            disable("Staircase waits until LavaCast has finished.");
            return;
        }
        Modules.stopFlight();
        useHeld = InputUtil.physicallyHeld(mc.options.keyUse);
        if (pace.is(Pace.WALK)) {
            fillFooting(footing());
            loan.giveBack();
            return;
        }
        if (movement.is(Movement.AUTO) && startPaused.isOn()) {
            paused = true;
            ChatUtil.message("§bStaircase §7waits for you to press use.");
        } else {
            startRapid();
        }
    }

    @Override
    protected void onDisable() {
        stopWalking();
        thaw();
        Timer.override(TIMER_KEY, 1);
        loan.release();
        placements.clear();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        // A dead player still ticks and the server ignores every click it makes.
        if (mc.player.isDeadOrDying()) {
            disable("Staircase stopped because you died.");
            return;
        }
        if (mc.player.isSpectator() || mc.player.isPassenger()) {
            stopWalking();
            thaw();
            Timer.override(TIMER_KEY, 1);
            return;
        }
        if (pace.is(Pace.WALK)) {
            leaveRapid();
            Timer.override(TIMER_KEY, speedUp.getFloat());
            walkTick();
        } else {
            stopWalking();
            rapidTick();
        }
    }

    // Leaving the game ends the run at once. Its speed up would otherwise run the menus fast
    // and the run would carry on after the next join.
    @Subscribe
    private void onClientTick(ClientTickEvent event) {
        if (mc.level == null) {
            setEnabled(false);
        }
    }

    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        placements.onPacket(event);
    }

    // The use key pauses and resumes a rapid run. Only a fresh press counts and the held
    // repeat is swallowed.
    @Subscribe
    private void onRightClick(RightClickEvent event) {
        if (event.isCancelled() || !pace.is(Pace.RAPID) || !movement.is(Movement.AUTO) || !inGame()) {
            return;
        }
        event.cancel();
        mc.rightClickDelay = InputUtil.USE_DELAY;
        if (useHeld) {
            return;
        }
        if (paused) {
            startRapid();
        } else {
            pause("");
        }
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        DrawBatch batch = event.getBatch();
        if (showNextStep.isOn() && inGame()) {
            BlockPos next = pace.is(Pace.WALK) ? walkStep() : nextStep();
            if (next != null) {
                nextStyle.draw(batch, next, false);
            }
            BlockPos top = topStep();
            if (top != null) {
                nextStyle.draw(batch, top, false);
            }
        }
        if (showEnds.isOn() && run != null) {
            endsStyle.draw(batch, run.lowest(), false);
            endsStyle.draw(batch, run.highest(), false);
        }
    }

    private void recordStep(BlockPos step) {
        if (!step.equals(lastStep)) {
            lastStep = step;
            justTurned = false;
        }
        run = run == null ? Run.from(step) : run.with(step);
    }

    // True for the way up. Following the view in rapid pace the pitch decides.
    private boolean climbing() {
        if (pace.is(Pace.RAPID) && thenCast.isOn()) {
            return true;
        }
        if (pace.is(Pace.RAPID) && followView.isOn()) {
            return mc.player.getXRot() <= STEEP_PITCH;
        }
        return slope.is(Slope.UP);
    }

    // Following the view the camera tilts. The Direction setting changes otherwise.
    private void setClimbing(boolean up) {
        if (pace.is(Pace.RAPID) && followView.isOn()) {
            mc.player.setXRot(up ? LOOK_UP_PITCH : LOOK_DOWN_PITCH);
        } else {
            slope.setValue(up ? Slope.UP : Slope.DOWN);
        }
    }

    // The step's feet are past a height the settings keep them within. Null whilst inside.
    private String limitPassed(boolean up, int feetY) {
        if (up && pace.is(Pace.RAPID) && thenCast.isOn() && feetY > mountainTop()) {
            return "at the mountain height";
        }
        if (up && topLimit.isOn() && feetY > topHeight.getInt()) {
            return "at the top height";
        }
        if (!up && bottomLimit.isOn() && feetY < bottomHeight.getInt()) {
            return "at the bottom height";
        }
        return null;
    }

    // The feet height a mountain climbs to from the lowest step of the run. It stops low
    // enough under the top of the world for every layer of the cast after it.
    private int mountainTop() {
        LavaCast cast = Modules.get(LavaCast.class);
        int room = cast == null ? 0 : cast.climbRoom();
        return Math.min(run.lowest().getY() + 1 + mountainHeight.getInt(), mc.level.getMaxY() - room);
    }

    // Turns the stairs around at an end when the settings ask for it. False when they do
    // not or the last end already turned them.
    private boolean turnBack(boolean up) {
        if (justTurned || !(up ? turnBackAtTop.isOn() : turnBackAtBottom.isOn())) {
            return false;
        }
        justTurned = true;
        setClimbing(!up);
        return true;
    }

    private void walkTick() {
        if (walkForYou.isOn() && !walker.isLocked()) {
            lineHeading = mc.player.getDirection();
            walker.lock();
        } else if (!walkForYou.isOn() && walker.isLocked()) {
            stopWalking();
        }
        if (mc.player.onGround()) {
            recordStep(mc.player.getOnPos());
            boolean going = placeWalkStep();
            loan.giveBack();
            if (!going) {
                return;
            }
        }
        if (walkForYou.isOn()) {
            walk();
        }
    }

    // The step in front of you. Null where the ground itself goes on.
    private BlockPos walkStep() {
        BlockPos ahead = mc.player.getOnPos().relative(walkHeading());
        if (climbing()) {
            return ahead.above();
        }
        return BlockUtil.isReplaceable(ahead) ? ahead.below() : null;
    }

    private Direction walkHeading() {
        return walker.isLocked() ? lineHeading : mc.player.getDirection();
    }

    // Puts the step in front down when it is missing. False once the module has stopped.
    private boolean placeWalkStep() {
        BlockPos step = walkStep();
        if (step == null) {
            return true;
        }
        boolean up = climbing();
        String limit = limitPassed(up, step.getY() + 1);
        if (limit != null) {
            if (turnBack(up)) {
                return true;
            }
            ChatUtil.message("§bStaircase §7stopped " + limit + ".");
            setEnabled(false);
            return false;
        }
        if (!BlockUtil.blockFits(step) || BlockUtil.intersectsPlayer(step)) {
            return true;
        }
        if (!placeStep(step, walkHeading(), up)) {
            disable("Staircase ran out of blocks.");
            return false;
        }
        return true;
    }

    // Holds forward along the line and jumps onto a full block step once you walk into it.
    // Stairs need no jump. Your back key stands you still. On the ground the walk waits
    // whilst the next step is missing. Mid jump it carries on to the step it jumps for.
    private void walk() {
        releaseStrafe();
        if (InputUtil.physicallyHeld(mc.options.keyDown)) {
            forward.letGo();
            lastPos = null;
            stillTicks = 0;
            return;
        }
        BlockPos step = walkStep();
        if (mc.player.onGround() && step != null && !BlockUtil.isSolid(step)) {
            forward.letGo();
        } else {
            forward.hold();
            walker.holdAxis();
            if (mc.player.onGround() && mc.player.horizontalCollision && climbing()) {
                mc.player.jumpFromGround();
            }
        }
        Vec3 pos = mc.player.position();
        if (lastPos == null) {
            lastPos = pos;
        } else if (pos.subtract(lastPos).horizontalDistance() > STUCK_DISTANCE) {
            lastPos = pos;
            stillTicks = 0;
        } else if (++stillTicks > STUCK_TICKS) {
            stuckWalking();
        }
    }

    private void stuckWalking() {
        lastPos = null;
        stillTicks = 0;
        if (!turnBack(climbing())) {
            disable("Staircase stopped because something blocks the way.");
        }
    }

    // The strafe keys would walk you off the line.
    private void releaseStrafe() {
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
    }

    private void stopWalking() {
        if (walker.isLocked()) {
            InputUtil.release(mc.options.keyLeft);
            InputUtil.release(mc.options.keyRight);
            walker.clear();
        }
        forward.letGo();
        lastPos = null;
        stillTicks = 0;
    }

    private void rapidTick() {
        Keys held = readKeys();
        followHeading();
        if (!paused) {
            steer(held);
        }
        keys = held;
        useHeld = InputUtil.physicallyHeld(mc.options.keyUse);
        if (!keepSteps()) {
            return;
        }
        askAgain();
        // Manual steps have no pause. A run that has not started yet starts on this tick.
        if (standingOn == null && !paused || paused && movement.is(Movement.MANUAL)) {
            startRapid();
            if (!isEnabled()) {
                return;
            }
        }
        if (paused) {
            thaw();
            Timer.override(TIMER_KEY, 1);
            loan.giveBack();
            return;
        }
        freeze();
        Timer.override(TIMER_KEY, speedUp.getFloat());
        if (knockedOff()) {
            disable("Staircase stopped because you were knocked off the steps.");
            return;
        }
        if (!holdOnStep()) {
            return;
        }
        if (placements.waiting() >= MAX_UNANSWERED) {
            return;
        }
        // A click past the server's use limit is thrown away. Waiting for room costs less.
        if (UseBudget.remaining() == 0) {
            return;
        }
        if (delayLeft > 0) {
            delayLeft--;
            return;
        }
        if (movement.is(Movement.MANUAL) && !held.forward() && !held.back()) {
            return;
        }
        step(held.jump());
    }

    // Drops the rapid run once the pace is changed to walking.
    private void leaveRapid() {
        thaw();
        standingOn = null;
        paused = false;
        placements.clear();
    }

    // Starts or resumes a rapid run on the block under you. Mountains check the cast can
    // follow before the first step.
    private void startRapid() {
        if (thenCast.isOn() && !castReady()) {
            return;
        }
        BlockPos under = footing();
        boolean open = BlockUtil.isReplaceable(under);
        if (!fillFooting(under)) {
            return;
        }
        if (open) {
            placements.placed(under);
        }
        VoxelShape shape = BlockUtil.state(under).getCollisionShape(mc.level, under);
        if (shape.isEmpty()) {
            disable("Staircase needs a block under you to start.");
            return;
        }
        standingOn = under;
        standY = under.getY() + shape.max(Direction.Axis.Y);
        heading = snapHeading(mc.player.getYRot());
        paused = false;
        endNoted = false;
        recordStep(under);
    }

    private boolean castReady() {
        if (InventoryUtil.findSlot(Items.LAVA_BUCKET, InventoryUtil.WHOLE_INVENTORY) == -1
            || InventoryUtil.findSlot(Items.WATER_BUCKET, InventoryUtil.WHOLE_INVENTORY) == -1) {
            disable("Then cast needs a lava bucket and a water bucket.");
            return false;
        }
        if (BlockUtil.waterEvaporates(mc.player.blockPosition())) {
            disable("Water boils away here and the cast after the stairs needs water.");
            return false;
        }
        return true;
    }

    // The block your feet rest on. In the air it is the one just below your feet.
    private BlockPos footing() {
        return mc.player.onGround() ? mc.player.getOnPos() : mc.player.blockPosition().below();
    }

    // Puts a block in the footing when it is open. False once the module has stopped for want of one.
    private boolean fillFooting(BlockPos under) {
        if (!BlockUtil.isReplaceable(under) || placeStep(under, mc.player.getDirection(), true)) {
            return true;
        }
        disable("Staircase needs a block under you to start.");
        return false;
    }

    // Holds you on the middle of the step. True when you were already there. A step taken
    // in the same tick as the pull back would start the server's move from the wrong place.
    private boolean holdOnStep() {
        mc.player.setDeltaMovement(ONTO_STEP);
        if (mc.player.position().distanceTo(stepSpot()) <= DRIFT) {
            return true;
        }
        mc.player.setPos(stepSpot());
        return false;
    }

    private boolean knockedOff() {
        return mc.player.position().distanceTo(stepSpot()) > LOST_DISTANCE;
    }

    // The middle of the top of the step you stand on.
    private Vec3 stepSpot() {
        return new Vec3(standingOn.getX() + 0.5, standY, standingOn.getZ() + 0.5);
    }

    // Turns on a fresh press of left or right. With the view left alone forward and back
    // choose the way up or down.
    private void steer(Keys held) {
        if (held.left() && !keys.left()) {
            turn(-1);
        }
        if (held.right() && !keys.right()) {
            turn(1);
        }
        if (!followView.isOn() && held.forward() && !keys.forward()) {
            setClimbing(true);
        }
        if (!followView.isOn() && held.back() && !keys.back()) {
            setClimbing(false);
        }
    }

    private void turn(int notches) {
        int eighths = notches * headingStep();
        if (followView.isOn()) {
            mc.player.setYRot(mc.player.getYRot() + eighths * EIGHTH);
        } else {
            heading = Math.floorMod(heading + eighths, EIGHTHS);
        }
    }

    private int headingStep() {
        return diagonal.isOn() ? 1 : 2;
    }

    private int snapHeading(float yaw) {
        int step = headingStep();
        return Math.floorMod(Math.round(Mth.wrapDegrees(yaw) / (EIGHTH * step)) * step, EIGHTHS);
    }

    // Following the view the heading is the view's. Without Diagonal it keeps to the four
    // straight ways. Either switch changed mid run carries on from where the stairs point.
    private void followHeading() {
        if (followView.isOn()) {
            heading = snapHeading(mc.player.getYRot());
        } else if (!diagonal.isOn() && heading % 2 != 0) {
            heading = snapHeading(heading * EIGHTH);
        }
    }

    // The way of the next step. A diagonal takes a run of steps along one of its sides and
    // then the same along the other. A new heading starts on the first side.
    private Direction nextWay() {
        int now = heading;
        if (now % 2 == 0) {
            return wayOf(now);
        }
        boolean second = now == stepHeading && secondSide;
        return wayOf(second ? now + 1 : now - 1);
    }

    private void countDiagonalStep() {
        int now = heading;
        if (now != stepHeading) {
            stepHeading = now;
            diagonalSteps = 0;
            secondSide = false;
        }
        if (now % 2 != 0 && ++diagonalSteps >= diagonalRun.getInt()) {
            diagonalSteps = 0;
            secondSide = !secondSide;
        }
    }

    private static Direction wayOf(int eighths) {
        return Direction.fromYRot(eighths * EIGHTH);
    }

    // Where the feet go next. A drop never passes the fall a landing forgives.
    private BlockPos nextFeet(Direction way, boolean up, int skip) {
        int rise = up ? 1 + skip : -1 - Math.min(skip, SAFE_DROP - 1);
        return mc.player.blockPosition().relative(way).above(rise);
    }

    private BlockPos nextStep() {
        int skip = keys.jump() ? gap.getInt() : 0;
        return nextFeet(nextWay(), climbing(), skip).below();
    }

    private void step(boolean jump) {
        Direction way = nextWay();
        boolean up = climbing();
        int skip = jump ? gap.getInt() : 0;
        BlockPos feet = mc.player.blockPosition();
        BlockPos nextFeet = nextFeet(way, up, skip);
        BlockPos step = nextFeet.below();
        String limit = limitPassed(up, nextFeet.getY());
        if (limit != null) {
            reachedEnd(up, limit);
            return;
        }
        if (!sweep(feet, way, nextFeet.getY() - feet.getY()).stream().allMatch(this::passable)
            || !mc.level.getWorldBorder().isWithinBounds(step) || !canStandOn(step)) {
            reachedEnd(up, "because something blocks the way");
            return;
        }
        if (BlockUtil.isReplaceable(step)) {
            if (!placeStep(step, way, up)) {
                disable("Staircase ran out of blocks.");
                return;
            }
            if (!BlockUtil.isSolid(step)) {
                halt("because the step will not go down");
                return;
            }
            placements.placed(step);
        }
        standingOn = step;
        standY = step.getY() + 1;
        mc.player.setPos(nextFeet.getX() + 0.5, standY, nextFeet.getZ() + 0.5);
        mc.player.setDeltaMovement(ONTO_STEP);
        mc.player.resetFallDistance();
        lastWay = way;
        countDiagonalStep();
        delayLeft = stepDelay.getInt();
        endNoted = false;
        recordStep(step);
    }

    // Every block you pass through on the way to the next step. A rise goes straight up and
    // then across and a drop goes across and then down. The server moves you the same way.
    private static List<BlockPos> sweep(BlockPos feet, Direction way, int rise) {
        List<BlockPos> cells = new ArrayList<>();
        BlockPos ahead = feet.relative(way);
        if (rise > 0) {
            for (int y = 2; y <= rise + 1; y++) {
                cells.add(feet.above(y));
            }
            cells.add(ahead.above(rise));
            cells.add(ahead.above(rise + 1));
        } else {
            for (int y = rise; y <= 1; y++) {
                cells.add(ahead.above(y));
            }
        }
        return cells;
    }

    // Open air or anything you walk through. Fluids would slow the move and push you off.
    private boolean passable(BlockPos pos) {
        return BlockUtil.isReplaceable(pos) && mc.level.getFluidState(pos).isEmpty()
            && mc.level.getWorldBorder().isWithinBounds(pos);
    }

    // A step to place or one already there whose top is level with the top of its block.
    private boolean canStandOn(BlockPos step) {
        if (BlockUtil.isReplaceable(step)) {
            return BlockUtil.unobstructed(step);
        }
        VoxelShape shape = BlockUtil.state(step).getCollisionShape(mc.level, step);
        return !shape.isEmpty() && shape.max(Direction.Axis.Y) == 1;
    }

    // The stairs can go no further this way. A mountain hands over to the cast. Otherwise
    // they turn back when asked or the run pauses.
    private void reachedEnd(boolean up, String where) {
        if (thenCast.isOn() && run.highest().getY() > run.lowest().getY()) {
            handOver();
        } else if (thenCast.isOn() || !turnBack(up)) {
            halt(where);
        }
    }

    // Pauses an automatic run. A manual one says once why the key does nothing.
    private void halt(String where) {
        if (movement.is(Movement.AUTO)) {
            pause(where);
        } else if (!endNoted) {
            endNoted = true;
            ChatUtil.message("§bStaircase §7can go no further " + where + ".");
        }
    }

    private void pause(String where) {
        paused = true;
        thaw();
        Timer.override(TIMER_KEY, 1);
        loan.giveBack();
        String why = where.isEmpty() ? "" : " " + where;
        ChatUtil.message("§bStaircase §7paused" + why + ". Press use to carry on.");
    }

    // Starts LavaCast on the top step once the server holds every step.
    private void handOver() {
        if (placements.waiting() > 0) {
            return;
        }
        Direction down = lastWay().getOpposite();
        LavaCast cast = Modules.get(LavaCast.class);
        setEnabled(false);
        ChatUtil.message("§bStaircase §7reached the top. Starting §fLavaCast§7.");
        if (cast != null) {
            cast.castDownStairs(down);
        }
    }

    // The way of the last step or the way you face before the first.
    private Direction lastWay() {
        return lastWay == null ? mc.player.getDirection() : lastWay;
    }

    // Puts back every step the server refused or threw away that is still in reach.
    // False once the run has stopped.
    private boolean keepSteps() {
        for (BlockPos missing : placements.sweep()) {
            if (placements.attempts(missing) >= MAX_ATTEMPTS) {
                disable("Staircase stopped because the server keeps refusing the steps.");
                return false;
            }
            if (!BlockUtil.inReach(missing) || !BlockUtil.blockFits(missing)) {
                continue;
            }
            if (!placeStep(missing, lastWay(), climbing())) {
                disable("Staircase ran out of blocks.");
                return false;
            }
            placements.placed(missing);
        }
        return true;
    }

    // A click the server threw away is never answered. Clicking the top of the step you
    // stand on asks again. The server puts the step there when it is missing and otherwise
    // refuses a block where your feet are. Either answer settles every guess before it.
    // Standing on a step the server lacks too long reads to it as flying.
    private void askAgain() {
        long now = System.currentTimeMillis();
        if (placements.longestWait() < ANSWER_MILLIS || now - lastSettleAt < ANSWER_MILLIS
            || standingOn == null || !mc.player.blockPosition().equals(standingOn.above())) {
            return;
        }
        int slot = stepSlot(standingOn.above());
        if (slot != -1 && loan.select(slot)) {
            lastSettleAt = now;
            BlockUtil.place(standingOn.above(), Direction.DOWN, false, false);
        }
    }

    // Stands a still input in for yours whilst the steps move you. Held keys would walk
    // you off the step.
    private void freeze() {
        if (ownInput == null) {
            ownInput = mc.player.input;
            mc.player.input = stillInput;
        }
    }

    private void thaw() {
        if (ownInput != null && mc.player != null && mc.player.input == stillInput) {
            mc.player.input = ownInput;
        }
        ownInput = null;
    }

    private static Keys readKeys() {
        Options options = mc.options;
        return new Keys(held(options.keyUp), held(options.keyDown), held(options.keyLeft),
            held(options.keyRight), held(options.keyJump));
    }

    private static boolean held(KeyMapping key) {
        return InputUtil.physicallyHeld(key);
    }

    // The top step of a climb with a height to reach when the stairs run straight.
    private BlockPos topStep() {
        if (pace.is(Pace.WALK) || paused || standingOn == null || !climbing() || heading % 2 != 0) {
            return null;
        }
        int top = Integer.MAX_VALUE;
        if (thenCast.isOn() && run != null) {
            top = mountainTop();
        }
        if (topLimit.isOn()) {
            top = Math.min(top, topHeight.getInt());
        }
        int climb = top - mc.player.blockPosition().getY();
        if (top == Integer.MAX_VALUE || climb <= 0) {
            return null;
        }
        return standingOn.relative(wayOf(heading), climb).above(climb);
    }

    // Places a step. Stairs face up the slope. False when there is nothing to place.
    private boolean placeStep(BlockPos step, Direction way, boolean up) {
        int slot = stepSlot(step);
        if (slot == -1 || !loan.select(slot)) {
            return false;
        }
        if (mc.player.getMainHandItem().getItem() instanceof BlockItem item && item.getBlock() instanceof StairBlock) {
            placeStair(step, way, up);
        } else {
            BlockUtil.placeAny(step, rotate.isOn(), true);
        }
        return true;
    }

    private int stepSlot(BlockPos step) {
        int stair = blockSlot(block -> block instanceof StairBlock && listed(block));
        int full = blockSlot(block -> BlockUtil.isBuildingBlock(block, step) && listed(block));
        if (stairsFirst.isOn()) {
            return stair != -1 ? stair : full;
        }
        return full != -1 ? full : stair;
    }

    private static int blockSlot(Predicate<Block> test) {
        return InventoryUtil.findSlot(stack -> stack.getItem() instanceof BlockItem item && test.test(item.getBlock()),
            InventoryUtil.WHOLE_INVENTORY);
    }

    private boolean listed(Block block) {
        return blocks.size() == 0 || blocks.contains(block);
    }

    // The server gives a stair the facing of the yaw it holds when the click lands. The
    // yaw goes out on its own packet first. Going down the stair faces back up the slope.
    private void placeStair(BlockPos step, Direction way, boolean up) {
        Direction back = up ? way : way.getOpposite();
        Direction support = stairSupport(step);
        Vec3 hit = support != null
            ? BlockUtil.hitPoint(step.relative(support), support.getOpposite())
            : Vec3.atBottomCenterOf(step).add(0, LOWER_QUARTER, 0);
        FaceMode.SPAM.faceExact(back.toYRot(), RotationManager.pitchTo(hit), RotationPriority.PLACE);
        if (support != null) {
            BlockUtil.place(step, support, false, true);
        } else {
            BlockUtil.placeDirect(step, hit, false, true);
        }
    }

    // A stair leans on the block below it or beside it. One hung from above lands upside down.
    private static Direction stairSupport(BlockPos step) {
        for (Direction side : BlockUtil.BELOW_THEN_SIDES) {
            BlockPos next = step.relative(side);
            if (BlockUtil.isSolid(next) && !BlockUtil.opensOnClick(BlockUtil.state(next))) {
                return side;
            }
        }
        return null;
    }
}
