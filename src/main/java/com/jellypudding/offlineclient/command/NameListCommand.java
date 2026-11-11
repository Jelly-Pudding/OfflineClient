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

// A command over a list of player names. Adding and removing and listing and emptying work the
// same for every list. Each command says where its names live and may add verbs of its own.
// The config holds the lists and is written after every edit.
public abstract class NameListCommand extends Command {

    private static final String ADD = "add";
    private static final String REMOVE = "remove";
    private static final String LIST = "list";
    private static final String CLEAR = "clear";

    // One player on the list as chat calls them such as friend and the list itself such as friends.
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

    // False when the name was on the list already.
    protected abstract boolean add(String name);

    // False when the name was not on the list.
    protected abstract boolean remove(String name);

    protected abstract void clear();

    // The other list as chat calls it when the name sits on it. Adding the name here takes it
    // off there. Null when it sits on no other list.
    protected abstract String otherListOf(String name);

    // Runs one of the command's own verbs. False for a verb it does not know.
    protected boolean runOwn(String verb, String[] args) {
        return false;
    }

    // The names one of the command's own verbs takes.
    protected List<String> namesFor(String verb) {
        return List.of();
    }

    // Clear empties the list and takes no name. A player who is called all or clear is still
    // taken off by name.
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

    // A list with names on it waits for the command a second time before it is emptied.
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

    // A name that moves over from the other list is said to have left it.
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

    // The players in view who could still go on the list.
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
