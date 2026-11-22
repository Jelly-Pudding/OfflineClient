package com.jellypudding.offlineclient.gui;

import com.jellypudding.offlineclient.config.NameStore;
import com.jellypudding.offlineclient.util.ChatUtil;

import java.util.ArrayList;
import java.util.List;

public final class NamesTab implements TabView.Source {

    private static final String ACCOUNT = "account";

    @Override
    public List<TabView.Entry> entries(String query) {
        NameStore store = NameStore.get();
        String account = NameStore.accountName();
        String joining = store.joinName();
        List<TabView.Entry> rows = new ArrayList<>();
        if (GuiSources.matches(account, query)) {
            rows.add(new TabView.Entry(account, account.equals(joining) ? GuiSources.IN_USE : ACCOUNT,
                "Your account name. An online mode server takes no other."));
        }
        for (String name : store.saved()) {
            if (GuiSources.matches(name, query)) {
                rows.add(new TabView.Entry(name, name.equals(joining) ? GuiSources.IN_USE : "",
                    "Joins offline servers as " + name + " from your next join."));
            }
        }
        return rows;
    }

    @Override
    public String emptyText(String query) {
        String word = GuiSources.firstWord(query);
        if (word.equalsIgnoreCase(NameStore.RANDOM)) {
            return "press enter for a made up name";
        }
        return NameStore.valid(word) ? "press enter to join as " + word
            : "a name has 3 to 16 letters or digits or underscores";
    }

    @Override
    public String footer() {
        return "click a name to join as it";
    }

    @Override
    public String addHint() {
        return "type a name and press enter";
    }

    @Override
    public List<TabView.Hint> hints(String query) {
        if (!query.isEmpty() || !NameStore.get().saved().isEmpty()) {
            return List.of();
        }
        return List.of(new TabView.Hint("Add a name",
            "Type a name and press enter. The word random makes one up.", List.of(NameStore.RANDOM)));
    }

    @Override
    public void add(String text) {
        String name = NameStore.named(GuiSources.firstWord(text));
        if (name == null) {
            ChatUtil.error(NameStore.RULE);
            return;
        }
        NameStore.get().use(name);
    }

    @Override
    public void activate(String name) {
        NameStore.get().use(name);
    }

    @Override
    public String dropHint(String name) {
        return name.equals(NameStore.accountName()) ? "Your account name always stays"
            : "Forget the name " + name;
    }

    @Override
    public void drop(String name) {
        NameStore.get().remove(name);
    }
}
