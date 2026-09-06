package com.jellypudding.offlineclient.command;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.commands.BindCommand;
import com.jellypudding.offlineclient.command.commands.BindsCommand;
import com.jellypudding.offlineclient.command.commands.ClearCommand;
import com.jellypudding.offlineclient.command.commands.DamageCommand;
import com.jellypudding.offlineclient.command.commands.DisconnectCommand;
import com.jellypudding.offlineclient.command.commands.DismountCommand;
import com.jellypudding.offlineclient.command.commands.DropCommand;
import com.jellypudding.offlineclient.command.commands.EnderChestCommand;
import com.jellypudding.offlineclient.command.commands.FovCommand;
import com.jellypudding.offlineclient.command.commands.FriendCommand;
import com.jellypudding.offlineclient.command.commands.GetPosCommand;
import com.jellypudding.offlineclient.command.commands.GotoCommand;
import com.jellypudding.offlineclient.command.commands.GuiCommand;
import com.jellypudding.offlineclient.command.commands.HClipCommand;
import com.jellypudding.offlineclient.command.commands.AuthorCommand;
import com.jellypudding.offlineclient.command.commands.JumpCommand;
import com.jellypudding.offlineclient.command.commands.SimulationDistanceCommand;
import com.jellypudding.offlineclient.command.commands.TpCommand;
import com.jellypudding.offlineclient.command.commands.HelpCommand;
import com.jellypudding.offlineclient.command.commands.HudCommand;
import com.jellypudding.offlineclient.command.commands.MacroCommand;
import com.jellypudding.offlineclient.command.commands.ModulesCommand;
import com.jellypudding.offlineclient.command.commands.NbtCommand;
import com.jellypudding.offlineclient.command.commands.PeekCommand;
import com.jellypudding.offlineclient.command.commands.PrefixCommand;
import com.jellypudding.offlineclient.command.commands.ProfileCommand;
import com.jellypudding.offlineclient.command.commands.RenderDistanceCommand;
import com.jellypudding.offlineclient.command.commands.ResetCommand;
import com.jellypudding.offlineclient.command.commands.RotationCommand;
import com.jellypudding.offlineclient.command.commands.SayCommand;
import com.jellypudding.offlineclient.command.commands.ServerCommand;
import com.jellypudding.offlineclient.command.commands.SetCommand;
import com.jellypudding.offlineclient.command.commands.ToggleCommand;
import com.jellypudding.offlineclient.command.commands.VClipCommand;
import com.jellypudding.offlineclient.command.commands.WaypointCommand;
import com.jellypudding.offlineclient.command.commands.XRayCommand;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ChatSendEvent;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.Setting;
import com.jellypudding.offlineclient.util.ChatUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class CommandManager {

    private final List<Command> commands = List.of(
        new HelpCommand(),
        new GuiCommand(),
        new ToggleCommand(),
        new BindCommand(),
        new SetCommand(),
        new FriendCommand(),
        new PrefixCommand(),
        new ProfileCommand(),
        new WaypointCommand(),
        new ModulesCommand(),
        new BindsCommand(),
        new ResetCommand(),
        new SayCommand(),
        new ServerCommand(),
        new GetPosCommand(),
        new GotoCommand(),
        new VClipCommand(),
        new HClipCommand(),
        new DropCommand(),
        new DismountCommand(),
        new FovCommand(),
        new ClearCommand(),
        new DisconnectCommand(),
        new PeekCommand(),
        new NbtCommand(),
        new XRayCommand(),
        new RenderDistanceCommand(),
        new RotationCommand(),
        new DamageCommand(),
        new EnderChestCommand(),
        new MacroCommand(),
        new HudCommand(),
        new TpCommand(),
        new SimulationDistanceCommand(),
        new JumpCommand(),
        new AuthorCommand()
    );

    public static final String DEFAULT_PREFIX = ".";

    private String prefix = DEFAULT_PREFIX;

    public CommandManager() {
        OfflineClient.INSTANCE.getEventBus().register(this);
    }

    public String getPrefix() {
        return prefix;
    }

    // False when the prefix is blank or would collide with server commands.
    public boolean setPrefix(String prefix) {
        if (prefix.isBlank() || prefix.startsWith("/")) {
            return false;
        }
        this.prefix = prefix.trim();
        return true;
    }

    public List<Command> getCommands() {
        return commands;
    }

    // False when the message is not a command.
    public boolean run(String message) {
        if (!message.startsWith(prefix) || message.length() <= prefix.length()) {
            return false;
        }
        String[] parts = message.substring(prefix.length()).trim().split("\\s+");
        String name = parts[0];
        String[] args = Arrays.copyOfRange(parts, 1, parts.length);

        for (Command command : commands) {
            if (command.matches(name)) {
                try {
                    command.execute(args);
                } catch (Exception e) {
                    ChatUtil.error("Error: " + e.getMessage());
                    OfflineClient.LOG.error("Command {} failed", name, e);
                }
                return true;
            }
        }

        // A bare module name like .fastfall toggles the module. Anything after
        // it reads as a setting so .step height 5 is short for .set step height 5.
        Module module = OfflineClient.INSTANCE.getModuleManager().get(name);
        if (module != null) {
            if (args.length > 0) {
                return runSet(name, args);
            }
            if (module.isTogglable()) {
                module.toggle();
                ChatUtil.toggled(module);
                return true;
            }
        }

        ChatUtil.error("Unknown command. Try §f" + prefix + "help");
        return true;
    }

    private boolean runSet(String moduleName, String[] args) {
        String[] setArgs = new String[args.length + 1];
        setArgs[0] = moduleName;
        System.arraycopy(args, 0, setArgs, 1, args.length);
        try {
            find("set").execute(setArgs);
        } catch (Exception e) {
            ChatUtil.error("Error: " + e.getMessage());
            OfflineClient.LOG.error("Command set failed", e);
        }
        return true;
    }

    // Null when nothing answers to the name or one of its aliases.
    private Command find(String name) {
        for (Command command : commands) {
            if (command.matches(name)) {
                return command;
            }
        }
        return null;
    }

    @Subscribe(priority = 100)
    private void onChatSend(ChatSendEvent event) {
        if (run(event.getMessage())) {
            event.cancel();
        }
    }

    // Completions for the last token of the chat text as typed.
    public List<String> complete(String text) {
        if (!text.startsWith(prefix)) {
            return List.of();
        }
        String[] tokens = text.substring(prefix.length()).split("\\s+", -1);
        int index = tokens.length - 1;
        String current = tokens[index].toLowerCase(Locale.ROOT);

        if (index == 0) {
            List<String> names = new ArrayList<>();
            for (Command command : commands) {
                names.add(command.getName());
            }
            names.addAll(moduleIds());
            return filter(current, names);
        }

        Command command = find(tokens[0]);
        if (command == null) {
            // A module name in front reads like the set command without the word set.
            if (OfflineClient.INSTANCE.getModuleManager().get(tokens[0]) == null) {
                return List.of();
            }
            String[] shifted = new String[tokens.length + 1];
            shifted[0] = "set";
            System.arraycopy(tokens, 0, shifted, 1, tokens.length);
            return find("set").complete(shifted, index + 1, current);
        }

        return command.complete(tokens, index, current);
    }





    public static List<String> moduleIds() {
        List<String> ids = new ArrayList<>();
        for (Module module : OfflineClient.INSTANCE.getModuleManager().getAll()) {
            ids.add(module.getName().toLowerCase(Locale.ROOT));
        }
        return ids;
    }

    // Lowercase without spaces.
    public static String settingId(Setting<?> setting) {
        return setting.getName().replace(" ", "").toLowerCase(Locale.ROOT);
    }

    // The options that carry on from what has been typed.
    public static List<String> filter(String current, List<String> options) {
        List<String> result = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(current)) {
                result.add(option);
            }
        }
        result.sort(String.CASE_INSENSITIVE_ORDER);
        return result;
    }
}
