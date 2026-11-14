package com.jellypudding.offlineclient.path;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

// Asks the rules of a walk about one spot for the rig. It lives in the package of the rules to
// reach what they keep to themselves.
public final class PathProbe {

    private PathProbe() {
    }

    // How many steps the rules allow from where the player stands. None means a spot such as the
    // top of a spike that nobody leaves unhurt.
    public static int stepCount() {
        List<PathFinder.Step> steps = new ArrayList<>();
        new PathFinder().liveRules().steps(PathFinder.standingAt(OfflineClient.MC.player), steps);
        return steps.size();
    }

    // Every step the rules allow from where the player stands as they read the world this tick.
    public static String stepsFromPlayer() {
        BlockPos start = PathFinder.standingAt(OfflineClient.MC.player);
        PathFinder.Rules rules = new PathFinder().liveRules();
        List<PathFinder.Step> steps = new ArrayList<>();
        rules.steps(start, steps);
        StringBuilder told = new StringBuilder("From " + start.toShortString() + " the rules allow " + steps.size()
            + " steps and standing there is " + rules.canStand(start) + " and it fits " + rules.fitsAt(start) + ".");
        for (PathFinder.Step step : steps) {
            told.append(' ').append(step.to().toShortString().replace(" ", ""));
        }
        return told.toString();
    }
}
