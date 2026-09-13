package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.command.CommandManager;
import com.jellypudding.offlineclient.modules.misc.ClickGuiModule;
import com.jellypudding.offlineclient.util.ChatUtil;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.Comparator;
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

        List<Command> commands = new ArrayList<>(manager.getCommands());
        commands.sort(Comparator.comparing(Command::getName));
        MutableComponent line = Component.literal("§3Commands §8» ");
        for (int i = 0; i < commands.size(); i++) {
            if (i > 0) {
                line.append(Component.literal("§8 · "));
            }
            line.append(entry(prefix, commands.get(i)));
        }
        ChatUtil.component(line);
        ChatUtil.message("§7Hover a name for what it does and click it to type it. §f" + prefix
            + "help <command>§7 says the same.");
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

    // One name in the list. The hover carries the usage and the click types it in.
    private static Component entry(String prefix, Command command) {
        Component hover = Component.literal("§b" + prefix + command.getUsage() + "\n§7"
            + command.getDescription());
        return Component.literal("§b" + command.getName())
            .withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(hover))
                .withClickEvent(new ClickEvent.SuggestCommand(prefix + command.getName() + " ")));
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
