package com.jellypudding.offlineclient.rig.core;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

// What a case works with. It runs the cases and keeps their results and hands out the helpers
// that play the game the way a player does.
public final class Rig {

    private static final String LOG_FILE = "logs/latest.log";
    // How many faults of one case are spelled out in its message.
    private static final int MOST_FAULTS_TOLD = 3;
    private static final String JOIN = " | ";
    // Chunks a world of the game's own land shows round the player. The server sends three fewer.
    private static final int LAND_SIGHT = 12;
    private static final int OPENING_SIGHT = 5;

    private final ClientGameTestContext context;
    private final Results results;
    private final Filter filter;
    private final LogFile log;
    private final LogRules rules;
    private final Client client;
    private final Controls controls;
    private final Frames frames;
    private World world;

    // The notes and pictures of the case that is running.
    private final List<String> notes = new ArrayList<>();
    private final List<String> shots = new ArrayList<>();
    private String caseId = "";

    public Rig(ClientGameTestContext context, Path out, String filterWords) {
        this.context = context;
        this.results = new Results(out);
        this.filter = new Filter(filterWords);
        Path gameFolder = context.computeOnClient(mc -> mc.gameDirectory.toPath());
        this.log = new LogFile(gameFolder.resolve(LOG_FILE));
        this.rules = new LogRules(gameFolder, gameFolder.resolve("../../..").normalize());
        this.client = new Client(this);
        this.controls = new Controls(this);
        this.frames = new Frames(this);
        Uncaught.install();
    }

    public ClientGameTestContext context() {
        return context;
    }

    public Results results() {
        return results;
    }

    public LogFile log() {
        return log;
    }

    public LogRules rules() {
        return rules;
    }

    public Client client() {
        return client;
    }

    public Controls controls() {
        return controls;
    }

    public Frames frames() {
        return frames;
    }

    // The single player world the run is in. A case that needs one fails without it.
    public World world() {
        if (world == null) {
            throw new RigFailure("This case needs a world and none is open");
        }
        return world;
    }

    public boolean inWorld() {
        return world != null;
    }

    // Makes a fresh flat world and waits until the player stands in it.
    public World openWorld(WorldCreationUiState.SelectedGameMode mode) {
        return open(mode, null);
    }

    // The same with the game's own hills and woods and water grown from the seed. Nothing
    // hostile lives there.
    public World openLand(WorldCreationUiState.SelectedGameMode mode, String seed) {
        // A world opens with a short sight. Its first chunks would take too long to grow otherwise.
        act(mc -> mc.options.renderDistance().set(OPENING_SIGHT));
        World land = open(mode, seed);
        land.commands("difficulty peaceful", "weather clear");
        // The server grows every chunk in sight. A shorter sight keeps a fresh place quick to reach.
        act(mc -> {
            mc.options.renderDistance().set(LAND_SIGHT);
            mc.options.broadcastOptions();
        });
        return land;
    }

    private World open(WorldCreationUiState.SelectedGameMode mode, String seed) {
        if (world != null) {
            throw new RigFailure("A world is open already");
        }
        TestWorldBuilder builder = context.worldBuilder();
        builder.setUseConsistentSettings(seed == null);
        builder.adjustSettings(state -> {
            if (seed != null) {
                state.setSeed(seed);
            }
            state.setGameMode(mode);
            state.setAllowCommands(true);
        });
        TestSingleplayerContext single = builder.create();
        world = new World(this, single);
        world.settle();
        return world;
    }

    public void closeWorld() {
        if (world == null) {
            return;
        }
        World closing = world;
        world = null;
        closing.close();
    }

    // The world went away without the rig closing it.
    void forgetWorld() {
        world = null;
    }

    public boolean wants(String suite, String name) {
        return filter.wants(suite, name);
    }

    public List<Case> wanted(String suite, List<Case> cases) {
        return cases.stream().filter(each -> wants(suite, each.name())).toList();
    }

    public void ticks(int count) {
        context.waitTicks(count);
    }

    public <T> T ask(Function<Minecraft, T> question) {
        return context.computeOnClient(question::apply);
    }

    public void act(Consumer<Minecraft> action) {
        context.runOnClient(action::accept);
    }

    // Waits until the condition holds and says how many ticks it took. Fails after the limit.
    public int until(String what, int limitTicks, Predicate<Minecraft> condition) {
        for (int waited = 0; waited <= limitTicks; waited++) {
            if (ask(condition::test)) {
                return waited;
            }
            context.waitTick();
        }
        throw new RigFailure("Waited " + limitTicks + " ticks for " + what);
    }

    public void expect(boolean holds, String seen) {
        if (!holds) {
            throw new RigFailure(seen);
        }
    }

    // A line for the result of the running case. It tells what was measured.
    public void note(String words) {
        notes.add(words);
    }

    void addShot(String path) {
        shots.add(path);
    }

    public String caseId() {
        return caseId;
    }

    // Writes down a case that was left out and why.
    public void skip(String suite, String name, String why) {
        results.end(new Outcome(suite, name, Verdict.SKIPPED, why, 0, List.of()));
    }

    // Runs one case and writes its result. Nothing a case does can stop the cases after it.
    public Outcome run(String suite, Case each) {
        notes.clear();
        shots.clear();
        caseId = suite + "/" + each.name();
        results.begin(suite, each.name());
        log.skipToEnd();
        Uncaught.clear();
        long started = System.nanoTime();
        String failure = null;
        String known = each.knownFault();
        try {
            each.body().run(this);
        } catch (KnownFault thrown) {
            failure = thrown.getMessage();
            known = thrown.mark();
        } catch (Throwable thrown) {
            failure = describe(thrown);
        }
        try {
            controls.letGo();
        } catch (Throwable thrown) {
            failure = failure == null ? "The keys could not be let go. " + describe(thrown) : failure;
        }
        List<String> faults = faultsSince();
        if (failure == null && !faults.isEmpty()) {
            failure = String.join(JOIN, faults);
        } else if (failure != null && !faults.isEmpty()) {
            failure = failure + JOIN + String.join(JOIN, faults);
        }
        long millis = (System.nanoTime() - started) / 1_000_000;
        Outcome outcome = new Outcome(suite, each.name(), verdict(known, failure), message(known, failure), millis,
            List.copyOf(shots));
        results.end(outcome);
        caseId = "";
        return outcome;
    }

    private static Verdict verdict(String known, String failure) {
        if (failure == null) {
            return Verdict.PASSED;
        }
        return known == null ? Verdict.FAILED : Verdict.KNOWN_FAULT;
    }

    private String message(String known, String failure) {
        String told = String.join(JOIN, notes);
        if (failure == null) {
            return known == null ? told : join(told, "It passed. The known fault mark can come off [" + known + "]");
        }
        String seen = join(failure, told);
        return known == null ? seen : seen + " [known fault " + known + "]";
    }

    private static String join(String first, String second) {
        if (first.isEmpty() || second.isEmpty()) {
            return first + second;
        }
        return first + JOIN + second;
    }

    // What the log and the uncaught exception trap hold against the client since the case began.
    private List<String> faultsSince() {
        List<String> told = new ArrayList<>();
        List<LogRules.Fault> faults = rules.faults(log.readNew());
        for (LogRules.Fault fault : faults.subList(0, Math.min(MOST_FAULTS_TOLD, faults.size()))) {
            told.add("The log holds " + fault.brief());
        }
        if (faults.size() > MOST_FAULTS_TOLD) {
            told.add("and " + (faults.size() - MOST_FAULTS_TOLD) + " more faults in the log");
        }
        for (String uncaught : Uncaught.drain()) {
            told.add("Uncaught " + uncaught);
        }
        return told;
    }

    // The exception with the first frames that tell where it came from.
    public static String describe(Throwable thrown) {
        if (thrown instanceof RigFailure) {
            return thrown.getCause() == null ? thrown.getMessage()
                : thrown.getMessage() + JOIN + describe(thrown.getCause());
        }
        StringBuilder text = new StringBuilder(thrown.toString());
        int told = 0;
        for (StackTraceElement frame : thrown.getStackTrace()) {
            if (frame.getClassName().startsWith("com.jellypudding.offlineclient")) {
                text.append(" at ").append(frame);
                if (++told == MOST_FAULTS_TOLD) {
                    break;
                }
            }
        }
        if (thrown.getCause() != null && thrown.getCause() != thrown) {
            text.append(JOIN).append("caused by ").append(describe(thrown.getCause()));
        }
        return text.toString();
    }
}
