package com.jellypudding.offlineclient.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

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
