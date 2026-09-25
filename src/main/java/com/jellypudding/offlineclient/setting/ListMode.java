package com.jellypudding.offlineclient.setting;

// Whether a picked list names what a module acts on or what it leaves alone.
public enum ListMode {
    WHITELIST, BLACKLIST;

    // True when an entry passes given whether the list holds it.
    public boolean admits(boolean listed) {
        return listed == (this == WHITELIST);
    }

    // Each choice is described by what it lets through.
    public static EnumSetting<ListMode> setting(String name, ListMode defaultValue, String whitelist,
                                                String blacklist) {
        return new EnumSetting<>(name, "What the list means.", defaultValue)
            .describe(WHITELIST, whitelist)
            .describe(BLACKLIST, blacklist);
    }
}
