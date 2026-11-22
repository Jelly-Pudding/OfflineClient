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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// The cases of the Movement suite played again on a server that runs an anti cheat. Each anti
// cheat named in the local settings gets a server of its own. No case says a module must get past
// one. A move the anti cheat stops is written down and a kick is too. What fails a case is a
// plain walk that gets set back or a game that ends up standing somewhere the server does not
// have it or a fault in the log. The table of the run lands in guard.txt.
public final class GuardSuite implements Suite {

    private static final String NAME = "Guard";
    private static final String NO_GUARD = "No anti cheat plugin is named in testing/local.properties";
    private static final String REPORT = "guard.txt";
    private static final String SURVIVAL = "survival";
    private static final String PLAIN = "plain moves draw no setback";
    private static final String WORKED = "worked";
    private static final String UNDER = " under ";
    // The name of a check in a line such as RigPlayer failed Simulation.
    private static final Pattern FAILED_CHECK = Pattern.compile("failed ([A-Za-z]+)");

    private static final int FORWARD = InputConstants.KEY_W;
    private static final int JUMP = InputConstants.KEY_SPACE;
    private static final int SPRINT = InputConstants.KEY_LCONTROL;
    private static final int RUN_TICKS = 60;
    private static final int SETTLE_TICKS = 20;
    private static final int REST_LIMIT = 300;
    private static final double IN_STEP = 0.1;

    private Duo duo;
    private final List<String> rows = new ArrayList<>();

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void run(Rig rig) {
        List<String> guards = RigConfig.guard();
        if (guards.isEmpty()) {
            under(rig, null);
        }
        try {
            for (String guard : guards) {
                under(rig, guard);
            }
        } finally {
            write(rig);
        }
    }

    // Plays every case on a server that runs the one anti cheat. Null stands for none being
    // named and only says why the cases are left out.
    private void under(Rig rig, String guard) {
        MovementSuite moves = new MovementSuite();
        String where = guard == null ? "" : UNDER + guard;
        List<Case> cases = new ArrayList<>();
        cases.add(Case.of(PLAIN + where, each -> plainMoves(each, PLAIN + where)));
        moves.servedPlays().forEach(
            (name, play) -> cases.add(Case.of(name + where, each -> trial(each, name + where, play))));
        List<Case> wanted = rig.wanted(NAME, cases);
        if (wanted.isEmpty()) {
            return;
        }
        String missing = missing(guard);
        if (missing != null) {
            wanted.forEach(each -> rig.skip(NAME, each.name(), missing));
            return;
        }
        rows.add("");
        rows.add("What " + guard + " made of each move");
        rows.add("");
        duo = Duo.alone(rig, PaperServer.Setup.with(guard));
        try {
            if (!duo.server().runs(guard)) {
                throw new RigFailure("The plugin " + guard + " did not start on the test server. Its log is "
                    + "server.log in the result folder");
            }
            Stage ground = new Stage(rig);
            moves.standOn(ground.home());
            for (Case each : wanted) {
                if (!ground.worldStands()) {
                    duo.rejoin();
                }
                ground.reset(false);
                rig.world().gameMode(SURVIVAL);
                duo.server().resetTallies();
                rig.run(NAME, each);
            }
        } finally {
            duo.close();
        }
    }

    private static String missing(String guard) {
        if (!Duo.available()) {
            return Duo.NO_SERVER;
        }
        if (guard == null) {
            return NO_GUARD;
        }
        return PaperServer.has(guard) ? null : "Neither the server folder nor testing/plugins holds a plugin called "
            + guard;
    }

    private void plainMoves(Rig rig, String name) {
        int mark = duo.server().logMark();
        Moved walk = Motion.hold(rig, RUN_TICKS, FORWARD);
        duo.leadHome();
        Moved sprint = Motion.hold(rig, RUN_TICKS, FORWARD, SPRINT);
        duo.leadHome();
        Moved hops = Motion.hold(rig, RUN_TICKS, FORWARD, SPRINT, JUMP);
        int setBack = walk.setbacks() + sprint.setbacks() + hops.setbacks();
        Set<String> checks = checks(rig, mark);
        rows.add(row(name, setBack, checks, WORKED));
        rig.note(String.format(Locale.ROOT, "A walk went %.1f blocks and a sprint %.1f and a sprint with jumps %.1f",
            walk.across(), sprint.across(), hops.across()));
        rig.expect(setBack == 0, "The anti cheat set plain moves back " + setBack + " times");
        rig.expect(checks.isEmpty(), "The anti cheat flagged plain moves for " + checks);
    }

    // One case of the Movement suite. What it expects of the module may fail here and that is
    // written down. The module goes off and the game gets a moment to come to rest.
    private void trial(Rig rig, String name, Case.Body play) throws Exception {
        int mark = duo.server().logMark();
        int moved = rig.setbacks();
        String outcome = WORKED;
        try {
            play.run(rig);
        } catch (RigFailure stopped) {
            outcome = "stopped. " + stopped.getMessage();
        } catch (RuntimeException lost) {
            // A case that asks the player something falls over once a kick took the player away.
            if (rig.ask(mc -> mc.player != null && mc.level != null)) {
                throw lost;
            }
            outcome = "stopped";
        }
        Set<String> checks = checks(rig, mark);
        boolean kicked = rig.ask(mc -> mc.player == null || mc.level == null);
        if (kicked) {
            rows.add(row(name, 0, checks, "kicked. " + outcome));
            rig.note("The server kicked the player");
            return;
        }
        rig.client().restoreDefaults();
        comeToRest(rig);
        int setBack = duo.setBacks(moved);
        rows.add(row(name, setBack, checks, outcome));
        rig.note("Set back " + setBack + " times and flagged for " + checks + " and " + outcome);
        if (Stage.dead(rig)) {
            rig.note("The player died");
            Stage.revive(rig);
            return;
        }
        double apart = rig.ask(mc -> mc.player.position()).distanceTo(rig.world().where());
        rig.expect(apart < IN_STEP, String.format(Locale.ROOT,
            "The game and the server have the player %.2f blocks apart once all is still", apart));
    }

    private Set<String> checks(Rig rig, int mark) {
        Set<String> checks = new LinkedHashSet<>();
        String player = rig.world().player();
        for (String line : duo.server().since(mark)) {
            Matcher failed = FAILED_CHECK.matcher(line);
            if (line.contains(player) && failed.find()) {
                checks.add(failed.group(1));
            }
        }
        return checks;
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

    private static String row(String name, int setBack, Set<String> checks, String outcome) {
        return String.format(Locale.ROOT, "%-84s set back %3d times  flagged %-34s %s", name, setBack, checks, outcome);
    }

    private void write(Rig rig) {
        if (rows.isEmpty()) {
            return;
        }
        try {
            Files.write(rig.results().out().resolve(REPORT), rows, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RigFailure("The table of the anti cheat run could not be written", e);
        }
    }
}
