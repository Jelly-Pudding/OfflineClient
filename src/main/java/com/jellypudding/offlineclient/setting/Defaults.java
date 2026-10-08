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

// Puts settings back to how they started and remembers what they held. A change can be
// taken back until its settings move on or the game closes. Everything runs on the client thread.
public final class Defaults {

    // How many changes Ctrl Z can walk back through.
    private static final int REMEMBERED = 32;

    // Newest first. Never saved.
    private static final Deque<Change> HISTORY = new ArrayDeque<>();

    // When each setting was last put back or brought back. The GUI lights those rows up.
    private static final Map<Setting<?>, Long> TOUCHED = new IdentityHashMap<>();

    private static int nextId = 1;

    private Defaults() {
    }

    // What a setting held before a change and what the change left. A null after stands
    // for its default.
    private record Entry(Setting<?> setting, JsonElement before, JsonElement after) {

        // False once the player has moved the setting on.
        private boolean holds() {
            return after == null ? setting.isDefault() : after.equals(setting.toJson());
        }
    }

    // One reset or one bulk edit of a list. Chat names it and a link takes it back by its id.
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

        // How many settings it changed.
        public int size() {
            return entries.size();
        }
    }

    // What an undo brought back and how many settings it left alone because they had moved on.
    public record Undone(String name, int restored, int kept) {
    }

    // How many of the settings differ from their defaults.
    public static int changed(Collection<? extends Setting<?>> settings) {
        int changed = 0;
        for (Setting<?> setting : settings) {
            if (setting.isChanged()) {
                changed++;
            }
        }
        return changed;
    }

    // Puts back every setting in scope that differs from its default as one change. Null when
    // none did.
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

    // Runs an edit on a setting and remembers what it held before. Null when the edit changed
    // nothing.
    public static Change record(String name, Setting<?> setting, Runnable edit) {
        JsonElement before = setting.toJson();
        edit.run();
        JsonElement after = setting.toJson();
        List<Entry> entries = new ArrayList<>();
        if (!before.equals(after)) {
            entries.add(new Entry(setting, before, after));
        }
        return remember(name, entries);
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
        settled();
        return change;
    }

    // True whilst the newest change of the setting still holds and can be taken back.
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

    // True whilst the change is remembered and any of its settings still holds.
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

    // Takes back the newest change of each setting where it still holds. Hands back how many
    // settings came back.
    public static int undo(Collection<? extends Setting<?>> settings) {
        int restored = 0;
        for (Setting<?> setting : settings) {
            if (undoNewestOf(setting)) {
                restored++;
            }
        }
        if (restored > 0) {
            settled();
        }
        return restored;
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

    // Takes back a whole change and forgets it. Null when the history does not hold it.
    public static Undone undo(Change change) {
        if (change == null || !HISTORY.remove(change)) {
            return null;
        }
        int restored = 0;
        for (Entry entry : change.entries) {
            if (entry.holds()) {
                restore(entry);
                restored++;
            }
        }
        settled();
        return new Undone(change.name(), restored, change.size() - restored);
    }

    // The newest change. Null when nothing is remembered.
    public static Undone undoNewest() {
        return undo(HISTORY.peekFirst());
    }

    // Null when no remembered change has that id.
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

    // The freshest of the settings.
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

    // Every reset and undo saves. Rows that finished lighting up are dropped.
    private static void settled() {
        long now = System.currentTimeMillis();
        TOUCHED.values().removeIf(at -> at + Flash.SHOWN_MS < now);
        OfflineClient.INSTANCE.getConfigManager().saveSoon();
    }
}
