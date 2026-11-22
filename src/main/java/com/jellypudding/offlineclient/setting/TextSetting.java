package com.jellypudding.offlineclient.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

public final class TextSetting extends Setting<String> {

    // A value nobody else should read such as a shared key or the spot of a base. A copy of
    // settings leaves it out.
    private boolean secret;

    public TextSetting(String name, String description, String defaultValue) {
        super(name, description, defaultValue);
    }

    public TextSetting secret() {
        secret = true;
        return this;
    }

    public boolean isSecret() {
        return secret;
    }

    public boolean isBlank() {
        return value.isBlank();
    }

    @Override
    public String defaultText() {
        return defaultValue.isEmpty() ? "empty" : "\"" + defaultValue + "\"";
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
