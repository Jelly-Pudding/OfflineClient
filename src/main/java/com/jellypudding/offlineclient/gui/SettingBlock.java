package com.jellypudding.offlineclient.gui;

import com.jellypudding.offlineclient.hud.HudElement;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.Defaults;
import com.jellypudding.offlineclient.setting.KeybindSetting;
import com.jellypudding.offlineclient.setting.Setting;

import java.util.List;

// Keeps is the sentence that says what a reset of the whole block leaves alone.
public record SettingBlock(Object owner, String name, List<Setting<?>> settings, KeybindSetting bind,
                           String keeps) {

    public static SettingBlock of(Module module) {
        return new SettingBlock(module, module.getName(), module.allSettings(), module.getKeybind(),
            module.isTogglable() ? "The bind and the on or off state stay as they are."
                : "The bind stays as it is.");
    }

    // The editor draws the switch in the piece's own row. The block holds the options.
    public static SettingBlock of(HudElement element) {
        return new SettingBlock(element, element.getName(), element.getOptions(), null,
            "The switch stays as it is.");
    }

    // Only the key of a module that switches on and off can be a held one.
    public boolean holdable(Setting<?> row) {
        return row == bind && owner instanceof Module module && module.keySwitches();
    }

    // The bind is left out.
    public int changed() {
        return Defaults.changed(settings);
    }

    public int rows() {
        return bind == null ? settings.size() : settings.size() + 1;
    }

    public Setting<?> row(int index) {
        return index == settings.size() ? bind : settings.get(index);
    }
}
