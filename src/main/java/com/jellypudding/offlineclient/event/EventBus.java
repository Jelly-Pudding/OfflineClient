package com.jellypudding.offlineclient.event;

import com.jellypudding.offlineclient.OfflineClient;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// A Subscribe method on a registered listener becomes a handler for its event type.
// Registering the same listener twice does nothing.
public final class EventBus {

    private static final class Handler {

        private final Object listener;
        private final Method method;
        private final int priority;
        // A post already under way holds its own copy of the list. A handler taken
        // out partway through must not run for it.
        private volatile boolean removed;

        Handler(Object listener, Method method, int priority) {
            this.listener = listener;
            this.method = method;
            this.priority = priority;
        }

        void invoke(Event event) {
            if (removed) {
                return;
            }
            try {
                method.invoke(listener, event);
            } catch (InvocationTargetException e) {
                OfflineClient.LOG.error("Error in event handler {}.{}",
                    listener.getClass().getSimpleName(), method.getName(), e.getCause());
            } catch (IllegalAccessException e) {
                OfflineClient.LOG.error("Cannot reach event handler {}.{}",
                    listener.getClass().getSimpleName(), method.getName(), e);
            }
        }
    }

    private final Map<Class<?>, List<Handler>> handlers = new ConcurrentHashMap<>();

    public synchronized void register(Object listener) {
        for (Method method : getSubscribers(listener)) {
            Class<?> eventType = method.getParameterTypes()[0];
            List<Handler> current = handlers.getOrDefault(eventType, List.of());

            boolean already = current.stream().anyMatch(h -> h.listener == listener && h.method.equals(method));
            if (already) {
                continue;
            }

            List<Handler> updated = new ArrayList<>(current);
            updated.add(new Handler(listener, method, method.getAnnotation(Subscribe.class).priority()));
            updated.sort(Comparator.comparingInt((Handler h) -> h.priority).reversed());
            // Posts from other threads always see a complete handler list.
            handlers.put(eventType, List.copyOf(updated));
        }
    }

    public synchronized void unregister(Object listener) {
        for (Map.Entry<Class<?>, List<Handler>> entry : handlers.entrySet()) {
            if (entry.getValue().stream().noneMatch(h -> h.listener == listener)) {
                continue;
            }
            List<Handler> kept = new ArrayList<>();
            for (Handler handler : entry.getValue()) {
                if (handler.listener == listener) {
                    handler.removed = true;
                } else {
                    kept.add(handler);
                }
            }
            entry.setValue(List.copyOf(kept));
        }
    }

    public <T extends Event> T post(T event) {
        List<Handler> list = handlers.get(event.getClass());
        if (list != null) {
            for (Handler handler : list) {
                handler.invoke(event);
            }
        }
        return event;
    }

    private static List<Method> getSubscribers(Object listener) {
        List<Method> methods = new ArrayList<>();
        Class<?> clazz = listener.getClass();
        while (clazz != null && clazz != Object.class) {
            for (Method method : clazz.getDeclaredMethods()) {
                if (!method.isAnnotationPresent(Subscribe.class)) {
                    continue;
                }
                if (method.getParameterCount() != 1 || !Event.class.isAssignableFrom(method.getParameterTypes()[0])) {
                    throw new IllegalArgumentException("@Subscribe method " + clazz.getSimpleName() + "."
                        + method.getName() + " must take exactly one Event parameter");
                }
                method.setAccessible(true);
                methods.add(method);
            }
            clazz = clazz.getSuperclass();
        }
        return methods;
    }
}
