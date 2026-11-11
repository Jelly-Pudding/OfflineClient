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

// An alarm that players set off. It can listen for a few names only and a player who
// set it off stays quiet for a while. The module decides when someone arrives.
public final class PlayerAlarm {

    // One time an alarm went off. The alarm is named by its prefix such as Join.
    public record Ring(String alarm, String player) {
    }

    // Players remembered at once. The one heard from longest ago goes first.
    private static final int REMEMBERED = 512;

    private static final double MILLIS_PER_MINUTE = TimeUnit.MINUTES.toMillis(1);

    // The rings of every alarm for the watchers.
    private static final Feed<Ring> RINGS = new Feed<>();

    private final String prefix;
    private final AlertSound sound;
    private final TextLines names;
    private final NumberSetting quiet;

    // When each player last set the alarm off on the wall clock. Names are in lower case.
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

    // Who the alarm listens for comes straight under its switch and how it sounds after.
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

    // Makes the whole alarm a sub option of a switch. It only rings whilst that switch is on.
    public PlayerAlarm under(BoolSetting parent) {
        sound.under(parent);
        return this;
    }

    // True whilst the alarm and every switch above it are on.
    public boolean isOn() {
        return sound.switchRow().isOn() && sound.switchRow().isVisible();
    }

    // Rings for a player the names take in unless the same player rang it too recently.
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

    // Adds a player to the names the alarm listens for. An alarm with every name blank hears
    // them already. True when the name went in.
    public boolean listenFor(String name) {
        return !names.isEmpty() && names.add(name);
    }

    private boolean listensFor(String name) {
        return names.isEmpty() || names.holds(name);
    }

    // Every player can set the alarm off again at once.
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

        // The rings since the last call with the oldest first.
        public List<Ring> fresh() {
            return rings.fresh();
        }

        // Forgets the rings that came whilst the reader was off.
        public void sync() {
            rings.sync();
        }
    }
}
