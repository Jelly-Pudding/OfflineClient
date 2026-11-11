package com.jellypudding.offlineclient.module;

import com.jellypudding.offlineclient.OfflineClient;
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
    private String[] tags = new String[0];
    private boolean enabled;

    protected Module(String name, String description, Category category) {
        this(name, description, category, KeybindSetting.UNBOUND);
    }

    protected Module(String name, String description, Category category, int defaultKey) {
        this.name = name;
        this.description = description;
        this.category = category;
        this.keybind = new KeybindSetting("Bind", "The key or mouse button for " + name + ".", defaultKey);
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

    // For the constructor of a module that is only wanted whilst its key is down. The key
    // starts as a held one and the player can still make it a switch.
    protected final void holdByDefault() {
        keybind.holdByDefault();
    }

    // Higher scores match the query better. The name outranks a tag and a tag outranks the
    // description. Only the strongest tag counts. Setting names are searched as well and a
    // half remembered setting still finds its module.
    public int searchScore(String query) {
        List<String> settingNames = playerSettings().stream().map(Setting::getName).toList();
        return SearchRank.best(query, name, SearchRank.strongest(query, List.of(tags)), description,
            SearchRank.strongest(query, settingNames));
    }

    public List<Setting<?>> getSettings() {
        return settingsView;
    }

    // The settings the player can list and type. The config still saves the internal ones.
    public List<Setting<?>> playerSettings() {
        return getSettings().stream().filter(setting -> !setting.isInternal()).toList();
    }

    public List<String> settingIds() {
        return playerSettings().stream().map(Setting::id).toList();
    }

    // Spaces and case are ignored. An internal setting is never found.
    public Setting<?> getSetting(String settingName) {
        String wanted = Setting.idFor(settingName);
        for (Setting<?> setting : getSettings()) {
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

    // Switches the module for a held key. Nothing is written for it. The config holds a module
    // that is only on whilst its key is down as off.
    final void setHeld(boolean down) {
        switchTo(down);
    }

    // True when the switch changed. Every way of switching a module on ends here and a locked
    // module stays off whichever it was.
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

    // Null when the module clashes with nothing.
    public ExclusivityGroup getExclusivityGroup() {
        return null;
    }

    // True when the two cannot run at once. Modules of one group clash unless one of them says
    // otherwise.
    public boolean clashesWith(Module other) {
        ExclusivityGroup group = getExclusivityGroup();
        return group != null && group == other.getExclusivityGroup();
    }

    // Every module switched off here is named in chat by the same rules as a key press.
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

    // False for a module whose key does something other than switch it on and off. Such a key
    // cannot be a held one.
    public boolean keySwitches() {
        return isTogglable();
    }

    // True for a key that keeps the module on only whilst it is down.
    public final boolean holdsKey() {
        return keySwitches() && keybind.isHold();
    }

    // Joins a target name and a status word for the module list.
    protected static String suffix(String target, String status) {
        if (target == null) {
            return status;
        }
        return status == null ? target : target + " " + status;
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

    // A suffix for how many things a module is tracking. Null whilst none.
    protected static String count(int n) {
        return n == 0 ? null : String.valueOf(n);
    }

    // The same with a word after the number such as "3 fed".
    protected static String count(int n, String what) {
        return n == 0 ? null : n + " " + what;
    }

    public String getDisplayName() {
        String suffix = getSuffix();
        return suffix == null ? name : name + " §7[" + suffix + "]";
    }

    // Tells the player why in chat and switches the module off.
    protected void disable(String reason) {
        ChatUtil.error(reason);
        setEnabled(false);
    }

    // Tells the player something in chat under the module's name.
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
