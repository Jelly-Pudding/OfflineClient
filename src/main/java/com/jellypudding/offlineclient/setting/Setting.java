package com.jellypudding.offlineclient.setting;

import com.google.gson.JsonElement;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

public abstract class Setting<T> {

    private final String name;
    private final String description;
    protected T value;
    protected final T defaultValue;
    private Supplier<Boolean> visibility = () -> true;

    // The setting this one is a sub option of. Null for a top level row.
    private Setting<?> parent;

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

    // Makes this a sub option of another setting. The GUI draws it
    // indented beneath the parent and only whilst the supplier returns true.
    public <S extends Setting<T>> S under(Setting<?> parent, Supplier<Boolean> visibility) {
        this.parent = parent;
        return visibleWhen(visibility);
    }

    // A sub option shown whilst the box is ticked.
    public <S extends Setting<T>> S under(BoolSetting parent) {
        return under(parent, parent::isOn);
    }

    // A sub option shown whilst the box is clear.
    public <S extends Setting<T>> S unless(BoolSetting parent) {
        return under(parent, () -> !parent.isOn());
    }

    // A sub option shown whilst the parent holds one of the given values.
    @SafeVarargs
    public final <S extends Setting<T>, E extends Enum<E>> S under(EnumSetting<E> parent, E... values) {
        Set<E> allowed = new HashSet<>();
        for (E value : values) {
            allowed.add(value);
        }
        return under(parent, () -> allowed.contains(parent.getValue()));
    }

    public boolean isVisible() {
        return visibility.get();
    }

    public Setting<?> getParent() {
        return parent;
    }

    // How many settings this one sits beneath.
    public int depth() {
        int depth = 0;
        for (Setting<?> above = parent; above != null; above = above.parent) {
            depth++;
        }
        return depth;
    }

    public abstract JsonElement toJson();

    public abstract void fromJson(JsonElement json);
}
