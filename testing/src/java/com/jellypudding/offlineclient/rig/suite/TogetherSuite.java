package com.jellypudding.offlineclient.rig.suite;

import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.ExclusivityGroup;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.rig.core.Case;
import com.jellypudding.offlineclient.rig.core.Rig;
import com.jellypudding.offlineclient.rig.core.RigClock;
import com.jellypudding.offlineclient.rig.core.RigFailure;
import com.jellypudding.offlineclient.rig.core.Stage;
import com.jellypudding.offlineclient.rig.core.World;
import com.jellypudding.offlineclient.rig.duo.Duo;
import com.jellypudding.offlineclient.rig.duo.PaperServer;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// What happens once more than one module is on. Each module alone must leave the game as it
// found it. Two modules that say they cannot run at once must switch each other off. A whole
// category and then every module at once must run with no fault and leave nothing behind.
// KillAura and Nuker and Scaffold must go on working beside every other module in turn. See
// Beside. Modules that share a hand or a slot or the same keys are played together for what a
// player would expect of them. See TogetherPlays.
public final class TogetherSuite implements Suite {

    private static final String NAME = "Together";
    private static final String SURVIVAL = "survival";
    private static final String LEAVES = " leaves the game as it found it";
    private static final String CLASH = " modules switch each other off";
    private static final String EVERY = "Every ";
    private static final String AT_ONCE = " module at once";
    private static final String EVERYTHING = "Every module at once";
    private static final String SERVED = " on a Paper server";
    // Its whole work is to switch the others off. A crowd with it in is no crowd.
    private static final String PANIC = "Panic";
    // Stopped in the air the autopilot leaves the module that carries it on. Nobody is dropped.
    private static final Map<String, String> LEFT_ON_PURPOSE = Map.of("Autopilot", "Flight");
    private static final int FORWARD = InputConstants.KEY_W;

    private static final int ON_TICKS = 10;
    private static final int OFF_TICKS = 5;
    private static final int BUSY_TICKS = 60;
    // Blink sends what it held a tick at a time and takes as long over it as it held.
    private static final int SETTLE_TICKS = 200;
    // Five times the pace of the game. Nothing in the blanket cases measures time.
    private static final float HURRY = 5;
    private static final int SMALL_WIDTH = 480;
    private static final int SMALL_HEIGHT = 270;


    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void run(Rig rig) {
        List<Module> modules = rig.client().modules();
        alone(rig, modules);
        served(rig, modules);
        TogetherPlays plays = new TogetherPlays();
        Grounds.play(rig, NAME, plays.plays(), stage -> plays.standOn(stage.home()));
    }

    // The blanket cases in a flat world of the rig's own. Survival with the kit and two mobs
    // gives a module something to work on.
    private static void alone(Rig rig, List<Module> modules) {
        List<Case> cases = new ArrayList<>();
        for (Module module : modules) {
            String name = module.getName() + LEAVES;
            if (!rig.wants(NAME, name)) {
                continue;
            }
            String leftOut = SmokeSuite.leftOut(module.getName(), false);
            if (leftOut == null) {
                cases.add(Case.of(name, each -> leavesNoTrace(each, module)));
            } else {
                rig.skip(NAME, name, leftOut);
            }
        }
        for (ExclusivityGroup group : ExclusivityGroup.values()) {
            cases.add(Case.of(words(group.name()) + CLASH, each -> clash(each, group, modules)));
        }
        crowds(modules).forEach((name, crowd) -> cases.add(Case.of(name, each -> runAtOnce(each, crowd, null))));
        Stage[] standsOn = new Stage[1];
        cases.addAll(Beside.cases(modules, () -> standsOn[0]));
        List<Case> wanted = rig.wanted(NAME, cases);
        if (wanted.isEmpty()) {
            return;
        }
        int[] size = rig.ask(mc -> new int[] {mc.getWindow().getWidth(), mc.getWindow().getHeight()});
        rig.openWorld(WorldCreationUiState.SelectedGameMode.CREATIVE);
        rig.frames().drawAt(SMALL_WIDTH, SMALL_HEIGHT);
        rig.local().hurry(HURRY);
        Stage stage = new Stage(rig);
        standsOn[0] = stage;
        try {
            rig.world().gameMode(SURVIVAL);
            for (Case each : wanted) {
                stage.reset(true);
                rig.run(NAME, each);
                if (!stage.worldStands()) {
                    stage.reopen(WorldCreationUiState.SelectedGameMode.CREATIVE);
                    rig.local().hurry(HURRY);
                    rig.world().gameMode(SURVIVAL);
                }
            }
        } finally {
            RigClock.set(RigClock.NORMAL);
            rig.frames().drawAt(size[0], size[1]);
            rig.closeWorld();
        }
    }

    // The crowds again where a real server reads every packet and may throw the player out.
    private static void served(Rig rig, List<Module> modules) {
        Duo[] server = new Duo[1];
        List<Case> cases = new ArrayList<>();
        crowds(modules).forEach((name, crowd) -> cases.add(
            Case.of(name + SERVED, each -> runAtOnce(each, crowd, server[0]))));
        List<Case> wanted = rig.wanted(NAME, cases);
        if (wanted.isEmpty()) {
            return;
        }
        if (!Duo.available()) {
            wanted.forEach(each -> rig.skip(NAME, each.name(), Duo.NO_SERVER));
            return;
        }
        Duo duo = Duo.alone(rig, PaperServer.Setup.plain());
        server[0] = duo;
        try {
            Stage stage = new Stage(rig);
            for (Case each : wanted) {
                if (!stage.worldStands()) {
                    duo.rejoin();
                }
                stage.reset(true);
                rig.run(NAME, each);
            }
        } finally {
            duo.close();
        }
    }

    // Each category by itself and then all of them.
    private static Map<String, List<Module>> crowds(List<Module> modules) {
        Map<String, List<Module>> crowds = new LinkedHashMap<>();
        for (Category category : Category.values()) {
            crowds.put(EVERY + category.getDisplayName() + AT_ONCE,
                modules.stream().filter(module -> module.getCategory() == category).toList());
        }
        crowds.put(EVERYTHING, modules);
        return crowds;
    }

    private static String words(String constant) {
        String lower = constant.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private static void leavesNoTrace(Rig rig, Module module) {
        Map<String, String> before = Traces.of(rig);
        List<Module> onBefore = switchedOn(rig);
        boolean wasOn = rig.ask(mc -> module.isEnabled());
        if (module.isTogglable()) {
            rig.client().switchTo(module, !wasOn);
        } else {
            rig.act(mc -> module.onKeybind());
        }
        rig.ticks(ON_TICKS);
        if (module.isTogglable()) {
            rig.client().switchTo(module, wasOn);
        }
        rig.act(mc -> mc.gui.setScreen(null));
        List<Module> leftOn = switchedOn(rig);
        leftOn.removeAll(onBefore);
        leftOn.remove(module);
        leftOn.forEach(other -> rig.client().switchTo(other, false));
        rig.ticks(OFF_TICKS);
        List<String> left = Traces.changed(before, Traces.settled(rig, SETTLE_TICKS));
        List<String> names = leftOn.stream().map(Module::getName).toList();
        rig.expect(names.isEmpty() || names.equals(List.of(LEFT_ON_PURPOSE.getOrDefault(module.getName(), ""))),
            module.getName() + " left " + names + " switched on");
        rig.expect(left.isEmpty(), module.getName() + " left behind " + left);
    }

    private static List<Module> switchedOn(Rig rig) {
        return rig.ask(mc -> new ArrayList<>(rig.client().modules().stream().filter(Module::isEnabled).toList()));
    }

    // Every member is switched on after every other. The one that was on must be off again.
    // A module that will not go on by itself here says nothing either way.
    private static void clash(Rig rig, ExclusivityGroup group, List<Module> modules) {
        List<Module> members = modules.stream().filter(module -> module.getExclusivityGroup() == group).toList();
        List<String> stayed = new ArrayList<>();
        int proven = 0;
        for (Module first : members) {
            for (Module second : members) {
                // Two of a group may say they get along.
                if (first == second || !first.clashesWith(second) || !second.clashesWith(first)) {
                    continue;
                }
                rig.client().switchTo(first, true);
                boolean firstWent = rig.ask(mc -> first.isEnabled());
                rig.client().switchTo(second, true);
                boolean secondWent = rig.ask(mc -> second.isEnabled());
                boolean firstStayed = rig.ask(mc -> first.isEnabled());
                rig.client().switchTo(first, false);
                rig.client().switchTo(second, false);
                if (firstWent && secondWent) {
                    proven++;
                    if (firstStayed) {
                        stayed.add(first.getName() + " under " + second.getName());
                    }
                }
            }
        }
        rig.note(members.stream().map(Module::getName).toList() + " made " + proven + " pairs");
        rig.expect(members.size() >= 2 && proven > 0, "No two modules of the group went on. That proves nothing");
        rig.expect(stayed.isEmpty(), "Stayed on " + stayed);
    }

    // Switches the whole crowd on and walks a little and switches it off again. The player
    // cannot be hurt meanwhile. A server that throws the player out fails the case with its own
    // reason.
    private static void runAtOnce(Rig rig, List<Module> crowd, Duo duo) {
        World world = rig.world();
        world.command("effect give " + world.player() + " resistance 600 4 true");
        Map<String, String> before = Traces.of(rig);
        int mark = duo == null ? 0 : duo.server().logMark();
        List<Module> switched = new ArrayList<>();
        for (Module module : crowd) {
            boolean takesPart = module.isTogglable() && !module.getName().equals(PANIC)
                && SmokeSuite.leftOut(module.getName(), duo != null) == null;
            if (takesPart && !rig.ask(mc -> module.isEnabled())) {
                rig.client().switchTo(module, true);
                if (rig.ask(mc -> module.isEnabled())) {
                    switched.add(module);
                }
            }
        }
        RigFailure failed = null;
        try {
            rig.controls().hold(FORWARD);
            rig.ticks(BUSY_TICKS / 2);
            rig.controls().release(FORWARD);
            rig.ticks(BUSY_TICKS / 2);
            long stayed = switched.stream().filter(module -> rig.ask(mc -> module.isEnabled())).count();
            rig.note(switched.size() + " modules went on and " + stayed + " were still on at the end");
            switched.forEach(module -> rig.client().switchTo(module, false));
            rig.act(mc -> mc.gui.setScreen(null));
            rig.ticks(OFF_TICKS * 2);
        } catch (RigFailure thrown) {
            failed = thrown;
        }
        if (duo != null) {
            List<String> kicks = duo.server().allHeard("kick", mark);
            rig.expect(kicks.isEmpty(), "The server kicked " + kicks);
        }
        if (failed != null) {
            throw failed;
        }
        Stage.expectAlive(rig);
        List<String> left = Traces.changed(before, Traces.settled(rig, SETTLE_TICKS));
        if (!left.isEmpty()) {
            rig.note("The server has the player at " + rig.world().where() + " and the game at "
                + rig.ask(mc -> mc.player.position()));
        }
        rig.expect(left.isEmpty(), "Left behind " + left);
    }
}
