package com.jellypudding.offlineclient.command;

import com.jellypudding.offlineclient.util.ChatUtil;

import java.util.List;

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
        ChatUtil.error("Usage: " + usage);
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
