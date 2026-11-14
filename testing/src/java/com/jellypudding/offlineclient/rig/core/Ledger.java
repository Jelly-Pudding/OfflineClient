package com.jellypudding.offlineclient.rig.core;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.Setting;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

// Writes down every module of the client with its settings as a run starts. The report holds the
// cases of the run against this list and says which modules a case put to work.
public final class Ledger {

    public static final String FILE = "modules.txt";
    public static final String SPLIT = "\t";
    private static final String BETWEEN = " | ";

    private Ledger() {
    }

    public static void write(Rig rig) {
        List<String> lines = rig.ask(mc -> {
            List<String> all = new ArrayList<>();
            for (Module module : OfflineClient.INSTANCE.getModuleManager().getAll()) {
                StringJoiner settings = new StringJoiner(BETWEEN);
                for (Setting<?> setting : module.allSettings()) {
                    settings.add(setting.getName() + "=" + setting.getValueString());
                }
                all.add(module.getName() + SPLIT + module.getCategory() + SPLIT + settings);
            }
            return all;
        });
        try {
            Files.write(rig.results().out().resolve(FILE), lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RigFailure("The list of modules could not be written", e);
        }
    }
}
