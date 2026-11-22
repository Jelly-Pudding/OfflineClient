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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

// Each module alone must leave the game as it found it. Two modules that say they cannot run at
// once must switch each other off. A whole category and then every module at once must run with no
// fault and leave nothing behind and send the server no more than it puts up with. Crowds picked by
// a seed do the same. Beside and Lifecycle and TogetherPlays hold the rest.
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
    private static final long CROWD_SEED = 20261115;
    private static final int CROWD_SIZE = 8;
    private static final int LONE_CROWDS = 40;
    private static final int SERVED_CROWDS = 20;
    private static final int FRAME_COUNT_TICKS = 20;
    // Paper throws out a player who sends more than five hundred packets a second for seven
    // seconds. That is twenty five a tick.
    private static final int PACKETS_A_TICK = 20;
    private static final int MOST_SENDERS = 4;
    private static final String FLOODS = "Kaboom";
    // The top of the slider. The game draws as fast as it can there and a slow module shows.
    private static final int NO_FRAME_LIMIT = 260;
    private static final double MILLIS_A_SECOND = 1000;
    private static final int CROWD_MILLIS = 8;
    private static final int ALONE_MILLIS = 3;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void run(Rig rig) {
        List<Module> modules = rig.client().modules();
        // The blanket cases ask that nothing breaks and nothing is left behind. With every module
        // off nothing is and a control run only takes the plays.
        if (!rig.control()) {
            alone(rig, modules);
            served(rig, modules);
        }
        TogetherPlays plays = new TogetherPlays();
        Grounds.play(rig, NAME, plays.plays(), stage -> plays.standOn(stage.home()));
    }

    // Survival with the kit and two mobs gives a module something to work on.
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
        cases.addAll(Lifecycle.cases(modules, () -> standsOn[0], () -> null, false));
        cases.addAll(randomCrowds(modules, LONE_CROWDS, null, ""));
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

    private static void served(Rig rig, List<Module> modules) {
        Duo[] server = new Duo[1];
        Stage[] standsOn = new Stage[1];
        List<Case> cases = new ArrayList<>();
        crowds(modules).forEach((name, crowd) -> cases.add(
            Case.of(name + SERVED, each -> runAtOnce(each, crowd, server[0]))));
        cases.addAll(Beside.sampled(modules, () -> standsOn[0], SERVED));
        cases.addAll(Lifecycle.cases(modules, () -> standsOn[0], () -> server[0], true));
        cases.addAll(randomCrowds(modules, SERVED_CROWDS, server, SERVED));
        List<Module> drawn = modules.stream().filter(module -> module.getCategory() == Category.RENDER).toList();
        cases.add(Case.of("Every render module at once costs a frame no more than " + CROWD_MILLIS + " ms" + SERVED,
            each -> frameCost(each, drawn, CROWD_MILLIS)));
        cases.add(Case.of("Every module at once costs a frame no more than " + CROWD_MILLIS + " ms" + SERVED,
            each -> frameCost(each, modules, CROWD_MILLIS)));
        cases.add(Case.of("No render module alone costs a frame more than " + ALONE_MILLIS + " ms" + SERVED,
            each -> frameCostAlone(each, drawn)));
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
            standsOn[0] = stage;
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

    // The same seed picks the same crowds on every run and a crowd that fails is named in full by
    // its case.
    private static List<Case> randomCrowds(List<Module> modules, int crowds, Duo[] server, String where) {
        Random random = new Random(CROWD_SEED);
        List<Case> cases = new ArrayList<>();
        for (int number = 1; number <= crowds; number++) {
            List<Module> shuffled = new ArrayList<>(modules);
            Collections.shuffle(shuffled, random);
            List<Module> crowd = List.copyOf(shuffled.subList(0, CROWD_SIZE));
            cases.add(Case.of("Random crowd " + number + " runs with no fault and leaves nothing behind" + where,
                each -> {
                    each.note("The crowd is " + crowd.stream().map(Module::getName).toList());
                    runAtOnce(each, crowd, server == null ? null : server[0]);
                }));
        }
        return cases;
    }

    // The time a frame takes with nothing on and then with the crowd on and no limit on the
    // frames. A real server paces the game by the clock and the count means something there. A
    // game at sixty frames a second has under seventeen milliseconds for each. The budget is
    // there to catch a module that brings the game to its knees.
    private static void frameCost(Rig rig, List<Module> crowd, int budgetMillis) {
        int limit = rig.ask(mc -> mc.options.framerateLimit().get());
        rig.act(mc -> mc.options.framerateLimit().set(NO_FRAME_LIMIT));
        double bare = frameMillis(rig, 3);
        List<Module> on = switchOn(rig, crowd, true, Set.of());
        double busy;
        try {
            busy = frameMillis(rig, 3);
        } finally {
            on.forEach(module -> rig.client().switchTo(module, false));
            rig.act(mc -> {
                mc.gui.setScreen(null);
                mc.options.framerateLimit().set(limit);
            });
        }
        rig.note(String.format(Locale.ROOT, "A frame took %.2f ms with nothing on and %.2f ms with %d modules on",
            bare, busy, on.size()));
        rig.expect(busy - bare <= budgetMillis, String.format(Locale.ROOT, "The crowd cost every frame %.1f ms",
            busy - bare));
    }

    private static void frameCostAlone(Rig rig, List<Module> crowd) {
        int limit = rig.ask(mc -> mc.options.framerateLimit().get());
        rig.act(mc -> mc.options.framerateLimit().set(NO_FRAME_LIMIT));
        Map<String, Double> costs = new LinkedHashMap<>();
        try {
            double bare = frameMillis(rig, 3);
            for (Module module : switchable(crowd, true, Set.of())) {
                rig.client().switchTo(module, true);
                try {
                    costs.put(module.getName(), frameMillis(rig, 1) - bare);
                } finally {
                    rig.client().switchTo(module, false);
                    rig.act(mc -> mc.gui.setScreen(null));
                }
            }
        } finally {
            rig.act(mc -> mc.options.framerateLimit().set(limit));
        }
        List<String> dearest = costs.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed()).limit(MOST_SENDERS + 2)
            .map(cost -> String.format(Locale.ROOT, "%s %.2f ms", cost.getKey(), cost.getValue())).toList();
        rig.note("Of " + costs.size() + " modules the dearest were " + dearest);
        List<String> dear = costs.entrySet().stream().filter(cost -> cost.getValue() > ALONE_MILLIS)
            .map(cost -> String.format(Locale.ROOT, "%s %.1f ms", cost.getKey(), cost.getValue())).toList();
        rig.expect(dear.isEmpty(), "Each of these cost every frame more than " + ALONE_MILLIS + " ms. " + dear);
    }

    // The game counts its frames once a second. The slowest of the counts is taken.
    private static double frameMillis(Rig rig, int counts) {
        int lowest = Integer.MAX_VALUE;
        rig.ticks(FRAME_COUNT_TICKS + FRAME_COUNT_TICKS / 2);
        for (int count = 0; count < counts; count++) {
            lowest = Math.min(lowest, rig.ask(mc -> mc.getFps()));
            if (count + 1 < counts) {
                rig.ticks(FRAME_COUNT_TICKS);
            }
        }
        return MILLIS_A_SECOND / Math.max(1, lowest);
    }

    static List<Module> switchOn(Rig rig, List<Module> crowd, boolean served, Set<String> without) {
        List<Module> switched = new ArrayList<>();
        for (Module module : switchable(crowd, served, without)) {
            if (!rig.ask(mc -> module.isEnabled())) {
                rig.client().switchTo(module, true);
                if (rig.ask(mc -> module.isEnabled())) {
                    switched.add(module);
                }
            }
        }
        return switched;
    }

    static List<Module> switchable(List<Module> crowd, boolean served, Set<String> without) {
        return crowd.stream().filter(module -> module.isTogglable() && !module.getName().equals(PANIC)
            && !without.contains(module.getName()) && SmokeSuite.leftOut(module.getName(), served) == null).toList();
    }

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

    // The player cannot be hurt meanwhile. A server that throws the player out fails the case with
    // its own reason.
    private static void runAtOnce(Rig rig, List<Module> crowd, Duo duo) {
        World world = rig.world();
        world.command("effect give " + world.player() + " resistance 600 4 true");
        Map<String, String> before = Traces.of(rig);
        int mark = duo == null ? 0 : duo.server().logMark();
        List<Module> switched = switchOn(rig, crowd, duo != null, Set.of());
        PacketTally packets = new PacketTally(packet -> true);
        Map<String, Integer> sent = Map.of();
        RigFailure failed = null;
        try {
            packets.listen(rig);
            rig.controls().hold(FORWARD);
            rig.ticks(BUSY_TICKS / 2);
            rig.controls().release(FORWARD);
            rig.ticks(BUSY_TICKS / 2);
            sent = packets.drain();
            long stayed = switched.stream().filter(module -> rig.ask(mc -> module.isEnabled())).count();
            rig.note(switched.size() + " modules went on and " + stayed + " were still on at the end");
            switched.forEach(module -> rig.client().switchTo(module, false));
            rig.act(mc -> mc.gui.setScreen(null));
            rig.ticks(OFF_TICKS * 2);
        } catch (RigFailure thrown) {
            failed = thrown;
        } finally {
            packets.stop(rig);
        }
        if (duo != null) {
            List<String> kicks = duo.server().allHeard("kick", mark);
            rig.expect(kicks.isEmpty(), "The server kicked " + kicks);
        }
        if (failed != null) {
            throw failed;
        }
        Stage.expectAlive(rig);
        // A server may throw out whoever sends too much for too long. The crowd is not on for
        // long enough to find that out and the count stands in. Kaboom floods by its own account.
        int aTick = sent.values().stream().mapToInt(Integer::intValue).sum() / BUSY_TICKS;
        rig.note("The game sent " + aTick + " packets a tick. Most were " + PacketTally.busiest(sent, MOST_SENDERS));
        rig.expect(aTick <= PACKETS_A_TICK || switched.stream().anyMatch(module -> module.getName().equals(FLOODS)),
            "The crowd sent " + aTick + " packets a tick. Most were " + PacketTally.busiest(sent, MOST_SENDERS));
        List<String> left = Traces.changed(before, Traces.settled(rig, SETTLE_TICKS));
        if (!left.isEmpty()) {
            rig.note("The server has the player at " + rig.world().where() + " and the game at "
                + rig.ask(mc -> mc.player.position()));
        }
        rig.expect(left.isEmpty(), "Left behind " + left);
    }
}
