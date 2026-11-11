package com.jellypudding.offlineclient.setting;

import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.util.Modules;

// A switch that is a module's own on or off state. Flipping either one flips the other. It
// keeps nothing itself and the module saves the state and decides how it starts out.
public final class ModuleSwitch extends BoolSetting {

    // Looked up each time. The switch can be built before its module is.
    private final Class<? extends Module> owner;

    public ModuleSwitch(String name, String description, Class<? extends Module> owner) {
        super(name, description, false);
        this.owner = owner;
        noDefault();
    }

    @Override
    public boolean isOn() {
        return Modules.enabled(owner);
    }

    @Override
    public Boolean getValue() {
        return isOn();
    }

    @Override
    public void setValue(Boolean on) {
        Module module = Modules.get(owner);
        if (module != null) {
            module.setEnabled(on);
        }
    }

    // A reset of settings leaves a module on or off as it was.
    @Override
    public void reset() {
    }

    @Override
    public boolean saves() {
        return false;
    }
}
