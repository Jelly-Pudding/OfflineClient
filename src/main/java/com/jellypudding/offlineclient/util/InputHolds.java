package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

// Keys held down for the player for a set number of ticks. A key the player really holds stays
// down once its hold runs out. The holds end when the player leaves the world or the client
// goes dormant.
public enum InputHolds {
    INSTANCE;

    // The game's own controls that can be held. Use and attack work the hands.
    public enum Key {
        FORWARD(options -> options.keyUp, false),
        BACK(options -> options.keyDown, false),
        LEFT(options -> options.keyLeft, false),
        RIGHT(options -> options.keyRight, false),
        JUMP(options -> options.keyJump, false),
        SNEAK(options -> options.keyShift, false),
        SPRINT(options -> options.keySprint, false),
        USE(options -> options.keyUse, true),
        ATTACK(options -> options.keyAttack, true);

        private final HeldKey held;
        private final boolean hands;

        Key(Function<Options, KeyMapping> mapping, boolean hands) {
            held = new HeldKey(mapping);
            this.hands = hands;
        }

        // The word a command knows the key by.
        public String word() {
            return name().toLowerCase(Locale.ROOT);
        }

        // Null for a word that names no key.
        public static Key named(String word) {
            for (Key key : values()) {
                if (key.word().equalsIgnoreCase(word)) {
                    return key;
                }
            }
            return null;
        }
    }

    // Ticks each held key still has to go.
    private static final Map<Key, Integer> left = new EnumMap<>(Key.class);

    // Holds the key down for that many ticks. A hold already running on it starts over.
    // The game only swings at a mob on the press itself and a held attack gets that one swing.
    public static void hold(Key key, int ticks) {
        if (left.isEmpty()) {
            OfflineClient.INSTANCE.getEventBus().register(INSTANCE);
        }
        left.put(key, ticks);
        if (aside(key)) {
            return;
        }
        key.held.hold();
        if (key == Key.ATTACK && OfflineClient.MC.gui.screen() == null) {
            OfflineClient.MC.startAttack();
        }
    }

    // The keys held and the ticks each has left.
    public static Map<Key, Integer> running() {
        return Map.copyOf(left);
    }

    // True for a hold that ran out whilst a module had the hands. It lets go once they are free.
    public static boolean waiting(Key key) {
        return left.getOrDefault(key, -1) == 0 && aside(key);
    }

    // Lets go of one key. False when it was not held.
    public static boolean letGo(Key key) {
        if (left.remove(key) == null) {
            return false;
        }
        release(key);
        stopWhenIdle();
        return true;
    }

    // Lets go of every key and hands back how many were held.
    public static int letGoAll() {
        int held = left.size();
        left.keySet().forEach(InputHolds::release);
        left.clear();
        stopWhenIdle();
        return held;
    }

    // A screen lifts every key as it opens and closes. Each hold presses its key again every tick.
    // A hand key the hands are taken from waits and a hold that runs out meanwhile ends only once
    // the module is done with them.
    @Subscribe
    private void onClientTick(ClientTickEvent event) {
        if (OfflineClient.MC.player == null || Modules.dormant()) {
            letGoAll();
            return;
        }
        left.entrySet().removeIf(hold -> {
            Key key = hold.getKey();
            boolean aside = aside(key);
            if (hold.getValue() <= 0) {
                if (!aside) {
                    key.held.letGo();
                }
                return !aside;
            }
            if (!aside) {
                key.held.hold();
            }
            hold.setValue(hold.getValue() - 1);
            return false;
        });
        stopWhenIdle();
    }

    private static boolean aside(Key key) {
        return key.hands && Hands.slotsTaken();
    }

    // A module that took the hands keeps the key. Lifting it would end what the module does.
    private static void release(Key key) {
        if (!aside(key)) {
            key.held.letGo();
        }
    }

    private static void stopWhenIdle() {
        if (left.isEmpty()) {
            OfflineClient.INSTANCE.getEventBus().unregister(INSTANCE);
        }
    }
}
