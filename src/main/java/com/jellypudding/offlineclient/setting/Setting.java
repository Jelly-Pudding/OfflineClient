package com.jellypudding.offlineclient.setting;

import com.google.gson.JsonElement;

import java.util.function.Supplier;

public abstract class Setting<T> {

    private final String name;
    private final String description;
    protected T value;
    protected final T defaultValue;
    private Supplier<Boolean> visibility = () -> true;

    protected Setting(String name, String description, T defaultValue) {
        this.name = name;
        this.description = description;
        this.value = defaultValue;
        this.defaultValue = defaultValue;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    // How the value reads in the interface and in chat.
    public String getValueString() {
        return String.valueOf(value);
    }

    public T getValue() {
        return value;
    }

    public void setValue(T value) {
        this.value = value;
    }

    public void reset() {
        value = defaultValue;
    }

    // Hides this setting in the GUI whilst the supplier returns false.
    @SuppressWarnings("unchecked")
    public <S extends Setting<T>> S visibleWhen(Supplier<Boolean> visibility) {
        this.visibility = visibility;
        return (S) this;
    }

    public boolean isVisible() {
        return visibility.get();
    }

    public abstract JsonElement toJson();

    public abstract void fromJson(JsonElement json);
}
