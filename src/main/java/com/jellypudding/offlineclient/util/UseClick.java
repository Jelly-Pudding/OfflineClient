package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.event.events.RightClickEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;

// The player's right click whilst the game handles it. A use runs before its packet
// leaves and can empty the stack. What each hand held as the click began is kept.
public enum UseClick {
    INSTANCE;

    private static final Item[] held = new Item[InteractionHand.values().length];
    private static boolean clicking;

    // Ahead of every other handler. A module that repeats the click sees the hands as
    // they were before it.
    @Subscribe(priority = Subscribe.FIRST)
    private void onRightClick(RightClickEvent event) {
        LocalPlayer player = OfflineClient.MC.player;
        if (player == null) {
            return;
        }
        for (InteractionHand hand : InteractionHand.values()) {
            held[hand.ordinal()] = player.getItemInHand(hand).getItem();
        }
        clicking = true;
    }

    // Modules use items on their own from the player's tick onwards.
    @Subscribe(priority = Subscribe.FIRST)
    private void onTick(TickEvent event) {
        clicking = false;
    }

    @Subscribe(priority = Subscribe.FIRST)
    private void onClientTick(ClientTickEvent event) {
        clicking = false;
    }

    // The item the hand held as the player's click began. Null outside a click.
    public static Item heldAtClick(InteractionHand hand) {
        return clicking ? held[hand.ordinal()] : null;
    }
}
