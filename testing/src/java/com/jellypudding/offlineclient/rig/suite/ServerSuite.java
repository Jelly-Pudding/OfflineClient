package com.jellypudding.offlineclient.rig.suite;

import com.jellypudding.offlineclient.rig.core.Case;
import com.jellypudding.offlineclient.rig.core.Rig;
import com.jellypudding.offlineclient.rig.core.Stage;
import com.jellypudding.offlineclient.rig.duo.Duo;
import com.jellypudding.offlineclient.rig.duo.PaperServer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Two players on a Paper server. What the server holds about the first player and what the
// second player sees of it are the two things no single player world can show.
public final class ServerSuite implements Suite {

    // One thing to prove with both players on the server.
    @FunctionalInterface
    interface Play {
        void run(Rig rig, Duo duo) throws Exception;
    }

    private static final String NAME = "Server";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void run(Rig rig) {
        Map<String, Play> plays = new LinkedHashMap<>();
        SpoofPlays.addTo(plays);
        PlayerPlays.addTo(plays);
        List<Case> cases = new ArrayList<>();
        Duo[] stage = new Duo[1];
        plays.forEach((name, play) -> cases.add(Case.of(name, each -> play.run(each, stage[0]))));
        List<Case> wanted = rig.wanted(NAME, cases);
        if (wanted.isEmpty()) {
            return;
        }
        if (!Duo.available()) {
            wanted.forEach(each -> rig.skip(NAME, each.name(), Duo.NO_SERVER));
            return;
        }
        stage[0] = Duo.open(rig, PaperServer.Setup.plain());
        try {
            Stage ground = new Stage(rig);
            for (Case each : wanted) {
                if (!ground.worldStands()) {
                    stage[0].rejoin();
                }
                ground.reset(false);
                stage[0].partnerHome();
                stage[0].server().resetTallies();
                rig.run(NAME, each);
            }
        } finally {
            stage[0].close();
        }
    }
}
