package com.jellypudding.offlineclient.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.modules.misc.HudModule;
import com.jellypudding.offlineclient.setting.Setting;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Writes everything the client remembers to offlineclient/config.json.
 * Named profiles are saved next to it under offlineclient/profiles.
 */
public final class ConfigManager {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // Raised when old saved values need a one time migration.
    private static final int CONFIG_VERSION = 2;

    private final Path file;
    private final Path profilesFolder;
    private JsonObject guiState = new JsonObject();
    private volatile boolean dirty;

    public ConfigManager(Path folder) {
        this.file = folder.resolve("config.json");
        this.profilesFolder = folder.resolve("profiles");
        try {
            Files.createDirectories(profilesFolder);
        } catch (IOException e) {
            OfflineClient.LOG.error("Failed to create the profiles folder", e);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(this::saveNow, "OfflineClient config save"));
    }

    // Marks the config for a write at the end of the tick.
    public void saveSoon() {
        dirty = true;
    }

    public void tick() {
        if (dirty) {
            dirty = false;
            saveNow();
        }
    }

    public synchronized void saveNow() {
        write(file, buildRoot());
    }

    public void load() {
        if (Files.exists(file)) {
            applyRoot(read(file));
        }
    }

    private JsonObject buildRoot() {
        JsonObject root = new JsonObject();
        root.addProperty("version", CONFIG_VERSION);
        root.addProperty("prefix", OfflineClient.INSTANCE.getCommandManager().getPrefix());

        JsonArray friends = new JsonArray();
        OfflineClient.INSTANCE.getFriendManager().getAll().forEach(friends::add);
        root.add("friends", friends);

        JsonObject modules = new JsonObject();
        for (Module module : OfflineClient.INSTANCE.getModuleManager().getAll()) {
            JsonObject m = new JsonObject();
            m.addProperty("enabled", module.savesEnabledState() && module.isEnabled());
            m.add("bind", module.getKeybind().toJson());
            JsonObject settings = new JsonObject();
            for (Setting<?> setting : module.getSettings()) {
                settings.add(setting.getName(), setting.toJson());
            }
            m.add("settings", settings);
            modules.add(module.getName(), m);
        }
        root.add("modules", modules);
        root.add("gui", guiState);
        return root;
    }

    private void applyRoot(JsonObject root) {
        if (root == null) {
            return;
        }
        try {
            if (root.has("prefix")) {
                OfflineClient.INSTANCE.getCommandManager().setPrefix(root.get("prefix").getAsString());
            }
            if (root.has("friends")) {
                for (JsonElement friend : root.getAsJsonArray("friends")) {
                    OfflineClient.INSTANCE.getFriendManager().add(friend.getAsString());
                }
            }
            if (root.has("modules")) {
                JsonObject modules = root.getAsJsonObject("modules");
                for (Module module : OfflineClient.INSTANCE.getModuleManager().getAll()) {
                    if (!modules.has(module.getName())) {
                        continue;
                    }
                    JsonObject m = modules.getAsJsonObject(module.getName());
                    if (m.has("bind")) {
                        module.getKeybind().fromJson(m.get("bind"));
                    }
                    if (m.has("settings")) {
                        JsonObject settings = m.getAsJsonObject("settings");
                        for (Setting<?> setting : module.getSettings()) {
                            if (settings.has(setting.getName())) {
                                setting.fromJson(settings.get(setting.getName()));
                            }
                        }
                    }
                    if (m.has("enabled") && module.savesEnabledState()) {
                        module.setEnabled(m.get("enabled").getAsBoolean());
                    }
                }
            }
            if (root.has("gui")) {
                guiState = root.getAsJsonObject("gui");
            }

            int version = root.has("version") ? root.get("version").getAsInt() : 1;
            if (version < 2) {
                // Old configs forced every HUD element on.
                for (Setting<?> setting : OfflineClient.INSTANCE.getModuleManager()
                    .get(HudModule.class).getSettings()) {
                    setting.reset();
                }
                saveSoon();
            }
        } catch (Exception e) {
            OfflineClient.LOG.error("Failed to apply config", e);
        }
    }

    public void resetModules() {
        for (Module module : OfflineClient.INSTANCE.getModuleManager().getAll()) {
            module.setEnabled(false);
            module.getKeybind().reset();
            for (Setting<?> setting : module.getSettings()) {
                setting.reset();
            }
        }
        saveNow();
    }

    public void clearFriends() {
        OfflineClient.INSTANCE.getFriendManager().clear();
        saveNow();
    }

    public void resetGuiLayout() {
        guiState = new JsonObject();
        saveNow();
    }

    public void resetEverything() {
        OfflineClient.INSTANCE.getFriendManager().clear();
        OfflineClient.INSTANCE.getCommandManager().setPrefix(".");
        guiState = new JsonObject();
        resetModules();
    }

    public void saveProfile(String name) {
        write(profilesFolder.resolve(sanitize(name) + ".json"), buildRoot());
    }

    // False when the profile does not exist.
    public boolean loadProfile(String name) {
        Path path = profilesFolder.resolve(sanitize(name) + ".json");
        if (!Files.exists(path)) {
            return false;
        }
        applyRoot(read(path));
        saveNow();
        return true;
    }

    public List<String> listProfiles() {
        List<String> names = new ArrayList<>();
        try (Stream<Path> stream = Files.list(profilesFolder)) {
            stream.filter(p -> p.toString().endsWith(".json"))
                .forEach(p -> {
                    String file = p.getFileName().toString();
                    names.add(file.substring(0, file.length() - 5));
                });
        } catch (IOException ignored) {
        }
        return names;
    }

    private static String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    private static void write(Path path, JsonObject root) {
        try {
            Files.writeString(path, GSON.toJson(root));
        } catch (IOException e) {
            OfflineClient.LOG.error("Failed to save {}", path.getFileName(), e);
        }
    }

    private static JsonObject read(Path path) {
        try {
            return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        } catch (Exception e) {
            OfflineClient.LOG.error("Failed to read {}", path.getFileName(), e);
            return null;
        }
    }

    public JsonObject getGuiState() {
        return guiState;
    }
}
