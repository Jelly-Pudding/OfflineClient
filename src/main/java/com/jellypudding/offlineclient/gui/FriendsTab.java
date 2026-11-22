package com.jellypudding.offlineclient.gui;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.config.NameStore;
import com.jellypudding.offlineclient.friend.FriendManager;
import com.jellypudding.offlineclient.util.ChatUtil;

import java.util.ArrayList;
import java.util.List;

final class FriendsTab implements TabView.Source {

    private static final String ENEMY = "enemy";
    private static final String FRIEND_EXAMPLE = "Steve";
    private static final String ENEMY_EXAMPLE = "Alex";

    @Override
    public List<TabView.Entry> entries(String query) {
        FriendManager lists = OfflineClient.INSTANCE.getFriendManager();
        List<TabView.Entry> rows = new ArrayList<>();
        for (String name : lists.getAll()) {
            if (GuiSources.matches(name, query)) {
                rows.add(new TabView.Entry(name, "friend",
                    "Friends are never targeted. Click to make " + name + " an enemy."));
            }
        }
        for (String name : lists.getEnemies()) {
            if (GuiSources.matches(name, query)) {
                rows.add(new TabView.Entry(name, "enemy",
                    "Enemies are drawn in the enemy colour. Click to make " + name + " a friend.", true));
            }
        }
        return rows;
    }

    @Override
    public String emptyText(String query) {
        return query.isEmpty() ? "nobody on either list yet"
            : "press enter to add " + GuiSources.firstWord(query) + (asEnemy(query) ? " as an enemy" : " as a friend");
    }

    @Override
    public String footer() {
        return "click a name to move it to the other list";
    }

    @Override
    public String addHint() {
        return "type a name to add a friend";
    }

    @Override
    public List<TabView.Hint> hints(String query) {
        FriendManager lists = OfflineClient.INSTANCE.getFriendManager();
        if (!query.isEmpty() || !lists.getAll().isEmpty() || !lists.getEnemies().isEmpty()) {
            return List.of();
        }
        return List.of(
            new TabView.Hint("Add a friend", "Type a name and press enter. Friends are never targeted.",
                List.of(FRIEND_EXAMPLE)),
            new TabView.Hint("Add an enemy", "Type the word " + ENEMY + " after the name. Enemies are drawn in the"
                + " enemy colour.", List.of(ENEMY_EXAMPLE + " " + ENEMY)));
    }

    @Override
    public void add(String text) {
        FriendManager lists = OfflineClient.INSTANCE.getFriendManager();
        String name = GuiSources.firstWord(text);
        boolean enemy = asEnemy(text);
        if (name.equalsIgnoreCase(NameStore.ownName())) {
            ChatUtil.error("You cannot add yourself as " + (enemy ? "an enemy." : "a friend."));
        } else if (enemy ? lists.addEnemy(name) : lists.add(name)) {
            OfflineClient.INSTANCE.getConfigManager().saveSoon();
        }
    }

    @Override
    public void activate(String name) {
        FriendManager lists = OfflineClient.INSTANCE.getFriendManager();
        if (lists.isFriend(name) ? lists.addEnemy(name) : lists.add(name)) {
            OfflineClient.INSTANCE.getConfigManager().saveSoon();
        }
    }

    @Override
    public void drop(String name) {
        FriendManager lists = OfflineClient.INSTANCE.getFriendManager();
        boolean friend = lists.remove(name);
        boolean enemy = lists.removeEnemy(name);
        if (friend || enemy) {
            OfflineClient.INSTANCE.getConfigManager().saveSoon();
        }
    }

    private static boolean asEnemy(String text) {
        String[] words = text.trim().split("\\s+");
        return words.length > 1 && words[1].equalsIgnoreCase(ENEMY);
    }
}
