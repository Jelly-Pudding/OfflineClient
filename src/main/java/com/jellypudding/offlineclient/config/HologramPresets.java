package com.jellypudding.offlineclient.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

// Hologram texts saved under a name. Names match whatever their case and are listed from A to Z.
// A player with nothing saved yet starts with one example.
public final class HologramPresets {

    private static final String EXAMPLE_NAME = "welcome";
    private static final String EXAMPLE_TEXT = "&6&lWelcome\\n&7Make yourself at home";

    private static HologramPresets instance;

    private final Path file;
    private final Map<String, String> presets = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    private HologramPresets(Path file) {
        this.file = file;
        DataFiles.readJson(file, HologramPresets::decode).ifPresentOrElse(presets::putAll,
            () -> presets.put(EXAMPLE_NAME, EXAMPLE_TEXT));
    }

    public static synchronized HologramPresets get() {
        if (instance == null) {
            instance = new HologramPresets(DataFiles.path("holograms.json"));
        }
        return instance;
    }

    // Null when nothing is saved under the name.
    public String find(String name) {
        return presets.get(name);
    }

    public List<String> names() {
        return List.copyOf(presets.keySet());
    }

    // A preset of the same name is replaced and takes the case typed this time.
    public void save(String name, String text) {
        presets.remove(name);
        presets.put(name, text);
        write();
    }

    public boolean delete(String name) {
        boolean deleted = presets.remove(name) != null;
        if (deleted) {
            write();
        }
        return deleted;
    }

    // Puts the example back as it shipped. The other presets stay.
    public String restoreExample() {
        save(EXAMPLE_NAME, EXAMPLE_TEXT);
        return EXAMPLE_NAME;
    }

    private static Map<String, String> decode(JsonElement root) {
        Map<String, String> decoded = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject().entrySet()) {
            decoded.put(entry.getKey(), entry.getValue().getAsString());
        }
        return decoded;
    }

    private void write() {
        JsonObject root = new JsonObject();
        presets.forEach(root::addProperty);
        DataFiles.writeJson(file, root);
    }
}
