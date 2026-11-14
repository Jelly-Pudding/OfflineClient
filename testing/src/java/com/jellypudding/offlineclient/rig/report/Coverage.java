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
import java.util.regex.Pattern;

// Says which modules the cases of a run put to work. A case counts for every module its name
// holds as a whole word. The suite that only switches modules on and off counts for none.
final class Coverage {

    private static final String FILE = "coverage.txt";
    private static final String BLANKET_SUITE = "Smoke";
    private static final String NOT_A_LETTER_BEFORE = "(?<![A-Za-z])";
    private static final String NOT_A_LETTER_AFTER = "(?![A-Za-z])";
    private static final int NAMES_IN_A_LINE = 8;

    private Coverage() {
    }

    // One line for the summary. Nothing when the run left no list of modules behind.
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
            if (each.suite().equals(BLANKET_SUITE) || each.verdict() == Verdict.SKIPPED) {
                continue;
            }
            for (Map.Entry<String, List<String>> module : cases.entrySet()) {
                Pattern named = Pattern.compile(NOT_A_LETTER_BEFORE + Pattern.quote(module.getKey())
                    + NOT_A_LETTER_AFTER);
                if (named.matcher(each.name()).find()) {
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
