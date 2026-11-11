package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.util.function.Predicate;

// The other players in view who may mean you harm and what they are up to. The rules that
// act for you and the warnings about players read the same tests here.
public final class Strangers {

    private Strangers() {
    }

    // True for a living player in view who is no friend. You and the bodies only this client
    // puts down and the fake players AntiBot spots never count. Enemies only narrows it to the
    // players on your enemy list.
    public static boolean counts(Entity entity, boolean enemiesOnly) {
        return EntityUtil.isOtherPlayer(entity) && EntityUtil.isEnemy(entity)
            && (!enemiesOnly || EntityUtil.isListedEnemy(entity));
    }

    // The closest such player within the range who passes the test. Null when none does.
    public static Player nearest(double range, boolean enemiesOnly, Predicate<Player> test) {
        LocalPlayer self = OfflineClient.MC.player;
        ClientLevel level = OfflineClient.MC.level;
        Player best = null;
        double bestDistance = range;
        for (Player player : level.players()) {
            double distance = self.distanceTo(player);
            if (distance <= bestDistance && counts(player, enemiesOnly) && test.test(player)) {
                bestDistance = distance;
                best = player;
            }
        }
        return best;
    }

    // True whilst the player has a bed in either hand. What a player carries out of hand is
    // never sent to anyone else.
    public static boolean holdsBed(Player player) {
        return player.getMainHandItem().is(ItemTags.BEDS) || player.getOffhandItem().is(ItemTags.BEDS);
    }
}
