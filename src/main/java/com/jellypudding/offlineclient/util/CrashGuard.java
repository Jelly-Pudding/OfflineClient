package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.Setting;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.function.Supplier;

// Keeps a glide clear of the only crash that hurts. The server flies a copy of the player
// along the look it holds for them (GlideMirror) and charges a wall when that copy runs into
// it. However gently the player arrives makes no difference. The body is swept along the
// flight path and the flight is braked short of the first place a step of the copy would
// hurt from. A look that changes can bring such a place to where the player already is and
// they are moved back out of it. Touching down is no crash and the fall the server holds is
// left to NoFall.
public final class CrashGuard {

    private static final double MIN_TICKS = 4;

    private static final double SWEEP_STEP = 0.5;
    private static final int MAX_STEPS = 64;

    // Share of the safe speed kept after a brake. Leaves room to add some back.
    private static final double KEEP = 0.6;

    private static final double STILL = 0.01;

    // A flight along the ground is held this far short of the first place that would hurt.
    // The copy goes on gathering speed past the ticks looked at and its reach swells and
    // shrinks as the look moves. The room takes that up. Without it the player would be
    // backed off and let in again with every nod and a copy a little faster than it is
    // taken to be would reach them. A flight straight down needs none.
    private static final double ROOM = 1;

    // Blocks a tick the player is moved back along the level look out of a place that hurts
    // and the least of that a wall behind them may leave for the move to be worth making.
    private static final double BACK_OFF = 0.3;
    private static final double LEAST_BACK = 0.05;

    // Something this close in front stops the copy before it has a speed that costs anything
    // to lose.
    private static final double PINNED = GlideMirror.FREE_LOSS;

    private static final int HALVINGS = 3;

    private record Verdict(Vec3 velocity, boolean stopped) {

        private static Verdict onward(Vec3 velocity) {
            return new Verdict(velocity, false);
        }
    }

    // A flight as the part that takes the player nearer along the level look and the rest
    // of it. The brake is for the first. A strafe or a sink that goes with it was never the
    // danger and is flown at the pace its own path allows.
    private record Split(Vec3 nearer, Vec3 aside) {

        private static Split of(Vec3 flight, Vec3 look) {
            Vec3 nearer = look.scale(Math.max(0, flight.dot(look)));
            return new Split(nearer, flight.subtract(nearer));
        }

        private boolean hasBoth() {
            return nearer.lengthSqr() > 0 && aside.length() >= STILL;
        }
    }

    // The player and the copy as one tick finds them. Every velocity the guard changes
    // leaves through flown and is tested there for where it really ends.
    private record Tick(LocalPlayer player, AABB box, GlideMirror.Ahead ahead, Vec3 look, double rise,
                        boolean hurtsHere) {

        private Vec3 retreat() {
            return look.scale(-BACK_OFF);
        }

        // How far the game's own move of a velocity gets this tick. Flying like creative
        // the game moves the player by the velocity and in a glide by the glide step of it.
        private Vec3 moved(Vec3 velocity) {
            Vec3 move = player.getAbilities().flying ? velocity : player.updateFallFlyingMovement(velocity);
            return Entity.collideBoundingBox(player, move, box, player.level(), List.of());
        }

        // A place that hurts already is only left for one the next step does not hurt from.
        // A place that does not is only left for one no step hurts from.
        private boolean endsBadly(Vec3 velocity) {
            AABB end = box.move(moved(velocity));
            return hurtsHere ? ahead.next().damage(player, end) > 0 : ahead.hurts(player, end);
        }

        // The sweep tests places half a block apart and a tick ends between them. A
        // velocity that would end the tick in a place that hurts is halved a few times and
        // then stopped. A glide that is stopped still sinks and where that sink ends in a
        // place that hurts the way left is back.
        private Verdict flown(Vec3 velocity, boolean stopped) {
            Vec3 tried = velocity;
            for (int halving = 0; halving <= HALVINGS && tried.lengthSqr() > 0; halving++) {
                if (!endsBadly(tried)) {
                    return lifted(tried, stopped);
                }
                tried = tried.scale(0.5);
            }
            boolean halved = velocity.lengthSqr() > 0;
            Vec3 halt = halved && endsBadly(Vec3.ZERO) && canBackOff() ? retreat() : Vec3.ZERO;
            return lifted(halt, stopped || halved);
        }

        // A rise that is no descent is never the danger and is kept out of the brake and
        // the back off. It goes back on wherever the place above does not hurt. A glide
        // held at a wall would sink without the lift that holds its height.
        private Verdict lifted(Vec3 velocity, boolean stopped) {
            Vec3 risen = new Vec3(velocity.x, rise, velocity.z);
            return new Verdict(rise > 0 && !endsBadly(risen) ? risen : velocity, stopped);
        }

        // True when the player may be moved back. The retreat is judged where the game
        // slides it to along a wall at their side and it has to get them somewhere. No
        // liquid is there and nothing that hurts to be in and the copy's next step does
        // not hurt from it.
        private boolean canBackOff() {
            Vec3 slid = moved(retreat());
            AABB behind = box.move(slid);
            Level level = player.level();
            return slid.horizontalDistance() >= LEAST_BACK && !level.containsAnyLiquid(behind)
                && level.getBlockStates(behind).noneMatch(BlockUtil::harmful)
                && ahead.next().damage(player, behind) <= 0;
        }
    }

    // What the last brake judged and the tick it did. Entity.setDeltaMovement keeps the very
    // vector it is handed. That vector still being there on the same tick means nothing has
    // set the velocity since and a second module guarding the same flight takes the first
    // one's word for it.
    private static Verdict last = Verdict.onward(null);
    private static int judgedAt;

    // Held from the start. The copy has to be followed long before a wall comes near.
    private final GlideMirror mirror = GlideMirror.get();

    private final BoolSetting enabled = new BoolSetting("No crash",
        "Stops you short of what you face when flying into it would hurt and moves you back when you are too"
            + " near it.",
        false);
    private final NumberSetting lookAhead = new NumberSetting("Crash look ahead",
        "The least distance along your flight path to check.", 5, 1, 15, 1, " blocks")
        .under(enabled);

    public Setting<?>[] settings() {
        return new Setting<?>[] {enabled, lookAhead};
    }

    public CrashGuard shownWhen(Supplier<Boolean> asked) {
        enabled.visibleWhen(asked);
        return this;
    }

    // True when the flight was stopped dead or turned back.
    public boolean brake(LocalPlayer player) {
        // The server takes no glide step from a landed position. The glide flag stays up
        // for some ticks after a touchdown and there is nothing to guard in them.
        if (!enabled.isOn() || player.onGround()) {
            return false;
        }
        Vec3 velocity = player.getDeltaMovement();
        if (velocity == last.velocity() && player.tickCount == judgedAt) {
            return last.stopped();
        }
        last = judge(player, velocity);
        judgedAt = player.tickCount;
        if (last.velocity() != velocity) {
            player.setDeltaMovement(last.velocity());
        }
        return last.stopped();
    }

    private Verdict judge(LocalPlayer player, Vec3 velocity) {
        AABB box = player.getBoundingBox();
        double pace = velocity.length();
        boolean still = pace < STILL;
        // Nothing stands near enough to the path for any step of the copy to reach.
        Vec3 path = still ? Vec3.ZERO : velocity.scale(reach(pace) / pace);
        if (player.level().noCollision(player, box.expandTowards(path).inflate(GlideMirror.FARTHEST))) {
            return Verdict.onward(velocity);
        }
        GlideMirror.Ahead ahead = mirror.ahead(player);
        if (!ahead.canHurt()) {
            return Verdict.onward(velocity);
        }
        Vec3 look = mirror.levelLook(player);
        // Close up against a block in front a copy too slow to be hurt is stopped by it
        // before it gets any faster and stays that slow. A faster one beside a wall only
        // loses its sideways part each tick and is no safer for it.
        if (!ahead.next().canHurt() && new GlideMirror.Step(0, look.scale(PINNED)).blocked(player, box)) {
            return Verdict.onward(velocity);
        }
        double rise = Math.max(0, velocity.y);
        Tick tick = new Tick(player, box, ahead, look, rise, ahead.hurts(player, box));
        Vec3 flight = rise > 0 ? velocity.subtract(0, rise, 0) : velocity;
        Split split = Split.of(flight, look);
        // A turn of the head can bring a wall within reach where the player already is. With
        // no way back they are only kept from going nearer. Every key handed back would have
        // them pushed in and backed out in turn. What is left of the flight is at right
        // angles to the look and not to the wall and is flown only where it ends well.
        if (tick.hurtsHere()) {
            boolean back = tick.canBackOff();
            return tick.flown(back ? tick.retreat() : split.aside(), back);
        }
        if (still) {
            return Verdict.onward(velocity);
        }
        if (flight.length() < STILL) {
            return tick.flown(Vec3.ZERO, false);
        }
        double whole = share(tick, flight);
        if (whole >= 1) {
            return tick.flown(flight, false);
        }
        double own = split.hasBoth() ? share(tick, split.aside()) : 0;
        if (own > whole) {
            return tick.flown(split.aside().scale(own).add(split.nearer().scale(whole)), false);
        }
        if (whole > 0) {
            return tick.flown(flight.scale(whole), false);
        }
        // Nothing may be flown. A landing beside a rise the player faces would hang in the
        // air for good and they are backed off until the way down is clear.
        return tick.flown(flight.y < 0 && tick.canBackOff() ? tick.retreat() : Vec3.ZERO, true);
    }

    private double reach(double pace) {
        return Math.max(lookAhead.getValue(), pace * MIN_TICKS);
    }

    private double share(Tick tick, Vec3 flight) {
        double pace = flight.length();
        Vec3 heading = flight.scale(1 / pace);
        double reach = reach(pace);
        double clear = clearStretch(tick, heading, reach);
        if (clear >= reach) {
            return 1;
        }
        return KEEP * Math.max(0, clear - ROOM * heading.horizontalDistance()) / reach;
    }

    // How far the body gets along the heading before it comes to a place a step would
    // hurt from. The reach when it never does. Each test is the place the game would slide
    // the body to along whatever the straight path meets. The sweep ends where that place
    // stops moving.
    private static double clearStretch(Tick tick, Vec3 heading, double reach) {
        LocalPlayer player = tick.player();
        int tests = Math.min((int) Math.ceil(reach / SWEEP_STEP), MAX_STEPS);
        Vec3 before = null;
        for (int test = 1; test <= tests; test++) {
            Vec3 slid = Entity.collideBoundingBox(player, heading.scale(test * SWEEP_STEP), tick.box(),
                player.level(), List.of());
            if (slid.equals(before)) {
                return reach;
            }
            if (tick.ahead().hurts(player, tick.box().move(slid))) {
                return (test - 1) * SWEEP_STEP;
            }
            before = slid;
        }
        return reach;
    }
}
