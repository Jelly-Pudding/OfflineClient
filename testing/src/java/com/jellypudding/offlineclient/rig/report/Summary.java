package com.jellypudding.offlineclient.rig.report;

import com.jellypudding.offlineclient.rig.core.Outcome;
import com.jellypudding.offlineclient.rig.core.Verdict;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// The plain summary of a run. One line for each suite and then every case that did not pass.
final class Summary {

    private static final String PASSED_LINE = "RESULT PASSED";
    private static final String FAILED_LINE = "RESULT FAILED";
    private static final String FAILED_LABEL = "FAILED";
    private static final String KNOWN_LABEL = "KNOWN ";
    private static final String FIXED_LABEL = "FIXED ";
    private static final String FIXED_WORDS = "mark can come off";
    private static final String INDENT = "\n        ";
    private static final double MILLIS = 1000.0;

    private Summary() {
    }

    static String of(Path out, List<Outcome> outcomes, List<Path> sheets, String coverage) {
        Map<String, List<Outcome>> suites = new LinkedHashMap<>();
        for (Outcome each : outcomes) {
            suites.computeIfAbsent(each.suite(), suite -> new ArrayList<>()).add(each);
        }
        StringBuilder text = new StringBuilder("Rig run " + out.getFileName() + "\n");
        for (Map.Entry<String, List<Outcome>> suite : suites.entrySet()) {
            List<Outcome> cases = suite.getValue();
            long millis = cases.stream().mapToLong(Outcome::millis).sum();
            text.append(String.format(Locale.ROOT,
                "%-10s %4d cases %4d passed %4d failed %4d known faults %4d left out %7.1f s%n", suite.getKey(),
                cases.size(), count(cases, Verdict.PASSED), count(cases, Verdict.FAILED),
                count(cases, Verdict.KNOWN_FAULT), count(cases, Verdict.SKIPPED), millis / MILLIS));
        }
        tell(text, outcomes, Verdict.FAILED, FAILED_LABEL);
        tell(text, outcomes, Verdict.KNOWN_FAULT, KNOWN_LABEL);
        for (Outcome each : outcomes) {
            if (each.verdict() == Verdict.PASSED && each.message().contains(FIXED_WORDS)) {
                text.append(FIXED_LABEL).append("  ").append(each.id()).append(INDENT).append(each.message())
                    .append('\n');
            }
        }
        text.append(coverage);
        if (!sheets.isEmpty()) {
            text.append("Contact sheets ").append(sheets.size()).append(" in ").append(sheets.getFirst().getParent())
                .append('\n');
        }
        long failed = count(outcomes, Verdict.FAILED);
        text.append(failed == 0 ? PASSED_LINE : FAILED_LINE).append(String.format(Locale.ROOT,
            "  %d passed  %d failed  %d known faults%n", count(outcomes, Verdict.PASSED), failed,
            count(outcomes, Verdict.KNOWN_FAULT)));
        text.append("Results ").append(out).append('\n');
        return text.toString();
    }

    private static void tell(StringBuilder text, List<Outcome> outcomes, Verdict verdict, String label) {
        for (Outcome each : outcomes) {
            if (each.verdict() == verdict) {
                text.append(label).append("  ").append(each.id()).append(INDENT).append(each.message()).append('\n');
            }
        }
    }

    private static long count(List<Outcome> outcomes, Verdict verdict) {
        return outcomes.stream().filter(each -> each.verdict() == verdict).count();
    }
}
