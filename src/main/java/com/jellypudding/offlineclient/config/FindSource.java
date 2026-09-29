package com.jellypudding.offlineclient.config;

import com.jellypudding.offlineclient.setting.Setting;
import com.jellypudding.offlineclient.util.Modules;

import java.util.List;

// A module that keeps a log of its finds. The finds list and the finds command reach every
// log through this without knowing the modules.
public interface FindSource {

    FindLog findLog();

    // Every log in the order the modules are listed. Empty before the modules exist.
    static List<FindLog> logs() {
        return Modules.all(FindSource.class).stream().map(FindSource::findLog).toList();
    }

    // The log of the module with that name whatever its case and spaces. Null when none.
    static FindLog named(String name) {
        String wanted = Setting.idFor(name);
        for (FindLog log : logs()) {
            if (Setting.idFor(log.name()).equals(wanted)) {
                return log;
            }
        }
        return null;
    }
}
