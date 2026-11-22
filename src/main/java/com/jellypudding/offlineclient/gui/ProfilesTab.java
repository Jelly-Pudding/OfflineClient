package com.jellypudding.offlineclient.gui;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.config.ConfigManager;
import com.jellypudding.offlineclient.config.Profiles;
import com.jellypudding.offlineclient.util.ChatUtil;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class ProfilesTab implements TabView.Source {

    private static final String JOINS = "on join";

    private final Runnable beforeSave;
    private final Runnable afterLoad;

    private Asked asked = Asked.of("");

    ProfilesTab(Runnable beforeSave, Runnable afterLoad) {
        this.beforeSave = beforeSave;
        this.afterLoad = afterLoad;
    }

    @Override
    public List<TabView.Entry> entries(String query) {
        asked = Asked.of(query);
        Profiles profiles = OfflineClient.INSTANCE.getConfigManager().profiles();
        List<TabView.Entry> rows = new ArrayList<>();
        for (String name : profiles.list()) {
            if (GuiSources.matches(name, asked.name())) {
                List<String> servers = profiles.joinServers(name);
                String joins = servers.isEmpty() ? ""
                    : " Loads by itself on joining " + String.join(" and ", servers) + ".";
                rows.add(new TabView.Entry(name, servers.isEmpty() ? "" : JOINS, asked.loadTip(name) + joins));
            }
        }
        return rows;
    }

    @Override
    public String emptyText(String query) {
        return query.isEmpty() ? "no saved setups yet" : "press enter to save " + Asked.of(query).saveText();
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
    public List<TabView.Hint> hints(String query) {
        if (!query.isEmpty() || !OfflineClient.INSTANCE.getConfigManager().profiles().list().isEmpty()) {
            return List.of();
        }
        String prefix = OfflineClient.INSTANCE.getCommandManager().getPrefix();
        return List.of(
            new TabView.Hint("Save a setup", "Type a name and press enter. Click it later to bring it back.",
                List.of("pvp")),
            new TabView.Hint("Save or load a part",
                "Add any of " + String.join(" ", Profiles.allPartWords()) + " after the name. Enter saves"
                    + " those into the profile and keeps its other parts. A click on the profile loads"
                    + " only those.",
                List.of("pvp binds", "travel waypoints macros")),
            new TabView.Hint("One setup for each server",
                "Type " + prefix + "profile join and a name in chat whilst on the server.", List.of()));
    }

    @Override
    public void add(String text) {
        Asked saving = Asked.of(text);
        beforeSave.run();
        OfflineClient.INSTANCE.getConfigManager().profiles().save(saving.name(), saving.parts());
    }

    @Override
    public void activate(String name) {
        if (OfflineClient.INSTANCE.getConfigManager().profiles().load(name, asked.parts())) {
            afterLoad.run();
        }
    }

    @Override
    public void drop(String name) {
        if (OfflineClient.INSTANCE.getConfigManager().profiles().delete(name)) {
            ChatUtil.message("§7Deleted the profile §b" + name + "§7.");
        }
    }

    // Words after the name that are not all parts belong to the name itself.
    private record Asked(String name, Set<ConfigManager.Part> parts) {

        private static Asked of(String text) {
            String[] words = text.trim().split("\\s+");
            Set<ConfigManager.Part> parts = EnumSet.noneOf(ConfigManager.Part.class);
            for (int i = 1; i < words.length; i++) {
                ConfigManager.Part part = ConfigManager.Part.named(words[i]);
                if (part == null) {
                    return new Asked(profileName(text), EnumSet.noneOf(ConfigManager.Part.class));
                }
                parts.add(part);
            }
            return new Asked(profileName(words[0]), parts);
        }

        private String saveText() {
            return parts.isEmpty() ? "as " + name : Profiles.worded(parts) + " into " + name;
        }

        private String loadTip(String profile) {
            return parts.isEmpty() ? "Load " + profile + "."
                : "Load " + Profiles.worded(parts) + " from " + profile + ".";
        }
    }

    // Profiles are saved as files. The name is kept to one plain word.
    private static String profileName(String text) {
        return text.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
    }
}
