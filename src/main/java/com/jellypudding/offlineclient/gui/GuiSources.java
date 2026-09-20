package com.jellypudding.offlineclient.gui;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.config.MacroStore;
import com.jellypudding.offlineclient.setting.KeybindSetting;
import com.jellypudding.offlineclient.util.ChatUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// What the tabs read and write. The client owns all of it already.
public final class GuiSources {

    private GuiSources() {
    }

    public static TabView.Source friends() {
        return new TabView.Source() {
            @Override
            public List<TabView.Entry> entries() {
                List<String> names = new ArrayList<>(
                    OfflineClient.INSTANCE.getFriendManager().getAll());
                names.sort(String.CASE_INSENSITIVE_ORDER);
                List<TabView.Entry> rows = new ArrayList<>(names.size());
                for (String name : names) {
                    rows.add(new TabView.Entry(name, "", "Friends are never targeted."));
                }
                return rows;
            }

            @Override
            public String emptyText() {
                return "nobody on the list yet";
            }

            @Override
            public String addHint() {
                return "type a name and press enter";
            }

            @Override
            public void add(String text) {
                String name = text.split("\\s+")[0];
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

    public static TabView.Source macros() {
        return new TabView.Source() {
            @Override
            public List<TabView.Entry> entries() {
                List<TabView.Entry> rows = new ArrayList<>();
                for (MacroStore.Macro macro : MacroStore.get().all()) {
                    String key = macro.key() == KeybindSetting.UNBOUND
                        ? "no key" : KeybindSetting.nameOfKey(macro.key());
                    rows.add(new TabView.Entry(macro.name(), key,
                        "Runs " + String.join(" then ", macro.lines())));
                }
                return rows;
            }

            @Override
            public String emptyText() {
                return "no macros yet";
            }

            @Override
            public String footer() {
                return "click a name to run it. click its key to change it.";
            }

            @Override
            public String addHint() {
                return "a name then what to run. try home /home";
            }

            // Everything after the first word is the line the macro sends.
            @Override
            public void add(String text) {
                int gap = text.indexOf(' ');
                if (gap < 1) {
                    ChatUtil.error("Give the macro a name and then something to run.");
                    return;
                }
                String name = text.substring(0, gap);
                String line = text.substring(gap + 1).trim();
                if (line.isEmpty()) {
                    ChatUtil.error("Give the macro a name and then something to run.");
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
                }
            }

            @Override
            public void drop(String name) {
                MacroStore.get().remove(name);
            }
        };
    }

    public static TabView.Source profiles() {
        return new TabView.Source() {
            @Override
            public List<TabView.Entry> entries() {
                List<TabView.Entry> rows = new ArrayList<>();
                for (String name : OfflineClient.INSTANCE.getConfigManager().listProfiles()) {
                    rows.add(new TabView.Entry(name, "", "Load " + name));
                }
                return rows;
            }

            @Override
            public String emptyText() {
                return "no saved setups yet";
            }

            @Override
            public String footer() {
                return "click a name to load that setup.";
            }

            @Override
            public String addHint() {
                return "save this setup as";
            }

            @Override
            public void add(String text) {
                String name = text.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
                OfflineClient.INSTANCE.getConfigManager().saveProfile(name);
                ChatUtil.message("§7Saved the profile §b" + name + "§7.");
            }

            @Override
            public void activate(String name) {
                if (OfflineClient.INSTANCE.getConfigManager().loadProfile(name)) {
                    ChatUtil.message("§7Loaded the profile §b" + name + "§7.");
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

    private static boolean isSelf(String name) {
        return OfflineClient.MC.player != null
            && name.equalsIgnoreCase(OfflineClient.MC.player.getGameProfile().name());
    }
}
