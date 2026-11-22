package com.jellypudding.offlineclient.setting;

public enum ListMode {
    WHITELIST, BLACKLIST;

    public boolean admits(boolean listed) {
        return listed == (this == WHITELIST);
    }

    public static EnumSetting<ListMode> setting(String name, ListMode defaultValue, String whitelist,
                                                String blacklist) {
        return new EnumSetting<>(name, "What the list means.", defaultValue)
            .describe(WHITELIST, whitelist)
            .describe(BLACKLIST, blacklist);
    }
}
