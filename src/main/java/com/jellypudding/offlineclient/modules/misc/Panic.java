package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.module.ModuleManager;
import com.jellypudding.offlineclient.setting.ActionSetting;
import com.jellypudding.offlineclient.setting.Bind;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.Tally;
import com.mojang.blaze3d.platform.InputConstants;

import java.util.ArrayList;
import java.util.List;

public final class Panic extends Module {

    private final BoolSetting announce = new BoolSetting("Announce",
        "Says in chat how many modules were turned off or back on.", true);
    private final ActionSetting restore = new ActionSetting("Restore",
        "Turns back on the modules Panic last switched off.", this::restore);
    private final BoolSetting dormant = new BoolSetting("Dormant",
        "The Panic key also puts the client to sleep until it is pressed again. No other client key works and a"
            + " line that starts with the command prefix is thrown away unsent. What the client draws in play is"
            + " hidden but lines it already wrote stay in chat. The second press brings the modules back.",
        false);
    private final BoolSetting keyRestores = new BoolSetting("Key restores",
        "Pressing Panic whilst every module is off turns back on the ones it switched off.", false)
        .visibleWhen(() -> !dormant.isOn());

    // Kept until a restore or the next panic that finds something to switch off. Never saved.
    private final List<Module> switchedOff = new ArrayList<>();

    // Never saved and a restart wakes the client. Read through isAsleep.
    private boolean asleep;

    public Panic() {
        super("Panic", "Turns every enabled module off at once.",
            Category.MISC);
        addSettings(announce, restore, dormant, keyRestores);
        searchTags("panic button", "disable all", "kill switch", "restore", "dormant", "sleep", "boss key",
            "hide client");
    }

    @Override
    public boolean savesEnabledState() {
        return false;
    }

    @Override
    public boolean keySwitches() {
        return false;
    }

    @Override
    public void onKeybind() {
        if (dormant.isOn()) {
            // Waking brings back what this press switched off and nothing from an earlier panic.
            switchedOff.clear();
            switchOffAll();
            asleep = true;
        } else if (keyRestores.isOn() && !switchedOff.isEmpty() && toSwitchOff().isEmpty()) {
            restore();
        } else {
            toggle();
        }
    }

    // The client only sleeps whilst the switch is on and the key is bound. Without both there
    // would be no way back. A reset of every module from the title screen takes both away.
    public boolean isAsleep() {
        return asleep && dormant.isOn() && getKeybind().isBound();
    }

    // The config holds such a module as on and a game closed whilst asleep loses nothing.
    public boolean bringsBack(Module module) {
        return isAsleep() && switchedOff.contains(module);
    }

    // A press of the Panic key is the one thing that gets through and it wakes the client.
    public static boolean sleepsThrough(Bind input, int action) {
        Panic panic = Modules.get(Panic.class);
        if (panic == null || !panic.asleep) {
            return false;
        }
        // The way back went whilst the client slept. The sleep is over and what Panic remembered
        // goes with it.
        if (!panic.isAsleep()) {
            panic.asleep = false;
            panic.switchedOff.clear();
            return false;
        }
        if (action == InputConstants.PRESS && mc.gui.screen() == null && panic.getKeybind().getValue().answers(input)) {
            panic.wake();
        }
        return true;
    }

    private void wake() {
        asleep = false;
        if (!switchedOff.isEmpty()) {
            restore();
        } else if (announce.isOn()) {
            ChatUtil.message("§cPanic§7. The client is awake again.");
        }
    }

    @Override
    protected void onEnable() {
        announce("Turned off", switchOffAll());
        setEnabled(false);
    }

    private int switchOffAll() {
        List<Module> off = new ArrayList<>();
        for (Module module : toSwitchOff()) {
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
            // A module that is only on whilst its key is down must not come back without the key.
            switchedOff.removeIf(Module::holdsKey);
        }
        return off.size();
    }

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
            } catch (Exception e) {
                OfflineClient.LOG.error("Panic could not turn {} back on", module.getName(), e);
            }
            // A locked module stays off.
            if (module.isEnabled()) {
                count++;
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
