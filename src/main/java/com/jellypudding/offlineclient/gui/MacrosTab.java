package com.jellypudding.offlineclient.gui;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.config.MacroLine;
import com.jellypudding.offlineclient.config.MacroStore;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.modules.misc.ClickGuiModule;
import com.jellypudding.offlineclient.setting.Bind;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.KeyUse;
import com.jellypudding.offlineclient.util.SearchRank;

import java.util.ArrayList;
import java.util.List;

// Every key the client answers to in one place. Macros first and then
// the modules that carry a bind.
final class MacrosTab implements TabView.Source {

    private static final String NO_KEY = "no key";
    private static final String HELD_TIP = " On only whilst its key is held.";
    private static final String STOP_TIP = " Still running. Click to stop it.";

    @Override
    public List<TabView.Entry> entries(String query) {
        List<TabView.Entry> rows = new ArrayList<>();
        for (MacroStore.Macro macro : MacroStore.get().all()) {
            if (GuiSources.matches(macro.name(), query)) {
                rows.add(keyRow(macro.name(), macro.key(), macro, macro.tip()
                    + (MacroStore.get().running(macro.name()) ? STOP_TIP : "")));
            }
        }
        for (Module module : bindable(query)) {
            rows.add(keyRow(module.getName(), module.getKeybind().getValue(), module.getKeybind(),
                module.getDescription() + (module.holdsKey() ? HELD_TIP : "")));
        }
        return rows;
    }

    // Without a query only the modules already on a key are listed.
    private List<Module> bindable(String query) {
        List<Module> all = OfflineClient.INSTANCE.getModuleManager().getAll();
        if (query.isEmpty()) {
            List<Module> bound = new ArrayList<>();
            for (Module module : all) {
                if (module.getKeybind().isBound()) {
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
        int gap = query.indexOf(' ');
        if (gap < 1) {
            return "no module or macro called " + query;
        }
        // The row is one line wide. Enter puts the whole of a problem in chat.
        MacroStore.Typed typed = MacroStore.typed(query.substring(gap + 1));
        if (typed.problem() != null) {
            return "press enter to hear what is wrong";
        }
        return "press enter to save the " + (typed.cycles() ? "cycling " : "") + "macro " + GuiSources.firstWord(query)
            + (typed.lines().size() > 1 ? " with " + typed.lines().size() + " lines" : "");
    }

    @Override
    public String footer() {
        return "click a key to change it";
    }

    @Override
    public String addHint() {
        return "find a module or add a macro";
    }

    // Only a fresh setup gets the whole guide. The part about longer macros stays
    // until the player has made one.
    @Override
    public List<TabView.Hint> hints(String query) {
        if (!query.isEmpty()) {
            return List.of();
        }
        List<MacroStore.Macro> macros = MacroStore.get().all();
        boolean fresh = macros.isEmpty() && !hasOwnBinds();
        String prefix = OfflineClient.INSTANCE.getCommandManager().getPrefix();
        String then = " " + MacroStore.SPLIT + " ";
        List<TabView.Hint> hints = new ArrayList<>();
        if (fresh) {
            hints.add(new TabView.Hint("Bind a module",
                "Type its name then click no key and press a key or a mouse button.",
                List.of("killaura")));
            hints.add(new TabView.Hint("Add a macro",
                "Type a name then what it sends and press enter. Chat and commands both work.",
                List.of("home /home", "fly " + prefix + "toggle flight")));
        }
        if (macros.stream().noneMatch(macro -> macro.cycles() || macro.lines().size() > 1)) {
            hints.add(new TabView.Hint("Give a macro more lines",
                "Put a semicolon between lines. A line can wait or repeat another. The word "
                    + MacroStore.CYCLE + " in front runs one line a press.",
                List.of("kit /home" + then + MacroLine.WAIT + " 2s" + then + "/kit",
                    "wave " + MacroLine.REPEAT + " 3 1s /wave",
                    "gear " + MacroStore.CYCLE + " " + prefix + "loadout load pvp" + then + prefix
                        + "loadout load mine")));
        }
        if (fresh) {
            hints.add(new TabView.Hint("Put a setting on a key",
                "A macro that sets a setting to toggle flips a switch. One that sets it to next or prev"
                    + " steps a mode.",
                List.of("smooth " + prefix + "set zoom smooth toggle",
                    "look " + prefix + "set freelook mode next")));
        }
        return hints;
    }

    // A name on its own is a search. A name and its lines is a macro.
    @Override
    public void add(String text) {
        int gap = text.indexOf(' ');
        if (gap < 1 || text.substring(gap).isBlank()) {
            return;
        }
        String name = text.substring(0, gap);
        MacroStore.Typed typed = MacroStore.typed(text.substring(gap + 1));
        if (typed.problem() != null) {
            ChatUtil.error(typed.problem());
            return;
        }
        MacroStore.Macro existing = MacroStore.get().find(name);
        MacroStore.get().add(existing == null
            ? new MacroStore.Macro(name, Bind.NONE, typed) : existing.with(typed));
    }

    // A click runs a macro. A click on one still running stops it.
    @Override
    public void activate(String name) {
        MacroStore.Macro macro = MacroStore.get().find(name);
        if (macro != null) {
            if (MacroStore.get().stop(name) == 0) {
                MacroStore.get().run(macro);
            }
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
    public void bind(String name, Bind key) {
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
            module.getKeybind().setValue(Bind.NONE);
            OfflineClient.INSTANCE.getConfigManager().saveSoon();
        }
    }

    // The ClickGUI starts on a key and does not count.
    private static boolean hasOwnBinds() {
        for (Module module : OfflineClient.INSTANCE.getModuleManager().getAll()) {
            if (!(module instanceof ClickGuiModule)
                && module.getKeybind().isBound()) {
                return true;
            }
        }
        return false;
    }

    private static String keyName(Bind key) {
        return key.isBound() ? key.name() : NO_KEY;
    }

    // A row whose detail is a key. A key something else answers to as well is drawn as a warning
    // and the tip names what else uses it.
    private static TabView.Entry keyRow(String name, Bind key, Object self, String tip) {
        String also = KeyUse.alsoUsedBy(key, self);
        return new TabView.Entry(name, keyName(key), tip + also, !also.isEmpty());
    }

    private static Module moduleNamed(String name) {
        return OfflineClient.INSTANCE.getModuleManager().get(name);
    }
}
