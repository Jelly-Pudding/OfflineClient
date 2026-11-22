package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.config.MacroLine;
import com.jellypudding.offlineclient.config.MacroStore.Macro;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// A wait or a repeat holds the lines after it back and a line for the server holds until the server
// will take it at the pace of a macro. A cycling macro runs one line a press and remembers where it
// stands in its list.
public final class MacroRuns {

    // The most steps one macro takes in a tick. A long list of lines would stall the game.
    // Reading a line is a step and sending it is another.
    private static final int STEPS_A_TICK = 100;

    private static final class Run {

        private final String name;
        private final List<String> lines;
        private int next;
        private int wait;
        private MacroLine.Repeat sending;
        private int sendsLeft;
        private boolean over;

        private Run(String name, List<String> lines, int wait) {
            this.name = name;
            this.lines = lines;
            this.wait = wait;
        }

        private void open(MacroLine.Repeat repeat) {
            sending = repeat;
            sendsLeft = repeat.times();
        }
    }

    private final List<Run> runs = new ArrayList<>();

    private final Map<String, Integer> turns = new HashMap<>();

    // Starts the macro once the delay in ticks is over. A dormant client runs none because
    // its chat lines would still go out. A macro may run another but never one that is still
    // under way. It could start itself over and over.
    public void start(Macro macro, int delay) {
        if (OfflineClient.MC.player == null || Modules.dormant()) {
            return;
        }
        if (running(macro.name())) {
            ChatUtil.error(macro.name() + " is still running. Stop it with "
                + OfflineClient.INSTANCE.getCommandManager().getPrefix() + "macro stop " + macro.name() + ".");
            return;
        }
        List<String> lines = macro.lines();
        if (macro.cycles() && !lines.isEmpty()) {
            int turn = turns.merge(key(macro.name()), 1, Integer::sum) - 1;
            lines = List.of(lines.get(Math.floorMod(turn, lines.size())));
        }
        Run run = new Run(macro.name(), lines, delay);
        runs.add(run);
        if (delay == 0) {
            advance(run);
        }
    }

    public boolean running(String name) {
        return runs.stream().anyMatch(run -> run.name.equalsIgnoreCase(name));
    }

    // A null name ends every run. Hands back how many ended.
    public int stop(String name) {
        int stopped = 0;
        for (Run run : List.copyOf(runs)) {
            if (name == null || run.name.equalsIgnoreCase(name)) {
                finish(run);
                stopped++;
            }
        }
        return stopped;
    }

    public void restartCycle(String name) {
        turns.remove(key(name));
    }

    // A line may start or stop other runs. Those started this tick take their turn from the next.
    public void tick() {
        if (runs.isEmpty()) {
            return;
        }
        if (OfflineClient.MC.player == null || Modules.dormant()) {
            stop(null);
            return;
        }
        for (Run run : List.copyOf(runs)) {
            if (run.over) {
                continue;
            }
            if (run.wait > 0) {
                run.wait--;
            }
            if (run.wait == 0) {
                advance(run);
            }
        }
    }

    private void advance(Run run) {
        int budget = STEPS_A_TICK;
        while (!run.over && run.wait == 0 && budget-- > 0) {
            if (run.sending == null) {
                take(run);
            } else if (!send(run.sending.text())) {
                return;
            } else if (--run.sendsLeft == 0) {
                run.sending = null;
            } else {
                run.wait = gapAfter(run.sending);
            }
        }
    }

    private void take(Run run) {
        if (run.next >= run.lines.size()) {
            finish(run);
            return;
        }
        String line = run.lines.get(run.next++);
        if (line.isBlank()) {
            return;
        }
        switch (MacroLine.read(line)) {
            case MacroLine.Wait wait -> run.wait = wait.ticks();
            case MacroLine.Repeat repeat -> run.open(repeat);
            case MacroLine.Send plain -> run.open(new MacroLine.Repeat(1, 0, plain.text()));
            case MacroLine.Unreadable unreadable -> {
                ChatUtil.error(run.name + " stopped at line " + run.next + ". " + unreadable.problem());
                finish(run);
            }
        }
    }

    // False whilst the server would not take another line yet. A command of ours fills in live
    // values itself where it takes them.
    private static boolean send(String text) {
        if (!ours(text)) {
            return ChatPace.MACRO.say(text, SentLine::live);
        }
        if (!ChatPace.MACRO.fits(1, 1)) {
            return false;
        }
        if (!OfflineClient.INSTANCE.getCommandManager().run(text)) {
            ChatPace.MACRO.say(text, SentLine::live);
        }
        return true;
    }

    // A command of ours runs at most once a tick. A repeat of one with no gap would otherwise
    // send whatever packets it sends as fast as the loop goes.
    private static int gapAfter(MacroLine.Repeat repeat) {
        return ours(repeat.text()) ? Math.max(repeat.gap(), 1) : repeat.gap();
    }

    private static boolean ours(String text) {
        return text.startsWith(OfflineClient.INSTANCE.getCommandManager().getPrefix());
    }

    private void finish(Run run) {
        run.over = true;
        runs.remove(run);
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
