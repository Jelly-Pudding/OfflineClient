package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.module.ModuleManager;
import com.jellypudding.offlineclient.setting.ActionSetting;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.Tally;

import java.util.ArrayList;
import java.util.List;

public final class Panic extends Module {

    private final BoolSetting announce = new BoolSetting("Announce",
        "Says in chat how many modules were turned off or back on.", true);
    private final ActionSetting restore = new ActionSetting("Restore",
        "Turns back on the modules Panic last switched off.", this::restore);
    private final BoolSetting keyRestores = new BoolSetting("Key restores",
        "Pressing Panic whilst every module is off turns back on the ones it switched off.", false);

    // Kept until a restore or the next panic that finds something to switch off. Never saved.
    private final List<Module> switchedOff = new ArrayList<>();

    public Panic() {
        super("Panic", "Turns every enabled module off at once.",
            Category.MISC);
        addSettings(announce, restore, keyRestores);
        searchTags("panic button", "disable all", "kill switch", "restore");
    }

    @Override
    public boolean savesEnabledState() {
        return false;
    }

    @Override
    public void onKeybind() {
        if (keyRestores.isOn() && !switchedOff.isEmpty() && toSwitchOff().isEmpty()) {
            restore();
        } else {
            toggle();
        }
    }

    @Override
    protected void onEnable() {
        List<Module> on = toSwitchOff();
        List<Module> off = new ArrayList<>();
        for (Module module : on) {
            try {
                module.setEnabled(false);
                off.add(module);
            } catch (Exception e) {
                // One module throwing on the way down must not strand the rest.
                OfflineClient.LOG.error("Panic could not turn off {}", module.getName(), e);
            }
        }
        if (!off.isEmpty()) {
            switchedOff.clear();
            switchedOff.addAll(off);
        }
        announce("Turned off", off.size());
        setEnabled(false);
    }

    // Every module that is on and can be switched off. Empty before the client has started.
    private List<Module> toSwitchOff() {
        ModuleManager modules = OfflineClient.INSTANCE.getModuleManager();
        List<Module> on = new ArrayList<>();
        if (modules == null) {
            return on;
        }
        // getEnabled hands back a copy.
        for (Module module : modules.getEnabled()) {
            if (module != this && module.isTogglable()) {
                on.add(module);
            }
        }
        return on;
    }

    private String restore() {
        if (switchedOff.isEmpty()) {
            return "Nothing to turn back on";
        }
        int count = 0;
        for (Module module : switchedOff) {
            if (module.isEnabled()) {
                continue;
            }
            try {
                module.setEnabled(true);
                count++;
            } catch (Exception e) {
                OfflineClient.LOG.error("Panic could not turn {} back on", module.getName(), e);
            }
        }
        switchedOff.clear();
        announce("Turned back on", count);
        return "Turned back on " + Tally.counted(count, "module");
    }

    private void announce(String done, int count) {
        if (announce.isOn()) {
            ChatUtil.message("§cPanic§7. " + done + " §f" + Tally.counted(count, "module") + "§7.");
        }
    }
}
