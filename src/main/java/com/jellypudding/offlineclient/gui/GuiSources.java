package com.jellypudding.offlineclient.gui;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.config.MacroStore;
import com.jellypudding.offlineclient.setting.KeybindSetting;
import com.jellypudding.offlineclient.util.ChatUtil;

import java.util.ArrayList;
import java.util.List;

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
                return "nobody yet. add one with .friend add";
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
                return "nothing yet. add one with .macro add";
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
                return "nothing yet. save one with .profile save";
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

    // A listed macro carries its key after the name.
    private static String nameOf(String entry) {
        int gap = entry.indexOf("  ");
        return gap == -1 ? entry : entry.substring(0, gap);
    }
}
