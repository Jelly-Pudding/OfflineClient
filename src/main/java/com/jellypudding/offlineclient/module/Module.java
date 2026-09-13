package com.jellypudding.offlineclient.module;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.setting.KeybindSetting;
import com.jellypudding.offlineclient.setting.Setting;
import com.jellypudding.offlineclient.util.SearchRank;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

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

    protected final void addSettings(Setting<?>... newSettings) {
        Collections.addAll(settings, newSettings);
    }

    protected final void searchTags(String... tags) {
        this.tags = tags;
    }

    // How closely this module matches a search query. Higher scores are better matches.
    // The name outranks a tag and a tag outranks the description.
    public int searchScore(String query) {
        String bestTag = "";
        int bestTagScore = SearchRank.NO_MATCH;
        for (String tag : tags) {
            // Only the strongest tag is offered. A weak one must not drag the module down.
            int score = SearchRank.score(tag, query);
            if (score > bestTagScore) {
                bestTagScore = score;
                bestTag = tag;
            }
        }
        return SearchRank.best(query, name, bestTag, description, bestSettingName(query));
    }

    // The name of the setting that best answers the query or an empty string.
    // Searching for a setting you half remember should find the module holding it.
    private String bestSettingName(String query) {
        String best = "";
        int bestScore = SearchRank.NO_MATCH;
        for (Setting<?> setting : settings) {
            int score = SearchRank.score(setting.getName(), query);
            if (score > bestScore) {
                bestScore = score;
                best = setting.getName();
            }
        }
        return best;
    }

    public List<Setting<?>> getSettings() {
        return settingsView;
    }

    // Spaces and case are ignored.
    public Setting<?> getSetting(String settingName) {
        String wanted = settingName.replace(" ", "").toLowerCase(Locale.ROOT);
        for (Setting<?> setting : getSettings()) {
            if (setting.getName().replace(" ", "").toLowerCase(Locale.ROOT).equals(wanted)) {
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

    public String getDisplayName() {
        String suffix = getSuffix();
        return suffix == null ? name : name + " §7[" + suffix + "]";
    }

    protected boolean inGame() {
        return mc.player != null && mc.level != null;
    }
}
