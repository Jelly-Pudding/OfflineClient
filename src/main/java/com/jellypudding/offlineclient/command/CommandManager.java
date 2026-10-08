package com.jellypudding.offlineclient.command;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.commands.BindCommand;
import com.jellypudding.offlineclient.command.commands.BindsCommand;
import com.jellypudding.offlineclient.command.commands.CenterCommand;
import com.jellypudding.offlineclient.command.commands.ChatBotCommand;
import com.jellypudding.offlineclient.command.commands.ChestsCommand;
import com.jellypudding.offlineclient.command.commands.ChunkDistanceCommand;
import com.jellypudding.offlineclient.command.commands.ClearCommand;
import com.jellypudding.offlineclient.command.commands.DamageCommand;
import com.jellypudding.offlineclient.command.commands.DisconnectCommand;
import com.jellypudding.offlineclient.command.commands.DismountCommand;
import com.jellypudding.offlineclient.command.commands.DossierCommand;
import com.jellypudding.offlineclient.command.commands.DropCommand;
import com.jellypudding.offlineclient.command.commands.EnderChestCommand;
import com.jellypudding.offlineclient.command.commands.ExportCommand;
import com.jellypudding.offlineclient.command.commands.FindsCommand;
import com.jellypudding.offlineclient.command.commands.FloorCommand;
import com.jellypudding.offlineclient.command.commands.FlowTimeCommand;
import com.jellypudding.offlineclient.command.commands.FovCommand;
import com.jellypudding.offlineclient.command.commands.FriendCommand;
import com.jellypudding.offlineclient.command.commands.GetPosCommand;
import com.jellypudding.offlineclient.command.commands.GotoCommand;
import com.jellypudding.offlineclient.command.commands.GuiCommand;
import com.jellypudding.offlineclient.command.commands.HClipCommand;
import com.jellypudding.offlineclient.command.commands.AuthorCommand;
import com.jellypudding.offlineclient.command.commands.AutoLoginCommand;
import com.jellypudding.offlineclient.command.commands.AutopilotCommand;
import com.jellypudding.offlineclient.command.commands.JumpCommand;
import com.jellypudding.offlineclient.command.commands.LoadoutCommand;
import com.jellypudding.offlineclient.command.commands.TpCommand;
import com.jellypudding.offlineclient.command.commands.HelpCommand;
import com.jellypudding.offlineclient.command.commands.HologramCommand;
import com.jellypudding.offlineclient.command.commands.HudCommand;
import com.jellypudding.offlineclient.command.commands.MacroCommand;
import com.jellypudding.offlineclient.command.commands.MemoryCommand;
import com.jellypudding.offlineclient.command.commands.ModulesCommand;
import com.jellypudding.offlineclient.command.commands.MuteCommand;
import com.jellypudding.offlineclient.command.commands.NameCommand;
import com.jellypudding.offlineclient.command.commands.NbtCommand;
import com.jellypudding.offlineclient.command.commands.NoReturnCommand;
import com.jellypudding.offlineclient.command.commands.PeekCommand;
import com.jellypudding.offlineclient.command.commands.PrefixCommand;
import com.jellypudding.offlineclient.command.commands.ProfileCommand;
import com.jellypudding.offlineclient.command.commands.ProxyCommand;
import com.jellypudding.offlineclient.command.commands.ResetCommand;
import com.jellypudding.offlineclient.command.commands.RotationCommand;
import com.jellypudding.offlineclient.command.commands.SaveSkinCommand;
import com.jellypudding.offlineclient.command.commands.SayCommand;
import com.jellypudding.offlineclient.command.commands.SeedCommand;
import com.jellypudding.offlineclient.command.commands.ServerCommand;
import com.jellypudding.offlineclient.command.commands.SetCommand;
import com.jellypudding.offlineclient.command.commands.ToggleCommand;
import com.jellypudding.offlineclient.command.commands.VClipCommand;
import com.jellypudding.offlineclient.command.commands.VelocityCommand;
import com.jellypudding.offlineclient.command.commands.WaypointCommand;
import com.jellypudding.offlineclient.command.commands.WorldCommand;
import com.jellypudding.offlineclient.command.commands.XRayCommand;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ChatSendEvent;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.ClientLines;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class CommandManager {

    private final List<Command> commands = List.of(
        new HelpCommand(),
        new GuiCommand(),
        new ToggleCommand(),
        new BindCommand(),
        new SetCommand(),
        new FriendCommand(),
        new MuteCommand(),
        new PrefixCommand(),
        new ProfileCommand(),
        new WaypointCommand(),
        new FindsCommand(),
        new DossierCommand(),
        new ModulesCommand(),
        new BindsCommand(),
        new ResetCommand(),
        new SayCommand(),
        new ServerCommand(),
        new WorldCommand(),
        new AutoLoginCommand(),
        new ChatBotCommand(),
        new NameCommand(),
        new ProxyCommand(),
        new GetPosCommand(),
        new GotoCommand(),
        new AutopilotCommand(),
        new NoReturnCommand(),
        new VClipCommand(),
        FloorCommand.up(),
        FloorCommand.down(),
        FloorCommand.top(),
        new HClipCommand(),
        new CenterCommand(),
        new DropCommand(),
        new DismountCommand(),
        new FovCommand(),
        new ClearCommand(),
        new MemoryCommand(),
        new DisconnectCommand(),
        new PeekCommand(),
        new NbtCommand(),
        new HologramCommand(),
        new SaveSkinCommand(),
        new ExportCommand(),
        new XRayCommand(),
        new SeedCommand(),
        ChunkDistanceCommand.render(),
        new RotationCommand(),
        new DamageCommand(),
        new EnderChestCommand(),
        new ChestsCommand(),
        new MacroCommand(),
        new LoadoutCommand(),
        new HudCommand(),
        new TpCommand(),
        ChunkDistanceCommand.simulation(),
        new FlowTimeCommand(),
        new JumpCommand(),
        new VelocityCommand(),
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
        String trimmed = prefix.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("/")) {
            return false;
        }
        this.prefix = trimmed;
        return true;
    }

    public List<Command> getCommands() {
        return commands;
    }

    // False when the message is not a command. The replies to a command stay in the chat.
    public boolean run(String message) {
        if (!message.startsWith(prefix) || message.length() <= prefix.length()) {
            return false;
        }
        String[] parts = message.substring(prefix.length()).trim().split("\\s+");
        ClientLines.inChat(() -> dispatch(parts));
        return true;
    }

    // A bare module name like .fastfall toggles the module even where a command shares the
    // name. Anything after it reads as a setting and .step height 5 is short for .set step
    // height 5. A word naming one of the module's settings wins over a command of the same
    // name unless it stands alone and the command takes that word itself.
    private void dispatch(String[] parts) {
        String name = parts[0];
        String[] args = Arrays.copyOfRange(parts, 1, parts.length);
        Module module = OfflineClient.INSTANCE.getModuleManager().get(name);
        if (module != null && args.length == 0 && module.isTogglable()) {
            module.toggle();
            ChatUtil.toggled(module);
            return;
        }
        // On or off after a module name switches the module and never a lone setting.
        Boolean state = module != null && args.length == 1 ? ToggleCommand.stateNamed(args[0]) : null;
        if (state != null) {
            ToggleCommand.switchTo(module, state);
            return;
        }
        Command command = find(name);
        boolean toSetting = module != null && args.length > 0 && module.getSetting(args[0]) != null
            && (command == null || args.length > 1 || !takesWord(command, parts));
        if (command != null && !toSetting) {
            execute(command, args);
        } else if (module != null && args.length > 0) {
            runSet(name, args);
        } else {
            ChatUtil.error("Unknown command. Try §f" + prefix + "help");
        }
    }

    // Whether the command offers the first word after its name as one it takes.
    private static boolean takesWord(Command command, String[] tokens) {
        String word = tokens[1];
        for (String option : command.complete(tokens, 1, word.toLowerCase(Locale.ROOT))) {
            if (option.equalsIgnoreCase(word)) {
                return true;
            }
        }
        return false;
    }

    private void runSet(String moduleName, String[] args) {
        String[] setArgs = new String[args.length + 1];
        setArgs[0] = moduleName;
        System.arraycopy(args, 0, setArgs, 1, args.length);
        execute(find("set"), setArgs);
    }

    // A command that throws tells the player why and the log where.
    private static void execute(Command command, String[] args) {
        try {
            command.execute(args);
        } catch (Exception e) {
            ChatUtil.error(String.valueOf(e.getMessage()));
            OfflineClient.LOG.error("Command {} failed", command.getName(), e);
        }
    }

    // Null when nothing answers to the name or one of its aliases.
    public Command find(String name) {
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
            // A module that shares a command's name is offered once.
            Set<String> names = new LinkedHashSet<>();
            for (Command command : commands) {
                names.add(command.getName());
            }
            names.addAll(moduleIds());
            return filter(current, new ArrayList<>(names));
        }

        Command command = find(tokens[0]);
        Module module = OfflineClient.INSTANCE.getModuleManager().get(tokens[0]);
        if (module == null) {
            return command == null ? List.of() : command.complete(tokens, index, current);
        }
        // A module name in front reads like the set command without the word set. A command of
        // the same name offers its own first words too and completes the rest unless a setting is named.
        if (command != null && index == 1) {
            Set<String> words = new LinkedHashSet<>(command.complete(tokens, index, current));
            words.addAll(module.settingIds());
            return filter(current, new ArrayList<>(words));
        }
        if (command != null && module.getSetting(tokens[1]) == null) {
            return command.complete(tokens, index, current);
        }
        String[] shifted = new String[tokens.length + 1];
        shifted[0] = "set";
        System.arraycopy(tokens, 0, shifted, 1, tokens.length);
        return find("set").complete(shifted, index + 1, current);
    }

    public static List<String> moduleIds() {
        List<String> ids = new ArrayList<>();
        for (Module module : OfflineClient.INSTANCE.getModuleManager().getAll()) {
            ids.add(module.getName().toLowerCase(Locale.ROOT));
        }
        return ids;
    }

    // The entry a player typed with or without its namespace. Null for none.
    public static <T> T registryEntry(Registry<T> registry, String text) {
        Identifier id = Identifier.tryParse(text.toLowerCase(Locale.ROOT));
        return id == null ? null : registry.getOptional(id).orElse(null);
    }

    // The path of every entry such as oak_log.
    public static List<String> registryPaths(Registry<?> registry) {
        List<String> paths = new ArrayList<>();
        for (Identifier id : registry.keySet()) {
            paths.add(id.getPath());
        }
        return paths;
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
