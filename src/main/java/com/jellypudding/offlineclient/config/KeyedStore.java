package com.jellypudding.offlineclient.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.jellypudding.offlineclient.event.events.KeyPressEvent;
import com.jellypudding.offlineclient.setting.Bind;
import com.mojang.blaze3d.platform.InputConstants;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

// Entries saved to a file under a name with an optional key. Names match whatever their
// case and an entry replaces the one already saved under its name.
public abstract class KeyedStore<T extends KeyedStore.Keyed<T>> {

    public interface Keyed<T> {
        String name();

        Bind key();

        T withKey(Bind key);
    }

    // Builds an entry from its name and key and the rest of what was saved for it.
    protected interface Reader<T> {
        T read(String name, Bind key, JsonObject saved);
    }

    private final Path file;
    private final Reader<T> reader;
    private final BiConsumer<T, JsonObject> writer;
    private final List<T> entries = new ArrayList<>();

    protected KeyedStore(String fileName, Reader<T> reader, BiConsumer<T, JsonObject> writer) {
        this.file = DataFiles.path(fileName);
        this.reader = reader;
        this.writer = writer;
        DataFiles.readJson(file, root -> decode(root, reader, fileName)).ifPresent(entries::addAll);
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
    public Bind keyOf(String name) {
        T entry = find(name);
        return entry == null ? Bind.NONE : entry.key();
    }

    // Every entry the press sets off.
    public List<T> pressedBy(KeyPressEvent event) {
        List<T> pressed = new ArrayList<>();
        if (event.getAction() != InputConstants.PRESS) {
            return pressed;
        }
        for (T entry : entries) {
            if (event.isFor(entry.key())) {
                pressed.add(entry);
            }
        }
        return pressed;
    }

    // An entry that cannot be read is left out.
    private static <E> List<E> decode(JsonElement root, Reader<E> reader, String fileName) {
        return DataFiles.entries(root, element -> {
            JsonObject object = element.getAsJsonObject();
            Bind key = object.has("key") ? Bind.fromJson(object.get("key")) : null;
            return reader.read(object.get("name").getAsString(), key == null ? Bind.NONE : key, object);
        }, "entries of " + fileName);
    }

    // Every entry the way the file holds them. A profile keeps a copy.
    public JsonArray toJson() {
        JsonArray root = new JsonArray();
        for (T entry : entries) {
            JsonObject object = new JsonObject();
            object.addProperty("name", entry.name());
            object.add("key", entry.key().toJson());
            writer.accept(entry, object);
            root.add(object);
        }
        return root;
    }

    // Swaps every entry for the saved ones. Saved text that is no list leaves the store as it is
    // and an entry that cannot be read is left out.
    public void replaceWith(JsonElement saved) {
        List<T> decoded = decode(saved, reader, file.getFileName().toString());
        entries.clear();
        entries.addAll(decoded);
        save();
    }

    private void save() {
        DataFiles.writeJson(file, toJson());
    }
}
