package com.jellypudding.offlineclient.config;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.CommandManager;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.module.ModuleManager;
import com.jellypudding.offlineclient.modules.misc.HudModule;
import com.jellypudding.offlineclient.setting.KeybindSetting;
import com.jellypudding.offlineclient.setting.Setting;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

// Writes everything the client remembers to offlineclient/config.json.
// Named profiles are saved next to it under offlineclient/profiles.
public final class ConfigManager {

    // Raised whenever saved values need a one time conversion on load.
    private static final int CONFIG_VERSION = 3;

    private final Path file = DataFiles.path("config.json");
    private final Path profilesFolder = DataFiles.path("profiles");
    private JsonObject guiState = new JsonObject();
    private final AtomicBoolean dirty = new AtomicBoolean();

    // False until the client has said hello once.
    private boolean greeted;

    // A save before the load has finished would wipe the file with defaults.
    private volatile boolean loaded;

    public ConfigManager() {
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
            DataFiles.writeJson(file, buildRoot());
        }
    }

    private void saveOnExit() {
        if (dirty.get()) {
            saveNow();
        }
    }

    public void load() {
        read(file).ifPresent(root -> {
            applyRoot(root);
            // Macros live in their own file and a profile must never touch them.
            if (versionOf(root) < 3) {
                MacroStore.get().migrateLegacyKeys();
            }
        });
        loaded = true;
    }

    private static int versionOf(JsonObject root) {
        return root.has("version") ? root.get("version").getAsInt() : 1;
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
            JsonObject folds = new JsonObject();
            for (Setting<?> setting : module.getSettings()) {
                settings.add(setting.getName(), setting.toJson());
                if (setting.foldChanged()) {
                    folds.addProperty(setting.getName(), setting.isFolded());
                }
            }
            m.add("settings", settings);
            if (!folds.isEmpty()) {
                m.add("folds", folds);
            }
            modules.add(module.getName(), m);
        }
        root.add("modules", modules);
        root.add("gui", guiState);
        root.addProperty("greeted", greeted);
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
            int version = versionOf(root);
            if (root.has("modules")) {
                JsonObject modules = root.getAsJsonObject("modules");
                for (Module module : OfflineClient.INSTANCE.getModuleManager().getAll()) {
                    if (modules.has(module.getName())) {
                        applyModule(module, modules.get(module.getName()), version < 3);
                    }
                }
            }
            if (root.has("gui")) {
                guiState = root.getAsJsonObject("gui");
            }
            greeted = root.has("greeted") && root.get("greeted").getAsBoolean();

            if (version < 2) {
                // A version 1 file has every HUD element forced on.
                for (Setting<?> setting : OfflineClient.INSTANCE.getModuleManager()
                    .get(HudModule.class).getSettings()) {
                    setting.reset();
                }
            }
            if (version < CONFIG_VERSION) {
                saveSoon();
            }
        } catch (Exception e) {
            OfflineClient.LOG.error("Failed to apply config", e);
        }
    }

    // One bad entry only loses its own module. A legacy config holds binds as
    // window library codes and only the ones read are moved across.
    private static void applyModule(Module module, JsonElement saved, boolean legacyKeys) {
        try {
            JsonObject m = saved.getAsJsonObject();
            if (m.has("bind")) {
                module.getKeybind().fromJson(m.get("bind"));
                if (legacyKeys) {
                    module.getKeybind().migrateLegacyKey();
                }
            }
            if (m.has("settings")) {
                JsonObject settings = m.getAsJsonObject("settings");
                JsonObject folds = m.has("folds") ? m.getAsJsonObject("folds") : new JsonObject();
                for (Setting<?> setting : module.getSettings()) {
                    if (settings.has(setting.getName())) {
                        setting.fromJson(settings.get(setting.getName()));
                        if (legacyKeys && setting instanceof KeybindSetting bind) {
                            bind.migrateLegacyKey();
                        }
                    }
                    if (folds.has(setting.getName())) {
                        setting.setFolded(folds.get(setting.getName()).getAsBoolean());
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
                setting.resetFold();
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

    // True the first time this is asked after a fresh install.
    public boolean needsGreeting() {
        if (greeted) {
            return false;
        }
        greeted = true;
        saveSoon();
        return true;
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
        DataFiles.writeJson(profileFile(name), buildRoot());
    }

    // False when the profile does not exist.
    public boolean loadProfile(String name) {
        JsonObject root = read(profileFile(name)).orElse(null);
        if (root == null) {
            return false;
        }
        // Settings missing from the profile must reset rather than keep their current value.
        resetModulesQuietly();
        // A profile lists its own friends and never adds to the ones already loaded.
        OfflineClient.INSTANCE.getFriendManager().clear();
        applyRoot(root);
        saveNow();
        return true;
    }

    // False when there was no profile of that name to remove.
    public boolean deleteProfile(String name) {
        try {
            return Files.deleteIfExists(profileFile(name));
        } catch (IOException e) {
            OfflineClient.LOG.error("Failed to delete the profile {}", name, e);
            return false;
        }
    }

    public List<String> listProfiles() {
        return DataFiles.jsonNames(profilesFolder);
    }

    // Anything but letters and digits and underscores and dashes becomes an underscore.
    private Path profileFile(String name) {
        return DataFiles.jsonFile(profilesFolder, name.replaceAll("[^a-zA-Z0-9_-]", "_"));
    }

    private static Optional<JsonObject> read(Path path) {
        return DataFiles.readJson(path, JsonElement::getAsJsonObject);
    }

    public JsonObject getGuiState() {
        return guiState;
    }
}
