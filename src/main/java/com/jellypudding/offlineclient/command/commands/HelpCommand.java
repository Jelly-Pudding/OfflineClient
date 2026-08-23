package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.modules.misc.ClickGuiModule;
import com.jellypudding.offlineclient.util.ChatUtil;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;

public final class HelpCommand extends Command {

    public HelpCommand() {
        super("help", "Lists all commands.", "help", "commands");
    }

    @Override
    public void execute(String[] args) {
        String prefix = OfflineClient.INSTANCE.getCommandManager().getPrefix();
        ChatUtil.message("§3Commands:");
        for (Command command : OfflineClient.INSTANCE.getCommandManager().getCommands()) {
            ChatUtil.message("§b" + prefix + command.getUsage() + " §8» §7" + command.getDescription());
        }
        ChatUtil.message("§7Typing just a module name like §b" + prefix + "speed§7 toggles it.");
        ChatUtil.message("§7Press §bTAB§7 whilst typing a command to autocomplete.");

        String key = OfflineClient.INSTANCE.getModuleManager()
            .get(ClickGuiModule.class).getKeybind().getKeyName();
        Component link = Component.literal("§b§nopen it now")
            .withStyle(style -> style.withClickEvent(new ClickEvent.RunCommand(prefix + "gui")));
        ChatUtil.component(Component.literal("§7The ClickGUI opens with §b" + key
                + "§7. Rebind it with §f" + prefix + "bind clickgui <key>§7 or click here to ")
            .append(link)
            .append(Component.literal("§7.")));
    }
}
