package com.jellypudding.offlineclient.command;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.commands.BindCommand;
import com.jellypudding.offlineclient.command.commands.FriendCommand;
import com.jellypudding.offlineclient.command.commands.GuiCommand;
import com.jellypudding.offlineclient.command.commands.HelpCommand;
import com.jellypudding.offlineclient.command.commands.PrefixCommand;
import com.jellypudding.offlineclient.command.commands.ProfileCommand;
import com.jellypudding.offlineclient.command.commands.SetCommand;
import com.jellypudding.offlineclient.command.commands.ToggleCommand;
import com.jellypudding.offlineclient.command.commands.WaypointCommand;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ChatSendEvent;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.Setting;
import com.jellypudding.offlineclient.util.ChatUtil;
import net.minecraft.client.player.AbstractClientPlayer;

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
        new WaypointCommand()
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

        // A bare module name like .fastfall toggles the module.
        Module module = OfflineClient.INSTANCE.getModuleManager().get(name);
        if (module != null && !module.isTogglable()) {
            module = null;
        }
        if (module != null && args.length == 0) {
            module.toggle();
            ChatUtil.toggled(module);
            return true;
        }

        ChatUtil.error("Unknown command. Try §f" + prefix + "help");
        return true;
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
            return matches(current, names);
        }

        Command command = null;
        for (Command c : commands) {
            if (c.matches(tokens[0])) {
                command = c;
                break;
            }
        }
        if (command == null) {
            return List.of();
        }

        return switch (command.getName()) {
            case "toggle", "bind" -> index == 1 ? matches(current, moduleIds()) : List.of();
            case "set" -> completeSet(tokens, index, current);
            case "friend" -> completeFriend(tokens, index, current);
            case "profile" -> completeProfile(tokens, index, current);
            case "waypoint" -> completeWaypoint(tokens, index, current);
            default -> List.of();
        };
    }

    private List<String> completeWaypoint(String[] tokens, int index, String current) {
        if (index == 1) {
            return matches(current, List.of("add", "remove", "list", "clear"));
        }
        if (index == 2 && !tokens[1].equalsIgnoreCase("add")) {
            return matches(current, WaypointCommand.names());
        }
        return List.of();
    }

    private List<String> completeSet(String[] tokens, int index, String current) {
        if (index == 1) {
            return matches(current, moduleIds());
        }
        Module module = OfflineClient.INSTANCE.getModuleManager().get(tokens[1]);
        if (module == null) {
            return List.of();
        }
        if (index == 2) {
            List<String> ids = new ArrayList<>();
            for (Setting<?> setting : module.getSettings()) {
                ids.add(settingId(setting));
            }
            return matches(current, ids);
        }
        if (index == 3) {
            Setting<?> setting = module.getSetting(tokens[2]);
            return switch (setting) {
                case BoolSetting ignored -> matches(current, List.of("true", "false"));
                case EnumSetting<?> e -> {
                    List<String> options = new ArrayList<>();
                    for (Object constant : e.getValue().getDeclaringClass().getEnumConstants()) {
                        options.add(((Enum<?>) constant).name().toLowerCase(Locale.ROOT));
                    }
                    yield matches(current, options);
                }
                case ColorSetting ignored -> matches(current, List.of("rainbow"));
                case null, default -> List.of();
            };
        }
        return List.of();
    }

    private List<String> completeFriend(String[] tokens, int index, String current) {
        if (index == 1) {
            return matches(current, List.of("add", "remove", "list"));
        }
        if (index != 2) {
            return List.of();
        }
        if (tokens[1].equalsIgnoreCase("remove")) {
            return matches(current, new ArrayList<>(OfflineClient.INSTANCE.getFriendManager().getAll()));
        }
        if (tokens[1].equalsIgnoreCase("add") && OfflineClient.MC.level != null) {
            List<String> names = new ArrayList<>();
            String self = OfflineClient.MC.player == null
                ? "" : OfflineClient.MC.player.getGameProfile().name();
            for (AbstractClientPlayer player : OfflineClient.MC.level.players()) {
                String name = player.getGameProfile().name();
                if (!name.equalsIgnoreCase(self)
                    && !OfflineClient.INSTANCE.getFriendManager().isFriend(name)) {
                    names.add(name);
                }
            }
            return matches(current, names);
        }
        return List.of();
    }

    private List<String> completeProfile(String[] tokens, int index, String current) {
        if (index == 1) {
            return matches(current, List.of("save", "load", "list"));
        }
        if (index == 2 && tokens[1].equalsIgnoreCase("load")) {
            return matches(current, OfflineClient.INSTANCE.getConfigManager().listProfiles());
        }
        return List.of();
    }

    private static List<String> moduleIds() {
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

    private static List<String> matches(String current, List<String> options) {
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
