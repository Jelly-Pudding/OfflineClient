package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.setting.ActionSetting;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.setting.Setting;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvent;

import java.util.List;
import java.util.Locale;

// An alarm a module rings when something turns up. It owns its settings the way a box
// style does and plays on the interface channel. Distance and the block sounds slider
// never quieten it.
public final class AlertSound {

    // The settings hold volume and pitch in percent and the sound engine wants shares of one.
    private static final float PERCENT = 100f;

    // The sound engine clamps the pitch to between half and double.
    private static final double LOWEST_PITCH = 50;
    private static final double HIGHEST_PITCH = 200;

    private final BoolSetting enabled;
    private final RegistryListSetting<SoundEvent> sounds;
    private final NumberSetting volume;
    private final NumberSetting pitch;
    private final NumberSetting rings;
    private final NumberSetting gap;
    private final ActionSetting test;

    // The sounds of the rings under way. Null whilst the alarm is quiet.
    private List<SoundEvent> playing;
    private int played;
    private int wait;

    // The description says what sets the alarm off. Each ring plays the next picked sound.
    public AlertSound(String description, SoundEvent defaultSound) {
        this("", description, defaultSound);
    }

    // A prefix such as New gives New alarm and New volume for a module with several alarms.
    public AlertSound(String prefix, String description, SoundEvent defaultSound) {
        this(prefix, description, defaultSound, 1);
    }

    // An alarm meant to wake a player who stepped away starts with several rings.
    public AlertSound(String prefix, String description, SoundEvent defaultSound, int defaultRings) {
        enabled = new BoolSetting(Setting.prefixed(prefix, "alarm"), description, false);
        sounds = new RegistryListSetting<>(Setting.prefixed(prefix, "sounds"),
            "The sounds the alarm plays. Each ring plays the next one. Click to pick them.",
            BuiltInRegistries.SOUND_EVENT, List.of(defaultSound)).under(enabled);
        volume = new NumberSetting(Setting.prefixed(prefix, "volume"),
            "How loud each ring is out of the full interface volume.", 100, 0, 100, 5, "%")
            .min(0).max(100).under(enabled);
        pitch = new NumberSetting(Setting.prefixed(prefix, "pitch"),
            "How high each ring sounds. At a hundred the sound plays unchanged.",
            100, LOWEST_PITCH, HIGHEST_PITCH, 5, "%").min(LOWEST_PITCH).max(HIGHEST_PITCH).under(enabled);
        rings = new NumberSetting(Setting.prefixed(prefix, "rings"),
            "How many times the alarm sounds each time it goes off.", defaultRings, 1, 10, 1).min(1).under(enabled);
        gap = new NumberSetting(Setting.prefixed(prefix, "ring gap"),
            "How long the alarm waits between two rings.", 20, 1, 100, 1, " ticks")
            .min(1).under(rings, () -> rings.getInt() > 1);
        String alarm = prefix.isEmpty() ? "alarm" : prefix.toLowerCase(Locale.ROOT) + " alarm";
        test = new ActionSetting("Test " + alarm, "Plays the alarm as it is set up now.", this::test)
            .under(enabled);
    }

    // Makes the whole alarm a sub option of a switch. It only rings whilst that switch is on.
    public AlertSound under(BoolSetting parent) {
        enabled.under(parent);
        return this;
    }

    public Setting<?>[] settings() {
        return new Setting<?>[] {enabled, sounds, volume, pitch, rings, gap, test};
    }

    // The switch every other row hangs from. A holder can hang rows of its own there.
    public BoolSetting switchRow() {
        return enabled;
    }

    // Starts the rings whilst the alarm and every switch above it are on. Rings still going
    // are left to finish. Restarting them on every find would ring for as long as finds come.
    public void ring() {
        if (playing == null && enabled.isOn() && enabled.isVisible()) {
            start();
        }
    }

    // A module calls this when it is switched off.
    public void stop() {
        playing = null;
        OfflineClient.INSTANCE.getEventBus().unregister(this);
    }

    private String test() {
        stop();
        return start() ? null : "Pick a sound first";
    }

    // The first ring plays at once and the rest on the client tick. False with no sound picked.
    private boolean start() {
        List<SoundEvent> picked = List.copyOf(sounds.resolved());
        if (picked.isEmpty()) {
            return false;
        }
        playing = picked;
        played = 0;
        playNext();
        if (playing != null) {
            OfflineClient.INSTANCE.getEventBus().register(this);
        }
        return true;
    }

    private void playNext() {
        SoundEvent sound = playing.get(played % playing.size());
        OfflineClient.MC.getSoundManager().play(SimpleSoundInstance.forUI(sound,
            pitch.getFloat() / PERCENT, volume.getFloat() / PERCENT));
        played++;
        if (played >= rings.getInt()) {
            stop();
        } else {
            wait = gap.getInt();
        }
    }

    @Subscribe
    private void onClientTick(ClientTickEvent event) {
        if (playing != null && --wait <= 0) {
            playNext();
        }
    }
}
