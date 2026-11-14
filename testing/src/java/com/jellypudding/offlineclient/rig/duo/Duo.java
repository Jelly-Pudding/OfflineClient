package com.jellypudding.offlineclient.rig.duo;

import com.jellypudding.offlineclient.rig.RigConfig;
import com.jellypudding.offlineclient.rig.core.Rig;
import com.jellypudding.offlineclient.rig.core.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// The stage of the two player suites. One Paper server with both players on it. Neither player
// is an operator. An anti cheat lets operators off and a test must not be let off.
public final class Duo {

    public static final String NO_SERVER = "No server folder is named in testing/local.properties";

    // Where the first player stands at the start of a case. It looks north.
    private static final double HOME_X = 0.5;
    private static final double HOME_Y = -60;
    private static final double HOME_Z = 0.5;
    private static final float HOME_YAW = 180;
    // The second player stands four blocks east of it out of the way of a run north and looks at it.
    private static final String PARTNER_SPOT = "4.5 -60 0.5 90 0";
    private static final String IN_REACH_SPOT = "2.5 -60 0.5 90 0";
    private static final String WALL_BEHIND = "fill 3 -60 0 3 -59 0 stone";
    // Further off than a server shows one player to another.
    private static final String FAR_SPOT = "300.5 -60 0.5";
    private static final int SEEN_TICKS = 5;
    private static final String TELEPORTS = "teleports.";
    private static final String BY_COMMAND = ".COMMAND";
    private static final List<String> STEADY_WORLD = List.of("time set noon", "weather clear",
        "gamerule advance_time false", "gamerule advance_weather false", "gamerule spawn_mobs false");

    private final Rig rig;
    private final PaperServer server;
    private final Partner partner;

    private Duo(Rig rig, PaperServer server, Partner partner) {
        this.rig = rig;
        this.server = server;
        this.partner = partner;
    }

    // False when the local settings name no server to borrow.
    public static boolean available() {
        return RigConfig.server() != null;
    }

    // Starts the server and brings both players onto it.
    public static Duo open(Rig rig, PaperServer.Setup setup) {
        return open(rig, setup, true);
    }

    // The same with the first player alone for cases that need a real server and nobody else.
    public static Duo alone(Rig rig, PaperServer.Setup setup) {
        return open(rig, setup, false);
    }

    private static Duo open(Rig rig, PaperServer.Setup setup, boolean both) {
        PaperServer server = PaperServer.start(rig, setup);
        try {
            rig.enter(PaperWorld.join(rig, server, PaperServer.address()));
            server.console(STEADY_WORLD);
            Partner partner = both ? Partner.get(rig) : null;
            Duo duo = new Duo(rig, server, partner);
            duo.leadHome();
            if (both) {
                partner.ask("join", PaperServer.address());
                duo.partnerHome();
            }
            return duo;
        } catch (RuntimeException | Error failed) {
            rig.closeWorld();
            server.stop();
            throw failed;
        }
    }

    public PaperServer server() {
        return server;
    }

    public Partner partner() {
        return partner;
    }

    public World world() {
        return rig.world();
    }

    public void leadHome() {
        rig.world().teleport(HOME_X, HOME_Y, HOME_Z, HOME_YAW, 0);
    }

    // Puts the second player back on its spot in survival with a full life and empty hands and
    // its client the way a first run starts.
    public void partnerHome() {
        server.console(List.of("gamemode survival " + Partner.NAME, "clear " + Partner.NAME,
            "effect clear " + Partner.NAME, "effect give " + Partner.NAME + " instant_health 1 20 true",
            "tp " + Partner.NAME + " " + PARTNER_SPOT));
        partner.ask("defaults");
        partner.ask("ticks", Integer.toString(SEEN_TICKS));
    }

    // Stands the second player two blocks east of the first with a wall at its back. No hit knocks
    // it out of reach. A hardy one loses no life to a hit either.
    public void partnerInReach(boolean hardy) {
        List<String> lines = new ArrayList<>(List.of(WALL_BEHIND, "tp " + Partner.NAME + " " + IN_REACH_SPOT));
        if (hardy) {
            lines.add("effect give " + Partner.NAME + " resistance 600 4 true");
        }
        server.console(lines);
        partner.ask("ticks", Integer.toString(SEEN_TICKS));
    }

    // Puts the second player on a spot of the case's own choosing.
    public void partnerAt(double x, double y, double z, float yaw) {
        server.console(String.format(Locale.ROOT, "tp %s %.2f %.2f %.2f %.1f 0", Partner.NAME, x, y, z, yaw));
        partner.ask("ticks", Integer.toString(SEEN_TICKS));
    }

    // Sends the second player out of the first one's sight.
    public void partnerAway() {
        server.console("tp " + Partner.NAME + " " + FAR_SPOT);
        partner.ask("ticks", Integer.toString(SEEN_TICKS));
    }

    // The first player leaves and joins again. What a client tells a server about itself is
    // said once as it joins. A player the server threw out joins again the same way.
    public void rejoin() {
        rig.closeWorld();
        rig.enter(PaperWorld.join(rig, server, PaperServer.address()));
        leadHome();
    }

    // What the server holds about the first player this tick.
    public Map<String, String> lead() {
        return server.player(rig.world().player());
    }

    // What the first player did on the server since the tallies were reset.
    public Map<String, Double> leadTally() {
        return server.tally(rig.world().player());
    }

    // How often the server moved the first player since the tallies were reset with no command
    // of the rig asking for it. An anti cheat that sets a player back shows here.
    public double setBacks() {
        double moved = 0;
        for (Map.Entry<String, Double> each : leadTally().entrySet()) {
            if (each.getKey().startsWith(TELEPORTS) && !each.getKey().endsWith(BY_COMMAND)) {
                moved += each.getValue();
            }
        }
        return moved;
    }

    public void close() {
        try {
            if (partner != null) {
                partner.ask("leave");
            }
        } finally {
            rig.closeWorld();
            server.stop();
        }
    }
}
