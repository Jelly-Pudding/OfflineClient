package com.jellypudding.offlineclient.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.KeyPressEvent;
import com.jellypudding.offlineclient.setting.KeybindSetting;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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

    private MacroStore(Path folder) {
        this.file = folder.resolve("macros.json");
        load();
        OfflineClient.INSTANCE.getEventBus().register(this);
    }

    public static synchronized MacroStore get() {
        if (instance == null) {
            Path folder = OfflineClient.MC.gameDirectory.toPath().resolve("offlineclient");
            try {
                Files.createDirectories(folder);
            } catch (IOException e) {
                OfflineClient.LOG.error("Failed to create the macro folder", e);
            }
            instance = new MacroStore(folder);
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

    public void run(Macro macro) {
        if (OfflineClient.MC.player == null) {
            return;
        }
        for (String line : macro.lines()) {
            if (line.isBlank()) {
                continue;
            }
            if (!OfflineClient.INSTANCE.getCommandManager().run(line)) {
                OfflineClient.MC.player.connection.sendChat(line);
            }
        }
    }

    @Subscribe
    private void onKeyPress(KeyPressEvent event) {
        if (event.getAction() != GLFW.GLFW_PRESS || OfflineClient.MC.gui.screen() != null) {
            return;
        }
        for (Macro macro : macros) {
            if (macro.key() != KeybindSetting.UNBOUND && macro.key() == event.getKey()) {
                run(macro);
            }
        }
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            JsonElement root = JsonParser.parseString(Files.readString(file));
            for (JsonElement element : root.getAsJsonArray()) {
                JsonObject object = element.getAsJsonObject();
                List<String> lines = new ArrayList<>();
                for (JsonElement line : object.getAsJsonArray("lines")) {
                    lines.add(line.getAsString());
                }
                macros.add(new Macro(object.get("name").getAsString(),
                    object.get("key").getAsInt(), lines));
            }
        } catch (Exception e) {
            OfflineClient.LOG.error("Failed to read macros", e);
        }
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
        ConfigManager.write(file, root);
    }
}
