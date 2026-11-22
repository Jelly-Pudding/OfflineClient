package com.jellypudding.offlineclient.module;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.KeybindSetting;
import com.jellypudding.offlineclient.setting.Setting;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.SearchRank;
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
    private final List<Setting<?>> settingsView = Collections.unmodifiableList(settings);
    private final KeybindSetting keybind;
    private final BoolSetting listed;
    private String[] tags = new String[0];
    private boolean enabled;

    // A module that ships switched on starts out of the list. One that cannot be switched on is
    // never written there.
    private final class ListSwitch extends BoolSetting {

        private ListSwitch() {
            super("In module list", "Lets the Module list on the HUD show and count " + name + " whilst " + name
                + " is on.", !enabledByDefault());
        }

        @Override
        public boolean isInternal() {
            return !isTogglable();
        }

        @Override
        public boolean isVisible() {
            return isTogglable();
        }
    }

    protected Module(String name, String description, Category category) {
        this(name, description, category, KeybindSetting.UNBOUND);
    }

    protected Module(String name, String description, Category category, int defaultKey) {
        this.name = name;
        this.description = description;
        this.category = category;
        this.keybind = new KeybindSetting("Bind", "The key or mouse button for " + name + ".", defaultKey);
        this.listed = new ListSwitch();
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

    protected final void addSettings(Setting<?>... newSettings) {
        Collections.addAll(settings, newSettings);
    }

    protected final void searchTags(String... tags) {
        this.tags = tags;
    }

    // The key starts as a held one and the player can still make it a switch.
    protected final void holdByDefault() {
        keybind.holdByDefault();
    }

    // The name outranks a tag and a tag outranks the description. Only the strongest tag counts.
    public int searchScore(String query) {
        List<String> settingNames = offered(getSettings()).stream().map(Setting::getName).toList();
        return SearchRank.best(query, name, SearchRank.strongest(query, List.of(tags)), description,
            SearchRank.strongest(query, settingNames));
    }

    public List<Setting<?>> getSettings() {
        return settingsView;
    }

    // Whatever saves or shows or resets settings reads these.
    public final List<Setting<?>> allSettings() {
        List<Setting<?>> all = new ArrayList<>(getSettings());
        all.add(listed);
        return all;
    }

    // The config still saves the internal ones.
    public List<Setting<?>> playerSettings() {
        return offered(allSettings());
    }

    private static List<Setting<?>> offered(List<Setting<?>> settings) {
        return settings.stream().filter(setting -> !setting.isInternal()).toList();
    }

    public Setting<?> onlySetting() {
        List<Setting<?>> own = offered(getSettings());
        return own.size() == 1 ? own.getFirst() : null;
    }

    public List<String> settingIds() {
        return playerSettings().stream().map(Setting::id).toList();
    }

    // An internal setting is never found.
    public Setting<?> getSetting(String settingName) {
        String wanted = Setting.idFor(settingName);
        for (Setting<?> setting : allSettings()) {
            if (!setting.isInternal() && setting.id().equals(wanted)) {
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
        if (switchTo(enabled)) {
            OfflineClient.INSTANCE.getConfigManager().saveSoon();
        }
    }

    // Nothing is written for a held key. The config holds such a module as off.
    final void setHeld(boolean down) {
        switchTo(down);
    }

    // Every way of switching a module on ends here and a locked module stays off.
    private boolean switchTo(boolean enabled) {
        if (this.enabled == enabled || enabled && Modules.lockRefuses(this)) {
            return false;
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
        return true;
    }

    public ExclusivityGroup getExclusivityGroup() {
        return null;
    }

    public boolean clashesWith(Module other) {
        ExclusivityGroup group = getExclusivityGroup();
        return group != null && group == other.getExclusivityGroup();
    }

    private void disableGroup() {
        ModuleManager manager = OfflineClient.INSTANCE.getModuleManager();
        if (manager == null) {
            return;
        }
        for (Module other : manager.getAll()) {
            if (other != this && other.isEnabled() && clashesWith(other) && other.clashesWith(this)) {
                other.setEnabled(false);
                ChatUtil.toggled(other);
            }
        }
    }

    public void onKeybind() {
        toggle();
    }

    // False for a module whose key does something other than switch it on and off.
    public boolean keySwitches() {
        return isTogglable();
    }

    public final boolean holdsKey() {
        return keySwitches() && keybind.isHold();
    }

    protected static String suffix(String target, String status) {
        if (target == null) {
            return status;
        }
        return status == null ? target : target + " " + status;
    }

    public boolean savesEnabledState() {
        return true;
    }

    // Asked whilst the module is still being built and the answer never leans on a field of the
    // module.
    public boolean enabledByDefault() {
        return false;
    }

    public final boolean isListed() {
        return listed.isOn();
    }

    // False for rows like ClickGUI that only exist to hold settings.
    public boolean isTogglable() {
        return true;
    }

    protected void onEnable() {
    }

    protected void onDisable() {
    }

    public String getSuffix() {
        return null;
    }

    protected static String count(int n) {
        return n == 0 ? null : String.valueOf(n);
    }

    protected static String count(int n, String what) {
        return n == 0 ? null : n + " " + what;
    }

    public String getDisplayName() {
        String suffix = getSuffix();
        return suffix == null ? name : name + " §7[" + suffix + "]";
    }

    protected void disable(String reason) {
        ChatUtil.error(reason);
        setEnabled(false);
    }

    protected final void tell(String words) {
        ChatUtil.tell(getName(), words);
    }

    protected final boolean inGame() {
        return mc.player != null && mc.level != null;
    }

    // Finishes work that has to carry on after the module is switched off.
    protected static void watch(Object watcher) {
        OfflineClient.INSTANCE.getEventBus().register(watcher);
    }

    protected static void unwatch(Object watcher) {
        OfflineClient.INSTANCE.getEventBus().unregister(watcher);
    }
}
