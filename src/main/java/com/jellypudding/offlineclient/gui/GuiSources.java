package com.jellypudding.offlineclient.gui;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.config.MacroStore;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.modules.misc.ClickGuiModule;
import com.jellypudding.offlineclient.setting.KeybindSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.SearchRank;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// What the tabs read and write. The client owns all of it already.
public final class GuiSources {

    private static final String NO_KEY = "no key";

    private GuiSources() {
    }

    public static TabView.Source friends() {
        return new TabView.Source() {
            @Override
            public List<TabView.Entry> entries(String query) {
                List<TabView.Entry> rows = new ArrayList<>();
                List<String> names = new ArrayList<>(
                    OfflineClient.INSTANCE.getFriendManager().getAll());
                names.sort(String.CASE_INSENSITIVE_ORDER);
                for (String name : names) {
                    if (matches(name, query)) {
                        rows.add(new TabView.Entry(name, "", "Friends are never targeted."));
                    }
                }
                return rows;
            }

            @Override
            public String emptyText(String query) {
                return query.isEmpty() ? "nobody on the list yet"
                    : "press enter to add " + firstWord(query);
            }

            @Override
            public String addHint() {
                return "type a name and press enter";
            }

            @Override
            public void add(String text) {
                String name = firstWord(text);
                if (isSelf(name)) {
                    ChatUtil.error("You cannot add yourself as a friend.");
                    return;
                }
                if (OfflineClient.INSTANCE.getFriendManager().add(name)) {
                    OfflineClient.INSTANCE.getConfigManager().saveSoon();
                }
            }

            @Override
            public void drop(String name) {
                OfflineClient.INSTANCE.getFriendManager().remove(name);
                OfflineClient.INSTANCE.getConfigManager().saveSoon();
            }
        };
    }

    // Every key the client answers to in one place. Macros first and then
    // the modules that carry a bind.
    public static TabView.Source macros() {
        return new TabView.Source() {
            @Override
            public List<TabView.Entry> entries(String query) {
                List<TabView.Entry> rows = new ArrayList<>();
                for (MacroStore.Macro macro : MacroStore.get().all()) {
                    if (matches(macro.name(), query)) {
                        rows.add(new TabView.Entry(macro.name(), keyName(macro.key()),
                            "Runs " + String.join(" then ", macro.lines())));
                    }
                }
                for (Module module : bindable(query)) {
                    rows.add(new TabView.Entry(module.getName(),
                        keyName(module.getKeybind().getValue()), module.getDescription()));
                }
                return rows;
            }

            // Without a query only the modules already on a key are listed.
            private List<Module> bindable(String query) {
                List<Module> all = OfflineClient.INSTANCE.getModuleManager().getAll();
                if (query.isEmpty()) {
                    List<Module> bound = new ArrayList<>();
                    for (Module module : all) {
                        if (module.getKeybind().getValue() != KeybindSetting.UNBOUND) {
                            bound.add(module);
                        }
                    }
                    return bound;
                }
                return SearchRank.rank(all, module -> module.searchScore(query));
            }

            @Override
            public String emptyText(String query) {
                if (query.isEmpty()) {
                    return "nothing on a key yet";
                }
                return query.indexOf(' ') > 0
                    ? "press enter to save the macro " + firstWord(query)
                    : "no module or macro called " + query;
            }

            @Override
            public String footer() {
                return "click a key to change it";
            }

            @Override
            public String addHint() {
                return "find a module or add a macro";
            }

            // Only a fresh setup gets the guide.
            @Override
            public List<TabView.Hint> hints(String query) {
                if (!query.isEmpty() || !MacroStore.get().all().isEmpty() || hasOwnBinds()) {
                    return List.of();
                }
                String prefix = OfflineClient.INSTANCE.getCommandManager().getPrefix();
                return List.of(
                    new TabView.Hint("Bind a module",
                        "Type its name then click no key and press any key.",
                        List.of("killaura")),
                    new TabView.Hint("Add a macro",
                        "Type a name then what it sends and press enter. Chat and commands both work.",
                        List.of("home /home", "fly " + prefix + "toggle flight")));
            }

            // A name on its own is a search. A name and a line is a macro.
            @Override
            public void add(String text) {
                int gap = text.indexOf(' ');
                if (gap < 1) {
                    return;
                }
                String name = text.substring(0, gap);
                String line = text.substring(gap + 1).trim();
                if (line.isEmpty()) {
                    return;
                }
                MacroStore.Macro existing = MacroStore.get().find(name);
                int key = existing == null ? KeybindSetting.UNBOUND : existing.key();
                MacroStore.get().add(new MacroStore.Macro(name, key, List.of(line)));
            }

            @Override
            public void activate(String name) {
                MacroStore.Macro macro = MacroStore.get().find(name);
                if (macro != null) {
                    MacroStore.get().run(macro);
                    return;
                }
                Module module = moduleNamed(name);
                if (module != null && module.isTogglable()) {
                    module.toggle();
                }
            }

            @Override
            public boolean bindable() {
                return true;
            }

            @Override
            public void bind(String name, int key) {
                MacroStore.Macro macro = MacroStore.get().find(name);
                if (macro != null) {
                    MacroStore.get().add(macro.withKey(key));
                    return;
                }
                Module module = moduleNamed(name);
                if (module != null) {
                    module.getKeybind().setValue(key);
                    OfflineClient.INSTANCE.getConfigManager().saveSoon();
                }
            }

            @Override
            public String dropHint(String name) {
                return MacroStore.get().find(name) == null
                    ? "Take the key off " + name : "Remove the macro " + name;
            }

            // A macro goes for good. A module only loses its key.
            @Override
            public void drop(String name) {
                if (MacroStore.get().remove(name)) {
                    return;
                }
                Module module = moduleNamed(name);
                if (module != null) {
                    module.getKeybind().setValue(KeybindSetting.UNBOUND);
                    OfflineClient.INSTANCE.getConfigManager().saveSoon();
                }
            }
        };
    }

    // The layout is written out before a profile is saved and the screen is
    // built again after one is loaded.
    public static TabView.Source profiles(Runnable beforeSave, Runnable afterLoad) {
        return new TabView.Source() {
            @Override
            public List<TabView.Entry> entries(String query) {
                List<TabView.Entry> rows = new ArrayList<>();
                for (String name : OfflineClient.INSTANCE.getConfigManager().listProfiles()) {
                    if (matches(name, query)) {
                        rows.add(new TabView.Entry(name, "", "Load " + name));
                    }
                }
                return rows;
            }

            @Override
            public String emptyText(String query) {
                return query.isEmpty() ? "no saved setups yet"
                    : "press enter to save as " + profileName(query);
            }

            @Override
            public String footer() {
                return "click a name to load it";
            }

            @Override
            public String addHint() {
                return "save this setup as";
            }

            @Override
            public void add(String text) {
                String name = profileName(text);
                beforeSave.run();
                OfflineClient.INSTANCE.getConfigManager().saveProfile(name);
                ChatUtil.message("§7Saved the profile §b" + name + "§7.");
            }

            @Override
            public void activate(String name) {
                if (OfflineClient.INSTANCE.getConfigManager().loadProfile(name)) {
                    ChatUtil.message("§7Loaded the profile §b" + name + "§7.");
                    afterLoad.run();
                }
            }

            @Override
            public void drop(String name) {
                if (OfflineClient.INSTANCE.getConfigManager().deleteProfile(name)) {
                    ChatUtil.message("§7Deleted the profile §b" + name + "§7.");
                }
            }
        };
    }

    private static String firstWord(String text) {
        return text.trim().split("\\s+")[0];
    }

    // Profiles are saved as files. The name is kept to one plain word.
    private static String profileName(String text) {
        return text.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
    }

    // The ClickGUI starts on a key and does not count.
    private static boolean hasOwnBinds() {
        for (Module module : OfflineClient.INSTANCE.getModuleManager().getAll()) {
            if (!(module instanceof ClickGuiModule)
                && module.getKeybind().getValue() != KeybindSetting.UNBOUND) {
                return true;
            }
        }
        return false;
    }

    private static String keyName(int key) {
        return key == KeybindSetting.UNBOUND ? NO_KEY : KeybindSetting.nameOfKey(key);
    }

    private static Module moduleNamed(String name) {
        return OfflineClient.INSTANCE.getModuleManager().get(name);
    }

    private static boolean matches(String name, String query) {
        return query.isEmpty() || SearchRank.score(name, query) != SearchRank.NO_MATCH;
    }

    private static boolean isSelf(String name) {
        return OfflineClient.MC.player != null
            && name.equalsIgnoreCase(OfflineClient.MC.player.getGameProfile().name());
    }
}
