package com.jellypudding.offlineclient.friend;

import java.util.Set;
import java.util.TreeSet;

// Players on this list are never targeted by combat modules.
public final class FriendManager {

    private final Set<String> friends = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

    public boolean isFriend(String name) {
        return friends.contains(name);
    }

    public boolean add(String name) {
        return friends.add(name);
    }

    public boolean remove(String name) {
        return friends.remove(name);
    }

    public Set<String> getAll() {
        return friends;
    }
}
