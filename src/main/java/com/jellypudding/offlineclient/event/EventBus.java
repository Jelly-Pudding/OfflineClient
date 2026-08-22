package com.jellypudding.offlineclient.event;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A small event bus. Register a listener object once and every method on it
 * annotated with {@link Subscribe} becomes a handler for its event type.
 * Registering the same listener twice does nothing.
 */
public final class EventBus {

    private record Handler(Object listener, Method method, int priority) {
        void invoke(Event event) {
            try {
                method.invoke(listener, event);
            } catch (Exception e) {
                System.err.println("[OfflineClient] Error in event handler "
                    + listener.getClass().getSimpleName() + "." + method.getName());
                e.printStackTrace();
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
            if (entry.getValue().stream().anyMatch(h -> h.listener == listener)) {
                entry.setValue(entry.getValue().stream()
                    .filter(h -> h.listener != listener).toList());
            }
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
