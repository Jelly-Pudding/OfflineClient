package com.jellypudding.offlineclient.setting;

import com.google.gson.JsonElement;
import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.util.Flash;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

// A change can be taken back until its settings move on or the game closes. Everything runs on the
// client thread.
public final class Defaults {

    private static final int REMEMBERED = 32;

    // Newest first. Never saved.
    private static final Deque<Change> HISTORY = new ArrayDeque<>();

    private static final Map<Setting<?>, Long> TOUCHED = new IdentityHashMap<>();

    // Told after every reset and edit and undo which settings it changed. The HUD listens. A
    // switch one of those flips was not flipped by the player's own hand.
    private static final List<Consumer<Collection<Setting<?>>>> LISTENERS = new ArrayList<>();

    private static int nextId = 1;

    private Defaults() {
    }

    // A null after stands for the default.
    private record Entry(Setting<?> setting, JsonElement before, JsonElement after) {

        // False once the player has moved the setting on.
        private boolean holds() {
            return after == null ? setting.isDefault() : after.equals(setting.toJson());
        }
    }

    public static final class Change {

        private final int id;
        private final String name;
        private final List<Entry> entries;

        private Change(int id, String name, List<Entry> entries) {
            this.id = id;
            this.name = name;
            this.entries = entries;
        }

        public int id() {
            return id;
        }

        public String name() {
            return name;
        }

        public int size() {
            return entries.size();
        }
    }

    public record Undone(String name, int restored, int kept) {
    }

    public static int changed(Collection<? extends Setting<?>> settings) {
        int changed = 0;
        for (Setting<?> setting : settings) {
            if (setting.isChanged()) {
                changed++;
            }
        }
        return changed;
    }

    // Null when no setting in scope differed from its default.
    public static Change reset(String name, Collection<? extends Setting<?>> scope) {
        List<Entry> entries = new ArrayList<>();
        for (Setting<?> setting : scope) {
            if (setting.isChanged()) {
                entries.add(new Entry(setting, setting.toJson(), null));
                setting.reset();
                touch(setting);
            }
        }
        return remember(name, entries);
    }

    // For settings whose own defaults are not the place to go. The name is asked for once the edit
    // has run. Null when the edit changed nothing.
    public static Change reset(Supplier<String> name, Collection<? extends Setting<?>> scope, Runnable edit) {
        List<Entry> entries = changedBy(scope, edit);
        for (Entry entry : entries) {
            touch(entry.setting());
        }
        return entries.isEmpty() ? null : remember(name.get(), entries);
    }

    // Null when the edit changed nothing.
    public static Change record(String name, Setting<?> setting, Runnable edit) {
        return remember(name, changedBy(List.of(setting), edit));
    }

    private static List<Entry> changedBy(Collection<? extends Setting<?>> scope, Runnable edit) {
        List<JsonElement> before = new ArrayList<>();
        for (Setting<?> setting : scope) {
            before.add(setting.toJson());
        }
        edit.run();
        List<Entry> entries = new ArrayList<>();
        Iterator<JsonElement> held = before.iterator();
        for (Setting<?> setting : scope) {
            JsonElement was = held.next();
            JsonElement after = setting.toJson();
            if (!was.equals(after)) {
                entries.add(new Entry(setting, was, after));
            }
        }
        return entries;
    }

    public static void listen(Consumer<Collection<Setting<?>>> listener) {
        LISTENERS.add(listener);
    }

    private static Change remember(String name, List<Entry> entries) {
        if (entries.isEmpty()) {
            return null;
        }
        Change change = new Change(nextId++, name, entries);
        HISTORY.addFirst(change);
        while (HISTORY.size() > REMEMBERED) {
            HISTORY.removeLast();
        }
        List<Setting<?>> changed = new ArrayList<>();
        for (Entry entry : entries) {
            changed.add(entry.setting());
        }
        saveAndTell(changed);
        return change;
    }

    public static boolean canUndo(Setting<?> setting) {
        for (Change change : HISTORY) {
            for (Entry entry : change.entries) {
                if (entry.setting() == setting) {
                    return entry.holds();
                }
            }
        }
        return false;
    }

    public static boolean canUndo(Change change) {
        if (!HISTORY.contains(change)) {
            return false;
        }
        for (Entry entry : change.entries) {
            if (entry.holds()) {
                return true;
            }
        }
        return false;
    }

    public static int undo(Collection<? extends Setting<?>> settings) {
        List<Setting<?>> restored = new ArrayList<>();
        for (Setting<?> setting : settings) {
            if (undoNewestOf(setting)) {
                restored.add(setting);
            }
        }
        if (!restored.isEmpty()) {
            saveAndTell(restored);
        }
        return restored.size();
    }

    // A setting taken back alone leaves the rest of its change to be taken back later.
    private static boolean undoNewestOf(Setting<?> setting) {
        for (Iterator<Change> changes = HISTORY.iterator(); changes.hasNext(); ) {
            Change change = changes.next();
            for (Iterator<Entry> entries = change.entries.iterator(); entries.hasNext(); ) {
                Entry entry = entries.next();
                if (entry.setting() != setting) {
                    continue;
                }
                if (!entry.holds()) {
                    return false;
                }
                restore(entry);
                entries.remove();
                if (change.entries.isEmpty()) {
                    changes.remove();
                }
                return true;
            }
        }
        return false;
    }

    // Null when the history does not hold the change.
    public static Undone undo(Change change) {
        if (change == null || !HISTORY.remove(change)) {
            return null;
        }
        List<Setting<?>> restored = new ArrayList<>();
        for (Entry entry : change.entries) {
            if (entry.holds()) {
                restore(entry);
                restored.add(entry.setting());
            }
        }
        saveAndTell(restored);
        return new Undone(change.name(), restored.size(), change.size() - restored.size());
    }

    public static Undone undoNewest() {
        return undo(HISTORY.peekFirst());
    }

    public static Change find(int id) {
        for (Change change : HISTORY) {
            if (change.id() == id) {
                return change;
            }
        }
        return null;
    }

    // One just after a setting was put back or brought back and down to nought as it settles.
    public static float freshness(Setting<?> setting) {
        Long at = TOUCHED.get(setting);
        return at == null ? 0f : Flash.strength(at + Flash.SHOWN_MS);
    }

    public static float freshness(Collection<? extends Setting<?>> settings) {
        float freshest = 0f;
        for (Setting<?> setting : settings) {
            freshest = Math.max(freshest, freshness(setting));
        }
        return freshest;
    }

    // A profile load or a reset of everything leaves nothing to take back.
    public static void forget() {
        HISTORY.clear();
        TOUCHED.clear();
    }

    private static void restore(Entry entry) {
        entry.setting().fromJson(entry.before());
        touch(entry.setting());
    }

    private static void touch(Setting<?> setting) {
        TOUCHED.put(setting, System.currentTimeMillis());
    }

    private static void saveAndTell(Collection<Setting<?>> changed) {
        long now = System.currentTimeMillis();
        TOUCHED.values().removeIf(at -> at + Flash.SHOWN_MS < now);
        OfflineClient.INSTANCE.getConfigManager().saveSoon();
        LISTENERS.forEach(listener -> listener.accept(changed));
    }
}
