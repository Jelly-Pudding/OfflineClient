package com.jellypudding.offlineclient.friend;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

// Friends are never targeted by combat modules. Enemies are picked out wherever players are
// coloured and some alerts listen for them alone. A name sits on one list at most and both
// match whatever the case. The config saves the two lists side by side.
public final class FriendManager {

    private final Set<String> friends = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    private final Set<String> friendsView = Collections.unmodifiableSet(friends);
    private final Set<String> enemies = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    private final Set<String> enemiesView = Collections.unmodifiableSet(enemies);

    public boolean isFriend(String name) {
        return friends.contains(name);
    }

    // A new friend stops being an enemy.
    public boolean add(String name) {
        if (!friends.add(name)) {
            return false;
        }
        enemies.remove(name);
        return true;
    }

    public boolean remove(String name) {
        return friends.remove(name);
    }

    public void clear() {
        friends.clear();
    }

    public Set<String> getAll() {
        return friendsView;
    }

    public boolean isEnemy(String name) {
        return enemies.contains(name);
    }

    // A new enemy stops being a friend.
    public boolean addEnemy(String name) {
        if (!enemies.add(name)) {
            return false;
        }
        friends.remove(name);
        return true;
    }

    public boolean removeEnemy(String name) {
        return enemies.remove(name);
    }

    public void clearEnemies() {
        enemies.clear();
    }

    public Set<String> getEnemies() {
        return enemiesView;
    }
}
