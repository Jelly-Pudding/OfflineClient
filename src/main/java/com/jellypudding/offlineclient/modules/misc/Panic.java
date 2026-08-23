package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.config.ConfigManager;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.module.ModuleManager;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import org.lwjgl.glfw.GLFW;

public final class Panic extends Module {

    private final BoolSetting announce = new BoolSetting("Announce",
        "Print how many modules were turned off.", true);

    public Panic() {
        super("Panic", "Turns every enabled module off at once.",
            Category.MISC, GLFW.GLFW_KEY_END);
        addSettings(announce);
        searchTags("panic button", "disable all", "kill switch");
    }

    @Override
    public boolean savesEnabledState() {
        return false;
    }

    @Override
    protected void onEnable() {
        ModuleManager modules = OfflineClient.INSTANCE.getModuleManager();
        if (modules == null) {
            setEnabled(false);
            return;
        }
        int count = 0;
        // getEnabled hands back a copy.
        for (Module module : modules.getEnabled()) {
            if (module == this || !module.isTogglable()) {
                continue;
            }
            try {
                module.setEnabled(false);
                count++;
            } catch (Exception e) {
                // One module throwing on the way down must not strand the rest.
                OfflineClient.LOG.error("Panic could not turn off {}", module.getName(), e);
            }
        }
        if (announce.isOn()) {
            ChatUtil.message("§cPanic§7. Turned off §f" + count + "§7 "
                + (count == 1 ? "module" : "modules") + ".");
        }
        ConfigManager config = OfflineClient.INSTANCE.getConfigManager();
        if (config != null) {
            config.saveSoon();
        }
        setEnabled(false);
    }
}
