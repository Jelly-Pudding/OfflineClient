package com.jellypudding.offlineclient.config;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.CommandManager;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.module.ModuleManager;
import com.jellypudding.offlineclient.modules.misc.HudModule;
import com.jellypudding.offlineclient.setting.Defaults;
import com.jellypudding.offlineclient.setting.KeybindSetting;
import com.jellypudding.offlineclient.setting.Setting;
import com.jellypudding.offlineclient.util.Modules;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

// Writes everything the client remembers to offlineclient/config.json. What it holds is split
// into parts and a named profile keeps any of them.
public final class ConfigManager {

    // A part of the setup that can be saved and brought back on its own. The settings of the
    // HUD module are the layout of the overlay and count as a part of their own. Every key
    // setting inside a module goes with the binds.
    public enum Part {
        BINDS, ENABLED, SETTINGS, HUD, FRIENDS, MACROS, WAYPOINTS;

        // What config.json holds. Macros and waypoints live in files of their own.
        public static final Set<Part> OWN =
            Collections.unmodifiableSet(EnumSet.of(BINDS, ENABLED, SETTINGS, HUD, FRIENDS));

        // What a player types and reads for the part.
        public String word() {
            return name().toLowerCase(Locale.ROOT);
        }

        // Null for a word that names no part.
        public static Part named(String word) {
            for (Part part : values()) {
                if (part.word().equalsIgnoreCase(word)) {
                    return part;
                }
            }
            return null;
        }
    }

    // Raised whenever saved values need a one time conversion on load. SaveConversion reads
    // across a save written below it.
    private static final int CONFIG_VERSION = 5;

    // A change waits this long for others before the config is written. A scroll held on a
    // setting then writes once when it stops.
    private static final long QUIET_MS = 2000;
    // Changes that never pause are still written this often.
    private static final long LONGEST_WAIT_MS = 30_000;

    // The names a save files things under that SaveConversion looks up too.
    static final String VERSION = "version";
    static final String MODULES = "modules";
    static final String SETTINGS = "settings";
    static final String ENABLED = "enabled";

    // The modules starred in the GUI. They live in its layout and outlive a reset of it.
    private static final String FAVOURITES = "favourites";

    private static final String PREFIX = "prefix";
    private static final String GUI = "gui";
    private static final String FRIENDS = "friends";
    private static final String ENEMIES = "enemies";
    private static final String MACROS = "macros";
    private static final String WAYPOINTS = "waypoints";
    private static final String BIND = "bind";
    private static final String FOLDS = "folds";
    private static final String GREETED = "greeted";
    private static final String JOIN_PROFILES = "joinProfiles";

    private final Path file = DataFiles.path("config.json");
    private final Profiles profiles = new Profiles(this);
    private JsonObject guiState = new JsonObject();
    private final AtomicBoolean dirty = new AtomicBoolean();
    // When the config was last marked and when the oldest change still waiting was marked.
    private volatile long changedAt;
    private volatile long waitingSince;

    private boolean greeted;

    // A save before the load has finished would wipe the file with defaults. It stays false
    // whilst a file that cannot be read could not be set aside.
    private volatile boolean loaded;

    public ConfigManager() {
        Runtime.getRuntime().addShutdownHook(new Thread(this::saveOnExit, "OfflineClient config save"));
    }

    public Profiles profiles() {
        return profiles;
    }

    // Marks the config for a write once the changes pause. Safe from any thread.
    public void saveSoon() {
        long now = System.currentTimeMillis();
        changedAt = now;
        if (!dirty.getAndSet(true)) {
            waitingSince = now;
        }
    }

    public void tick() {
        profiles.tick();
        if (!dirty.get()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - changedAt >= QUIET_MS || now - waitingSince >= LONGEST_WAIT_MS) {
            dirty.set(false);
            saveNow();
        }
    }

    public synchronized void saveNow() {
        if (!loaded) {
            return;
        }
        JsonObject root = buildRoot(Part.OWN);
        root.addProperty(GREETED, greeted);
        root.add(JOIN_PROFILES, profiles.joinsToJson());
        DataFiles.writeJson(file, root);
    }

    private void saveOnExit() {
        if (dirty.get()) {
            saveNow();
        }
    }

    // The saved values and then whatever a save written below this version holds differently.
    // A save that was read across is written again at this version. A file that cannot be read
    // is set aside first and nothing is saved over it whilst that fails.
    public void load() {
        Optional<JsonObject> saved = read(file);
        if (saved.isEmpty() && Files.exists(file) && !DataFiles.setAside(file)) {
            return;
        }
        saved.ifPresent(root -> {
            SaveConversion.reshape(root);
            applyRoot(root, Part.OWN);
            applyOwn(root);
            if (SaveConversion.convert(root)) {
                saveSoon();
            }
            hudTakesAllAsTheyStand();
        });
        loaded = true;
    }

    // What only config.json holds and no profile does.
    private void applyOwn(JsonObject root) {
        applyPart(root, GREETED, saved -> greeted = saved.getAsBoolean());
        applyPart(root, JOIN_PROFILES, profiles::joinsFromJson);
    }

    // Values applied in bulk flip switches the player did not touch. The HUD takes its pieces
    // as they stand afterwards and moves none of them.
    private static void hudTakesAllAsTheyStand() {
        HudModule hud = Modules.get(HudModule.class);
        if (hud != null) {
            hud.getManager().takeAllAsTheyStand();
        }
    }

    // Which part the settings of a module belong to.
    private static Part settingsPart(Module module) {
        return module instanceof HudModule ? Part.HUD : Part.SETTINGS;
    }

    // Which part one setting belongs to. A key goes with the binds whichever module holds it.
    private static Part partOf(Module module, Setting<?> setting) {
        return setting instanceof KeybindSetting ? Part.BINDS : settingsPart(module);
    }

    // The parts as a save holds them. The prefix and the layout of the GUI go with the settings
    // and the enemies go with the friends.
    JsonObject buildRoot(Set<Part> parts) {
        JsonObject root = new JsonObject();
        root.addProperty(VERSION, CONFIG_VERSION);
        if (parts.contains(Part.SETTINGS)) {
            root.addProperty(PREFIX, OfflineClient.INSTANCE.getCommandManager().getPrefix());
            root.add(GUI, guiState);
        }
        if (parts.contains(Part.FRIENDS)) {
            JsonArray friends = new JsonArray();
            OfflineClient.INSTANCE.getFriendManager().getAll().forEach(friends::add);
            root.add(FRIENDS, friends);
            JsonArray enemies = new JsonArray();
            OfflineClient.INSTANCE.getFriendManager().getEnemies().forEach(enemies::add);
            root.add(ENEMIES, enemies);
        }
        JsonObject modules = new JsonObject();
        for (Module module : OfflineClient.INSTANCE.getModuleManager().getAll()) {
            JsonObject saved = buildModule(module, parts);
            if (!saved.isEmpty()) {
                modules.add(module.getName(), saved);
            }
        }
        if (!modules.isEmpty()) {
            root.add(MODULES, modules);
        }
        if (parts.contains(Part.MACROS)) {
            root.add(MACROS, MacroStore.get().toJson());
        }
        if (parts.contains(Part.WAYPOINTS)) {
            root.add(WAYPOINTS, WaypointStore.get().toJson());
        }
        return root;
    }

    private static JsonObject buildModule(Module module, Set<Part> parts) {
        JsonObject m = new JsonObject();
        if (parts.contains(Part.ENABLED)) {
            m.addProperty(ENABLED, OfflineClient.INSTANCE.getModuleManager().savesAsOn(module));
        }
        if (parts.contains(Part.BINDS)) {
            m.add(BIND, module.getKeybind().toJson());
        }
        boolean own = parts.contains(settingsPart(module));
        JsonObject settings = new JsonObject();
        JsonObject folds = new JsonObject();
        for (Setting<?> setting : module.getSettings()) {
            if (setting.saves() && parts.contains(partOf(module, setting))) {
                settings.add(setting.getName(), setting.toJson());
            }
            if (own && setting.foldChanged()) {
                folds.addProperty(setting.getName(), setting.isFolded());
            }
        }
        if (own || !settings.isEmpty()) {
            m.add(SETTINGS, settings);
        }
        if (!folds.isEmpty()) {
            m.add(FOLDS, folds);
        }
        return m;
    }

    // What a saved root holds of the parts and nothing of any other part. It is cut the way
    // buildRoot would have written those parts alone.
    JsonObject pick(JsonObject root, Set<Part> parts) {
        JsonObject picked = new JsonObject();
        copy(root, picked, VERSION);
        if (parts.contains(Part.SETTINGS)) {
            copy(root, picked, PREFIX);
            copy(root, picked, GUI);
        }
        if (parts.contains(Part.FRIENDS)) {
            copy(root, picked, FRIENDS);
            copy(root, picked, ENEMIES);
        }
        if (parts.contains(Part.MACROS)) {
            copy(root, picked, MACROS);
        }
        if (parts.contains(Part.WAYPOINTS)) {
            copy(root, picked, WAYPOINTS);
        }
        if (!root.has(MODULES)) {
            return picked;
        }
        JsonObject saved = root.getAsJsonObject(MODULES);
        JsonObject modules = new JsonObject();
        for (Module module : OfflineClient.INSTANCE.getModuleManager().getAll()) {
            if (saved.has(module.getName())) {
                JsonObject kept = pickModule(module, saved.getAsJsonObject(module.getName()), parts);
                if (!kept.isEmpty()) {
                    modules.add(module.getName(), kept);
                }
            }
        }
        if (!modules.isEmpty()) {
            picked.add(MODULES, modules);
        }
        return picked;
    }

    private static JsonObject pickModule(Module module, JsonObject m, Set<Part> parts) {
        JsonObject kept = new JsonObject();
        if (parts.contains(Part.ENABLED)) {
            copy(m, kept, ENABLED);
        }
        if (parts.contains(Part.BINDS)) {
            copy(m, kept, BIND);
        }
        boolean own = parts.contains(settingsPart(module));
        if (m.has(SETTINGS)) {
            JsonObject saved = m.getAsJsonObject(SETTINGS);
            JsonObject settings = new JsonObject();
            for (Setting<?> setting : module.getSettings()) {
                if (parts.contains(partOf(module, setting))) {
                    copy(saved, settings, setting.getName());
                }
            }
            if (own || !settings.isEmpty()) {
                kept.add(SETTINGS, settings);
            }
        }
        if (own) {
            copy(m, kept, FOLDS);
        }
        return kept;
    }

    private static void copy(JsonObject from, JsonObject to, String key) {
        if (from.has(key)) {
            to.add(key, from.get(key));
        }
    }

    // Lays one saved root over another. The two hold different parts and share no value.
    static void merge(JsonObject onto, JsonObject over) {
        for (Map.Entry<String, JsonElement> entry : over.entrySet()) {
            JsonElement there = onto.get(entry.getKey());
            if (there != null && there.isJsonObject() && entry.getValue().isJsonObject()) {
                merge(there.getAsJsonObject(), entry.getValue().getAsJsonObject());
            } else {
                onto.add(entry.getKey(), entry.getValue());
            }
        }
    }

    // Takes the parts from a save and leaves every other part of it unread.
    private void applyRoot(JsonObject root, Set<Part> parts) {
        if (parts.contains(Part.SETTINGS)) {
            applyPart(root, PREFIX,
                prefix -> OfflineClient.INSTANCE.getCommandManager().setPrefix(prefix.getAsString()));
            applyPart(root, GUI, gui -> guiState = gui.getAsJsonObject());
        }
        if (parts.contains(Part.FRIENDS)) {
            applyPart(root, FRIENDS, friends -> DataFiles.entries(friends, JsonElement::getAsString, FRIENDS)
                .forEach(OfflineClient.INSTANCE.getFriendManager()::add));
            applyPart(root, ENEMIES, enemies -> DataFiles.entries(enemies, JsonElement::getAsString, ENEMIES)
                .forEach(OfflineClient.INSTANCE.getFriendManager()::addEnemy));
        }
        applyPart(root, MODULES, saved -> {
            JsonObject modules = saved.getAsJsonObject();
            for (Module module : OfflineClient.INSTANCE.getModuleManager().getAll()) {
                if (modules.has(module.getName())) {
                    applyModule(module, modules.get(module.getName()), parts);
                }
            }
        });
        if (parts.contains(Part.MACROS)) {
            applyPart(root, MACROS, macros -> MacroStore.get().replaceWith(macros));
        }
        if (parts.contains(Part.WAYPOINTS)) {
            applyPart(root, WAYPOINTS, waypoints -> WaypointStore.get().replaceWith(waypoints));
        }
    }

    // One saved value that cannot be read loses only itself. A save without the key changes nothing.
    private static void applyPart(JsonObject root, String key, Consumer<JsonElement> apply) {
        JsonElement saved = root.get(key);
        if (saved == null) {
            return;
        }
        try {
            apply.accept(saved);
        } catch (RuntimeException e) {
            OfflineClient.LOG.error("Failed to apply the saved {}", key, e);
        }
    }

    // One bad entry only loses its own module.
    private static void applyModule(Module module, JsonElement saved, Set<Part> parts) {
        try {
            JsonObject m = saved.getAsJsonObject();
            if (parts.contains(Part.BINDS) && m.has(BIND)) {
                module.getKeybind().fromJson(m.get(BIND));
            }
            if (m.has(SETTINGS)) {
                boolean own = parts.contains(settingsPart(module));
                JsonObject settings = m.getAsJsonObject(SETTINGS);
                JsonObject folds = m.has(FOLDS) ? m.getAsJsonObject(FOLDS) : new JsonObject();
                for (Setting<?> setting : module.getSettings()) {
                    if (setting.saves() && parts.contains(partOf(module, setting)) && settings.has(setting.getName())) {
                        setting.fromJson(settings.get(setting.getName()));
                    }
                    if (own && folds.has(setting.getName())) {
                        setting.setFolded(folds.get(setting.getName()).getAsBoolean());
                    }
                }
            }
            if (parts.contains(Part.ENABLED) && m.has(ENABLED) && module.savesEnabledState()) {
                module.setEnabled(m.get(ENABLED).getAsBoolean());
            }
        } catch (Exception e) {
            OfflineClient.LOG.error("Failed to apply the saved state of {}", module.getName(), e);
        }
    }

    // The parts back to how a fresh install has them without writing anything yet. The prefix
    // and the layout of the GUI and the macros and waypoints stay. No reset made before can be
    // taken back afterwards.
    private static void resetQuietly(Set<Part> parts) {
        Defaults.forget();
        ModuleManager modules = OfflineClient.INSTANCE.getModuleManager();
        for (Module module : modules.getAll()) {
            if (parts.contains(Part.ENABLED)) {
                module.setEnabled(false);
            }
            if (parts.contains(Part.BINDS)) {
                module.getKeybind().reset();
            }
            boolean own = parts.contains(settingsPart(module));
            for (Setting<?> setting : module.getSettings()) {
                if (parts.contains(partOf(module, setting))) {
                    setting.reset();
                }
                if (own) {
                    setting.resetFold();
                }
            }
        }
        if (parts.contains(Part.ENABLED)) {
            modules.enableDefaults();
        }
        if (parts.contains(Part.FRIENDS)) {
            clearLists();
        }
        hudTakesAllAsTheyStand();
    }

    // The friends and the enemies are one part and go together.
    private static void clearLists() {
        OfflineClient.INSTANCE.getFriendManager().clear();
        OfflineClient.INSTANCE.getFriendManager().clearEnemies();
    }

    public void resetModules() {
        resetQuietly(EnumSet.of(Part.BINDS, Part.ENABLED, Part.SETTINGS, Part.HUD));
        saveNow();
    }

    public void clearFriends() {
        clearLists();
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
        JsonObject fresh = new JsonObject();
        writeFavourites(fresh, readFavourites(guiState));
        guiState = fresh;
        saveNow();
    }

    // No server loads a profile afterwards. A tied profile would bring back what this clears.
    public void resetEverything() {
        clearLists();
        OfflineClient.INSTANCE.getCommandManager().setPrefix(CommandManager.DEFAULT_PREFIX);
        guiState = new JsonObject();
        profiles.forgetJoins();
        resetModules();
    }

    // Brings the parts back from a profile. Each part goes back to its defaults first. A setting
    // the profile leaves out must not keep its value and a profile lists its own friends. A
    // profile written below this version is read across like the config is. Profiles only hands
    // such a profile over whole.
    void applyProfile(JsonObject root, Set<Part> parts) {
        resetQuietly(parts);
        SaveConversion.reshape(root);
        applyRoot(root, parts);
        SaveConversion.convert(root);
        hudTakesAllAsTheyStand();
        saveNow();
    }

    static Optional<JsonObject> read(Path path) {
        return DataFiles.readJson(path, JsonElement::getAsJsonObject);
    }

    public JsonObject getGuiState() {
        return guiState;
    }

    // The modules starred in a saved layout of the GUI. Anything in its list but a name is passed
    // over. A throw here would crash the window style as it draws.
    public static Set<String> readFavourites(JsonObject gui) {
        Set<String> names = new LinkedHashSet<>();
        if (gui.get(FAVOURITES) instanceof JsonArray list) {
            for (JsonElement name : list) {
                if (name.isJsonPrimitive() && name.getAsJsonPrimitive().isString()) {
                    names.add(name.getAsString());
                }
            }
        }
        return names;
    }

    public static void writeFavourites(JsonObject gui, Collection<String> names) {
        JsonArray list = new JsonArray();
        names.forEach(list::add);
        gui.add(FAVOURITES, list);
    }
}
