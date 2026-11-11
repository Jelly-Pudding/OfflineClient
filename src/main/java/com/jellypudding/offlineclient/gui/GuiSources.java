package com.jellypudding.offlineclient.gui;

import com.jellypudding.offlineclient.util.SearchRank;

// What the tabs read and write. The client owns all of it already.
final class GuiSources {

    static final String IN_USE = "in use";

    private GuiSources() {
    }

    // What a tab opens. Null for the HUD tab which opens the HUD editor in its place.
    static TabView.Source of(TabStrip.Tab tab, Runnable beforeSave, Runnable afterLoad) {
        return switch (tab) {
            case FRIENDS -> new FriendsTab();
            case MACROS -> new MacrosTab();
            case WAYPOINTS -> new WaypointsTab();
            case NAMES -> new NamesTab();
            case PROXIES -> new ProxiesTab();
            case HUD -> null;
            case PROFILES -> new ProfilesTab(beforeSave, afterLoad);
        };
    }

    static String firstWord(String text) {
        return text.trim().split("\\s+")[0];
    }

    static boolean matches(String name, String query) {
        return query.isEmpty() || SearchRank.score(name, query) != SearchRank.NO_MATCH;
    }
}
