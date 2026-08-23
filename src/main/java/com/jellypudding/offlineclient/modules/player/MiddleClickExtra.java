package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.friend.FriendManager;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import net.minecraft.world.entity.player.Player;

public final class MiddleClickExtra extends Module {

    private final BoolSetting notify = new BoolSetting("Notify",
        "Say in chat who was added or removed.", true);

    public MiddleClickExtra() {
        super("MiddleClickExtra", "Middle click a player to toggle them as a friend.", Category.PLAYER);
        addSettings(notify);
        searchTags("friend", "add friend");
    }

    // Called from the mouse mixin on every middle click press.
    public void onMiddleClick() {
        if (!isEnabled() || !inGame() || mc.gui.screen() != null) {
            return;
        }
        if (!(mc.crosshairPickEntity instanceof Player player) || player == mc.player) {
            return;
        }
        String name = player.getGameProfile().name();
        FriendManager friends = OfflineClient.INSTANCE.getFriendManager();
        boolean added = !friends.isFriend(name);
        if (added) {
            friends.add(name);
        } else {
            friends.remove(name);
        }
        OfflineClient.INSTANCE.getConfigManager().saveSoon();
        if (notify.isOn()) {
            ChatUtil.message("§b" + name + (added
                ? " §7is now a friend." : " §7is no longer a friend."));
        }
    }
}
