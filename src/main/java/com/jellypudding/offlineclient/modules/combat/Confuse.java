package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.modules.movement.HoleSnap;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.MovementUtil;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.ThreadLocalRandom;

// Runs round the target whilst you fight. The pace is a plain sprint which the movement
// check never questions.
public final class Confuse extends Module {

    public enum Mode { CIRCLE, RANDOM }

    // How hard a wrong distance pulls you back onto the circle. One closes the gap in a tick.
    private static final double RADIUS_PULL = 0.5;

    // Random picks a new way round after somewhere between these many ticks.
    private static final int MIN_FLIP_TICKS = 5;
    private static final int MAX_FLIP_TICKS = 25;

    // Random strays up to this far either side of the chosen distance.
    private static final double RADIUS_WOBBLE = 1;

    // How many ticks of travel ahead a wall or a drop turns you back.
    private static final double LOOK_AHEAD_TICKS = 2;

    private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
        "How you move round the target.", Mode.CIRCLE)
        .describe(Mode.CIRCLE, "Runs round at a steady distance. Walls and drops turn you back and your strafe keys pick the way round.")
        .describe(Mode.RANDOM, "Runs round and turns back and changes distance at random moments.");
    private final NumberSetting radius = new NumberSetting("Radius",
        "How far from the target you run round it.", 2.5, 1, 6, 0.25, " blocks").min(0.5);
    private final NumberSetting range = new NumberSetting("Range",
        "How close a target has to be before you start moving round it.", 6, 2, 16, 0.5, " blocks")
        .min(1);
    private final BoolSetting onlyKillAura = new BoolSetting("Only KillAura targets",
        "Only moves round whatever KillAura is hitting. Off also moves round the nearest enemy player.",
        false);
    private final NumberSetting maxDrop = new NumberSetting("Max drop",
        "Turns back rather than run off a drop deeper than this.", 3, 1, 10, 1, " blocks").min(1);
    private final BoolSetting jump = new BoolSetting("Jump",
        "Hops the whole way round to be harder still to hit.", false);

    // One runs to your left as you face the target and minus one to your right.
    private int way = 1;
    private double wobble;
    private int flipTimer;

    public Confuse() {
        super("Confuse", "Runs round the target whilst you fight to be hard to hit.", Category.COMBAT);
        addSettings(mode, radius, range, onlyKillAura, maxDrop, jump);
        searchTags("target strafe", "strafe", "circle", "dodge");
    }

    @Override
    public String getSuffix() {
        return mode.getValueString();
    }

    @Override
    protected void onEnable() {
        way = 1;
        wobble = 0;
        flipTimer = 0;
    }

    // Runs after Speed. The way round wins over its push.
    @Subscribe(priority = -20)
    private void onTick(TickEvent event) {
        if (!inGame() || !free(mc.player)) {
            return;
        }
        HoleSnap holeSnap = Modules.get(HoleSnap.class);
        if (holeSnap != null && holeSnap.holdsMovement()) {
            return;
        }
        LivingEntity target = pickTarget();
        if (target == null) {
            return;
        }
        chooseWay();
        run(target);
    }

    // Swimming and climbing and flying and sneaking are left alone.
    private static boolean free(LocalPlayer player) {
        return MovementUtil.inPlainAir(player) && !player.isShiftKeyDown();
    }

    private LivingEntity pickTarget() {
        KillAura aura = Modules.active(KillAura.class);
        LivingEntity fought = aura == null ? null : aura.getTarget();
        if (fought == null && !onlyKillAura.isOn()) {
            fought = EntityUtil.nearestEnemy(range.getValue());
        }
        return fought != null && mc.player.distanceTo(fought) <= range.getValue() ? fought : null;
    }

    private void chooseWay() {
        Input keys = mc.player.input.keyPresses;
        if (keys.left() != keys.right()) {
            way = keys.left() ? 1 : -1;
        }
        if (!mode.is(Mode.RANDOM) || --flipTimer > 0) {
            return;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        flipTimer = random.nextInt(MIN_FLIP_TICKS, MAX_FLIP_TICKS + 1);
        way = random.nextBoolean() ? 1 : -1;
        wobble = random.nextDouble(-RADIUS_WOBBLE, RADIUS_WOBBLE);
    }

    private void run(LivingEntity target) {
        double pace = MovementUtil.withSpeedEffects(mc.player, MovementUtil.SPRINT_SPEED);
        Vec3 heading = heading(target, way);
        if (blocked(heading, pace)) {
            way = -way;
            heading = heading(target, way);
            if (blocked(heading, pace)) {
                return;
            }
        }
        if (jump.isOn() && mc.player.onGround() && !mc.player.input.keyPresses.jump()) {
            mc.player.jumpFromGround();
        }
        Vec3 velocity = mc.player.getDeltaMovement();
        mc.player.setDeltaMovement(heading.x * pace, velocity.y, heading.z * pace);
    }

    // Along the circle with a pull back towards the chosen distance.
    private Vec3 heading(LivingEntity target, int side) {
        Vec3 away = mc.player.position().subtract(target.position()).multiply(1, 0, 1);
        double distance = away.length();
        Vec3 out = distance < 1.0E-4 ? mc.player.getLookAngle().multiply(-1, 0, -1).normalize()
            : away.scale(1 / distance);
        Vec3 round = new Vec3(-out.z * side, 0, out.x * side);
        double wanted = Math.max(radius.getValue() + (mode.is(Mode.RANDOM) ? wobble : 0), 0);
        return round.add(out.scale((wanted - distance) * RADIUS_PULL)).normalize();
    }

    // A wall too tall to step up or a drop deeper than allowed. The drop only counts
    // from the ground.
    private boolean blocked(Vec3 heading, double pace) {
        Vec3 ahead = heading.scale(pace * LOOK_AHEAD_TICKS);
        AABB box = mc.player.getBoundingBox().move(ahead.x, 0, ahead.z);
        if (!mc.level.noCollision(mc.player, box)
            && !mc.level.noCollision(mc.player, box.move(0, mc.player.maxUpStep(), 0))) {
            return true;
        }
        return mc.player.onGround()
            && mc.level.noCollision(mc.player, box.expandTowards(0, -maxDrop.getValue(), 0));
    }
}
