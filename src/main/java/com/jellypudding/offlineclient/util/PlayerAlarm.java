package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.Setting;
import net.minecraft.sounds.SoundEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

// The module decides when someone arrives.
public final class PlayerAlarm {

    // The alarm is named by its prefix such as Join.
    public record Ring(String alarm, String player) {
    }

    private static final int REMEMBERED = 512;

    private static final double MILLIS_PER_MINUTE = TimeUnit.MINUTES.toMillis(1);

    private static final Feed<Ring> RINGS = new Feed<>();

    private final String prefix;
    private final AlertSound sound;
    private final TextLines names;
    private final NumberSetting quiet;

    // Wall clock times by lower case name.
    private final Map<String, Long> rungAt = new BoundedMap<>(REMEMBERED, true);

    // The prefix names every row such as Join alarm and Join name 1.
    public PlayerAlarm(String prefix, String description, SoundEvent defaultSound, int defaultRings,
                       int quietMinutes) {
        this.prefix = prefix;
        sound = new AlertSound(prefix, description, defaultSound, defaultRings);
        String alarm = prefix.toLowerCase(Locale.ROOT) + " alarm";
        names = TextLines.named(prefix + " names", prefix + " name",
            "How many players the " + alarm + " listens for. With every name blank it rings for anyone.",
            "A player the " + alarm + " listens for. Click to type the name.")
            .under(sound.switchRow());
        quiet = new NumberSetting(prefix + " quiet time",
            "How long before the same player can set off the " + alarm + " again. Nought rings every time.",
            quietMinutes, 0, 60, 1, " minutes").under(sound.switchRow());
    }

    public Setting<?>[] settings() {
        List<Setting<?>> all = new ArrayList<>();
        for (Setting<?> setting : sound.settings()) {
            all.add(setting);
            if (setting == sound.switchRow()) {
                all.addAll(List.of(names.settings()));
                all.add(quiet);
            }
        }
        return all.toArray(new Setting<?>[0]);
    }

    // The alarm only rings whilst that switch is on.
    public PlayerAlarm under(BoolSetting parent) {
        sound.under(parent);
        return this;
    }

    public boolean isOn() {
        return sound.switchRow().isOn() && sound.switchRow().isVisible();
    }

    public void ring(String name) {
        if (!isOn() || !listensFor(name)) {
            return;
        }
        String key = name.toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        Long last = rungAt.get(key);
        if (last != null && now - last < quiet.getValue() * MILLIS_PER_MINUTE) {
            return;
        }
        rungAt.put(key, now);
        sound.ring();
        RINGS.add(new Ring(prefix, name));
    }

    // An alarm with every name blank hears everyone already. True when the name went in.
    public boolean listenFor(String name) {
        return !names.isEmpty() && names.add(name);
    }

    private boolean listensFor(String name) {
        return names.isEmpty() || names.holds(name);
    }

    public void forget() {
        rungAt.clear();
    }

    // The owning module calls this when it is switched off.
    public void stop() {
        sound.stop();
    }

    // Each reader keeps its own and sees every ring once. Rings from before it was made are no news.
    public static final class Watcher {

        private final Feed.Reader<Ring> rings = RINGS.reader();

        public List<Ring> fresh() {
            return rings.fresh();
        }

        public void sync() {
            rings.sync();
        }
    }
}
