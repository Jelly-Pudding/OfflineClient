package com.jellypudding.offlineclient.config;

import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class HologramPresets {

    private static final String EXAMPLE_NAME = "welcome";
    private static final String EXAMPLE_TEXT = "&6&lWelcome\\n&7Make yourself at home";

    private static HologramPresets instance;

    private final Path file;
    private final Map<String, String> presets = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    private HologramPresets(Path file) {
        this.file = file;
        DataFiles.readJson(file, root -> DataFiles.members(root, (name, text) -> text.getAsString(),
            "hologram presets")).ifPresentOrElse(presets::putAll, () -> presets.put(EXAMPLE_NAME, EXAMPLE_TEXT));
    }

    public static synchronized HologramPresets get() {
        if (instance == null) {
            instance = new HologramPresets(DataFiles.path("holograms.json"));
        }
        return instance;
    }

    public String find(String name) {
        return presets.get(name);
    }

    public List<String> names() {
        return List.copyOf(presets.keySet());
    }

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

    public String restoreExample() {
        save(EXAMPLE_NAME, EXAMPLE_TEXT);
        return EXAMPLE_NAME;
    }

    private void write() {
        JsonObject root = new JsonObject();
        presets.forEach(root::addProperty);
        DataFiles.writeJson(file, root);
    }
}
