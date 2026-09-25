package com.jellypudding.offlineclient.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.KeyPressEvent;
import com.jellypudding.offlineclient.setting.KeybindSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.mojang.blaze3d.platform.InputConstants;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

// Named lists of lines run by one key press. A line beginning with the command
// prefix runs as a client command and anything else goes to chat.
public final class MacroStore {

    public record Macro(String name, int key, List<String> lines) {

        public Macro withKey(int key) {
            return new Macro(name, key, lines);
        }
    }

    private static MacroStore instance;

    private final Path file;
    private final List<Macro> macros = new ArrayList<>();
    private final Set<String> running = new HashSet<>();

    private MacroStore(Path file) {
        this.file = file;
        load();
        OfflineClient.INSTANCE.getEventBus().register(this);
    }

    public static synchronized MacroStore get() {
        if (instance == null) {
            instance = new MacroStore(DataFiles.path("macros.json"));
        }
        return instance;
    }

    public List<Macro> all() {
        return List.copyOf(macros);
    }

    // A macro of the same name is replaced.
    public void add(Macro macro) {
        macros.removeIf(existing -> existing.name().equalsIgnoreCase(macro.name()));
        macros.add(macro);
        save();
    }

    public boolean remove(String name) {
        boolean removed = macros.removeIf(macro -> macro.name().equalsIgnoreCase(name));
        if (removed) {
            save();
        }
        return removed;
    }

    // Null when nothing answers to that name.
    public Macro find(String name) {
        for (Macro macro : macros) {
            if (macro.name().equalsIgnoreCase(name)) {
                return macro;
            }
        }
        return null;
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
        for (Macro macro : macros) {
            if (macro.key() != KeybindSetting.UNBOUND && macro.key() == event.getKey()) {
                run(macro);
            }
        }
    }

    private void load() {
        DataFiles.readJson(file, MacroStore::decode).ifPresent(macros::addAll);
    }

    private static List<Macro> decode(JsonElement root) {
        List<Macro> decoded = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray()) {
            JsonObject object = element.getAsJsonObject();
            List<String> lines = new ArrayList<>();
            for (JsonElement line : object.getAsJsonArray("lines")) {
                lines.add(line.getAsString());
            }
            decoded.add(new Macro(object.get("name").getAsString(), object.get("key").getAsInt(), lines));
        }
        return decoded;
    }

    private void save() {
        JsonArray root = new JsonArray();
        for (Macro macro : macros) {
            JsonObject object = new JsonObject();
            object.addProperty("name", macro.name());
            object.addProperty("key", macro.key());
            JsonArray lines = new JsonArray();
            macro.lines().forEach(lines::add);
            object.add("lines", lines);
            root.add(object);
        }
        DataFiles.writeJson(file, root);
    }
}
