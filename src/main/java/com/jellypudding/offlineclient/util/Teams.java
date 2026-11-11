package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.setting.BoolSetting;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.scores.PlayerTeam;

import java.util.Optional;

// Which side the server puts a player on and the colour it gives their name. A scoreboard team
// says both. Where a server only colours names the name in the player list stands in.
public final class Teams {

    // What a name has when nobody coloured it.
    private static final int PLAIN = 0xFFFFFF;

    private Teams() {
    }

    // The switch of every render that can draw players in the colour of their side.
    public static BoolSetting colorSwitch() {
        return new BoolSetting("Team colours",
            "Draws each player in the colour the server gives their name. A player it gives none keeps"
                + " the usual colour.", false);
    }

    // The colour of the player's side or zero when the server gives them none.
    public static int colorOf(Player player) {
        PlayerTeam team = player.getTeam();
        if (team != null && team.getColor().isPresent()) {
            return ColorUtil.withAlpha(team.getColor().get().rgb(), 255);
        }
        int listed = listedColor(player);
        return listed == PLAIN ? 0 : ColorUtil.withAlpha(listed, 255);
    }

    // The colour of the player's own name inside the styled name the player list shows. A rank
    // written in front of it may carry another. Plain when the name has none.
    private static int listedColor(Player player) {
        ClientPacketListener connection = OfflineClient.MC.getConnection();
        PlayerInfo info = connection == null ? null : connection.getPlayerInfo(player.getUUID());
        Component shown = info == null ? null : info.getTabListDisplayName();
        String name = EntityUtil.nameOf(player);
        if (shown == null || name == null) {
            return PLAIN;
        }
        return shown.visit((Style style, String text) -> text.contains(name) && style.getColor() != null
            ? Optional.of(style.getColor().getValue()) : Optional.<Integer>empty(), Style.EMPTY).orElse(PLAIN);
    }

    // True for a player the server puts on your side. That is someone on your scoreboard team
    // or someone whose name has the colour of yours.
    public static boolean isMate(Player player) {
        Player self = OfflineClient.MC.player;
        if (self == null || player == self) {
            return false;
        }
        PlayerTeam mine = self.getTeam();
        if (mine != null && mine.isAlliedTo(player.getTeam())) {
            return true;
        }
        int colour = colorOf(self);
        return colour != 0 && colour == colorOf(player);
    }
}
