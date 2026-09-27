package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;

import java.util.function.Function;

// A key a module holds down for the player. Letting go lifts only a hold made here and a
// key the player really holds stays down.
public final class HeldKey {

    // Looked up at each use. Modules are built before the game options exist.
    private final Function<Options, KeyMapping> key;
    private boolean held;

    public HeldKey(Function<Options, KeyMapping> key) {
        this.key = key;
    }

    public void hold() {
        InputUtil.hold(key.apply(OfflineClient.MC.options));
        held = true;
    }

    public void letGo() {
        if (held) {
            InputUtil.release(key.apply(OfflineClient.MC.options));
            held = false;
        }
    }
}
