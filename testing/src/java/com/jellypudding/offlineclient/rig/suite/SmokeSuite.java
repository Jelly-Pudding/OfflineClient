package com.jellypudding.offlineclient.rig.suite;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.rig.core.Case;
import com.jellypudding.offlineclient.rig.core.Rig;
import com.jellypudding.offlineclient.rig.core.RigClock;
import com.jellypudding.offlineclient.rig.core.RigFailure;
import com.jellypudding.offlineclient.rig.core.Stage;
import com.jellypudding.offlineclient.rig.duo.Duo;
import com.jellypudding.offlineclient.rig.duo.PaperServer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Every module is switched on for a moment and off again in a flat world. First in creative with
// empty hands and then in survival with a kit and two mobs beside the player. Then once more on a
// Paper server with a second player in reach where a kick fails the case. Every command
// is asked for its help and then typed with nothing after it. The log and the uncaught exception trap
// are read after each one and a failure names the module or the command.
public final class SmokeSuite implements Suite {

    private static final String NAME = "Smoke";
    private static final String CREATIVE = "creative";
    private static final String SURVIVAL = "survival";
    private static final String SERVER = "server";
    private static final String LEFT_OUT = "Left out of blanket runs because ";
    private static final String COMMAND = "command";
    private static final int ON_TICKS = 10;
    private static final int OFF_TICKS = 2;
    // Two seconds on a real server. Most modules have done their first piece of work by then.
    private static final int SERVED_TICKS = 40;
    private static final int LONGEST_REPLY = 90;
    // Five times the pace of the game. Nothing in this suite measures time.
    private static final float HURRY = 5;
    private static final int SMALL_WIDTH = 480;
    private static final int SMALL_HEIGHT = 270;

    // Modules that talk to the outside. A blanket run leaves them off and says why.
    private static final Map<String, String> OUTSIDE_MODULES = outsideModules();
    // Commands that talk to the outside or end the world the run stands in. A blanket run only
    // asks for their help.
    private static final Map<String, String> HELP_ONLY_COMMANDS = helpOnlyCommands();

    private static Map<String, String> outsideModules() {
        Map<String, String> reasons = new LinkedHashMap<>();
        reasons.put("ChatBot", "it can send chat to a chat model service on the web");
        reasons.put("Streamers", "it asks a web service who is streaming");
        reasons.put("Webhook", "it posts alerts to a web address");
        reasons.put("EntityOwner", "it looks owners up on the web by their id");
        reasons.put("Proxy", "it sends connections through a proxy");
        reasons.put("AutoReconnect", "it joins servers again by itself");
        reasons.put("AutoLogin", "it answers login prompts with saved passwords");
        return reasons;
    }

    // Modules a blanket run on a server leaves off as well.
    private static final Map<String, String> SERVER_MODULES =
        Map.of("Kaboom", "its own description says a Paper server kicks for it");

    private static Map<String, String> helpOnlyCommands() {
        Map<String, String> reasons = new LinkedHashMap<>();
        reasons.put("proxy", "it keeps and checks proxies");
        reasons.put("chatbot", "it keeps the key of a web service");
        reasons.put("autologin", "it keeps passwords");
        reasons.put("reconnect", "it leaves and joins a server again");
        reasons.put("disconnect", "it leaves the world the run stands in");
        return reasons;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void run(Rig rig) {
        List<Module> modules = rig.client().modules();
        List<Command> commands = rig.ask(mc -> OfflineClient.INSTANCE.getCommandManager().getCommands());
        List<Case> creative = moduleCases(rig, CREATIVE, modules);
        List<Case> survival = moduleCases(rig, SURVIVAL, modules);
        List<Case> typed = commandCases(rig, commands);
        Duo[] server = new Duo[1];
        List<Case> served = servedCases(rig, modules, server);
        if (!creative.isEmpty() || !survival.isEmpty() || !typed.isEmpty()) {
            inSinglePlayer(rig, creative, survival, typed);
        }
        if (!served.isEmpty()) {
            onServer(rig, served, server);
        }
    }

    private static void inSinglePlayer(Rig rig, List<Case> creative, List<Case> survival, List<Case> typed) {
        int[] size = rig.ask(mc -> new int[] {mc.getWindow().getWidth(), mc.getWindow().getHeight()});
        rig.openWorld(WorldCreationUiState.SelectedGameMode.CREATIVE);
        // Nothing here is looked at. A small frame keeps the load on the machine low.
        rig.frames().drawAt(SMALL_WIDTH, SMALL_HEIGHT);
        rig.local().hurry(HURRY);
        Stage stage = new Stage(rig);
        try {
            for (Case each : creative) {
                stage.reset(false);
                runOne(rig, stage, each, CREATIVE);
            }
            if (!survival.isEmpty()) {
                rig.world().gameMode(SURVIVAL);
                for (Case each : survival) {
                    stage.reset(true);
                    runOne(rig, stage, each, SURVIVAL);
                }
                rig.world().gameMode(CREATIVE);
            }
            for (Case each : typed) {
                stage.reset(false);
                runOne(rig, stage, each, CREATIVE);
            }
        } finally {
            RigClock.set(RigClock.NORMAL);
            rig.frames().drawAt(size[0], size[1]);
            rig.closeWorld();
        }
    }

    // The kit and the mobs again with a second player four blocks off. A player the server
    // threw out joins again before the next case.
    private static void onServer(Rig rig, List<Case> served, Duo[] server) {
        Duo duo = Duo.open(rig, PaperServer.Setup.plain());
        server[0] = duo;
        try {
            Stage stage = new Stage(rig);
            for (Case each : served) {
                if (!stage.worldStands()) {
                    duo.rejoin();
                }
                stage.reset(true);
                duo.partnerHome();
                duo.server().resetTallies();
                rig.run(NAME, each);
            }
        } finally {
            duo.close();
        }
    }

    private static List<Case> servedCases(Rig rig, List<Module> modules, Duo[] server) {
        List<Case> cases = new ArrayList<>();
        for (Module module : modules) {
            String name = SERVER + " " + module.getName();
            if (!rig.wants(NAME, name)) {
                continue;
            }
            String outside = OUTSIDE_MODULES.getOrDefault(module.getName(), SERVER_MODULES.get(module.getName()));
            if (outside != null) {
                rig.skip(NAME, name, LEFT_OUT + outside);
            } else if (!Duo.available()) {
                rig.skip(NAME, name, Duo.NO_SERVER);
            } else {
                cases.add(Case.of(name, each -> switchOnServer(each, server[0], module)));
            }
        }
        return cases;
    }

    // A kick is told with the server's own reason before anything else the case found.
    private static void switchOnServer(Rig rig, Duo duo, Module module) {
        int mark = duo.server().logMark();
        int setBack = rig.setbacks();
        RigFailure failed = null;
        try {
            switchOnAndOff(rig, module, SERVED_TICKS);
        } catch (RigFailure thrown) {
            failed = thrown;
        }
        List<String> kicks = duo.server().allHeard("kick", mark);
        if (!kicks.isEmpty()) {
            throw new RigFailure("The server kicked " + kicks);
        }
        if (failed != null) {
            throw failed;
        }
        if (rig.setbacks() > setBack) {
            rig.note("The server set the player back " + (rig.setbacks() - setBack) + " times");
        }
    }

    // A case that lost the world gets a new one before the next case runs.
    private static void runOne(Rig rig, Stage stage, Case each, String mode) {
        rig.run(NAME, each);
        if (!stage.worldStands()) {
            stage.reopen(WorldCreationUiState.SelectedGameMode.CREATIVE);
            rig.local().hurry(HURRY);
            rig.world().gameMode(mode);
        }
    }

    private static List<Case> moduleCases(Rig rig, String mode, List<Module> modules) {
        List<Case> cases = new ArrayList<>();
        for (Module module : modules) {
            String name = mode + " " + module.getName();
            if (!rig.wants(NAME, name)) {
                continue;
            }
            String outside = OUTSIDE_MODULES.get(module.getName());
            if (outside != null) {
                rig.skip(NAME, name, LEFT_OUT + outside);
            } else {
                cases.add(Case.of(name, each -> switchOnAndOff(each, module, ON_TICKS)));
            }
        }
        return cases;
    }

    // Why a blanket run leaves a module off. Null when it takes part.
    // Why a blanket run only asks for the help of a command. Null for a command it may type.
    static String helpOnly(String command) {
        return HELP_ONLY_COMMANDS.get(command);
    }

    static String leftOut(String module, boolean onServer) {
        String reason = OUTSIDE_MODULES.get(module);
        if (reason == null && onServer) {
            reason = SERVER_MODULES.get(module);
        }
        return reason == null ? null : LEFT_OUT + reason;
    }

    private static void switchOnAndOff(Rig rig, Module module, int onTicks) {
        int mark = rig.client().chatMark();
        boolean wasOn = rig.ask(mc -> module.isEnabled());
        if (module.isTogglable()) {
            rig.client().switchTo(module, !wasOn);
        } else {
            // A module that is no switch does its work when its key is pressed.
            rig.act(mc -> module.onKeybind());
        }
        boolean took = rig.ask(mc -> module.isEnabled()) != wasOn;
        rig.ticks(onTicks);
        boolean stayed = rig.ask(mc -> module.isEnabled()) != wasOn;
        Screen opened = rig.ask(mc -> mc.gui.screen());
        if (module.isTogglable()) {
            rig.client().switchTo(module, wasOn);
        }
        rig.ticks(OFF_TICKS);
        if (opened != null) {
            rig.note("It opened " + opened.getClass().getSimpleName());
        }
        if (module.isTogglable() && !took) {
            rig.note("It did not switch");
        } else if (module.isTogglable() && !stayed) {
            rig.note("It switched itself back");
        }
        List<String> said = rig.client().saidSince(mark);
        if (!said.isEmpty()) {
            rig.note("It said " + shortened(said.getFirst()));
        }
        rig.expect(rig.ask(mc -> module.isEnabled()) == wasOn || !module.isTogglable(),
            module.getName() + " did not go back to how it was");
        Stage.expectAlive(rig);
    }

    private static List<Case> commandCases(Rig rig, List<Command> commands) {
        List<Case> cases = new ArrayList<>();
        for (Command command : commands) {
            String name = COMMAND + " " + command.getName();
            if (rig.wants(NAME, name)) {
                cases.add(Case.of(name, each -> type(each, command)));
            }
        }
        return cases;
    }

    private static void type(Rig rig, Command command) {
        List<String> help = rig.client().command("help " + command.getName());
        rig.expect(!help.isEmpty(), "The help for " + command.getName() + " wrote nothing");
        String helpOnly = HELP_ONLY_COMMANDS.get(command.getName());
        if (helpOnly != null) {
            rig.note("Only its help was asked for because " + helpOnly);
            return;
        }
        List<String> reply = rig.client().command(command.getName());
        rig.ticks(OFF_TICKS);
        Screen opened = rig.ask(mc -> mc.gui.screen());
        if (opened != null) {
            rig.note("It opened " + opened.getClass().getSimpleName());
        }
        if (!reply.isEmpty()) {
            rig.note("It said " + shortened(reply.getFirst()));
        }
        if (rig.ask(mc -> mc.player == null)) {
            throw new RigFailure("The command " + command.getName() + " left the world");
        }
    }

    private static String shortened(String line) {
        return line.length() <= LONGEST_REPLY ? line : line.substring(0, LONGEST_REPLY);
    }
}
