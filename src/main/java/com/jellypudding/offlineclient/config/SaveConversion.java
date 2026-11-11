package com.jellypudding.offlineclient.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.hud.GamePartElement;
import com.jellypudding.offlineclient.hud.HudElement;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.modules.misc.HudModule;
import com.jellypudding.offlineclient.modules.render.Minimap;
import com.jellypudding.offlineclient.modules.render.WorldMap;
import com.jellypudding.offlineclient.setting.Setting;
import com.jellypudding.offlineclient.util.Modules;

import java.util.List;
import java.util.Map;

// Reads across what a save written below the version of the client holds differently. A save
// carries the version that wrote it. ConfigManager asks here twice. Before its values are
// applied the save itself is rewritten where this version files a value under another name.
// Once they are applied whatever a renamed value cannot say is put right on the modules.
final class SaveConversion {

    // Below this version the switch of a game part says whether the part is drawn and the
    // Minimap module holds what opens and saves the whole map and what is drawn over it.
    private static final int PARTS_AND_WORLD_MAP = 4;

    // The settings of the Minimap module in such a save that the whole map goes by. WorldMap
    // has settings of the same names at this version.
    private static final List<String> WORLD_MAP_SETTINGS = List.of("Map key", "Save map", "Entities", "Waypoints",
        "Chunk marks");

    // Below this version AutoLog holds each of its reasons as a row of its own and a Triggers
    // rule names its one condition as a word. At this version both hold a list of the picked
    // conditions and each condition keeps its rows under its own name.
    private static final int PICKED_CONDITIONS = 5;

    private static final String AUTO_LOG = "AutoLog";
    private static final String TRIGGERS = "Triggers";

    // The word a Triggers rule of such a save names its condition by and the condition it is
    // at this version.
    private static final Map<String, String> RULE_CONDITIONS = Map.of("LOW_HEALTH", "Health",
        "PLAYER_NEAR", "Player near", "DIMENSION", "Dimension", "FALLING", "Falling far", "JOINING", "Joining",
        "KILL", "Kill");
    private static final String LOW_HEALTH = "LOW_HEALTH";

    private SaveConversion() {
    }

    // True for a save written below a version that holds something differently. A save that
    // names no version counts as below every one. Such a save only reads right as a whole.
    static boolean isOlder(JsonObject root) {
        return versionOf(root) < PICKED_CONDITIONS;
    }

    private static int versionOf(JsonObject root) {
        JsonElement written = root.get(ConfigManager.VERSION);
        return written != null && written.isJsonPrimitive() && written.getAsJsonPrimitive().isNumber()
            ? written.getAsInt() : 0;
    }

    // Rewrites what a save written below this version files under other names. Call it before
    // the values are applied. Only the save is read and written and nothing of the client.
    static void reshape(JsonObject root) {
        if (!isOlder(root)) {
            return;
        }
        try {
            JsonObject modules = objectIn(root, ConfigManager.MODULES);
            autoLogReasons(objectIn(objectIn(modules, AUTO_LOG), ConfigManager.SETTINGS));
            triggerRules(objectIn(objectIn(modules, TRIGGERS), ConfigManager.SETTINGS));
        } catch (RuntimeException e) {
            OfflineClient.LOG.error("Failed to rewrite a save of version {}", root.get(ConfigManager.VERSION), e);
        }
    }

    // True when the save was written below this version and was read across. Call it once the
    // values are applied.
    static boolean convert(JsonObject root) {
        if (!isOlder(root)) {
            return false;
        }
        if (versionOf(root) >= PARTS_AND_WORLD_MAP) {
            return true;
        }
        try {
            JsonObject modules = objectIn(root, ConfigManager.MODULES);
            gameParts(modules);
            modulePieces(modules);
            worldMap(modules);
        } catch (RuntimeException e) {
            OfflineClient.LOG.error("Failed to read across a save of version {}", root.get(ConfigManager.VERSION), e);
        }
        return true;
    }

    // Below version five AutoLog watches the health always and each other reason whilst its own
    // row is on or above nought. The listed entities are watched whenever the list holds any
    // and a save with no list had the end crystal on it. At version five the Reasons list
    // names what is watched. Every reason such a save had on is picked and the rows that go by
    // another name are filed under it. Health and Totems and the rest keep their names.
    private static void autoLogReasons(JsonObject settings) {
        if (settings == null || settings.has("Reasons")) {
            return;
        }
        JsonArray reasons = new JsonArray();
        reasons.add("Health");
        if (number(settings, "Totems") > 0) {
            reasons.add("Totems");
        }
        if (number(settings, "Totem pops") > 0) {
            reasons.add("Totem pops");
        }
        if (isOn(settings, "Instant kill")) {
            reasons.add("Instant kill");
        }
        if (isOn(settings, "Only trusted")) {
            reasons.add("Player in view");
        }
        if (isOn(settings, "On alarm")) {
            reasons.add("Alarm");
        }
        JsonElement entities = settings.get("Entities");
        if (entities == null || !entities.isJsonArray() || !entities.getAsJsonArray().isEmpty()) {
            reasons.add("Entities near");
        }
        settings.add("Reasons", reasons);
        rename(settings, "Entities", "Entities near");
        rename(settings, "Count", "Entity counting");
        rename(settings, "Rearm when healed", "Rearm when safe");
    }

    // Below version five the row of a Triggers rule holds one word for its condition and that
    // condition keeps its number in a row of another name. At version five the row holds the
    // list of picked conditions. A low health rule of such a save looked at the health alone.
    private static void triggerRules(JsonObject settings) {
        if (settings == null) {
            return;
        }
        for (int rule = 1; settings.has("Rule " + rule); rule++) {
            JsonElement when = settings.get("Rule " + rule);
            if (!when.isJsonPrimitive() || !when.getAsJsonPrimitive().isString()) {
                continue;
            }
            JsonArray picked = new JsonArray();
            String condition = RULE_CONDITIONS.get(when.getAsString());
            if (condition != null) {
                picked.add(condition);
            }
            if (LOW_HEALTH.equals(when.getAsString())) {
                settings.addProperty("Predict damage " + rule, false);
            }
            settings.add("Rule " + rule, picked);
            rename(settings, "Range " + rule, "Player near " + rule);
            rename(settings, "Fall " + rule, "Falling far " + rule);
        }
    }

    private static void rename(JsonObject settings, String from, String to) {
        JsonElement value = settings.remove(from);
        if (value != null) {
            settings.add(to, value);
        }
    }

    // Nought for a value that is missing or is no number.
    private static double number(JsonObject settings, String name) {
        JsonElement saved = settings.get(name);
        return saved != null && saved.isJsonPrimitive() && saved.getAsJsonPrimitive().isNumber()
            ? saved.getAsDouble() : 0;
    }

    private static boolean isOn(JsonObject settings, String name) {
        JsonElement saved = settings.get(name);
        return isBoolean(saved) && saved.getAsBoolean();
    }

    // Below version four the switch of each part of the game's own HUD says whether the part is
    // drawn at all. On draws it where its place and size say and off hides it. At version four
    // on means the client places the part and off leaves it to the game. A part saved on and
    // left just as the game draws it reads as off and a part saved off reads as on and hidden.
    private static void gameParts(JsonObject modules) {
        HudModule hud = Modules.get(HudModule.class);
        JsonObject settings = objectIn(savedOf(modules, hud), ConfigManager.SETTINGS);
        if (settings == null) {
            return;
        }
        for (HudElement element : hud.getManager().all()) {
            JsonElement drawn = settings.get(element.getName());
            if (element instanceof GamePartElement part && isBoolean(drawn)) {
                boolean hidden = !drawn.getAsBoolean();
                part.setHidden(hidden);
                part.setActive(hidden || !part.positionIsDefault() || !part.sizeIsDefault());
            }
        }
    }

    // Below version four a piece that draws for a module has a saved switch of its own and
    // shows only whilst both are on. At version four its switch is the module's. A module
    // saved on with its piece saved off reads as off or the piece would come onto the screen.
    private static void modulePieces(JsonObject modules) {
        HudModule hud = Modules.get(HudModule.class);
        JsonObject settings = objectIn(savedOf(modules, hud), ConfigManager.SETTINGS);
        if (settings == null) {
            return;
        }
        for (HudElement element : hud.getManager().all()) {
            JsonElement shown = settings.get(element.getName());
            if (element.followsModule() && isBoolean(shown) && !shown.getAsBoolean()) {
                element.setActive(false);
            }
        }
    }

    // Below version four the Minimap module holds the key that opens the whole map and the
    // switch that keeps the land in files and the key works whilst the Minimap is on. The whole
    // map draws entities and waypoints and chunk marks as the Minimap's three switches say. At
    // version four WorldMap has all five and the key works whilst WorldMap is on.
    private static void worldMap(JsonObject modules) {
        WorldMap worldMap = Modules.get(WorldMap.class);
        JsonObject saved = savedOf(modules, Modules.get(Minimap.class));
        if (worldMap == null || saved == null) {
            return;
        }
        JsonObject settings = objectIn(saved, ConfigManager.SETTINGS);
        for (String name : WORLD_MAP_SETTINGS) {
            Setting<?> setting = worldMap.getSetting(name);
            if (setting != null && settings != null && settings.has(name)) {
                setting.fromJson(settings.get(name));
            }
        }
        JsonElement enabled = saved.get(ConfigManager.ENABLED);
        if (isBoolean(enabled) && enabled.getAsBoolean()) {
            worldMap.setEnabled(true);
        }
    }

    private static boolean isBoolean(JsonElement saved) {
        return saved != null && saved.isJsonPrimitive() && saved.getAsJsonPrimitive().isBoolean();
    }

    // What the save holds for a module. Null when it holds nothing for it.
    private static JsonObject savedOf(JsonObject modules, Module module) {
        return module == null ? null : objectIn(modules, module.getName());
    }

    private static JsonObject objectIn(JsonObject parent, String key) {
        return parent != null && parent.has(key) && parent.get(key).isJsonObject() ? parent.getAsJsonObject(key)
            : null;
    }
}
