package com.jellypudding.offlineclient.gui;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.config.MacroStore;
import com.jellypudding.offlineclient.setting.KeybindSetting;
import com.jellypudding.offlineclient.util.ChatUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// What the list panels read and write. The client owns all of it already.
public final class GuiSources {

    private GuiSources() {
    }

    public static TabView.Source friends() {
        return new TabView.Source() {
            @Override
            public List<String> entries() {
                List<String> names = new ArrayList<>(
                    OfflineClient.INSTANCE.getFriendManager().getAll());
                names.sort(String.CASE_INSENSITIVE_ORDER);
                return names;
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
            public void drop(String entry) {
                OfflineClient.INSTANCE.getFriendManager().remove(entry);
                OfflineClient.INSTANCE.getConfigManager().saveSoon();
            }
        };
    }

    public static TabView.Source macros() {
        return new TabView.Source() {
            @Override
            public List<String> entries() {
                List<String> names = new ArrayList<>();
                for (MacroStore.Macro macro : MacroStore.get().all()) {
                    String key = KeybindSetting.nameOfKey(macro.key());
                    names.add(macro.name() + "  " + key);
                }
                return names;
            }

            @Override
            public String emptyText() {
                return "no macros yet";
            }

            @Override
            public String addHint() {
                return "name then the line to run";
            }

            // Everything after the first word is the line the macro sends.
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
                MacroStore.get().add(new MacroStore.Macro(name, KeybindSetting.UNBOUND,
                    List.of(line)));
            }

            @Override
            public String actionName() {
                return "Run";
            }

            @Override
            public void activate(String entry) {
                MacroStore.Macro macro = MacroStore.get().find(nameOf(entry));
                if (macro != null) {
                    MacroStore.get().run(macro);
                }
            }

            @Override
            public void drop(String entry) {
                MacroStore.get().remove(nameOf(entry));
            }
        };
    }

    public static TabView.Source profiles() {
        return new TabView.Source() {
            @Override
            public List<String> entries() {
                return OfflineClient.INSTANCE.getConfigManager().listProfiles();
            }

            @Override
            public String emptyText() {
                return "no saved setups yet";
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
            public String actionName() {
                return "Load";
            }

            @Override
            public void activate(String entry) {
                if (OfflineClient.INSTANCE.getConfigManager().loadProfile(entry)) {
                    ChatUtil.message("§7Loaded the profile §b" + entry + "§7.");
                }
            }

            @Override
            public void drop(String entry) {
                if (OfflineClient.INSTANCE.getConfigManager().deleteProfile(entry)) {
                    ChatUtil.message("§7Deleted the profile §b" + entry + "§7.");
                }
            }
        };
    }

    private static boolean isSelf(String name) {
        return OfflineClient.MC.player != null
            && name.equalsIgnoreCase(OfflineClient.MC.player.getGameProfile().name());
    }

    // A listed macro carries its key after the name.
    private static String nameOf(String entry) {
        int gap = entry.indexOf("  ");
        return gap == -1 ? entry : entry.substring(0, gap);
    }
}
