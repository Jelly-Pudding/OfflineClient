package com.jellypudding.offlineclient;

import com.jellypudding.offlineclient.command.CommandManager;
import com.jellypudding.offlineclient.config.ConfigManager;
import com.jellypudding.offlineclient.event.EventBus;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.friend.FriendManager;
import com.jellypudding.offlineclient.module.ModuleManager;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;

/**
 * OfflineClient. A utility client custom designed for minecraftoffline.net
 */
public enum OfflineClient {
    INSTANCE;

    public static final String NAME = "OfflineClient";
    public static final String VERSION = "0.1.0";
    public static final String SERVER_NAME = "minecraftoffline.net";
    public static final String SERVER_ADDRESS = "minecraftoffline.net";

    public static final Minecraft MC = Minecraft.getInstance();

    private EventBus eventBus;
    private FriendManager friendManager;
    private CommandManager commandManager;
    private ModuleManager moduleManager;
    private ConfigManager configManager;

    public void init() {
        System.out.println("Starting " + NAME + " v" + VERSION + " - " + SERVER_NAME);

        Path folder = MC.gameDirectory.toPath().resolve("offlineclient");

        eventBus = new EventBus();
        friendManager = new FriendManager();
        commandManager = new CommandManager();
        moduleManager = new ModuleManager();

        configManager = new ConfigManager(folder);
        configManager.load();

        eventBus.register(this);
    }

    @Subscribe
    private void onTick(TickEvent event) {
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
