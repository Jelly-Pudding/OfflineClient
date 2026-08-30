package com.jellypudding.offlineclient.config;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.CommandManager;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.module.ModuleManager;
import com.jellypudding.offlineclient.modules.misc.HudModule;
import com.jellypudding.offlineclient.setting.Setting;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private final AtomicBoolean dirty = new AtomicBoolean();

    // A save before the load has finished would wipe the file with defaults.
    private volatile boolean loaded;

    public ConfigManager(Path folder) {
        this.file = folder.resolve("config.json");
        this.profilesFolder = folder.resolve("profiles");
        try {
            Files.createDirectories(profilesFolder);
        } catch (IOException e) {
            OfflineClient.LOG.error("Failed to create the profiles folder", e);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(this::saveOnExit, "OfflineClient config save"));
    }

    // Marks the config for a write at the end of the tick. Safe from any thread.
    public void saveSoon() {
        dirty.set(true);
    }

    public void tick() {
        if (dirty.getAndSet(false)) {
            saveNow();
        }
    }

    public synchronized void saveNow() {
        if (loaded) {
            write(file, buildRoot());
        }
    }

    private void saveOnExit() {
        if (dirty.get()) {
            saveNow();
        }
    }

    public void load() {
        if (Files.exists(file)) {
            applyRoot(read(file));
        }
        loaded = true;
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
                    if (modules.has(module.getName())) {
                        applyModule(module, modules.get(module.getName()));
                    }
                }
            }
            if (root.has("gui")) {
                guiState = root.getAsJsonObject("gui");
            }

            int version = root.has("version") ? root.get("version").getAsInt() : 1;
            if (version < 2) {
                // Version 1 forced every HUD element on.
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

    // One bad entry only loses its own module.
    private static void applyModule(Module module, JsonElement saved) {
        try {
            JsonObject m = saved.getAsJsonObject();
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
        } catch (Exception e) {
            OfflineClient.LOG.error("Failed to apply the saved state of {}", module.getName(), e);
        }
    }

    // Every module back to its defaults without writing anything yet.
    private static void resetModulesQuietly() {
        ModuleManager modules = OfflineClient.INSTANCE.getModuleManager();
        for (Module module : modules.getAll()) {
            module.setEnabled(false);
            module.getKeybind().reset();
            for (Setting<?> setting : module.getSettings()) {
                setting.reset();
            }
        }
        modules.enableDefaults();
    }

    public void resetModules() {
        resetModulesQuietly();
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
        OfflineClient.INSTANCE.getCommandManager().setPrefix(CommandManager.DEFAULT_PREFIX);
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
        JsonObject root = read(path);
        if (root == null) {
            return false;
        }
        // A profile saved before a setting existed must not keep the old value of it.
        resetModulesQuietly();
        applyRoot(root);
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
        } catch (IOException e) {
            OfflineClient.LOG.warn("Cannot list the profiles folder", e);
        }
        return names;
    }

    private static String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    // The file is swapped in whole. A crash mid write leaves the old one intact.
    static void write(Path path, JsonElement root) {
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.writeString(temp, GSON.toJson(root));
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
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
