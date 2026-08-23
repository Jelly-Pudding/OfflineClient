package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.module.ModuleManager;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Module lookup for mixins and other hot paths. ModuleManager walks every
 * module on each call and is null until the client has started.
 */
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
}
