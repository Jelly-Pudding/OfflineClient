package com.jellypudding.offlineclient.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

public final class KeybindSetting extends Setting<Bind> {

    // The default key of a setting that starts with none.
    public static final int UNBOUND = 0;

    private static final String SAVED_HOLD = "hold";

    // Only the key of a module can be a held one. It keeps the module on whilst it is down.
    private boolean hold;
    private boolean holdsByDefault;

    // True for a key inside a module that is listened for whilst the module is off too.
    private boolean heardWhilstOff;

    public KeybindSetting(String name, String description, int defaultKey) {
        super(name, description, Bind.key(defaultKey));
    }

    public boolean isHeld() {
        return value.isHeld();
    }

    public boolean isBound() {
        return value.isBound();
    }

    // True when the press a screen was handed is the one this key answers.
    public boolean answers(KeyEvent event) {
        return value.answers(Bind.of(event));
    }

    // A screen keeps the left and the right button for itself whatever is bound to them.
    public boolean answers(MouseButtonEvent event) {
        return !value.isMainButton() && value.answers(Bind.of(event.buttonInfo()));
    }

    public boolean isHold() {
        return hold;
    }

    public void setHold(boolean hold) {
        this.hold = hold;
    }

    // For a key whose listener stays whilst its module is switched off.
    public KeybindSetting heardWhilstOff() {
        heardWhilstOff = true;
        return this;
    }

    public boolean isHeardWhilstOff() {
        return heardWhilstOff;
    }

    // Makes the key a held one until the player chooses otherwise.
    public KeybindSetting holdByDefault() {
        hold = true;
        holdsByDefault = true;
        return this;
    }

    @Override
    public String getValueString() {
        return value.name();
    }

    @Override
    public String defaultText() {
        return defaultValue.name();
    }

    @Override
    public void reset() {
        super.reset();
        hold = holdsByDefault;
    }

    @Override
    public boolean isDefault() {
        return super.isDefault() && hold == holdsByDefault;
    }

    @Override
    public JsonElement toJson() {
        JsonObject saved = value.toJson().getAsJsonObject();
        if (hold || holdsByDefault) {
            saved.addProperty(SAVED_HOLD, hold);
        }
        return saved;
    }

    @Override
    public void fromJson(JsonElement json) {
        Bind bind = Bind.fromJson(json);
        if (bind == null) {
            return;
        }
        value = bind;
        // A bare key code says nothing about holding.
        if (json.isJsonObject()) {
            JsonObject saved = json.getAsJsonObject();
            hold = saved.has(SAVED_HOLD) && saved.get(SAVED_HOLD).getAsBoolean();
        }
    }
}
