package com.jellypudding.offlineclient.gui;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.config.MacroStore;
import com.jellypudding.offlineclient.config.NameStore;
import com.jellypudding.offlineclient.config.WaypointStore;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.modules.misc.ClickGuiModule;
import com.jellypudding.offlineclient.path.Travel;
import com.jellypudding.offlineclient.setting.KeybindSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.KeyUse;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.SearchRank;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

// What the tabs read and write. The client owns all of it already.
public final class GuiSources {

    private static final String NO_KEY = "no key";
    private static final String IN_USE = "in use";
    private static final String ACCOUNT = "account";
    // A waypoint typed with its spot is a name and three coordinates.
    private static final int SPOT_WORDS = 4;

    private GuiSources() {
    }

    // What a tab opens. Null for the HUD tab which opens the HUD editor in its place.
    public static TabView.Source of(TabStrip.Tab tab, Runnable beforeSave, Runnable afterLoad) {
        return switch (tab) {
            case FRIENDS -> friends();
            case MACROS -> macros();
            case WAYPOINTS -> waypoints();
            case NAMES -> names();
            case HUD -> null;
            case PROFILES -> profiles(beforeSave, afterLoad);
        };
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
                        String join = macro.joinText() == null ? "" : " " + macro.joinText();
                        rows.add(keyRow(macro.name(), macro.key(), macro,
                            "Runs " + String.join(" then ", macro.lines()) + join + "."));
                    }
                }
                for (Module module : bindable(query)) {
                    rows.add(keyRow(module.getName(), module.getKeybind().getValue(), module.getKeybind(),
                        module.getDescription()));
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
                MacroStore.get().add(existing == null
                    ? new MacroStore.Macro(name, KeybindSetting.UNBOUND, List.of(line))
                    : existing.withLines(List.of(line)));
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
                    KeyUse.warn(key, macro);
                    return;
                }
                Module module = moduleNamed(name);
                if (module != null) {
                    module.getKeybind().setValue(key);
                    OfflineClient.INSTANCE.getConfigManager().saveSoon();
                    KeyUse.warn(key, module.getKeybind());
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

    // The waypoints of the world you are in from A to Z. A click walks there and closes the GUI.
    public static TabView.Source waypoints() {
        return new TabView.Source() {
            @Override
            public List<TabView.Entry> entries(String query) {
                List<WaypointStore.Waypoint> here = new ArrayList<>(WaypointStore.get().here());
                here.sort(Comparator.comparing(WaypointStore.Waypoint::name, String.CASE_INSENSITIVE_ORDER));
                List<TabView.Entry> rows = new ArrayList<>();
                for (WaypointStore.Waypoint waypoint : here) {
                    if (matches(waypoint.name(), query)) {
                        rows.add(new TabView.Entry(waypoint.name(),
                            BlockUtil.text(waypoint.pos()),
                            "Walk to " + waypoint.name() + " with the pathfinder."));
                    }
                }
                return rows;
            }

            @Override
            public String emptyText(String query) {
                if (query.isEmpty()) {
                    return "no waypoints in this world yet";
                }
                Mark mark = mark(query);
                if (mark == null) {
                    return "type a name or a name and x y z";
                }
                return "press enter to mark " + mark.name()
                    + (mark.spot() == null ? " where you stand" : " at " + BlockUtil.text(mark.spot()));
            }

            @Override
            public String footer() {
                return "click a waypoint to walk there";
            }

            @Override
            public String addHint() {
                return "type a name and press enter";
            }

            @Override
            public void add(String text) {
                LocalPlayer player = OfflineClient.MC.player;
                if (player == null) {
                    ChatUtil.error("You need to be in a world to mark a waypoint.");
                    return;
                }
                Mark mark = mark(text);
                if (mark == null) {
                    ChatUtil.error("Type a name or a name and x y z.");
                    return;
                }
                BlockPos pos = mark.spot() == null ? player.blockPosition() : mark.spot();
                WaypointStore.get().mark(mark.name(), pos, Modules.nextWaypointHue());
                ChatUtil.message("§aSaved §b" + mark.name() + " §7at §f" + BlockUtil.text(pos));
            }

            @Override
            public void activate(String name) {
                WaypointStore.Waypoint waypoint = WaypointStore.get().find(name);
                if (waypoint == null || !Travel.to(waypoint.pos())) {
                    return;
                }
                Screen open = OfflineClient.MC.gui.screen();
                if (open != null) {
                    open.onClose();
                }
            }

            @Override
            public String dropHint(String name) {
                return "Remove the waypoint " + name;
            }

            @Override
            public void drop(String name) {
                WaypointStore.get().remove(name);
            }
        };
    }

    // The names you join offline servers with. Your account name heads the list.
    public static TabView.Source names() {
        return new TabView.Source() {
            @Override
            public List<TabView.Entry> entries(String query) {
                NameStore store = NameStore.get();
                String account = NameStore.accountName();
                String joining = store.joinName();
                List<TabView.Entry> rows = new ArrayList<>();
                if (matches(account, query)) {
                    rows.add(new TabView.Entry(account, account.equals(joining) ? IN_USE : ACCOUNT,
                        "Your account name. An online mode server takes no other."));
                }
                for (String name : store.saved()) {
                    if (matches(name, query)) {
                        rows.add(new TabView.Entry(name, name.equals(joining) ? IN_USE : "",
                            "Joins offline servers as " + name + " from your next join."));
                    }
                }
                return rows;
            }

            @Override
            public String emptyText(String query) {
                String word = firstWord(query);
                if (word.equalsIgnoreCase(NameStore.RANDOM)) {
                    return "press enter for a made up name";
                }
                return NameStore.valid(word) ? "press enter to join as " + word
                    : "a name has 3 to 16 letters or digits or underscores";
            }

            @Override
            public String footer() {
                return "click a name to join as it";
            }

            @Override
            public String addHint() {
                return "type a name and press enter";
            }

            // Only shown until a name is saved.
            @Override
            public List<TabView.Hint> hints(String query) {
                if (!query.isEmpty() || !NameStore.get().saved().isEmpty()) {
                    return List.of();
                }
                return List.of(new TabView.Hint("Add a name",
                    "Type a name and press enter. The word random makes one up.", List.of(NameStore.RANDOM)));
            }

            @Override
            public void add(String text) {
                String name = NameStore.named(firstWord(text));
                if (name == null) {
                    ChatUtil.error(NameStore.RULE);
                    return;
                }
                NameStore.get().use(name);
            }

            // Your account name puts you back on it.
            @Override
            public void activate(String name) {
                NameStore.get().use(name);
            }

            @Override
            public String dropHint(String name) {
                return name.equals(NameStore.accountName()) ? "Your account name always stays"
                    : "Forget the name " + name;
            }

            @Override
            public void drop(String name) {
                NameStore.get().remove(name);
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

    // A waypoint typed in the tab. A name alone has no spot and marks where you stand.
    private record Mark(String name, BlockPos spot) {
    }

    // A name alone or a name and x y z. Null for any other text.
    private static Mark mark(String text) {
        String[] words = text.trim().split("\\s+");
        if (words.length == 1) {
            return new Mark(words[0], null);
        }
        if (words.length != SPOT_WORDS) {
            return null;
        }
        try {
            return new Mark(words[0], BlockPos.containing(Double.parseDouble(words[1]),
                Double.parseDouble(words[2]), Double.parseDouble(words[3])));
        } catch (NumberFormatException e) {
            return null;
        }
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

    // A row whose detail is a key. A key something else answers to as well is drawn as a warning
    // and the tip names what else uses it.
    private static TabView.Entry keyRow(String name, int key, Object self, String tip) {
        String also = KeyUse.alsoUsedBy(key, self);
        return new TabView.Entry(name, keyName(key), tip + also, !also.isEmpty());
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
