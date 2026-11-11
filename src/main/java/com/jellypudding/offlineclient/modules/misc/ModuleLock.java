package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.ChoiceListSetting;
import com.jellypudding.offlineclient.util.ChatUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Module asks here before it switches on. The list is a setting like any other and a profile
// tied to a server brings the list for that server with it.
public final class ModuleLock extends Module {

    // How long the client stays quiet about a module after saying that it is locked. Something
    // that tries every tick must not fill the chat.
    private static final long QUIET_MS = 3000;

    private final ChoiceListSetting locked = new ChoiceListSetting("Locked modules",
        "The modules that stay off whilst this is on. Click to pick them. No key or click or command or other"
            + " module can switch one on and any that are on go off.",
        () -> OfflineClient.INSTANCE.getModuleManager().togglableNames(this))
        .onChange(() -> {
            if (isEnabled()) {
                switchOffLocked();
            }
        });

    // When the player was last told about each module.
    private final Map<Module, Long> told = new HashMap<>();

    public ModuleLock() {
        super("ModuleLock", "Keeps the modules you pick switched off until you switch this off again.",
            Category.MISC);
        addSettings(locked);
        searchTags("lock modules", "block modules", "forbid", "server rules", "safe list", "banned modules");
    }

    @Override
    public String getSuffix() {
        return count(locked.size());
    }

    // True for a module on the list. The lock never holds itself.
    public boolean holds(Module module) {
        return module != this && locked.contains(module.getName());
    }

    // Tells the player that the module stays off.
    public void refuse(Module module) {
        long now = System.currentTimeMillis();
        Long last = told.get(module);
        if (last != null && now - last < QUIET_MS) {
            return;
        }
        told.put(module, now);
        ChatUtil.error(module.getName() + " is locked. Switch ModuleLock off or take it off the list first.");
    }

    @Override
    protected void onEnable() {
        switchOffLocked();
    }

    private void switchOffLocked() {
        List<String> off = new ArrayList<>();
        for (String name : locked.getValue()) {
            Module module = OfflineClient.INSTANCE.getModuleManager().get(name);
            if (module != null && holds(module) && module.isEnabled()) {
                module.setEnabled(false);
                off.add(module.getName());
            }
        }
        if (!off.isEmpty()) {
            tell("switched off §f" + String.join("§7 and §f", off) + "§7.");
        }
    }
}
