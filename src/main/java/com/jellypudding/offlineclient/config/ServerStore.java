package com.jellypudding.offlineclient.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

// One value per server address kept in its own file under offlineclient. Values are saved
// as json text because a tool that reads json numbers as doubles would round a long seed.
public final class ServerStore<T> {

    private static ServerStore<String> logins;
    private static ServerStore<Long> seeds;

    private final Path file;
    private final Function<String, T> parse;
    private final Function<T, String> format;
    private final Map<String, T> values = new TreeMap<>();

    private ServerStore(String name, Function<String, T> parse, Function<T, String> format) {
        this.file = DataFiles.path(name);
        this.parse = parse;
        this.format = format;
        load();
    }

    // Login passwords. They stay out of config.json because players hand their configs to each other.
    public static synchronized ServerStore<String> logins() {
        if (logins == null) {
            logins = new ServerStore<>("logins.json", Function.identity(), Function.identity());
        }
        return logins;
    }

    // World seeds OreSight works out the ores from.
    public static synchronized ServerStore<Long> seeds() {
        if (seeds == null) {
            seeds = new ServerStore<>("seeds.json", Long::parseLong, String::valueOf);
        }
        return seeds;
    }

    // Null when nothing is saved for that server.
    public T get(String server) {
        return values.get(server);
    }

    public void set(String server, T value) {
        values.put(server, value);
        save();
    }

    public boolean remove(String server) {
        boolean removed = values.remove(server) != null;
        if (removed) {
            save();
        }
        return removed;
    }

    // Every server with a saved value from A to Z.
    public List<String> servers() {
        return List.copyOf(values.keySet());
    }

    private void load() {
        DataFiles.readJson(file, this::decode).ifPresent(values::putAll);
    }

    private Map<String, T> decode(JsonElement root) {
        Map<String, T> decoded = new TreeMap<>();
        for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject().entrySet()) {
            decoded.put(entry.getKey(), parse.apply(entry.getValue().getAsString()));
        }
        return decoded;
    }

    private void save() {
        JsonObject root = new JsonObject();
        values.forEach((server, value) -> root.addProperty(server, format.apply(value)));
        DataFiles.writeJson(file, root);
    }
}
