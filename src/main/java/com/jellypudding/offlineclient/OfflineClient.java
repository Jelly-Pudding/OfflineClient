package com.jellypudding.offlineclient;

import com.jellypudding.offlineclient.command.CommandManager;
import com.jellypudding.offlineclient.config.ConfigManager;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.modules.misc.ClickGuiModule;
import com.jellypudding.offlineclient.config.MacroStore;
import com.jellypudding.offlineclient.event.EventBus;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.friend.FriendManager;
import com.jellypudding.offlineclient.module.ModuleManager;
import com.jellypudding.offlineclient.util.Hop;
import com.jellypudding.offlineclient.util.Lagback;
import com.jellypudding.offlineclient.util.MoveGate;
import com.jellypudding.offlineclient.util.RotationManager;
import com.jellypudding.offlineclient.util.TickRate;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


// A Fabric client made for minecraftoffline.net
public enum OfflineClient {
    INSTANCE;

    public static final String NAME = "OfflineClient";
    public static final String VERSION = "0.9.0";
    public static final String SERVER_NAME = "minecraftoffline.net";

    // Read again in init. Anything that loads this class before the game has finished
    // building itself captures a null here and would keep it for the whole run.
    public static Minecraft MC = Minecraft.getInstance();

    public static final Logger LOG = LoggerFactory.getLogger(NAME);

    private EventBus eventBus;
    private FriendManager friendManager;
    private CommandManager commandManager;
    private ModuleManager moduleManager;
    private ConfigManager configManager;

    // Whether the player was in a world last tick.
    private boolean wasInWorld;

    public void init() {
        MC = Minecraft.getInstance();
        LOG.info("Starting {} v{} for {}", NAME, VERSION, SERVER_NAME);

        eventBus = new EventBus();
        friendManager = new FriendManager();
        commandManager = new CommandManager();
        configManager = new ConfigManager();
        moduleManager = new ModuleManager();
        moduleManager.enableDefaults();
        configManager.load();

        eventBus.register(this);
        eventBus.register(RotationManager.INSTANCE);
        eventBus.register(MoveGate.INSTANCE);
        eventBus.register(Hop.INSTANCE);
        eventBus.register(TickRate.INSTANCE);
        eventBus.register(Lagback.INSTANCE);
        // Loading the macros puts their key handler on the bus.
        MacroStore.get();
    }

    // Not TickEvent because settings also change from the menus.
    @Subscribe
    private void onTick(ClientTickEvent event) {
        configManager.tick();
        greetOnFirstJoin();
    }

    // Says hello the first time a fresh install reaches a world.
    private void greetOnFirstJoin() {
        boolean inWorld = MC.player != null;
        if (inWorld && !wasInWorld && configManager.needsGreeting()) {
            String key = moduleManager.get(ClickGuiModule.class).getKeybind().getKeyName();
            ChatUtil.message("§7Welcome to §b" + NAME + "§7. Press §b" + key
                + "§7 for the menu.");
            ChatUtil.message("§7Type §f" + commandManager.getPrefix()
                + "help§7 for everything it can do.");
        }
        wasInWorld = inWorld;
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
