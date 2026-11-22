package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.modules.hunting.NewChunks;
import com.jellypudding.offlineclient.modules.movement.Autopilot.Stop;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.Setting;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.TrailScan;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.function.Consumer;

// The trail route of Autopilot. It reads which way a trail of old chunks runs every few ticks and
// puts the stop far along it.
final class TrailFollower {

    enum TrailEnd { STOP, FLY_ON }

    private enum Tracking { WAITING, FOLLOWING, SEARCHING }

    private static final Minecraft MC = OfflineClient.MC;

    private static final int CHUNK = SectionPos.SECTION_SIZE;

    private static final int TRAIL_READ_TICKS = 10;
    private static final float TRAIL_TURN = 60;
    private static final float TRAIL_STEP = 5;
    // Chunks this close say nothing about the way ahead.
    private static final int TRAIL_NEAR = 2;
    // A new chunk counts against a way half as much as an old one counts for it.
    private static final double NEW_WEIGHT = 0.5;
    private static final float WAIT_SPREAD = 90;
    private static final double START_SCORE = 3;
    private static final float SEARCH_SPREAD = 120;
    private static final double SEARCH_CIRCLE = 48;
    private static final float WIDEST = 180;

    private final BoolSetting waitForTrail = new BoolSetting("Wait for a trail",
        "Flies straight on along your heading until a trail of old chunks turns up ahead and then follows it.",
        false);
    private final NumberSetting searchTime = new NumberSetting("Search time",
        "Circles where the trail was lost for this long looking for it before the trail counts as ended. Nought"
            + " never circles.", 0, 0, 60, 1, "s");
    private final EnumSetting<TrailScan.Side> preferSide = new EnumSetting<>("Prefer side",
        "Which branch is followed where the trail forks.", TrailScan.Side.EITHER)
        .describe(TrailScan.Side.EITHER, "The branch with the most old chunks.")
        .describe(TrailScan.Side.LEFT,
            "The branch on your left whenever it holds at least half as many old chunks as the other.")
        .describe(TrailScan.Side.RIGHT,
            "The branch on your right whenever it holds at least half as many old chunks as the other.");
    private final NumberSetting widestTurn = new NumberSetting("Widest turn",
        "How far the trail may turn away from the heading it was found on. It keeps you off trails that cross it.",
        180, 10, 180, 5, " degrees")
        .min(0).max(180);
    private final BoolSetting oldVersionsOnly = new BoolSetting("Old versions only",
        "Follows only the chunks NewChunks marks as an older game version. Its Old versions setting has to be on.",
        false);
    private final EnumSetting<TrailEnd> trailEnd = new EnumSetting<>("Trail end",
        "What happens when the trail runs out.", TrailEnd.STOP)
        .describe(TrailEnd.STOP, "Ends the flight the way At the end says.")
        .describe(TrailEnd.FLY_ON,
            "Flies on along the last heading of the trail and takes up the next trail that turns up ahead.");

    private final Consumer<String> tell;

    private Tracking tracking = Tracking.FOLLOWING;
    private float trailYaw;
    private int trailWait;
    private float firstYaw;
    private Vec3 lostAt;
    private float lostYaw;
    private int searchTicks;
    private boolean trailFound;

    TrailFollower(Consumer<String> tell) {
        this.tell = tell;
    }

    Setting<?>[] settings() {
        return new Setting<?>[] {waitForTrail, searchTime, preferSide, widestTurn, oldVersionsOnly, trailEnd};
    }

    <E extends Enum<E>> TrailFollower under(EnumSetting<E> parent, E value) {
        Setting.under(settings(), parent, value);
        return this;
    }

    void start(float yaw) {
        trailYaw = yaw;
        trailWait = 0;
        trailFound = false;
        tracking = waitForTrail.isOn() ? Tracking.WAITING : Tracking.FOLLOWING;
    }

    void readAgain() {
        trailWait = 0;
    }

    String startWords() {
        return tracking == Tracking.WAITING ? "flying on until a trail turns up ahead." : "following the trail ahead.";
    }

    String words() {
        return switch (tracking) {
            case WAITING -> "waiting for a trail";
            case FOLLOWING -> "on a trail";
            case SEARCHING -> "searching for the trail";
        };
    }

    // Why the trail flight is over in words that follow the name of Autopilot.
    String endWords() {
        return !Modules.enabled(NewChunks.class) ? "stopped as NewChunks was switched off."
            : trailFound ? "reached the end of the trail." : "found no trail of old chunks ahead.";
    }

    // Null once the trail has ended and nothing is left to fly.
    Stop next(Stop flying) {
        if (!Modules.enabled(NewChunks.class)) {
            return null;
        }
        Stop stop = flying;
        if (tracking == Tracking.SEARCHING) {
            searchTicks++;
            stop = searchStop();
        }
        if (stop != null && trailWait-- > 0) {
            return stop;
        }
        trailWait = TRAIL_READ_TICKS;
        Vec3 here = MC.player.position();
        return switch (tracking) {
            case WAITING -> waitForTrail(here);
            case FOLLOWING -> followTrail(here);
            case SEARCHING -> searchForTrail(here, stop);
        };
    }

    private Stop waitForTrail(Vec3 here) {
        float way = scan(false).way(here, trailYaw, WAIT_SPREAD, TRAIL_STEP, TRAIL_NEAR, trailReach(), START_SCORE);
        if (!Float.isNaN(way)) {
            tell.accept("found a trail and follows it.");
            takeUpTrail(way);
        }
        return aimTrail(Float.isNaN(way) ? trailYaw : way);
    }

    private Stop followTrail(Vec3 here) {
        float way = scan(trailFound).way(here, trailYaw, TRAIL_TURN, TRAIL_STEP, TRAIL_NEAR, trailReach(), 0);
        if (!Float.isNaN(way)) {
            if (!trailFound) {
                takeUpTrail(way);
            }
            return aimTrail(way);
        }
        if (!trailFound || searchTime.getValue() <= 0) {
            return trailEnded();
        }
        tracking = Tracking.SEARCHING;
        lostAt = here;
        lostYaw = trailYaw;
        searchTicks = 0;
        Stop circling = searchStop();
        tell.accept("lost the trail and circles to find it again.");
        return circling;
    }

    // The way back along the trail lies outside the fan.
    private Stop searchForTrail(Vec3 here, Stop circling) {
        float way = scan(true).way(here, lostYaw, SEARCH_SPREAD, TRAIL_STEP, TRAIL_NEAR, trailReach(), 0);
        if (!Float.isNaN(way)) {
            tell.accept("found the trail again.");
            tracking = Tracking.FOLLOWING;
            return aimTrail(way);
        }
        return searchTicks < searchTime.getValue() * SharedConstants.TICKS_PER_SECOND ? circling : trailEnded();
    }

    private Stop searchStop() {
        return Stop.at(Autopilot.circlePoint(lostAt, SEARCH_CIRCLE));
    }

    private void takeUpTrail(float way) {
        tracking = Tracking.FOLLOWING;
        trailFound = true;
        firstYaw = way;
    }

    private Stop trailEnded() {
        if (!trailEnd.is(TrailEnd.FLY_ON)) {
            return null;
        }
        tell.accept((trailFound ? "reached the end of the trail" : "found no trail ahead") + " and flies on.");
        tracking = Tracking.WAITING;
        trailFound = false;
        return aimTrail(trailYaw);
    }

    private Stop aimTrail(float yaw) {
        trailYaw = Mth.wrapDegrees(yaw);
        Vec3 way = Vec3.directionFromRotation(0, trailYaw).scale(trailReach() * CHUNK);
        return new Stop(null, MC.player.getX() + way.x, MC.player.getZ() + way.z);
    }

    // Widest turn holds a trail once it is found. Before that every way of the fan counts.
    private TrailScan scan(boolean held) {
        return new TrailScan(this::trailWeight, preferSide.getValue(), firstYaw,
            held ? widestTurn.getFloat() : WIDEST);
    }

    private double trailWeight(int chunkX, int chunkZ) {
        NewChunks.Verdict verdict = Modules.chunkVerdict(chunkX, chunkZ);
        if (verdict == null) {
            return 0;
        }
        if (!verdict.old()) {
            return -NEW_WEIGHT;
        }
        return !oldVersionsOnly.isOn() || verdict == NewChunks.Verdict.OLD_VERSION ? 1 : 0;
    }

    private int trailReach() {
        return Math.max(TRAIL_NEAR + 1, Modules.renderDistance() - 1);
    }
}
