package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.NameListCommand;
import com.jellypudding.offlineclient.friend.FriendManager;

import java.util.List;
import java.util.Set;

public final class EnemyCommand extends NameListCommand {

    public EnemyCommand() {
        super("enemy", "Manages your enemies list. Enemies are drawn in their own colour and some alerts"
            + " can listen for them alone.", "enemy", "enemies", List.of());
    }

    private static FriendManager lists() {
        return OfflineClient.INSTANCE.getFriendManager();
    }

    @Override
    protected Set<String> names() {
        return lists().getEnemies();
    }

    @Override
    protected boolean add(String name) {
        return lists().addEnemy(name);
    }

    @Override
    protected boolean remove(String name) {
        return lists().removeEnemy(name);
    }

    @Override
    protected void clear() {
        lists().clearEnemies();
    }

    @Override
    protected String otherListOf(String name) {
        return lists().isFriend(name) ? "friends" : null;
    }
}
