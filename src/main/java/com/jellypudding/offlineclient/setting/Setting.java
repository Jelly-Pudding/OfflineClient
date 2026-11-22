package com.jellypudding.offlineclient.setting;

import com.google.gson.JsonElement;
import com.jellypudding.offlineclient.util.ChatUtil;

import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

public abstract class Setting<T> {

    private final String name;
    private final String description;
    protected T value;
    protected final T defaultValue;
    private Supplier<Boolean> visibility = () -> true;

    private Setting<?> parent;

    private boolean folded;
    private boolean foldedByDefault;

    // Saved with the config but never shown or offered to the player.
    private boolean internal;

    // A value the player made rather than chose. Nothing puts it back.
    private boolean noDefault;

    protected Setting(String name, String description, T defaultValue) {
        this.name = name;
        this.description = description;
        this.value = defaultValue;
        this.defaultValue = defaultValue;
    }

    public String getName() {
        return name;
    }

    // How commands type a name. Lower case with the spaces taken out.
    public static String idFor(String name) {
        return name.replace(" ", "").toLowerCase(Locale.ROOT);
    }

    public String id() {
        return idFor(name);
    }

    // A row name for a holder of several settings. The prefix Safe gives Safe shape and
    // an empty prefix gives Shape.
    public static String prefixed(String prefix, String noun) {
        String name = prefix.isEmpty() ? noun : prefix + " " + noun;
        return ChatUtil.capitalised(name);
    }

    public String getDescription() {
        return description;
    }

    public String getValueString() {
        return String.valueOf(getValue());
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

    public boolean isDefault() {
        return Objects.equals(value, defaultValue);
    }

    public final boolean isChanged() {
        return !internal && !noDefault && !isDefault();
    }

    // Null where no short text says the starting value.
    public String defaultText() {
        return null;
    }

    // Equal items in the same order. Some modules rank by the order things were picked in.
    protected static boolean sameOrder(Collection<?> one, Collection<?> two) {
        if (one.size() != two.size()) {
            return false;
        }
        Iterator<?> other = two.iterator();
        for (Object item : one) {
            if (!Objects.equals(item, other.next())) {
                return false;
            }
        }
        return true;
    }

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

    public <S extends Setting<T>> S under(BoolSetting parent) {
        return under(parent, parent::isOn);
    }

    public <S extends Setting<T>> S unless(BoolSetting parent) {
        return under(parent, () -> !parent.isOn());
    }

    @SafeVarargs
    public final <S extends Setting<T>, E extends Enum<E>> S under(EnumSetting<E> parent, E... values) {
        Set<E> allowed = new HashSet<>();
        for (E value : values) {
            allowed.add(value);
        }
        return under(parent, () -> allowed.contains(parent.getValue()));
    }

    // A row that is a sub option already stays under its own row.
    public static <E extends Enum<E>> void under(Setting<?>[] rows, EnumSetting<E> parent, E value) {
        for (Setting<?> row : rows) {
            if (row.parent == null) {
                row.under(parent, value);
            }
        }
    }

    // A sub option only shows whilst its parent does.
    public boolean isVisible() {
        return !internal && visibility.get() && (parent == null || parent.isVisible());
    }

    @SuppressWarnings("unchecked")
    public <S extends Setting<T>> S internal() {
        internal = true;
        return (S) this;
    }

    public boolean isInternal() {
        return internal;
    }

    @SuppressWarnings("unchecked")
    public <S extends Setting<T>> S noDefault() {
        noDefault = true;
        return (S) this;
    }

    public boolean hasDefault() {
        return !noDefault;
    }

    // False for a row that keeps nothing of its own. The config leaves its value out.
    public boolean saves() {
        return true;
    }

    public Setting<?> getParent() {
        return parent;
    }

    @SuppressWarnings("unchecked")
    public <S extends Setting<T>> S startFolded() {
        folded = true;
        foldedByDefault = true;
        return (S) this;
    }

    public boolean isFolded() {
        return folded;
    }

    public void setFolded(boolean folded) {
        this.folded = folded;
    }

    public void resetFold() {
        folded = foldedByDefault;
    }

    // Only a fold that differs from the default is worth saving.
    public boolean foldChanged() {
        return folded != foldedByDefault;
    }

    public abstract JsonElement toJson();

    public abstract void fromJson(JsonElement json);
}
