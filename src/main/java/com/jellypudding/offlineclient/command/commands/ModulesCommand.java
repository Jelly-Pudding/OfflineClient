package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.command.CommandManager;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.util.ChatUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ModulesCommand extends Command {

    public ModulesCommand() {
        super("modules", "Lists modules by category and shows which are on.",
            "modules [category]", "list");
    }

    @Override
    public void execute(String[] args) {
        Category only = null;
        if (args.length == 1) {
            only = categoryNamed(args[0]);
            if (only == null) {
                ChatUtil.error("Unknown category: " + args[0]);
                return;
            }
        } else if (args.length > 1) {
            usage();
            return;
        }
        for (Category category : Category.values()) {
            if (only != null && category != only) {
                continue;
            }
            List<String> names = new ArrayList<>();
            for (Module module : OfflineClient.INSTANCE.getModuleManager().getAll()) {
                if (module.getCategory() == category) {
                    names.add((module.isEnabled() ? "§a" : "§7") + module.getName());
                }
            }
            ChatUtil.message("§3" + category.getDisplayName() + " §8(" + names.size()
                + ")§7 " + String.join("§8 ", names));
        }
    }

    private static Category categoryNamed(String text) {
        for (Category category : Category.values()) {
            if (category.name().equalsIgnoreCase(text)) {
                return category;
            }
        }
        return null;
    }

    @Override
    public List<String> complete(String[] tokens, int index, String current) {
        if (index != 1) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (Category category : Category.values()) {
            names.add(category.name().toLowerCase(Locale.ROOT));
        }
        return CommandManager.filter(current, names);
    }
}
