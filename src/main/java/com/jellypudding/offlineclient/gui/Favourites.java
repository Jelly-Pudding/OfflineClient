package com.jellypudding.offlineclient.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.module.Module;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

// The modules the user starred. Both ClickGUI styles read the same list and
// it lives in the saved layout beside them.
public final class Favourites {

    private static final String KEY = "favourites";

    private static final Set<String> names = new LinkedHashSet<>();
    private static JsonObject source;
    private static int version;

    private Favourites() {
    }

    private static Set<String> names() {
        JsonObject gui = OfflineClient.INSTANCE.getConfigManager().getGuiState();
        if (gui == source) {
            return names;
        }
        source = gui;
        names.clear();
        if (gui.has(KEY) && gui.get(KEY).isJsonArray()) {
            for (var name : gui.getAsJsonArray(KEY)) {
                names.add(name.getAsString());
            }
        }
        version++;
        return names;
    }

    public static boolean has(Module module) {
        return names().contains(module.getName());
    }

    public static void toggle(Module module) {
        if (!names().remove(module.getName())) {
            names().add(module.getName());
        }
        version++;
        save();
        OfflineClient.INSTANCE.getConfigManager().saveSoon();
    }

    // Bumped on every change. A panel watches it to know when to rebuild.
    public static int version() {
        names();
        return version;
    }

    public static List<Module> modules() {
        List<Module> picked = new ArrayList<>();
        for (Module module : OfflineClient.INSTANCE.getModuleManager().getAll()) {
            if (names().contains(module.getName())) {
                picked.add(module);
            }
        }
        return picked;
    }

    private static void save() {
        JsonArray array = new JsonArray();
        names().forEach(array::add);
        OfflineClient.INSTANCE.getConfigManager().getGuiState().add(KEY, array);
    }
}
