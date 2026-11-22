package com.jellypudding.offlineclient.condition;

import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.util.Dimension;

enum Within {
    ANY(null),
    OVERWORLD(Dimension.OVERWORLD),
    NETHER(Dimension.NETHER),
    END(Dimension.END);

    // Null for any.
    private final Dimension realm;

    Within(Dimension realm) {
        this.realm = realm;
    }

    boolean isHere() {
        return realm == null || realm.isHere();
    }

    // Any in a dimension a server added.
    static Within here() {
        Dimension here = Dimension.here();
        for (Within within : values()) {
            if (here != null && within.realm == here) {
                return within;
            }
        }
        return ANY;
    }

    String words() {
        return realm == null ? "in any dimension" : "in " + realm.words();
    }

    static EnumSetting<Within> row(String name) {
        return new EnumSetting<>(name, "The dimension it counts in.", ANY)
            .describe(ANY, "It counts in every dimension.")
            .describe(OVERWORLD, "It only counts in the Overworld.")
            .describe(NETHER, "It only counts in the Nether.")
            .describe(END, "It only counts in the End.");
    }
}
