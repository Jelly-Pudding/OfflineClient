package com.jellypudding.offlineclient.module;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.setting.KeybindSetting;
import com.jellypudding.offlineclient.setting.Setting;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public abstract class Module {

    protected static final Minecraft mc = OfflineClient.MC;

    private final String name;
    private final String description;
    private final Category category;
    private final List<Setting<?>> settings = new ArrayList<>();
    private final KeybindSetting keybind;
    private String[] tags = new String[0];
    private boolean enabled;

    protected Module(String name, String description, Category category) {
        this(name, description, category, KeybindSetting.UNBOUND);
    }

    protected Module(String name, String description, Category category, int defaultKey) {
        this.name = name;
        this.description = description;
        this.category = category;
        this.keybind = new KeybindSetting("Bind", "Keyboard shortcut to toggle " + name + ".", defaultKey);
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public Category getCategory() {
        return category;
    }

    public KeybindSetting getKeybind() {
        return keybind;
    }

    protected void addSettings(Setting<?>... newSettings) {
        Collections.addAll(settings, newSettings);
    }

    protected void searchTags(String... tags) {
        this.tags = tags;
    }

    public boolean matchesSearch(String query) {
        String q = query.toLowerCase();
        if (name.toLowerCase().contains(q) || description.toLowerCase().contains(q)) {
            return true;
        }
        for (String tag : tags) {
            if (tag.toLowerCase().contains(q)) {
                return true;
            }
        }
        return false;
    }

    public List<Setting<?>> getSettings() {
        return settings;
    }

    // Spaces and case are ignored.
    public Setting<?> getSetting(String settingName) {
        String wanted = settingName.replace(" ", "").toLowerCase();
        for (Setting<?> setting : settings) {
            if (setting.getName().replace(" ", "").toLowerCase().equals(wanted)) {
                return setting;
            }
        }
        return null;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void toggle() {
        setEnabled(!enabled);
    }

    public void setEnabled(boolean enabled) {
        if (this.enabled == enabled) {
            return;
        }
        this.enabled = enabled;

        if (enabled) {
            disableGroup();
            OfflineClient.INSTANCE.getEventBus().register(this);
            onEnable();
        } else {
            OfflineClient.INSTANCE.getEventBus().unregister(this);
            onDisable();
        }
    }

    // Null when the module clashes with nothing.
    public ExclusivityGroup getExclusivityGroup() {
        return null;
    }

    private void disableGroup() {
        ExclusivityGroup group = getExclusivityGroup();
        ModuleManager manager = OfflineClient.INSTANCE.getModuleManager();
        if (group == null || manager == null) {
            return;
        }
        for (Module other : manager.getAll()) {
            if (other != this && other.isEnabled() && other.getExclusivityGroup() == group) {
                other.setEnabled(false);
            }
        }
    }

    public void onKeybind() {
        toggle();
    }

    public boolean savesEnabledState() {
        return true;
    }

    // True for modules that ship switched on when there is no saved state.
    public boolean enabledByDefault() {
        return false;
    }

    // False for rows like ClickGUI that only exist to hold settings.
    public boolean isTogglable() {
        return true;
    }

    protected void onEnable() {
    }

    protected void onDisable() {
    }

    // Extra info shown next to the name in the HUD list.
    public String getSuffix() {
        return null;
    }

    public String getDisplayName() {
        String suffix = getSuffix();
        return suffix == null ? name : name + " §7[" + suffix + "]";
    }

    protected boolean inGame() {
        return mc.player != null && mc.level != null;
    }
}
