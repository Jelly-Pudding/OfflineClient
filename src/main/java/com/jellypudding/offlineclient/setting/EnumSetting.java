package com.jellypudding.offlineclient.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.util.Locale;

public final class EnumSetting<E extends Enum<E>> extends Setting<E> {

    private final E[] values;

    public EnumSetting(String name, String description, E defaultValue) {
        super(name, description, defaultValue);
        this.values = defaultValue.getDeclaringClass().getEnumConstants();
    }

    public void cycle(boolean forward) {
        int i = value.ordinal() + (forward ? 1 : -1);
        if (i < 0) {
            i = values.length - 1;
        } else if (i >= values.length) {
            i = 0;
        }
        value = values[i];
    }

    public boolean is(E other) {
        return value == other;
    }

    @Override
    public String getValueString() {
        return label(value);
    }

    /**
     * Display text for a constant. An enum overriding toString keeps its own
     * wording. Everything else reads as a sentence with LOWEST_HEALTH
     * becoming Lowest health.
     */
    public static String label(Enum<?> constant) {
        String custom = constant.toString();
        if (!custom.equals(constant.name())) {
            return custom;
        }
        String words = constant.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    @Override
    public JsonElement toJson() {
        return new JsonPrimitive(value.name());
    }

    @Override
    public void fromJson(JsonElement json) {
        if (!json.isJsonPrimitive()) {
            return;
        }
        try {
            value = Enum.valueOf(defaultValue.getDeclaringClass(), json.getAsString());
        } catch (IllegalArgumentException ignored) {
        }
    }
}
