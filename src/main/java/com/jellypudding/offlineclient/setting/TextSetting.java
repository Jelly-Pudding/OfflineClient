package com.jellypudding.offlineclient.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

/**
 * A line of free text. Clicking the value in the GUI opens typing mode
 * and the set command can change it from chat.
 */
public final class TextSetting extends Setting<String> {

    public TextSetting(String name, String description, String defaultValue) {
        super(name, description, defaultValue);
    }

    public boolean isBlank() {
        return value.isBlank();
    }

    @Override
    public JsonElement toJson() {
        return new JsonPrimitive(value);
    }

    @Override
    public void fromJson(JsonElement json) {
        if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isString()) {
            value = json.getAsString();
        }
    }
}
