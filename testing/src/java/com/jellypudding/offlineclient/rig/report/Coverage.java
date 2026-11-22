package com.jellypudding.offlineclient.rig.report;

import com.jellypudding.offlineclient.rig.core.Ledger;
import com.jellypudding.offlineclient.rig.core.Outcome;
import com.jellypudding.offlineclient.rig.core.Verdict;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

// Says which modules the cases of a run put to work. A case counts for every module its name
// holds as a whole word. The suites that only switch every module on and off or turn every
// setting count for none. A module whose cases reach only part of its work is listed with what
// is left out.
final class Coverage {

    private static final String FILE = "coverage.txt";
    private static final Set<String> BLANKET_SUITES = Set.of("Smoke", "Settings");
    // Modules a whole suite works without a case naming them.
    private static final Map<String, String> SUITE_OF = Map.of("Pathfinder", "Goto", "ClickGUI", "Screens");
    private static final List<String> ONLY_IN_PART = List.of(
        "Playlist  Only its refusal is played. Its work is heard and the rig runs with the sound off",
        "Privacy  Only the gate for usage reports. Unsigned chat needs a signed in account",
        "MapPrinter  Only the marking of a station. A whole print takes hours",
        "Streamers  Only a check that no site answers. The rig talks to nothing beyond this machine");
    private static final String NOT_A_LETTER_BEFORE = "(?<![A-Za-z])";
    private static final String NOT_A_LETTER_AFTER = "(?![A-Za-z])";
    private static final int NAMES_IN_A_LINE = 8;

    private Coverage() {
    }

    static String write(Path out, List<Outcome> outcomes) throws IOException {
        Path list = out.resolve(Ledger.FILE);
        if (!Files.isRegularFile(list)) {
            return "";
        }
        Map<String, List<String>> cases = new LinkedHashMap<>();
        for (String line : Files.readAllLines(list, StandardCharsets.UTF_8)) {
            cases.put(line.split(Ledger.SPLIT, -1)[0], new ArrayList<>());
        }
        for (Outcome each : outcomes) {
            if (BLANKET_SUITES.contains(each.suite()) || each.verdict() == Verdict.SKIPPED) {
                continue;
            }
            for (Map.Entry<String, List<String>> module : cases.entrySet()) {
                Pattern named = Pattern.compile(NOT_A_LETTER_BEFORE + Pattern.quote(module.getKey())
                    + NOT_A_LETTER_AFTER);
                if (named.matcher(each.name()).find() || each.suite().equals(SUITE_OF.get(module.getKey()))) {
                    module.getValue().add(each.id());
                }
            }
        }
        List<String> idle = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        long worked = cases.values().stream().filter(named -> !named.isEmpty()).count();
        text.append("Modules a case of this run put to work  ").append(worked).append(" of ").append(cases.size())
            .append("\n\n");
        cases.forEach((module, named) -> {
            if (named.isEmpty()) {
                idle.add(module);
            } else {
                text.append(module).append("  ").append(named.size()).append('\n');
                named.forEach(id -> text.append("    ").append(id).append('\n'));
            }
        });
        text.append("\nModules the rig works only in part  ").append(ONLY_IN_PART.size()).append('\n');
        ONLY_IN_PART.forEach(line -> text.append("    ").append(line).append('\n'));
        text.append("\nModules no case of this run named  ").append(idle.size()).append('\n');
        for (int from = 0; from < idle.size(); from += NAMES_IN_A_LINE) {
            text.append("    ").append(String.join("  ", idle.subList(from, Math.min(idle.size(),
                from + NAMES_IN_A_LINE)))).append('\n');
        }
        Files.writeString(out.resolve(FILE), text.toString(), StandardCharsets.UTF_8);
        return "Coverage   " + worked + " of " + cases.size() + " modules put to work by a case. The list is in "
            + FILE + "\n";
    }
}
