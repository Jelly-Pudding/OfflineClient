package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.config.KeyedStore;
import com.jellypudding.offlineclient.config.LoadoutStore;
import com.jellypudding.offlineclient.config.MacroStore;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.KeybindSetting;
import com.jellypudding.offlineclient.setting.Setting;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

// Everything that answers to a key. The game controls and the client binds and every macro or
// loadout put on a key. A key two of them share does both at once.
public final class KeyUse {

    // One thing on the key. The source tells a setting or a saved entry apart from the rest.
    private record Holder(String name, Object source) {
    }

    @FunctionalInterface
    private interface Visitor {
        void visit(int key, Holder holder);
    }

    private KeyUse() {
    }

    // Empty for a key nothing uses.
    public static List<String> holders(int key) {
        return others(key, null);
    }

    // What else uses the key besides the given bind or macro or loadout.
    public static List<String> others(int key, Object self) {
        List<String> names = new ArrayList<>();
        for (Holder holder : all(key)) {
            if (!same(holder.source(), self)) {
                names.add(holder.name());
            }
        }
        return names;
    }

    // Every key a client bind or macro or loadout sits on in the order of the key codes.
    public static SortedSet<Integer> clientKeys() {
        SortedSet<Integer> keys = new TreeSet<>();
        visitClient((key, holder) -> keys.add(key));
        return keys;
    }

    // A sentence for a tip such as Also used by Drop Selected Item. Empty whilst nothing else uses it.
    public static String alsoUsedBy(int key, Object self) {
        List<String> others = others(key, self);
        return others.isEmpty() ? "" : " Also used by " + String.join(" and ", others) + ".";
    }

    // Says in chat what else a key that was just caught is on. Quiet for a key nothing else uses.
    public static void warn(int key, Object self) {
        List<String> others = others(key, self);
        if (!others.isEmpty()) {
            ChatUtil.message("§b" + KeybindSetting.nameOfKey(key) + " §7is also §f"
                + String.join("§7 and §f", others) + "§7.");
        }
    }

    private static List<Holder> all(int key) {
        List<Holder> found = new ArrayList<>();
        if (key == KeybindSetting.UNBOUND) {
            return found;
        }
        InputConstants.Key pressed = InputConstants.Type.KEYBOARD.getOrCreate(key);
        // A debug combo acts only whilst the debug key is held and the client leaves that press to
        // the game.
        List<KeyMapping> combos = List.of(OfflineClient.MC.options.debugKeys);
        for (KeyMapping mapping : OfflineClient.MC.options.keyMappings) {
            if (!combos.contains(mapping) && mapping.matches(pressed)) {
                found.add(new Holder(Component.translatable(mapping.getName()).getString(), mapping));
            }
        }
        visitClient((bound, holder) -> {
            if (bound == key) {
                found.add(holder);
            }
        });
        return found;
    }

    private static void visitClient(Visitor visitor) {
        for (Module module : OfflineClient.INSTANCE.getModuleManager().getAll()) {
            visitBound(visitor, module.getKeybind().getValue(), module.getName(), module.getKeybind());
            for (Setting<?> setting : module.getSettings()) {
                if (setting instanceof KeybindSetting bind) {
                    visitBound(visitor, bind.getValue(), module.getName() + " " + bind.getName(), bind);
                }
            }
        }
        for (MacroStore.Macro macro : MacroStore.get().all()) {
            visitBound(visitor, macro.key(), "the macro " + macro.name(), macro);
        }
        for (LoadoutStore.Loadout loadout : LoadoutStore.get().all()) {
            visitBound(visitor, loadout.key(), "the loadout " + loadout.name(), loadout);
        }
    }

    private static void visitBound(Visitor visitor, int key, String name, Object source) {
        if (key != KeybindSetting.UNBOUND) {
            visitor.visit(key, new Holder(name, source));
        }
    }

    // A macro or a loadout is a new record once its key changes. The same name in the same store
    // is the same entry.
    private static boolean same(Object source, Object self) {
        if (source == self) {
            return true;
        }
        return source instanceof KeyedStore.Keyed<?> entry && self instanceof KeyedStore.Keyed<?> other
            && entry.getClass() == other.getClass() && entry.name().equalsIgnoreCase(other.name());
    }
}
