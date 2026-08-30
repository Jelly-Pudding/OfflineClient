package com.jellypudding.offlineclient;

import com.jellypudding.offlineclient.command.CommandManager;
import com.jellypudding.offlineclient.config.ConfigManager;
import com.jellypudding.offlineclient.event.EventBus;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.friend.FriendManager;
import com.jellypudding.offlineclient.module.ModuleManager;
import com.jellypudding.offlineclient.util.RotationManager;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

// A utility client made for minecraftoffline.net
public enum OfflineClient {
    INSTANCE;

    public static final String NAME = "OfflineClient";
    public static final String VERSION = "0.5.0";
    public static final String SERVER_NAME = "minecraftoffline.net";
    public static final String SERVER_ADDRESS = "minecraftoffline.net";

    public static final Minecraft MC = Minecraft.getInstance();

    public static final Logger LOG = LoggerFactory.getLogger(NAME);

    private EventBus eventBus;
    private FriendManager friendManager;
    private CommandManager commandManager;
    private ModuleManager moduleManager;
    private ConfigManager configManager;

    public void init() {
        LOG.info("Starting {} v{} for {}", NAME, VERSION, SERVER_NAME);

        Path folder = MC.gameDirectory.toPath().resolve("offlineclient");

        eventBus = new EventBus();
        friendManager = new FriendManager();
        commandManager = new CommandManager();
        moduleManager = new ModuleManager();
        moduleManager.enableDefaults();

        configManager = new ConfigManager(folder);
        configManager.load();

        eventBus.register(this);
        eventBus.register(RotationManager.INSTANCE);
    }

    // Not TickEvent because settings also change from the menus.
    @Subscribe
    private void onTick(ClientTickEvent event) {
        configManager.tick();
    }

    public EventBus getEventBus() {
        return eventBus;
    }

    public FriendManager getFriendManager() {
        return friendManager;
    }

    public CommandManager getCommandManager() {
        return commandManager;
    }

    public ModuleManager getModuleManager() {
        return moduleManager;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }
}
