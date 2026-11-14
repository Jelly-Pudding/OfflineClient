package com.jellypudding.offlineclient.rig.report;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.jellypudding.offlineclient.rig.core.LogFile;
import com.jellypudding.offlineclient.rig.core.LogRules;
import com.jellypudding.offlineclient.rig.core.Outcome;
import com.jellypudding.offlineclient.rig.core.Results;
import com.jellypudding.offlineclient.rig.core.Verdict;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

// Runs after the game has gone. It reads what the cases left behind and writes the summary and
// the contact sheets. A game that died before it could finish is reported as that.
public final class ReportMain {

    private static final String RUN = "Run";
    private static final String LOG_FILE = "logs/latest.log";
    private static final String LOG_COPY = "game.log";
    private static final String CRASH_FOLDER = "crash-reports";
    private static final String TIMEOUT_FILE = "timeout.txt";
    private static final String RESULT_FILE = "results.json";
    private static final String SUMMARY_FILE = "summary.txt";
    private static final String TEST_THREAD_DIED = "Client gametests failed with an exception";
    private static final String COMMENT = "//";
    private static final String JOIN = " | ";
    private static final int CRASH_LINES = 12;
    private static final int MOST_FAULTS = 5;
    private static final int FAILED_EXIT = 1;

    private ReportMain() {
    }

    public static void main(String[] args) throws IOException {
        Path out = Path.of(args[0]);
        Path game = Path.of(args[1]);
        Files.createDirectories(out);
        List<Outcome> outcomes = read(out);
        List<LogFile.Entry> log = new LogFile(game.resolve(LOG_FILE)).readNew();
        LogRules rules = new LogRules(game, game.resolve("../../..").normalize());
        outcomes.addAll(endOfRun(out, game, outcomes.isEmpty(), log, rules));

        copy(game.resolve(LOG_FILE), out.resolve(LOG_COPY));
        copyCrashes(game, out);
        List<Path> sheets = ContactSheets.write(out, outcomes);
        write(out.resolve(RESULT_FILE), json(outcomes));
        String summary = Summary.of(out, outcomes, sheets);
        write(out.resolve(SUMMARY_FILE), summary);
        System.out.print(summary);
        if (outcomes.stream().anyMatch(each -> each.verdict() == Verdict.FAILED)) {
            System.exit(FAILED_EXIT);
        }
    }

    private static List<Outcome> read(Path out) throws IOException {
        List<Outcome> outcomes = new ArrayList<>();
        Path file = out.resolve(Results.FILE);
        if (Files.isRegularFile(file)) {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    outcomes.add(Outcome.fromJson(JsonParser.parseString(line).getAsJsonObject()));
                }
            }
        }
        return outcomes;
    }

    // What the cases themselves could not write down. A game that died leaves the name of the
    // case it died in and a game that never reached the tests leaves only its log.
    private static List<Outcome> endOfRun(Path out, Path game, boolean nothingRan, List<LogFile.Entry> log,
                                          LogRules rules) throws IOException {
        List<Outcome> added = new ArrayList<>();
        String death = death(out, game);
        Path running = out.resolve(Results.RUNNING);
        if (Files.isRegularFile(running)) {
            JsonObject at = JsonParser.parseString(Files.readString(running)).getAsJsonObject();
            added.add(failed(at.get(Results.SUITE).getAsString(), at.get(Results.NAME).getAsString(),
                "The game ended whilst this case ran. " + death));
        } else if (nothingRan) {
            List<String> faults = rules.faults(log).stream().limit(MOST_FAULTS).map(LogRules.Fault::brief).toList();
            added.add(failed(RUN, "the game reached the tests", "No case left a result. " + death
                + (faults.isEmpty() ? "" : " The log holds " + faults)));
        }
        for (LogFile.Entry entry : log) {
            if (entry.text().startsWith(TEST_THREAD_DIED)) {
                added.add(failed(RUN, "the tests ran to their end", entry.brief()));
            }
        }
        return added;
    }

    private static Outcome failed(String suite, String name, String message) {
        return new Outcome(suite, name, Verdict.FAILED, message.strip(), 0, List.of());
    }

    // Why the game stopped as far as its own files tell.
    private static String death(Path out, Path game) throws IOException {
        if (Files.isRegularFile(out.resolve(TIMEOUT_FILE))) {
            return Files.readAllLines(out.resolve(TIMEOUT_FILE)).getFirst();
        }
        Path crash = newestCrash(game);
        if (crash == null) {
            return "It left no crash report.";
        }
        StringBuilder told = new StringBuilder("Its crash report says");
        Files.readAllLines(crash).stream().filter(line -> !line.isBlank() && !line.startsWith(COMMENT))
            .skip(1).limit(CRASH_LINES).forEach(line -> told.append(JOIN).append(line.strip()));
        return told.toString();
    }

    private static Path newestCrash(Path game) throws IOException {
        Path folder = game.resolve(CRASH_FOLDER);
        if (!Files.isDirectory(folder)) {
            return null;
        }
        try (Stream<Path> files = Files.list(folder)) {
            return files.max(Comparator.comparingLong(file -> file.toFile().lastModified())).orElse(null);
        }
    }

    private static void copyCrashes(Path game, Path out) throws IOException {
        Path folder = game.resolve(CRASH_FOLDER);
        if (!Files.isDirectory(folder)) {
            return;
        }
        Files.createDirectories(out.resolve(CRASH_FOLDER));
        try (Stream<Path> files = Files.list(folder)) {
            for (Path file : files.toList()) {
                copy(file, out.resolve(CRASH_FOLDER).resolve(file.getFileName()));
            }
        }
    }

    private static void copy(Path from, Path to) throws IOException {
        if (Files.isRegularFile(from)) {
            Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String json(List<Outcome> outcomes) {
        JsonArray all = new JsonArray();
        outcomes.forEach(each -> all.add(each.toJson()));
        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(all) + "\n";
    }

    private static void write(Path file, String text) throws IOException {
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }
}
