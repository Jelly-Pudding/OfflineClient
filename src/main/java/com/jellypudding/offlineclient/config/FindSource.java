package com.jellypudding.offlineclient.config;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.util.Modules;

import java.util.List;

public interface FindSource {

    FindLog findLog();

    static List<FindLog> logs() {
        return Modules.all(FindSource.class).stream().map(FindSource::findLog).toList();
    }

    static FindLog named(String name) {
        return OfflineClient.INSTANCE.getModuleManager().get(name) instanceof FindSource source
            ? source.findLog() : null;
    }
}
