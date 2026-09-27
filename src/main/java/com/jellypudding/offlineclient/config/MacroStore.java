package com.jellypudding.offlineclient.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.KeyPressEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.JoinWatch;
import com.jellypudding.offlineclient.util.ServerInfo;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.SharedConstants;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

// Named lists of lines run by one key press or on joining a server. A line beginning
// with the command prefix runs as a client command and anything else goes to chat.
public final class MacroStore extends KeyedStore<MacroStore.Macro> {

    // The join server of a macro that runs on joining any server.
    public static final String ANY_SERVER = "*";

    // The join server is null for a macro that never runs on joining. The delay gives
    // a login plugin time to let commands through.
    public record Macro(String name, int key, List<String> lines, String joinServer, int joinDelay)
        implements KeyedStore.Keyed<Macro> {

        public Macro(String name, int key, List<String> lines) {
            this(name, key, lines, null, 0);
        }

        @Override
        public Macro withKey(int key) {
            return new Macro(name, key, lines, joinServer, joinDelay);
        }

        public Macro withLines(List<String> lines) {
            return new Macro(name, key, lines, joinServer, joinDelay);
        }

        public Macro withJoin(String server, int delay) {
            return new Macro(name, key, lines, server, delay);
        }

        public boolean runsOnJoining(String server) {
            return joinServer != null && (joinServer.equals(ANY_SERVER) || joinServer.equals(server));
        }

        // When the macro runs by itself. Null for one that only runs on its key.
        public String joinText() {
            if (joinServer == null) {
                return null;
            }
            String where = joinServer.equals(ANY_SERVER) ? "any server" : joinServer;
            return "on joining " + where + (joinDelay > 0 ? " after " + joinDelay + "s" : "");
        }
    }

    // A join macro waiting out its delay. It is looked up by name when it runs.
    private static final class Waiting {

        final String name;
        int ticksLeft;

        Waiting(String name, int ticksLeft) {
            this.name = name;
            this.ticksLeft = ticksLeft;
        }
    }

    private static MacroStore instance;

    private final Set<String> running = new HashSet<>();
    private final List<Waiting> waiting = new ArrayList<>();
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

    // A macro may run another but never one already under way. It would repeat itself for ever.
    public void run(Macro macro) {
        if (OfflineClient.MC.player == null) {
            return;
        }
        String key = macro.name().toLowerCase(Locale.ROOT);
        if (!running.add(key)) {
            ChatUtil.error("The macro " + macro.name() + " cannot run itself.");
            return;
        }
        try {
            for (String line : macro.lines()) {
                if (line.isBlank()) {
                    continue;
                }
                if (!OfflineClient.INSTANCE.getCommandManager().run(line)) {
                    ChatUtil.say(line);
                }
            }
        } finally {
            running.remove(key);
        }
    }

    @Subscribe
    private void onKeyPress(KeyPressEvent event) {
        if (event.getAction() != InputConstants.PRESS || OfflineClient.MC.gui.screen() != null) {
            return;
        }
        for (Macro macro : boundTo(event.getKey())) {
            run(macro);
        }
    }

    // A fresh join drops any macro still waiting from the join before.
    @Subscribe
    private void onTick(TickEvent event) {
        if (joins.joined()) {
            waiting.clear();
            String server = ServerInfo.key();
            for (Macro macro : all()) {
                if (macro.runsOnJoining(server)) {
                    waiting.add(new Waiting(macro.name(), macro.joinDelay() * SharedConstants.TICKS_PER_SECOND));
                }
            }
        }
        Iterator<Waiting> it = waiting.iterator();
        while (it.hasNext()) {
            Waiting next = it.next();
            if (next.ticksLeft-- > 0) {
                continue;
            }
            it.remove();
            Macro macro = find(next.name);
            if (macro != null) {
                run(macro);
            }
        }
    }

    private static Macro read(String name, int key, JsonObject saved) {
        List<String> lines = new ArrayList<>();
        for (JsonElement line : saved.getAsJsonArray("lines")) {
            lines.add(line.getAsString());
        }
        String join = saved.has("join") ? saved.get("join").getAsString() : null;
        int delay = saved.has("joinDelay") ? saved.get("joinDelay").getAsInt() : 0;
        return new Macro(name, key, lines, join, delay);
    }

    private static void write(Macro macro, JsonObject saved) {
        JsonArray lines = new JsonArray();
        macro.lines().forEach(lines::add);
        saved.add("lines", lines);
        if (macro.joinServer() != null) {
            saved.addProperty("join", macro.joinServer());
            saved.addProperty("joinDelay", macro.joinDelay());
        }
    }
}
