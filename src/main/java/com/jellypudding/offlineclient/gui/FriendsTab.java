package com.jellypudding.offlineclient.gui;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.config.NameStore;
import com.jellypudding.offlineclient.friend.FriendManager;
import com.jellypudding.offlineclient.util.ChatUtil;

import java.util.ArrayList;
import java.util.List;

// The friends from A to Z and then the enemies. A click on a name moves it to the other
// list and a name typed with the word enemy after it goes on the enemies.
final class FriendsTab implements TabView.Source {

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
        return "type a name or a name and the word enemy";
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

    // True for a name typed with the word enemy after it.
    private static boolean asEnemy(String text) {
        String[] words = text.trim().split("\\s+");
        return words.length > 1 && words[1].equalsIgnoreCase("enemy");
    }
}
