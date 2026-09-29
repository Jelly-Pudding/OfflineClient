package com.jellypudding.offlineclient.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

// A row that does something when it is clicked or named in a command. It holds no value
// and is never saved. An action that throws work away waits for a second press.
public final class ActionSetting extends Setting<Void> {

    // How long a confirmed action waits for its second press.
    public static final int CONFIRM_SECONDS = 3;

    // How long what the action said stays on its row. The last part eases back.
    private static final long RESULT_MS = 2500;
    private static final long FADE_MS = 400;

    // Hands back a few words about what happened or null when there is nothing to say. The
    // row shows them and a command naming the row writes them in chat unless the action
    // wrote a line of its own.
    private final Supplier<String> action;
    private boolean confirm;
    private long armedUntil;
    private String result;
    private long resultUntil;

    public ActionSetting(String name, String description, Runnable action) {
        this(name, description, () -> {
            action.run();
            return null;
        });
    }

    public ActionSetting(String name, String description, Supplier<String> action) {
        super(name, description, null);
        this.action = action;
    }

    // The first press only arms the row. A second one within a few seconds runs it.
    public ActionSetting confirm() {
        confirm = true;
        return this;
    }

    // A click on the row or a command naming it. False when the press only armed it.
    public boolean press() {
        long now = System.currentTimeMillis();
        if (confirm && now > armedUntil) {
            armedUntil = now + TimeUnit.SECONDS.toMillis(CONFIRM_SECONDS);
            result = null;
            return false;
        }
        armedUntil = 0;
        result = action.get();
        resultUntil = now + RESULT_MS;
        return true;
    }

    public boolean isArmed() {
        return System.currentTimeMillis() <= armedUntil;
    }

    // The name as it reads after "again to" such as clear finds.
    public String confirmWords() {
        String name = getName();
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    // What the last press said whilst it is still worth showing. Null otherwise.
    public String result() {
        return System.currentTimeMillis() < resultUntil ? result : null;
    }

    // One whilst the result is fresh and down to nought as it goes.
    public float resultStrength() {
        return Math.clamp((resultUntil - System.currentTimeMillis()) / (float) FADE_MS, 0f, 1f);
    }

    @Override
    public String getValueString() {
        return "";
    }

    // Nothing to put back. An armed row stands down.
    @Override
    public void reset() {
        armedUntil = 0;
        result = null;
    }

    @Override
    public JsonElement toJson() {
        return JsonNull.INSTANCE;
    }

    @Override
    public void fromJson(JsonElement json) {
    }
}
