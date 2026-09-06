package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.command.CommandManager;
import com.jellypudding.offlineclient.modules.misc.ClickGuiModule;
import com.jellypudding.offlineclient.util.ChatUtil;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

public final class HelpCommand extends Command {

    public HelpCommand() {
        super("help", "Lists every command or explains one of them.", "help [command]", "commands");
    }

    @Override
    public void execute(String[] args) {
        CommandManager manager = OfflineClient.INSTANCE.getCommandManager();
        String prefix = manager.getPrefix();
        if (args.length > 1) {
            usage();
            return;
        }
        if (args.length == 1) {
            describe(manager, prefix, args[0]);
            return;
        }

        List<String> names = new ArrayList<>();
        for (Command command : manager.getCommands()) {
            names.add(command.getName());
        }
        ChatUtil.message("§3Commands §8» §b" + String.join("§8 ", names));
        ChatUtil.message("§7Type §f" + prefix + "help <command>§7 for what one of them does.");
        ChatUtil.message("§7A module name on its own toggles it. §f" + prefix
            + "step§7 toggles Step and §f" + prefix + "step height 2§7 changes a setting.");

        String key = OfflineClient.INSTANCE.getModuleManager()
            .get(ClickGuiModule.class).getKeybind().getKeyName();
        Component link = Component.literal("§b§nopen it now")
            .withStyle(style -> style.withClickEvent(new ClickEvent.RunCommand(prefix + "gui")));
        ChatUtil.component(Component.literal("§7The ClickGUI opens with §b" + key
                + "§7. Rebind it with §f" + prefix + "bind clickgui <key>§7 or click here to ")
            .append(link)
            .append(Component.literal("§7.")));
    }

    private static void describe(CommandManager manager, String prefix, String name) {
        for (Command command : manager.getCommands()) {
            if (command.matches(name)) {
                ChatUtil.message("§b" + prefix + command.getUsage() + " §8» §7"
                    + command.getDescription());
                return;
            }
        }
        ChatUtil.error("There is no command called " + name);
    }

    @Override
    public List<String> complete(String[] tokens, int index, String current) {
        if (index != 1) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (Command command : OfflineClient.INSTANCE.getCommandManager().getCommands()) {
            names.add(command.getName());
        }
        return CommandManager.filter(current, names);
    }
}
