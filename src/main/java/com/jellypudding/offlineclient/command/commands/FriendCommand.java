package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.NameListCommand;
import com.jellypudding.offlineclient.friend.FriendManager;
import com.jellypudding.offlineclient.modules.misc.SecretChat;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.Modules;

import java.util.List;
import java.util.Set;

public final class FriendCommand extends NameListCommand {

    private static final String PING = "ping";

    public FriendCommand() {
        super("friend", "Manages your friends list. Friends are never targeted by combat modules.",
            "friend", "friends", List.of(PING), "f");
    }

    private static FriendManager friends() {
        return OfflineClient.INSTANCE.getFriendManager();
    }

    @Override
    protected Set<String> names() {
        return friends().getAll();
    }

    @Override
    protected boolean add(String name) {
        return friends().add(name);
    }

    @Override
    protected boolean remove(String name) {
        return friends().remove(name);
    }

    @Override
    protected void clear() {
        friends().clear();
    }

    @Override
    protected String otherListOf(String name) {
        return friends().isEnemy(name) ? "enemies" : null;
    }

    // A ping with no name goes to every friend online.
    @Override
    protected boolean runOwn(String verb, String[] args) {
        SecretChat secretChat = Modules.get(SecretChat.class);
        if (!verb.equals(PING) || args.length > 2 || secretChat == null) {
            return false;
        }
        ChatUtil.message("§7" + secretChat.ping(args.length == 2 ? args[1] : null) + ".");
        return true;
    }

    @Override
    protected List<String> namesFor(String verb) {
        SecretChat secretChat = Modules.get(SecretChat.class);
        return verb.equals(PING) && secretChat != null ? secretChat.onlineFriends() : List.of();
    }
}
