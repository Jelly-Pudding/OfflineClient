package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.module.ModuleManager;
import com.jellypudding.offlineclient.modules.player.AutoEat;
import com.jellypudding.offlineclient.modules.player.AutoGap;
import com.jellypudding.offlineclient.modules.player.AutoPotion;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// Module lookup for mixins and other hot paths. ModuleManager is null until the
// client has started and this hands back null instead of throwing.
public final class Modules {

    private static final Map<Class<?>, Module> CACHE = new ConcurrentHashMap<>();
    private static final Set<Class<?>> MISSING = ConcurrentHashMap.newKeySet();

    private Modules() {
    }

    // Null before the client has started. Reached from other threads.
    public static <T extends Module> T get(Class<T> type) {
        Module cached = CACHE.get(type);
        if (cached != null) {
            return type.cast(cached);
        }
        if (MISSING.contains(type)) {
            return null;
        }
        ModuleManager manager = OfflineClient.INSTANCE.getModuleManager();
        if (manager == null) {
            return null;
        }
        T module;
        try {
            module = manager.get(type);
        } catch (IllegalStateException e) {
            // A mixin asking for an unregistered module must not take the game down.
            MISSING.add(type);
            OfflineClient.LOG.error("Module not registered: {}", type.getSimpleName());
            return null;
        }
        CACHE.put(type, module);
        return module;
    }

    public static boolean enabled(Class<? extends Module> type) {
        Module module = get(type);
        return module != null && module.isEnabled();
    }

    // True whilst a feeder other than the asker holds the use key.
    public static boolean feeding(Module asker) {
        AutoEat eat = get(AutoEat.class);
        if (eat != null && eat != asker && eat.isBusy()) {
            return true;
        }
        AutoGap gap = get(AutoGap.class);
        if (gap != null && gap != asker && gap.isBusy()) {
            return true;
        }
        AutoPotion potion = get(AutoPotion.class);
        return potion != null && potion != asker && potion.isDrinking();
    }

    // True whilst either feeder is putting something away.
    public static boolean eating() {
        AutoEat autoEat = get(AutoEat.class);
        if (autoEat != null && autoEat.isEating()) {
            return true;
        }
        AutoGap autoGap = get(AutoGap.class);
        return autoGap != null && autoGap.isEating();
    }

    // The module only whilst it is switched on. Null otherwise.
    public static <T extends Module> T active(Class<T> type) {
        T module = get(type);
        return module != null && module.isEnabled() ? module : null;
    }
}
