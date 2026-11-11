package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.config.KeyedStore;
import com.jellypudding.offlineclient.config.LoadoutStore;
import com.jellypudding.offlineclient.config.MacroStore;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.modules.player.Loadouts;
import com.jellypudding.offlineclient.setting.Bind;
import com.jellypudding.offlineclient.setting.KeybindSetting;
import com.jellypudding.offlineclient.setting.Setting;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

// Everything that answers to a key or a mouse button. The game controls and the client binds and
// every macro or loadout put on one. Two of them on the same bind both act at once.
public final class KeyUse {

    // One thing on the bind. The source tells a setting or a saved entry apart from the rest and
    // the module is the one that has to be on for it to act. Null where nothing has to be.
    private record Holder(String name, Object source, Module module) {

        // A key inside a module only acts whilst the module is on unless its listener stays.
        private boolean live() {
            return module == null || module.isEnabled()
                || source instanceof KeybindSetting key && key.isHeardWhilstOff();
        }
    }

    @FunctionalInterface
    private interface Visitor {
        void visit(Bind bind, Holder holder);
    }

    private KeyUse() {
    }

    // Empty for a bind nothing uses.
    public static List<String> holders(Bind bind) {
        return others(bind, null);
    }

    // What else uses the bind besides the given setting or macro or loadout.
    public static List<String> others(Bind bind, Object self) {
        List<String> names = new ArrayList<>();
        for (Holder holder : all(bind)) {
            if (!same(holder.source(), self)) {
                names.add(holder.name());
            }
        }
        return names;
    }

    // Every bind a module or key setting or macro or loadout sits on with the keys first.
    public static SortedSet<Bind> clientBinds() {
        SortedSet<Bind> binds = new TreeSet<>(Bind.ORDER);
        visitClient((bind, holder) -> binds.add(bind));
        return binds;
    }

    // True when something of the client that is able to act sits on exactly this bind.
    public static boolean asksFor(Bind exact) {
        boolean[] asked = new boolean[1];
        visitClient((bind, holder) -> asked[0] |= bind.equals(exact) && holder.live());
        return asked[0];
    }

    // A sentence for a tip such as Also used by Drop Selected Item. Empty whilst nothing else uses it.
    public static String alsoUsedBy(Bind bind, Object self) {
        List<String> others = others(bind, self);
        return others.isEmpty() ? "" : " Also used by " + String.join(" and ", others) + ".";
    }

    // Every module key and every key row inside a module. A key row counts as a bind whichever
    // module holds it.
    public static List<KeybindSetting> keySettings() {
        List<KeybindSetting> keys = new ArrayList<>();
        for (Module module : OfflineClient.INSTANCE.getModuleManager().getAll()) {
            keys.add(module.getKeybind());
            for (Setting<?> setting : module.getSettings()) {
                if (setting instanceof KeybindSetting key) {
                    keys.add(key);
                }
            }
        }
        return keys;
    }

    // Says in chat what else a bind that was just caught is on. Quiet for one nothing else uses.
    public static void warn(Bind bind, Object self) {
        List<String> others = others(bind, self);
        if (!others.isEmpty()) {
            ChatUtil.message("§b" + bind.name() + " §7is also §f"
                + String.join("§7 and §f", others) + "§7.");
        }
    }

    // The game acts on its key or button whatever is held with it. Two things of the client
    // only share a bind when their modifiers are the same too.
    private static List<Holder> all(Bind bind) {
        List<Holder> found = new ArrayList<>();
        if (!bind.isBound()) {
            return found;
        }
        // A debug combo acts only whilst the debug key is held and the client leaves that press to
        // the game.
        List<KeyMapping> combos = List.of(OfflineClient.MC.options.debugKeys);
        for (KeyMapping mapping : OfflineClient.MC.options.keyMappings) {
            if (!combos.contains(mapping) && InputUtil.isOn(mapping, bind)) {
                found.add(new Holder(Component.translatable(mapping.getName()).getString(), mapping, null));
            }
        }
        visitClient((bound, holder) -> {
            if (bound.equals(bind)) {
                found.add(holder);
            }
        });
        return found;
    }

    private static void visitClient(Visitor visitor) {
        for (Module module : OfflineClient.INSTANCE.getModuleManager().getAll()) {
            visitBound(visitor, module.getKeybind().getValue(), module.getName(), module.getKeybind(), null);
            for (Setting<?> setting : module.getSettings()) {
                if (setting instanceof KeybindSetting bind) {
                    visitBound(visitor, bind.getValue(), module.getName() + " " + bind.getName(), bind, module);
                }
            }
        }
        for (MacroStore.Macro macro : MacroStore.get().all()) {
            visitBound(visitor, macro.key(), "the macro " + macro.name(), macro, null);
        }
        // Only the Loadouts module runs a loadout from its key.
        Module loadouts = Modules.get(Loadouts.class);
        for (LoadoutStore.Loadout loadout : LoadoutStore.get().all()) {
            visitBound(visitor, loadout.key(), "the loadout " + loadout.name(), loadout, loadouts);
        }
    }

    private static void visitBound(Visitor visitor, Bind bind, String name, Object source, Module module) {
        if (bind.isBound()) {
            visitor.visit(bind, new Holder(name, source, module));
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
