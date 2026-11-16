package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.client.player.LocalPlayer;

// Whether something else has a claim on the player's hands. A module that works on the world
// takes a hotbar slot or a turn or the use key and waits whilst this says they are taken.
public final class Hands {

    private Hands() {
    }

    // True whilst a feeder eats or ShulkerPacker moves stacks or a combat module has something to
    // fight or the player uses an item or has no use of the hands at all.
    public static boolean taken() {
        LocalPlayer player = OfflineClient.MC.player;
        return tied() || slotsTaken() || Modules.fighting() || player != null && player.isUsingItem();
    }

    // True whilst the player lies dead or looks on as a spectator. Neither can hit or use or dig
    // and the plain game sends the server nothing of the kind from one.
    public static boolean tied() {
        LocalPlayer player = OfflineClient.MC.player;
        return player != null && (player.isDeadOrDying() || player.isSpectator());
    }

    // True whilst a feeder eats or ShulkerPacker moves stacks. A change of slot would cut the meal
    // and the packer counts on the stacks staying put. A module that works on through a fight asks this.
    public static boolean slotsTaken() {
        return Modules.feeding(null) || Modules.packing();
    }
}
