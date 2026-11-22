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
import java.util.Set;

// Two players on a Paper server. What the server holds about the first player and what the
// second player sees of it are the two things no single player world can show.
public final class ServerSuite implements Suite {

    @FunctionalInterface
    interface Play {
        void run(Rig rig, Duo duo) throws Exception;
    }

    private static final String NAME = "Server";
    // Cases that hold with every module off. Two say how things stand before a module touches
    // them and Streamers checks on its button whether it is on or not. A control run leaves
    // them out.
    private static final Set<String> NEEDS_NO_MODULE = Set.of("both players stand on the server",
        "the server sees the mod loader with ServerSpoof off",
        "Streamers says what a check found with no site in reach");
    private static final String NO_MODULE = "It holds with every module off and a control run has no use for it";

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
        plays.forEach((name, play) -> {
            if (!rig.control() || !NEEDS_NO_MODULE.contains(name)) {
                cases.add(Case.of(name, each -> play.run(each, stage[0])));
            } else if (rig.wants(NAME, name)) {
                rig.skip(NAME, name, NO_MODULE);
            }
        });
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
