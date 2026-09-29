package com.jellypudding.offlineclient.util;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

// Fakes a long fall for a mace hit. The player rises by packet and drops back onto the
// spot the hit comes from and the server holds the whole drop as a fall. More hits land
// inside the target's hurt window. Each has to hit harder than the one before to count
// and a totem only saves its holder from the first.
public final class MaceSmash {

    // The server lets a harder hit into the hurt window for ten ticks after one lands.
    private static final int HURT_WINDOW = 10;

    // A fall has to pass one and a half blocks to smash.
    private static final double LEAST_FALL = 2;
    // A lift with no room above tries again a block lower.
    private static final double LIFT_STEP = 1;

    // The hits that went into the plan and the ticks all the ones after the first needed.
    public record Smash(int hits, int followTicks) {

        // True when the hurt window closed before every hit could land.
        public boolean outOfTime() {
            return followTicks >= HURT_WINDOW;
        }
    }

    private static final Smash NONE = new Smash(0, 0);

    private MaceSmash() {
    }

    // Adds smashes on the spot to the plan. The first falls the given height where the room
    // above allows and each after it a step further. A strike runs as the player lands for
    // it and hears which hit it is. No hits leave the plan as it was.
    public static Smash append(Hop.Plan plan, Vec3 spot, double height, int hits, double step,
                               IntConsumer strike) {
        double first = liftAndDrop(plan, spot, height, 0, () -> strike.accept(0));
        if (Double.isNaN(first)) {
            return NONE;
        }
        // The hits after the first are rehearsed to see how many the hurt window takes.
        Hop.Plan rehearsal = plan.fork(spot);
        List<Double> lifts = new ArrayList<>();
        List<Integer> ticks = new ArrayList<>();
        double last = first;
        for (int hit = 1; hit < hits; hit++) {
            double lift = liftAndDrop(rehearsal, spot, height + hit * step, last, () -> { });
            if (Double.isNaN(lift)) {
                break;
            }
            lifts.add(lift);
            ticks.add(rehearsal.ticks());
            last = lift;
        }
        int landed = 1;
        for (int i = 0; i < lifts.size() && ticks.get(i) < HURT_WINDOW; i++) {
            int hit = landed++;
            plan.to(spot.add(0, lifts.get(i), 0)).dropTo(spot).then(() -> strike.accept(hit));
        }
        return new Smash(landed, ticks.isEmpty() ? 0 : ticks.getLast());
    }

    // The highest lift up to the wanted one with a clear way up and back. A hit after the
    // first has to fall further than the one before. NaN when no lift will do.
    private static double liftAndDrop(Hop.Plan plan, Vec3 spot, double wanted, double beat,
                                      Runnable strike) {
        for (double lift = wanted; lift >= LEAST_FALL && lift > beat; lift -= LIFT_STEP) {
            Vec3 top = spot.add(0, lift, 0);
            if (plan.attempt(steps -> steps.to(top).dropTo(spot).then(strike))) {
                return lift;
            }
        }
        return Double.NaN;
    }
}
