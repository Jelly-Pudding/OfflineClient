package com.jellypudding.offlineclient.config;

import com.google.gson.JsonObject;
import com.jellypudding.offlineclient.util.ProxyTunnel;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Function;

// Values are saved as json text because a tool that reads json numbers as doubles would round a
// long seed.
public final class ServerStore<T> {

    private static ServerStore<String> logins;
    private static ServerStore<ProxyTunnel.Login> proxyLogins;
    private static ServerStore<String> modelKeys;
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

    // They stay out of config.json because players hand their configs to each other.
    public static synchronized ServerStore<String> logins() {
        if (logins == null) {
            logins = new ServerStore<>("logins.json", Function.identity(), Function.identity());
        }
        return logins;
    }

    public static synchronized ServerStore<ProxyTunnel.Login> proxyLogins() {
        if (proxyLogins == null) {
            proxyLogins = new ServerStore<>("proxies.json", ProxyTunnel.Login::parse, ProxyTunnel.Login::text);
        }
        return proxyLogins;
    }

    public static synchronized ServerStore<String> modelKeys() {
        if (modelKeys == null) {
            modelKeys = new ServerStore<>("modelkeys.json", Function.identity(), Function.identity());
        }
        return modelKeys;
    }

    public static synchronized ServerStore<Long> seeds() {
        if (seeds == null) {
            seeds = new ServerStore<>("seeds.json", Long::parseLong, String::valueOf);
        }
        return seeds;
    }

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

    public void edit(Consumer<Map<String, T>> change) {
        change.accept(values);
        save();
    }

    public List<String> servers() {
        return List.copyOf(values.keySet());
    }

    private void load() {
        DataFiles.readJson(file, root -> DataFiles.members(root, (server, value) -> parse.apply(value.getAsString()),
            "entries of " + file.getFileName())).ifPresent(values::putAll);
    }

    private void save() {
        JsonObject root = new JsonObject();
        values.forEach((server, value) -> root.addProperty(server, format.apply(value)));
        DataFiles.writeJson(file, root);
    }
}
