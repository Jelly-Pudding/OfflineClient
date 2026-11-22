package com.jellypudding.offlineclient.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.config.ConfigManager.Part;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.ServerInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public final class Profiles {

    private static final String PARTS = "parts";

    private final ConfigManager config;
    private final Path folder = DataFiles.path("profiles");

    private final Map<String, String> joins = new TreeMap<>();

    // True from the login packet of a join until the profile of the server is in. A dormant
    // client leaves it waiting.
    private boolean joinWaits;

    Profiles(ConfigManager config) {
        this.config = config;
    }

    public List<String> list() {
        return DataFiles.jsonNames(folder);
    }

    public static Set<Part> partsNamed(String[] words, int from) {
        Set<Part> parts = EnumSet.noneOf(Part.class);
        for (int i = from; i < words.length; i++) {
            Part part = Part.named(words[i]);
            if (part == null) {
                ChatUtil.error(words[i] + " is no part of a profile. The parts are " + worded(List.of(Part.values()))
                    + ".");
                return null;
            }
            parts.add(part);
        }
        return parts;
    }

    public static List<String> allPartWords() {
        List<String> words = new ArrayList<>();
        for (Part part : Part.values()) {
            words.add(part.word());
        }
        return words;
    }

    public static String worded(Collection<Part> parts) {
        List<String> words = new ArrayList<>();
        for (Part part : parts) {
            words.add(part.word());
        }
        return String.join(" and ", words);
    }

    private static String olderLine(String name) {
        return "The profile " + name + " was saved by an older version. Load it whole once and save it again.";
    }

    // With no part named the profile becomes what the config itself holds. Macros and waypoints
    // only go in when named.
    public void save(String name, Set<Part> parts) {
        Set<Part> held = EnumSet.noneOf(Part.class);
        JsonObject root = new JsonObject();
        JsonObject there = parts.isEmpty() ? null : ConfigManager.read(file(name)).orElse(null);
        if (there != null) {
            if (SaveConversion.isOlder(there)) {
                ChatUtil.error(olderLine(name));
                return;
            }
            held.addAll(heldBy(there));
            held.removeAll(parts);
            root = config.pick(there, held);
        }
        Set<Part> saved = parts.isEmpty() ? Part.OWN : parts;
        ConfigManager.merge(root, config.buildRoot(saved));
        held.addAll(saved);
        JsonArray listed = new JsonArray();
        held.forEach(part -> listed.add(part.word()));
        root.add(PARTS, listed);
        if (!DataFiles.writeJson(file(name), root)) {
            ChatUtil.error("The profile " + name + " could not be saved.");
        } else if (parts.isEmpty()) {
            ChatUtil.message("§7Saved the profile §b" + name + "§7.");
        } else {
            ChatUtil.message("§7Saved §f" + worded(parts) + "§7 into the profile §b" + name + "§7.");
        }
    }

    // A profile saved below this version reads right only as a whole.
    public boolean load(String name, Set<Part> wanted) {
        JsonObject root = ConfigManager.read(file(name)).orElse(null);
        if (root == null) {
            ChatUtil.error("There is no profile called " + name + ".");
            return false;
        }
        if (!wanted.isEmpty() && SaveConversion.isOlder(root)) {
            ChatUtil.error(olderLine(name));
            return false;
        }
        Set<Part> held = heldBy(root);
        Set<Part> missing = EnumSet.noneOf(Part.class);
        missing.addAll(wanted);
        missing.removeAll(held);
        if (!missing.isEmpty()) {
            ChatUtil.error("The profile " + name + " holds no " + worded(missing).replace(" and ", " or ") + ".");
            return false;
        }
        config.applyProfile(root, wanted.isEmpty() ? held : wanted);
        if (wanted.isEmpty()) {
            ChatUtil.message("§7Loaded the profile §b" + name + "§7.");
        } else {
            ChatUtil.message("§7Loaded §f" + worded(wanted) + "§7 from the profile §b" + name + "§7.");
        }
        return true;
    }

    // A profile that lists no parts holds what the config holds.
    private static Set<Part> heldBy(JsonObject root) {
        if (!root.has(PARTS) || !root.get(PARTS).isJsonArray()) {
            return Part.OWN;
        }
        Set<Part> held = EnumSet.noneOf(Part.class);
        for (String word : DataFiles.entries(root.get(PARTS), JsonElement::getAsString, "profile parts")) {
            Part part = Part.named(word);
            if (part != null) {
                held.add(part);
            }
        }
        return held;
    }

    public boolean delete(String name) {
        try {
            if (!Files.deleteIfExists(file(name))) {
                return false;
            }
        } catch (IOException e) {
            OfflineClient.LOG.error("Failed to delete the profile {}", name, e);
            return false;
        }
        if (joins.values().removeIf(name::equalsIgnoreCase)) {
            config.saveSoon();
        }
        return true;
    }

    public boolean exists(String name) {
        return Files.exists(file(name));
    }

    public String joinProfile(String server) {
        return joins.get(server);
    }

    public List<String> joinServers(String name) {
        List<String> servers = new ArrayList<>();
        joins.forEach((server, profile) -> {
            if (profile.equalsIgnoreCase(name)) {
                servers.add(server);
            }
        });
        return servers;
    }

    public void setJoinProfile(String server, String name) {
        if (name == null) {
            joins.remove(server);
        } else {
            joins.put(server, name);
        }
        config.saveSoon();
    }

    void forgetJoins() {
        joins.clear();
    }

    // The login packet of a join has just built the player. The profile of the server goes in
    // before that player ticks and before a join macro or a trigger acts on the join.
    public void onLogin() {
        joinWaits = true;
        loadForJoin();
    }

    // A join that arrived whilst the client slept gets its profile once the client wakes.
    void tick() {
        loadForJoin();
    }

    private void loadForJoin() {
        if (!joinWaits || Modules.dormant()) {
            return;
        }
        joinWaits = false;
        if (OfflineClient.MC.player == null) {
            return;
        }
        String name = joins.get(ServerInfo.key());
        if (name != null && load(name, EnumSet.noneOf(Part.class))) {
            ChatUtil.message("§7It loads every time you join here.");
        }
    }

    JsonObject joinsToJson() {
        JsonObject saved = new JsonObject();
        joins.forEach(saved::addProperty);
        return saved;
    }

    void joinsFromJson(JsonElement saved) {
        if (saved.isJsonObject()) {
            joins.putAll(DataFiles.members(saved, (server, name) -> name.getAsString(), "join profiles"));
        }
    }

    private Path file(String name) {
        return DataFiles.jsonFile(folder, DataFiles.safeName(name));
    }
}
