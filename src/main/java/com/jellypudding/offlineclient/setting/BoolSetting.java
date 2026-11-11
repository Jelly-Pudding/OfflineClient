package com.jellypudding.offlineclient.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

public class BoolSetting extends Setting<Boolean> {

    public BoolSetting(String name, String description, boolean defaultValue) {
        super(name, description, defaultValue);
    }

    public boolean isOn() {
        return value;
    }

    public final void toggle() {
        setValue(!isOn());
    }

    @Override
    public String defaultText() {
        return defaultValue ? "on" : "off";
    }

    @Override
    public JsonElement toJson() {
        return new JsonPrimitive(isOn());
    }

    @Override
    public void fromJson(JsonElement json) {
        if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isBoolean()) {
            setValue(json.getAsBoolean());
        }
    }
}
