package com.jellypudding.offlineclient.condition;

import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.util.Dimension;

// Where a place or a height counts. Every dimension or one of the three. The same numbers
// name another spot in each dimension.
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

    // True whilst you are somewhere it takes in.
    boolean isHere() {
        return realm == null || realm.isHere();
    }

    // The dimension you are in. Any in a dimension a server added.
    static Within here() {
        Dimension here = Dimension.here();
        for (Within within : values()) {
            if (here != null && within.realm == here) {
                return within;
            }
        }
        return ANY;
    }

    // As a sentence goes on such as in the Nether or in any dimension.
    String words() {
        return realm == null ? "in any dimension" : "in " + realm.words();
    }

    // The row a condition about a place shows. It starts at any dimension.
    static EnumSetting<Within> row(String name) {
        return new EnumSetting<>(name, "The dimension it counts in.", ANY)
            .describe(ANY, "It counts in every dimension.")
            .describe(OVERWORLD, "It only counts in the Overworld.")
            .describe(NETHER, "It only counts in the Nether.")
            .describe(END, "It only counts in the End.");
    }
}
