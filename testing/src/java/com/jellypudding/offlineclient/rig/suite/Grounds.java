package com.jellypudding.offlineclient.rig.suite;

import com.jellypudding.offlineclient.rig.core.Case;
import com.jellypudding.offlineclient.rig.core.Rig;
import com.jellypudding.offlineclient.rig.core.Stage;
import com.jellypudding.offlineclient.rig.duo.Duo;
import com.jellypudding.offlineclient.rig.duo.PaperServer;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

// Plays the cases of a suite on a flat single player world and again on a Paper server. A module
// is only proven once a real server took what it did.
final class Grounds {

    private static final WorldCreationUiState.SelectedGameMode CREATIVE =
        WorldCreationUiState.SelectedGameMode.CREATIVE;
    private static final String SURVIVAL = "survival";
    private static final String ALONE = "in single player";
    private static final String SERVED = "on a Paper server";
    private static final String HOLDS_BACK = "It proves what a module leaves alone and a control run has no use for it";

    private Grounds() {
    }

    // Which cases play on one ground alone and which the client is known to fail on a real server
    // with what was seen. And which prove what a module leaves alone.
    record Limits(Set<String> serverOnly, Set<String> aloneOnly, Map<String, String> faultsOnServer,
                  Set<String> holdsBack) {

        static final Limits NONE = new Limits(Set.of(), Set.of(), Map.of(), Set.of());

        // Cases only a real server can play. The test framework steps a single player game and
        // its server together and nothing there can run ahead of the server.
        Limits serverOnly(String... names) {
            return new Limits(Set.of(names), aloneOnly, faultsOnServer, holdsBack);
        }

        // Cases a real server would throw the player out for.
        Limits aloneOnly(String... names) {
            return new Limits(serverOnly, Set.of(names), faultsOnServer, holdsBack);
        }

        Limits knownOnServer(String name, String fault) {
            Map<String, String> faults = new HashMap<>(faultsOnServer);
            faults.put(name, fault);
            return new Limits(serverOnly, aloneOnly, Map.copyOf(faults), holdsBack);
        }

        // Cases that prove a module keeps its hands off something. A module that is off keeps
        // them off as well and a control run leaves these out. Each has a case beside it that
        // proves the same module at work.
        Limits holdsBack(String... names) {
            return new Limits(serverOnly, aloneOnly, faultsOnServer, Set.of(names));
        }

        private boolean plays(String name, boolean served) {
            return served ? !aloneOnly.contains(name) : !serverOnly.contains(name);
        }
    }

    // Every case starts in survival on a stage put back to how it began. The suite is handed the
    // stage of each ground before the first case on it.
    static void play(Rig rig, String suite, Map<String, Case.Body> plays, Consumer<Stage> standOn) {
        play(rig, suite, plays, standOn, Limits.NONE);
    }

    static void play(Rig rig, String suite, Map<String, Case.Body> plays, Consumer<Stage> standOn, Limits limits) {
        for (String where : List.of(ALONE, SERVED)) {
            boolean served = where.equals(SERVED);
            List<Case> cases = new ArrayList<>();
            plays.forEach((name, play) -> {
                // A control run needs each case once. The server only gets what it alone can play.
                boolean twice = rig.control() && served && limits.plays(name, false);
                if (!limits.plays(name, served) || twice) {
                    return;
                }
                String named = name + " " + where;
                if (rig.control() && limits.holdsBack().contains(name)) {
                    if (rig.wants(suite, named)) {
                        rig.skip(suite, named, HOLDS_BACK);
                    }
                    return;
                }
                Case made = Case.of(named, play);
                String fault = served ? limits.faultsOnServer().get(name) : null;
                cases.add(fault == null ? made : made.known(fault));
            });
            List<Case> wanted = rig.wanted(suite, cases);
            if (wanted.isEmpty()) {
                continue;
            }
            if (served && !Duo.available()) {
                wanted.forEach(each -> rig.skip(suite, each.name(), Duo.NO_SERVER));
                continue;
            }
            Duo duo = served ? Duo.alone(rig, PaperServer.Setup.plain()) : null;
            if (!served) {
                rig.openWorld(CREATIVE);
            }
            try {
                Stage stage = new Stage(rig);
                standOn.accept(stage);
                for (Case each : wanted) {
                    if (!stage.worldStands()) {
                        comeBack(stage, duo);
                        standOn.accept(stage);
                    }
                    stage.reset(false);
                    rig.world().gameMode(SURVIVAL);
                    rig.run(suite, each);
                }
            } finally {
                if (served) {
                    duo.close();
                } else {
                    rig.closeWorld();
                }
            }
        }
    }

    // A case that lost the world gets it back before the next one runs.
    private static void comeBack(Stage stage, Duo duo) {
        if (duo == null) {
            stage.reopen(CREATIVE);
        } else {
            duo.rejoin();
        }
    }
}
