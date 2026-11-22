package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.AttackEntityEvent;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.jellypudding.offlineclient.event.events.PacketSentEvent;
import com.jellypudding.offlineclient.mixinterface.ISprintReport;
import com.jellypudding.offlineclient.module.Module;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket.Action;

import java.util.HashSet;
import java.util.Set;

// Steers what the server believes about the sprint for one hit whilst the client goes on
// as it was. A module asks with hide or lend or renew as the hit passes it and the
// strongest wish stands. The last handler before the write sends the one stop or start
// that wish needs ahead of the hit. The handler behind the write sends what leaves the
// server where the game last told it. Word from the server that the sprint is off can
// trail those packets and staleStop says when that word is stale.
public final class SprintPause {

    // The further down the stronger and the strongest asked for stands.
    private enum Wish {
        NONE,
        RENEW,
        HIDE,
        LEND
    }

    // The server calls a hit full strength past this share of the attack cooldown. Only
    // such a hit gets the sprint knockback and ends the sprint.
    private static final float FULL_STRENGTH = 0.9f;

    // The longest the server takes to say back what it made of the sprint.
    private static final int ECHO_TICKS = 20;

    private static final int LONG_AGO = -1000;

    private static final SprintPause INSTANCE = new SprintPause();

    private static final Set<Module> users = new HashSet<>();

    private static Wish wish = Wish.NONE;

    // The hit being written and what its wish still owes the server behind it.
    private static Packet<?> flanked;
    private static Wish owed = Wish.NONE;

    private static boolean lending;

    // Whether the server sees no sprint for the hit the game is playing out. Every attack
    // starts it afresh.
    private static boolean hitUnsprinted;

    // Whether the next hit that leaves was swung at full strength. Read as the game begins
    // to play a hit out and kept for a hit a module holds back. The cooldown reads low by
    // the time such a hit leaves. A hit sent without a swing of its own counts as full
    // strength.
    private static boolean fullSwing = true;

    // The client tick up to which word from the server that the sprint is off is stale.
    private static int staleUntil = LONG_AGO;

    private SprintPause() {
    }

    // A module that asks for something around its hits calls this as it starts. The first
    // one puts the pause on the bus.
    public static void attach(Module module) {
        if (users.add(module) && users.size() == 1) {
            OfflineClient.INSTANCE.getEventBus().register(INSTANCE);
        }
    }

    public static void detach(Module module) {
        if (users.remove(module) && users.isEmpty()) {
            OfflineClient.INSTANCE.getEventBus().unregister(INSTANCE);
            wish = Wish.NONE;
            flanked = null;
            owed = Wish.NONE;
            hitUnsprinted = false;
            staleUntil = LONG_AGO;
        }
    }

    // The server hears no sprint for the next hit that leaves. It refuses a critical hit to
    // anyone it believes is sprinting.
    public static void hide() {
        ask(Wish.HIDE);
    }

    // The server hears a sprint for the next hit that leaves whether the player runs or not.
    public static void lend() {
        ask(Wish.LEND);
    }

    // The sprint is told afresh behind the next hit that leaves. The server ends the sprint
    // of anyone who lands a sprinting hit and the client only hears of it later.
    public static void renew() {
        ask(Wish.RENEW);
    }

    private static void ask(Wish asked) {
        if (asked.compareTo(wish) > 0) {
            wish = asked;
        }
    }

    // A module that holds back the hit the game is playing out and will send it with the
    // sprint hidden calls this. The client plays the hit out at once and has to know.
    public static void willHide() {
        hitUnsprinted = true;
    }

    // Read by Modules. AntiHunger lets the start lent to a hit through.
    public static boolean lending() {
        return lending;
    }

    // Read by PlayerMixin. The client plays the hit out the way the server sees it.
    public static boolean hitUnsprinted() {
        return hitUnsprinted;
    }

    // Read it before the game resets the cooldown.
    public static boolean fullStrength(LocalPlayer player) {
        return player.getAttackStrengthScale(0.5f) > FULL_STRENGTH;
    }

    // Read by Sprint ahead of every tick. True whilst the sprint the game told the server
    // of is found switched off and word of that from the server is stale. The server sends
    // its sprint back once a tick. One of its ticks can fall between a stop and the start
    // that follows it and the off it says then reaches a client that sprints again.
    public static boolean staleStop() {
        LocalPlayer player = OfflineClient.MC.player;
        return player != null && !player.isSprinting() && told(player) && MoveGate.tick() <= staleUntil;
    }

    @Subscribe
    private void onAttack(AttackEntityEvent event) {
        LocalPlayer player = OfflineClient.MC.player;
        hitUnsprinted = false;
        fullSwing = player == null || fullStrength(player);
    }

    // The last handler before the write. No module holds the hit back after this.
    @Subscribe(priority = Subscribe.LAST)
    private void onPacketSend(PacketSendEvent event) {
        LocalPlayer player = OfflineClient.MC.player;
        if (player == null || !(event.getPacket() instanceof ServerboundAttackPacket hit)) {
            return;
        }
        boolean told = told(player);
        Wish taken = wish;
        wish = Wish.NONE;
        if (taken == Wish.LEND) {
            lending = true;
            try {
                send(player, Action.START_SPRINTING);
            } finally {
                lending = false;
            }
        } else if (!told) {
            taken = Wish.NONE;
        } else if (taken == Wish.HIDE) {
            send(player, Action.STOP_SPRINTING);
        } else if (taken == Wish.NONE && (fullSwing || fullStrength(player))) {
            // A sprinting hit at full strength with nothing asked for it really ends the
            // sprint on the server. Its word that the sprint is off is to be believed.
            staleUntil = LONG_AGO;
        }
        hitUnsprinted = taken == Wish.HIDE || taken == Wish.NONE && !told;
        fullSwing = true;
        flanked = hit;
        owed = taken;
    }

    // Right behind the write and after every listener has seen the packet leave. Once a
    // start has left any off the server still says is stale. Behind a hit a sprinting hit
    // that lands has ended the sprint on the server without a word and the stop makes that
    // known before the start.
    @Subscribe(priority = Subscribe.LAST)
    private void onPacketSent(PacketSentEvent event) {
        LocalPlayer player = OfflineClient.MC.player;
        if (player == null) {
            return;
        }
        if (event.getPacket() != flanked) {
            if (startsSprint(event.getPacket())) {
                staleUntil = MoveGate.tick() + ECHO_TICKS;
            }
            return;
        }
        Wish due = owed;
        flanked = null;
        owed = Wish.NONE;
        if (due == Wish.NONE) {
            return;
        }
        if (due != Wish.HIDE) {
            send(player, Action.STOP_SPRINTING);
        }
        if (told(player)) {
            send(player, Action.START_SPRINTING);
        }
    }

    private static boolean startsSprint(Packet<?> packet) {
        return packet instanceof ServerboundPlayerCommandPacket command
            && command.getAction() == Action.START_SPRINTING;
    }

    private static boolean told(LocalPlayer player) {
        return ((ISprintReport) player).offlineclient$toldSprinting();
    }

    private static void send(LocalPlayer player, Action action) {
        player.connection.send(new ServerboundPlayerCommandPacket(player, action));
    }
}
