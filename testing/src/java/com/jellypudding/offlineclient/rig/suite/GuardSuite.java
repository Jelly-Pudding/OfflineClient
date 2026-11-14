package com.jellypudding.offlineclient.rig.suite;

import com.jellypudding.offlineclient.rig.RigConfig;
import com.jellypudding.offlineclient.rig.core.Case;
import com.jellypudding.offlineclient.rig.core.Rig;
import com.jellypudding.offlineclient.rig.core.RigFailure;
import com.jellypudding.offlineclient.rig.core.Stage;
import com.jellypudding.offlineclient.rig.duo.Duo;
import com.jellypudding.offlineclient.rig.duo.PaperServer;
import com.jellypudding.offlineclient.rig.suite.Motion.Moved;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// The cases of the Movement suite played again on a server that runs an anti cheat. No case says
// a module must get past it. A move the anti cheat stops is written down and a kick is too. What
// fails a case is a plain walk that gets set back or a game that ends up standing somewhere the
// server does not have it or a fault in the log. The table of the run lands in guard.txt.
public final class GuardSuite implements Suite {

    private static final String NAME = "Guard";
    private static final String NO_GUARD = "No anti cheat plugin is named in testing/local.properties";
    private static final String REPORT = "guard.txt";
    private static final String SURVIVAL = "survival";
    private static final String PLAIN = "plain moves draw no setback";
    private static final String WORKED = "worked";

    private static final int FORWARD = InputConstants.KEY_W;
    private static final int JUMP = InputConstants.KEY_SPACE;
    private static final int SPRINT = InputConstants.KEY_LCONTROL;
    private static final int RUN_TICKS = 60;
    private static final int SETTLE_TICKS = 20;
    private static final int REST_LIMIT = 300;
    private static final double IN_STEP = 0.1;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void run(Rig rig) {
        List<String> guard = RigConfig.guard();
        MovementSuite moves = new MovementSuite();
        List<String> rows = new ArrayList<>();
        Duo[] stage = new Duo[1];
        List<Case> cases = new ArrayList<>();
        cases.add(Case.of(PLAIN, each -> plainMoves(each, stage[0], rows)));
        moves.plays().forEach(
            (name, play) -> cases.add(Case.of(name, each -> trial(each, stage[0], name, play, rows))));
        List<Case> wanted = rig.wanted(NAME, cases);
        if (wanted.isEmpty()) {
            return;
        }
        String missing = missing(guard);
        if (missing != null) {
            wanted.forEach(each -> rig.skip(NAME, each.name(), missing));
            return;
        }
        stage[0] = Duo.alone(rig, PaperServer.Setup.with(guard.toArray(String[]::new)));
        try {
            for (String plugin : guard) {
                if (!stage[0].server().runs(plugin)) {
                    throw new RigFailure("The plugin " + plugin + " did not start on the test server. Its log is "
                        + "server.log in the result folder");
                }
            }
            Stage ground = new Stage(rig);
            moves.standOn(ground.home());
            for (Case each : wanted) {
                if (!ground.worldStands()) {
                    stage[0].rejoin();
                }
                ground.reset(false);
                rig.world().gameMode(SURVIVAL);
                stage[0].server().resetTallies();
                rig.run(NAME, each);
            }
        } finally {
            stage[0].close();
            write(rig, guard, rows);
        }
    }

    // Why the suite cannot run. Null when it can.
    private static String missing(List<String> guard) {
        if (!Duo.available()) {
            return Duo.NO_SERVER;
        }
        if (guard.isEmpty()) {
            return NO_GUARD;
        }
        for (String plugin : guard) {
            if (!PaperServer.has(plugin)) {
                return "The server folder holds no plugin called " + plugin;
            }
        }
        return null;
    }

    private static void plainMoves(Rig rig, Duo duo, List<String> rows) {
        Moved walk = Motion.hold(rig, RUN_TICKS, FORWARD);
        duo.leadHome();
        Moved sprint = Motion.hold(rig, RUN_TICKS, FORWARD, SPRINT);
        duo.leadHome();
        Moved hops = Motion.hold(rig, RUN_TICKS, FORWARD, SPRINT, JUMP);
        int setBack = walk.setbacks() + sprint.setbacks() + hops.setbacks();
        rows.add(row(PLAIN, setBack, WORKED));
        rig.note(String.format(Locale.ROOT, "A walk went %.1f blocks and a sprint %.1f and a sprint with jumps %.1f",
            walk.across(), sprint.across(), hops.across()));
        rig.expect(setBack == 0, "The anti cheat set plain moves back " + setBack + " times");
    }

    // One case of the Movement suite. What it expects of the module may fail here and that is
    // written down. The module goes off and the game gets a moment to come to rest.
    private static void trial(Rig rig, Duo duo, String name, Case.Body play, List<String> rows) throws Exception {
        String outcome = WORKED;
        try {
            play.run(rig);
        } catch (RigFailure stopped) {
            outcome = "stopped. " + stopped.getMessage();
        }
        boolean kicked = rig.ask(mc -> mc.player == null || mc.level == null);
        if (kicked) {
            rows.add(row(name, 0, "kicked. " + outcome));
            rig.note("The server kicked the player");
            return;
        }
        rig.client().restoreDefaults();
        comeToRest(rig);
        double setBack = duo.setBacks();
        rows.add(row(name, setBack, outcome));
        rig.note("Set back " + (int) setBack + " times and " + outcome);
        boolean dead = rig.ask(mc -> mc.player.isDeadOrDying());
        if (dead) {
            return;
        }
        double apart = rig.ask(mc -> mc.player.position()).distanceTo(rig.world().where());
        rig.expect(apart < IN_STEP, String.format(Locale.ROOT,
            "The game and the server have the player %.2f blocks apart once all is still", apart));
    }

    // A player still sliding down a ladder is a tick ahead of what the server holds and that
    // is no fault. The game is asked once it has stood in one place for a while.
    private static void comeToRest(Rig rig) {
        int still = 0;
        Vec3 last = rig.ask(mc -> mc.player.position());
        for (int waited = 0; waited < REST_LIMIT && still < SETTLE_TICKS; waited++) {
            rig.ticks(1);
            Vec3 now = rig.ask(mc -> mc.player.position());
            still = now.equals(last) ? still + 1 : 0;
            last = now;
        }
    }

    private static String row(String name, double setBack, String outcome) {
        return String.format(Locale.ROOT, "%-72s set back %3d times  %s", name, (int) setBack, outcome);
    }

    private static void write(Rig rig, List<String> guard, List<String> rows) {
        List<String> lines = new ArrayList<>();
        lines.add("What " + String.join(" and ", guard) + " made of each move");
        lines.add("");
        lines.addAll(rows);
        try {
            Files.write(rig.results().out().resolve(REPORT), lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RigFailure("The table of the anti cheat run could not be written", e);
        }
    }
}
