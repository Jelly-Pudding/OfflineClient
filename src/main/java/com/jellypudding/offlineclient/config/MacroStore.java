package com.jellypudding.offlineclient.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.KeyPressEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.setting.Bind;
import com.jellypudding.offlineclient.util.JoinWatch;
import com.jellypudding.offlineclient.util.MacroRuns;
import com.jellypudding.offlineclient.util.ServerInfo;
import net.minecraft.SharedConstants;
import net.minecraft.util.StringUtil;

import java.util.ArrayList;
import java.util.List;

public final class MacroStore extends KeyedStore<MacroStore.Macro> {

    public static final String ANY_SERVER = "*";

    public static final String SPLIT = ";";

    public static final String CYCLE = "cycle";

    private static final int LONGEST_SHOWN = 240;

    // The join server is null for a macro that never runs on joining. The delay gives a login
    // plugin time to let commands through.
    public record Macro(String name, Bind key, List<String> lines, boolean cycles, String joinServer, int joinDelay)
        implements KeyedStore.Keyed<Macro> {

        public Macro(String name, Bind key, Typed typed) {
            this(name, key, typed.lines(), typed.cycles(), null, 0);
        }

        @Override
        public Macro withKey(Bind key) {
            return new Macro(name, key, lines, cycles, joinServer, joinDelay);
        }

        public Macro with(Typed typed) {
            return new Macro(name, key, typed.lines(), typed.cycles(), joinServer, joinDelay);
        }

        public Macro withJoin(String server, int delay) {
            return new Macro(name, key, lines, cycles, server, delay);
        }

        public boolean runsOnJoining(String server) {
            return joinServer != null && (joinServer.equals(ANY_SERVER) || joinServer.equals(server));
        }

        public String typedText() {
            String text = (cycles ? CYCLE + " " : "") + String.join(" " + SPLIT + " ", lines);
            return StringUtil.truncateStringIfNecessary(text, LONGEST_SHOWN, true);
        }

        public String tip() {
            String join = joinText() == null ? "" : " " + joinText();
            String text = cycles ? "Each press runs the next of " + String.join(" or ", lines)
                : "Runs " + String.join(" then ", lines);
            return StringUtil.truncateStringIfNecessary(text, LONGEST_SHOWN, true) + join + ".";
        }

        public String joinText() {
            if (joinServer == null) {
                return null;
            }
            String where = joinServer.equals(ANY_SERVER) ? "any server" : joinServer;
            return "on joining " + where + (joinDelay > 0 ? " after " + joinDelay + "s" : "");
        }
    }

    public record Typed(List<String> lines, boolean cycles, String problem) {
    }

    private static MacroStore instance;

    private final MacroRuns runs = new MacroRuns();
    private final JoinWatch joins = new JoinWatch();

    private MacroStore() {
        super("macros.json", MacroStore::read, MacroStore::write);
        OfflineClient.INSTANCE.getEventBus().register(this);
    }

    public static synchronized MacroStore get() {
        if (instance == null) {
            instance = new MacroStore();
        }
        return instance;
    }

    // A cycling macro runs one line a press and a wait would be a press that does nothing.
    public static Typed typed(String text) {
        String rest = text.trim();
        boolean cycles = rest.regionMatches(true, 0, CYCLE + " ", 0, CYCLE.length() + 1);
        if (cycles) {
            rest = rest.substring(CYCLE.length());
        }
        List<String> lines = new ArrayList<>();
        for (String line : rest.split(SPLIT)) {
            if (line.isBlank()) {
                continue;
            }
            MacroLine read = MacroLine.read(line);
            if (read instanceof MacroLine.Unreadable unreadable) {
                return new Typed(lines, cycles, unreadable.problem());
            }
            if (cycles && read instanceof MacroLine.Wait) {
                return new Typed(lines, cycles, "A cycling macro runs one line a press and cannot wait.");
            }
            lines.add(line.trim());
        }
        return new Typed(List.copyOf(lines), cycles, lines.isEmpty() ? "Give it something to run." : null);
    }

    public void run(Macro macro) {
        runs.start(macro, 0);
    }

    public boolean running(String name) {
        return runs.running(name);
    }

    public int stop(String name) {
        return runs.stop(name);
    }

    // A cycling macro saved with other lines starts from its first line again.
    @Override
    public void add(Macro macro) {
        Macro saved = find(macro.name());
        if (saved == null || !saved.lines().equals(macro.lines())) {
            runs.restartCycle(macro.name());
        }
        super.add(macro);
    }

    @Subscribe
    private void onKeyPress(KeyPressEvent event) {
        if (OfflineClient.MC.gui.screen() != null) {
            return;
        }
        for (Macro macro : pressedBy(event)) {
            run(macro);
        }
    }

    // A fresh join drops whatever was still running or waiting from the join before.
    @Subscribe
    private void onTick(TickEvent event) {
        if (joins.joined()) {
            runs.stop(null);
            String server = ServerInfo.key();
            for (Macro macro : all()) {
                if (macro.runsOnJoining(server)) {
                    runs.start(macro, (int) Math.min((long) macro.joinDelay() * SharedConstants.TICKS_PER_SECOND,
                        Integer.MAX_VALUE));
                }
            }
        }
        runs.tick();
    }

    private static Macro read(String name, Bind key, JsonObject saved) {
        List<String> lines = new ArrayList<>();
        for (JsonElement line : saved.getAsJsonArray("lines")) {
            lines.add(line.getAsString());
        }
        boolean cycles = saved.has("cycle") && saved.get("cycle").getAsBoolean();
        String join = saved.has("join") ? saved.get("join").getAsString() : null;
        int delay = saved.has("joinDelay") ? Math.max(0, saved.get("joinDelay").getAsInt()) : 0;
        return new Macro(name, key, List.copyOf(lines), cycles, join, delay);
    }

    private static void write(Macro macro, JsonObject saved) {
        JsonArray lines = new JsonArray();
        macro.lines().forEach(lines::add);
        saved.add("lines", lines);
        if (macro.cycles()) {
            saved.addProperty("cycle", true);
        }
        if (macro.joinServer() != null) {
            saved.addProperty("join", macro.joinServer());
            saved.addProperty("joinDelay", macro.joinDelay());
        }
    }
}
