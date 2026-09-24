package com.jellypudding.offlineclient.command;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.Hop;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;

public abstract class Command {

    private final String name;
    private final String description;
    private final String usage;
    private final String[] aliases;

    protected Command(String name, String description, String usage, String... aliases) {
        this.name = name;
        this.description = description;
        this.usage = usage;
        this.aliases = aliases;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getUsage() {
        return usage;
    }

    protected void usage() {
        usage(usage);
    }

    // Shows the player how to type one form of the command.
    protected void usage(String form) {
        ChatUtil.error("Type " + OfflineClient.INSTANCE.getCommandManager().getPrefix() + form);
    }

    // Empty after telling the player the text is not a number.
    protected static OptionalDouble number(String text) {
        double value;
        try {
            value = Double.parseDouble(text);
        } catch (NumberFormatException e) {
            value = Double.NaN;
        }
        if (!Double.isFinite(value)) {
            ChatUtil.error(text + " is not a number.");
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(value);
    }

    // Empty after telling the player the text is not a whole number.
    protected static OptionalInt wholeNumber(String text) {
        try {
            return OptionalInt.of(Integer.parseInt(text));
        } catch (NumberFormatException e) {
            ChatUtil.error(text + " is not a whole number.");
            return OptionalInt.empty();
        }
    }

    // Moves the player there in one hop and says how it went.
    protected static void hopTo(Vec3 spot, String done) {
        Hop.Result result = Hop.to(spot);
        if (result == Hop.Result.MOVED) {
            ChatUtil.message(done);
        } else {
            ChatUtil.error(result.problem());
        }
    }

    // Null after telling the player there is no module with that name.
    protected Module module(String name) {
        Module module = OfflineClient.INSTANCE.getModuleManager().get(name);
        if (module == null) {
            ChatUtil.error("There is no module called " + name + ".");
        }
        return module;
    }

    public boolean matches(String input) {
        if (name.equalsIgnoreCase(input)) {
            return true;
        }
        for (String alias : aliases) {
            if (alias.equalsIgnoreCase(input)) {
                return true;
            }
        }
        return false;
    }

    public abstract void execute(String[] args);

    // Suggestions for the token being typed. Token nought is the command name itself
    // and the first argument is index one.
    public List<String> complete(String[] tokens, int index, String current) {
        return List.of();
    }
}
