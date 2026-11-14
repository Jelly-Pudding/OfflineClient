package com.jellypudding.offlineclient.rig.suite;

import com.jellypudding.offlineclient.rig.core.Case;
import com.jellypudding.offlineclient.rig.core.Rig;
import com.jellypudding.offlineclient.rig.core.Stage;
import com.jellypudding.offlineclient.rig.duo.Duo;
import com.jellypudding.offlineclient.rig.duo.PaperServer;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

// Plays the cases of a suite on a flat single player world and again on a Paper server. A module
// is only proven once a real server took what it did.
final class Grounds {

    private static final WorldCreationUiState.SelectedGameMode CREATIVE =
        WorldCreationUiState.SelectedGameMode.CREATIVE;
    private static final String SURVIVAL = "survival";
    private static final String ALONE = "in single player";
    private static final String SERVED = "on a Paper server";

    private Grounds() {
    }

    // Every case starts in survival on a stage put back to how it began. The suite is handed the
    // stage of each ground before the first case on it.
    static void play(Rig rig, String suite, Map<String, Case.Body> plays, Consumer<Stage> standOn) {
        for (String where : List.of(ALONE, SERVED)) {
            List<Case> cases = new ArrayList<>();
            plays.forEach((name, play) -> cases.add(Case.of(name + " " + where, play)));
            List<Case> wanted = rig.wanted(suite, cases);
            if (wanted.isEmpty()) {
                continue;
            }
            boolean served = where.equals(SERVED);
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
