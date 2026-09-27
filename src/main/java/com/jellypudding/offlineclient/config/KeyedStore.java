package com.jellypudding.offlineclient.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.jellypudding.offlineclient.setting.KeybindSetting;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

// Entries saved to a file under a name with an optional key. Names match whatever their
// case and an entry replaces the one already saved under its name.
public abstract class KeyedStore<T extends KeyedStore.Keyed<T>> {

    public interface Keyed<T> {
        String name();

        int key();

        T withKey(int key);
    }

    // Builds an entry from its name and key and the rest of what was saved for it.
    protected interface Reader<T> {
        T read(String name, int key, JsonObject saved);
    }

    private final Path file;
    private final BiConsumer<T, JsonObject> writer;
    private final List<T> entries = new ArrayList<>();

    protected KeyedStore(String fileName, Reader<T> reader, BiConsumer<T, JsonObject> writer) {
        this.file = DataFiles.path(fileName);
        this.writer = writer;
        DataFiles.readJson(file, root -> decode(root, reader)).ifPresent(entries::addAll);
    }

    public List<T> all() {
        return List.copyOf(entries);
    }

    public void add(T entry) {
        entries.removeIf(existing -> existing.name().equalsIgnoreCase(entry.name()));
        entries.add(entry);
        save();
    }

    public boolean remove(String name) {
        boolean removed = entries.removeIf(entry -> entry.name().equalsIgnoreCase(name));
        if (removed) {
            save();
        }
        return removed;
    }

    // Null when nothing answers to that name.
    public T find(String name) {
        for (T entry : entries) {
            if (entry.name().equalsIgnoreCase(name)) {
                return entry;
            }
        }
        return null;
    }

    // The key of whatever is saved under the name. Saving over an entry keeps its key.
    public int keyOf(String name) {
        T entry = find(name);
        return entry == null ? KeybindSetting.UNBOUND : entry.key();
    }

    // Every entry a press of the key sets off.
    public List<T> boundTo(int key) {
        List<T> bound = new ArrayList<>();
        if (key == KeybindSetting.UNBOUND) {
            return bound;
        }
        for (T entry : entries) {
            if (entry.key() == key) {
                bound.add(entry);
            }
        }
        return bound;
    }

    private static <E> List<E> decode(JsonElement root, Reader<E> reader) {
        List<E> decoded = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray()) {
            JsonObject object = element.getAsJsonObject();
            int key = object.has("key") ? object.get("key").getAsInt() : KeybindSetting.UNBOUND;
            decoded.add(reader.read(object.get("name").getAsString(), key, object));
        }
        return decoded;
    }

    private void save() {
        JsonArray root = new JsonArray();
        for (T entry : entries) {
            JsonObject object = new JsonObject();
            object.addProperty("name", entry.name());
            object.addProperty("key", entry.key());
            writer.accept(entry, object);
            root.add(object);
        }
        DataFiles.writeJson(file, root);
    }
}
