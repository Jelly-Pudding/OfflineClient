package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PostMotionEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.event.events.VehicleTickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.ExplosionUtil;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.MovementUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

// Simple mode raises the step height. Legit mode sends the packets of a real jump.
// A step under a low ceiling fails because the server refuses to move through a block.
public final class Step extends Module {

    public enum Mode { SIMPLE, LEGIT }

    public enum ActiveWhen { ALWAYS, SNEAKING, NOT_SNEAKING }

    // The two heights a real jump passes through on its way up one block.
    private static final double FIRST_JUMP_POINT = 0.42;
    private static final double SECOND_JUMP_POINT = 0.753;

    // The most a legit step can climb. Any higher is not a jump the server believes.
    private static final double LEGIT_LIMIT = 1;

    // A stride onto the ledge. A crystal hidden behind the edge counts from there.
    private static final double LEDGE_STRIDE = 1.2;

    private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
        "How the step is done.", Mode.SIMPLE)
        .describe(Mode.SIMPLE, "Raises the step height. You walk straight up any height.")
        .describe(Mode.LEGIT, "Sends the packets of a real jump. Anti cheats see nothing odd. One block only.");
    private final NumberSetting height = new NumberSetting("Height",
        "How high you can step up without jumping.", 1, 0.6, 3, 0.1, " blocks")
        .under(mode, Mode.SIMPLE);
    private final EnumSetting<ActiveWhen> activeWhen = new EnumSetting<>("Active when",
        "When the step is allowed.", ActiveWhen.NOT_SNEAKING)
        .describe(ActiveWhen.ALWAYS, "Steps up sneaking or not.")
        .describe(ActiveWhen.SNEAKING, "Only steps up whilst you sneak.")
        .describe(ActiveWhen.NOT_SNEAKING, "Never steps up whilst you sneak.");
    private final BoolSetting inWater = new BoolSetting("Whilst swimming",
        "Lifts you onto a bank you swim into. Reaches as high as the step does on land.", true);
    private final BoolSetting safeStep = new BoolSetting("Safe step",
        "Stops stepping up when your health is low or an end crystal could hurt you.", false);
    private final BoolSetting safeHealth = new BoolSetting("Low health",
        "Stops stepping whilst your health is at or below the limit.", true)
        .under(safeStep);
    private final NumberSetting healthLimit = new NumberSetting("Health limit",
        "Hearts at or below which the step stays off.", 5, 0.5, 10, 0.5, " hearts")
        .under(safeHealth);
    private final BoolSetting safeCrystals = new BoolSetting("Crystals",
        "Never steps up to where a nearby crystal could take you below the health limit.", true)
        .under(safeStep);
    private final BoolSetting stepDown = new BoolSetting("Step down",
        "Snaps you down small drops instead of letting you fall.", false);
    private final NumberSetting downDistance = new NumberSetting("Down distance",
        "Longest drop that is snapped.", 3, 0.5, 10, 0.5, " blocks")
        .under(stepDown);
    private final NumberSetting downSpeed = new NumberSetting("Down speed",
        "Blocks a tick the snap pulls you down.", 3, 0.5, 10, 0.1, " blocks").min(0.1)
        .under(stepDown);
    private final BoolSetting edgeGuardWins = new BoolSetting("EdgeGuard wins",
        "An edge EdgeGuard is holding you on is never snapped down from.", true)
        .under(stepDown);
    private final BoolSetting downVehicles = new BoolSetting("Vehicles",
        "Also snaps the boat or mount you ride down small drops.", false)
        .under(stepDown);

    // The step height the crystals around allow this tick. Worked out once a tick
    // because the game asks for the step height several times in one move.
    private double safeHeight;

    private int legitStage;
    private double legitRise;
    private double legitFoot;

    // Set on the tick a swimmer is lifted onto a bank.
    private boolean lifted;

    // Whether the player and the ridden vehicle stood on something last tick. A snap
    // only follows a walk off a ledge and never a jump.
    private boolean wasOnGround;
    private boolean vehicleWasOnGround;

    public Step() {
        super("Step", "Step up full blocks without jumping.", Category.MOVEMENT);
        addSettings(mode, height, activeWhen, inWater, safeStep, safeHealth, healthLimit,
            safeCrystals, stepDown, downDistance, downSpeed, edgeGuardWins,
            downVehicles);
        searchTags("reverse step", "step down");
    }

    @Override
    public String getSuffix() {
        return mode.is(Mode.LEGIT) ? "Legit" : height.getValueString();
    }

    @Override
    protected void onEnable() {
        safeHeight = height.getValue();
        legitStage = 0;
    }

    // Called from LocalPlayerMixin.maxUpStep().
    public float adjustStepHeight(float vanilla) {
        if (!raisesStepHeight()) {
            return vanilla;
        }
        return Math.max(vanilla, (float) safeHeight);
    }

    // True whilst simple mode hands the game a taller step.
    // EntityMixin reads this. A bouncy block must not read a step up as a huge bounce.
    public boolean raisesStepHeight() {
        return isEnabled() && mc.player != null && mode.is(Mode.SIMPLE) && allowed()
            && safeHeight > 0;
    }

    private boolean allowed() {
        boolean sneaking = mc.player.isShiftKeyDown();
        if (activeWhen.is(ActiveWhen.SNEAKING) && !sneaking) {
            return false;
        }
        // The sneak edge check uses the step height as its drop probe.
        if (activeWhen.is(ActiveWhen.NOT_SNEAKING) && sneaking) {
            return false;
        }
        return !safeStep.isOn() || !inDanger();
    }

    private boolean inDanger() {
        return safeHealth.isOn() && healthAfter(0) <= healthLimit.getFloat();
    }

    // Hearts left once the damage has landed.
    private float healthAfter(double damage) {
        return (float) ((EntityUtil.totalHealth(mc.player) - damage) / EntityUtil.HEART);
    }

    // The worst a crystal around could do with the player's box moved by the offset.
    // The box is put back before anything else can read it.
    private double crystalDamageAt(Vec3 offset) {
        AABB box = mc.player.getBoundingBox();
        mc.player.setBoundingBox(box.move(offset));
        try {
            double worst = 0;
            for (Entity entity : mc.level.entitiesForRendering()) {
                if (entity instanceof EndCrystal && entity.isAlive()) {
                    worst = Math.max(worst,
                        ExplosionUtil.crystalDamage(mc.player, entity.position(), offset));
                }
            }
            return worst;
        } finally {
            mc.player.setBoundingBox(box);
        }
    }

    // True when standing at the offset is no worse than where the player is now.
    private boolean saferThan(double current, Vec3 offset) {
        double there = crystalDamageAt(offset);
        return healthAfter(there) > healthLimit.getFloat() || there <= current;
    }

    // The tallest step that keeps the crystals around from hurting past the limit.
    // Each block up is tested where the player is and a stride onto the ledge.
    private double crystalSafeHeight() {
        double max = height.getValue();
        if (!safeStep.isOn() || !safeCrystals.isOn()) {
            return max;
        }
        double current = crystalDamageAt(Vec3.ZERO);
        Vec3 stride = MovementUtil.inputDirection().scale(LEDGE_STRIDE);
        double allowed = 0;
        for (int up = 1; up < max; up++) {
            if (!saferThan(current, new Vec3(0, up, 0))
                || !saferThan(current, stride.add(0, up, 0))) {
                return allowed;
            }
            allowed = up;
        }
        return saferThan(current, new Vec3(0, max, 0)) ? max : allowed;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.player.isPassenger() || jesusLifting()) {
            legitStage = 0;
            return;
        }
        safeHeight = crystalSafeHeight();
        boolean swimming = mc.player.isInWater();
        boolean climbing = allowed() && !swimming && mode.is(Mode.LEGIT);
        if (!climbing) {
            legitStage = 0;
        }
        if (climbing) {
            stepLegit();
        } else if (allowed() && swimming && inWater.isOn()) {
            stepFromWater();
        }
        if (stepDown.isOn()) {
            snapDown();
        }
    }

    // A ridden vehicle is snapped right before its own tick moves it.
    @Subscribe
    private void onVehicleTick(VehicleTickEvent event) {
        Entity vehicle = event.getVehicle();
        boolean walkedOff = vehicleWasOnGround && !vehicle.onGround();
        vehicleWasOnGround = vehicle.onGround();
        if (!stepDown.isOn() || !downVehicles.isOn() || !inGame()
            || !vehicle.isLocalInstanceAuthoritative() || !walkedOff || vehicle.getDeltaMovement().y > 0
            || vehicle.isInWater() || vehicle.isInLava() || vehicle.noPhysics
            || mc.options.keyJump.isDown() || !groundWithinReach(vehicle)) {
            return;
        }
        Vec3 velocity = vehicle.getDeltaMovement();
        vehicle.setDeltaMovement(velocity.x, -downSpeed.getValue(), velocity.z);
    }

    // The lift leaves its upward speed behind once the swimmer is out of the water.
    // Left alone it would carry them on up like a high jump.
    @Subscribe
    private void onPostMotion(PostMotionEvent event) {
        if (!lifted || !inGame()) {
            return;
        }
        lifted = false;
        Vec3 velocity = mc.player.getDeltaMovement();
        mc.player.setDeltaMovement(velocity.x, Math.min(velocity.y, 0), velocity.z);
    }

    // Jesus lifts the player onto held liquid on its own. Nothing else may move
    // them on that tick.
    private static boolean jesusLifting() {
        Jesus jesus = Modules.get(Jesus.class);
        return jesus != null && jesus.lifting();
    }

    private static boolean pushing() {
        return mc.player.input.getMoveVector().lengthSquared() >= 1.0E-6f;
    }

    private static double ledgeRise() {
        return MovementUtil.ledgeRise(mc.player, MovementUtil.inputDirection());
    }

    // Walks the two heights a real jump passes through at one a tick.
    private void stepLegit() {
        if (legitStage > 0) {
            climbLegit();
            return;
        }
        if (!mc.player.horizontalCollision || !mc.player.onGround()) {
            return;
        }
        if (mc.player.onClimbable() || mc.player.isInLava()
            || mc.player.input.keyPresses.jump() || !pushing()) {
            return;
        }
        double rise = ledgeRise();
        if (rise <= 0 || rise > LEGIT_LIMIT) {
            return;
        }
        legitRise = rise;
        legitFoot = mc.player.getY();
        legitStage = 1;
        climbLegit();
    }

    private void climbLegit() {
        double point = switch (legitStage) {
            case 1 -> FIRST_JUMP_POINT;
            case 2 -> SECOND_JUMP_POINT;
            default -> 1;
        };
        double y = legitFoot + point * legitRise;
        // Something moved the player away from the ledge part way up.
        if (mc.player.getY() < legitFoot - LEGIT_LIMIT || mc.player.getY() > y + LEGIT_LIMIT) {
            legitStage = 0;
            return;
        }
        mc.player.setPos(mc.player.getX(), y, mc.player.getZ());
        legitStage = legitStage >= 3 ? 0 : legitStage + 1;
    }

    // The game only steps whilst the feet are on the ground.
    // A swimmer pushing against a bank is given the lift as speed instead.
    private void stepFromWater() {
        if (!mc.player.horizontalCollision || mc.player.input.keyPresses.jump() || !pushing()) {
            return;
        }
        double rise = ledgeRise();
        double limit = mode.is(Mode.LEGIT) ? LEGIT_LIMIT : height.getValue();
        if (rise <= 0 || rise > limit) {
            return;
        }
        Vec3 velocity = mc.player.getDeltaMovement();
        mc.player.setDeltaMovement(velocity.x, rise + MovementUtil.LEDGE_CLEARANCE, velocity.z);
        lifted = true;
    }

    private void snapDown() {
        boolean walkedOff = wasOnGround && !mc.player.onGround();
        wasOnGround = mc.player.onGround();
        if (!walkedOff || mc.player.getDeltaMovement().y > 0 || mc.player.isInWater()
            || mc.player.isInLava() || mc.player.noPhysics || mc.options.keyJump.isDown()
            || mc.player.isShiftKeyDown()) {
            return;
        }
        if (!MovementUtil.hasInput()) {
            return;
        }
        if (edgeGuardWins.isOn()) {
            EdgeGuard edgeGuard = Modules.get(EdgeGuard.class);
            if (edgeGuard != null && edgeGuard.shouldGuard()) {
                return;
            }
        }
        // A bed edge would bounce the snap straight back up.
        BlockPos feet = mc.player.blockPosition();
        if (BlockUtil.state(feet).getBlock() instanceof BedBlock
            || BlockUtil.state(feet.below()).getBlock() instanceof BedBlock) {
            return;
        }
        if (!groundWithinReach(mc.player)) {
            return;
        }
        Vec3 velocity = mc.player.getDeltaMovement();
        mc.player.setDeltaMovement(velocity.x, -downSpeed.getValue(), velocity.z);
    }

    // True when ground sits within the down distance under an entity already off its ledge.
    private boolean groundWithinReach(Entity entity) {
        double drop = downDistance.getValue() + 0.01;
        return !mc.level.noCollision(entity, entity.getBoundingBox().expandTowards(0, -drop, 0));
    }
}
