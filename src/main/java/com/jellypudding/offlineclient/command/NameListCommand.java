package com.jellypudding.offlineclient.command;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.config.NameStore;
import com.jellypudding.offlineclient.setting.ActionSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import net.minecraft.client.player.AbstractClientPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public abstract class NameListCommand extends Command {

    private static final String ADD = "add";
    private static final String REMOVE = "remove";
    private static final String LIST = "list";
    private static final String CLEAR = "clear";

    private final String one;
    private final String many;
    private final List<String> verbs;
    private final ActionSetting emptyList;

    // The escape is safe because the clear row runs the reference only when a later command presses it.
    @SuppressWarnings("this-escape")
    protected NameListCommand(String name, String description, String one, String many, List<String> ownVerbs,
                              String... aliases) {
        super(name, description, name + " <" + String.join("|", withOwn(ownVerbs)) + "> [name]", aliases);
        this.one = one;
        this.many = many;
        this.verbs = withOwn(ownVerbs);
        emptyList = new ActionSetting("Clear your " + many + " list", "Takes every name off your " + many + " list.",
            this::takeAllOff).confirm();
    }

    private static List<String> withOwn(List<String> ownVerbs) {
        List<String> all = new ArrayList<>(List.of(ADD, REMOVE, LIST, CLEAR));
        all.addAll(ownVerbs);
        return all;
    }

    protected abstract Set<String> names();

    protected abstract boolean add(String name);

    protected abstract boolean remove(String name);

    protected abstract void clear();

    protected abstract String otherListOf(String name);

    protected boolean runOwn(String verb, String[] args) {
        return false;
    }

    protected List<String> namesFor(String verb) {
        return List.of();
    }

    // A player who is called all or clear is still taken off by name.
    @Override
    public void execute(String[] args) {
        String verb = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (verb.equals(LIST) && args.length == 1) {
            list();
        } else if (verb.equals(CLEAR) && args.length == 1) {
            empty();
        } else if (verb.equals(ADD) && args.length == 2) {
            addNamed(args[1]);
        } else if (verb.equals(REMOVE) && args.length == 2) {
            removeNamed(args[1]);
        } else if (!runOwn(verb, args)) {
            usage();
        }
    }

    private void list() {
        if (names().isEmpty()) {
            ChatUtil.message("§7Your " + many + " list is empty.");
        } else {
            ChatUtil.message("§3" + ChatUtil.capitalised(many) + " §b" + String.join("§7 §b", names()));
        }
    }

    private void empty() {
        if (names().isEmpty()) {
            ChatUtil.message("§7Your " + many + " list is empty.");
        } else {
            press(emptyList);
        }
    }

    private void takeAllOff() {
        int count = names().size();
        clear();
        save();
        ChatUtil.message("§7Took all §f" + count + "§7 off your " + many + " list.");
    }

    private void addNamed(String name) {
        if (name.equalsIgnoreCase(NameStore.ownName())) {
            ChatUtil.error("You cannot add yourself as " + ChatUtil.withArticle(one) + ".");
            return;
        }
        String left = otherListOf(name);
        if (!add(name)) {
            ChatUtil.error(name + " is already " + ChatUtil.withArticle(one) + ".");
            return;
        }
        save();
        ChatUtil.message("§7Added §b" + name + "§7 to your " + many
            + (left == null ? "" : " and took them off your " + left) + ".");
    }

    private void removeNamed(String name) {
        if (remove(name)) {
            save();
            ChatUtil.message("§7Removed §b" + name + "§7 from your " + many + ".");
        } else {
            ChatUtil.error(name + " is not on your " + many + " list.");
        }
    }

    private static void save() {
        OfflineClient.INSTANCE.getConfigManager().saveSoon();
    }

    @Override
    public List<String> complete(String[] tokens, int index, String current) {
        if (index == 1) {
            return CommandManager.filter(current, verbs);
        }
        if (index != 2) {
            return List.of();
        }
        String verb = tokens[1].toLowerCase(Locale.ROOT);
        return CommandManager.filter(current, switch (verb) {
            case ADD -> inView();
            case REMOVE -> new ArrayList<>(names());
            default -> namesFor(verb);
        });
    }

    private List<String> inView() {
        List<String> found = new ArrayList<>();
        if (OfflineClient.MC.level == null) {
            return found;
        }
        String self = NameStore.ownName();
        for (AbstractClientPlayer player : OfflineClient.MC.level.players()) {
            String name = player.getGameProfile().name();
            if (!name.equalsIgnoreCase(self) && !names().contains(name)) {
                found.add(name);
            }
        }
        return found;
    }
}
