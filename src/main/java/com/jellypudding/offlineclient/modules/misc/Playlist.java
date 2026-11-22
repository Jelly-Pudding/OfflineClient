package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.ChoiceListSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.Notice;
import net.minecraft.SharedConstants;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.MusicManager;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.client.sounds.Weighted;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

public final class Playlist extends Module {

    private record Track(Identifier event, Sound sound) {

        private String id() {
            return sound.getLocation().toString();
        }
    }

    private final class Song extends AbstractTickableSoundInstance {

        private final Track track;

        private Song(Track track) {
            super(SoundEvent.createVariableRangeEvent(track.event()), SoundSource.MUSIC,
                SoundInstance.createUnseededRandom());
            this.track = track;
            attenuation = Attenuation.NONE;
            relative = true;
            tick();
        }

        @Override
        public void tick() {
            volume = Playlist.this.volume.getFloat() / PERCENT;
            pitch = Playlist.this.pitch.getFloat();
        }

        @Override
        public WeighedSoundEvents getOrResolve(SoundManager manager) {
            WeighedSoundEvents events = super.getOrResolve(manager);
            if (events != null) {
                sound = track.sound();
            }
            return events;
        }
    }

    private static final String MUSIC = "music.";
    private static final String DISC = "music_disc.";
    // A disc's file sits here and its song is named after the file.
    private static final String RECORDS = "records/";
    private static final String DISC_TITLES = "jukebox_song.";
    // The game hands out a fresh one of these whenever it reloads its sounds.
    private static final Identifier GAME_MUSIC = Identifier.withDefaultNamespace("music.game");
    private static final int TICKS_PER_SECOND = SharedConstants.TICKS_PER_SECOND;
    private static final int RETRY_TICKS = TICKS_PER_SECOND;
    private static final float PERCENT = 100;
    // A server's resource pack can name a song.
    private static final int LONGEST_TITLE = 80;
    // A full fade takes five seconds.
    private static final float FADE_STEP = 0.01f;
    private static final float UNSET = -1;

    private final ChoiceListSetting songs = new ChoiceListSetting("Songs",
        "The songs and discs it plays. With none picked it plays every song of the game's own music.",
        this::trackIds).labelled(Playlist::title);
    private final NumberSetting pause = new NumberSetting("Pause",
        "How long it waits after a song before the next one.", 60, 0, 1200, 10, " seconds");
    private final NumberSetting volume = new NumberSetting("Volume",
        "How loud each song plays next to how the game plays it. Nothing plays louder than your music volume.",
        100, 0, 250, 5, "%");
    private final NumberSetting pitch = new NumberSetting("Pitch",
        "How high the songs play. One plays them as recorded.", 1, 0.5, 2, 0.05).max(2);
    private final EnumSetting<Notice.Where> messages = Notice.row(
        "Where the title of each song shows as it starts.", Notice.Where.CHAT);

    private Song playing;
    private int wait;
    private String lastPlayed;
    private boolean toldEmpty;
    private boolean toldSilent;
    private float gain = UNSET;
    private List<Track> tracks = List.of();
    private WeighedSoundEvents readWith;

    public Playlist() {
        super("Playlist", "Plays the songs and discs you pick in place of the game's own music.", Category.MISC);
        addSettings(songs, pause, volume, pitch, messages);
        searchTags("music", "songs", "soundtrack", "discs", "now playing", "jukebox");
    }

    @Override
    public String getSuffix() {
        return playing == null || !audible() ? null : title(playing.track.id());
    }

    @Override
    protected void onEnable() {
        // The saved config switches modules on before the game has made its music manager.
        MusicManager music = mc.getMusicManager();
        if (music != null) {
            music.stopPlaying();
        }
        playing = null;
        wait = 0;
        toldEmpty = false;
        toldSilent = false;
    }

    @Override
    protected void onDisable() {
        if (playing != null) {
            mc.getSoundManager().stop(playing);
            playing = null;
        }
        // The game moves the music gain only whilst it fades a song of its own. It would start
        // its next song at the gain left here.
        if (gain != UNSET) {
            mc.getSoundManager().updateCategoryVolume(SoundSource.MUSIC, mc.getMusicVolume());
            gain = UNSET;
        }
    }

    @Subscribe
    private void onClientTick(ClientTickEvent event) {
        SoundManager manager = mc.getSoundManager();
        fade(manager);
        if (playing != null) {
            if (manager.isActive(playing)) {
                return;
            }
            playing = null;
            wait = pause.getInt() * TICKS_PER_SECOND;
        }
        if (wait > 0) {
            wait--;
            return;
        }
        Track next = pick();
        if (next == null) {
            wait = RETRY_TICKS;
            // Outside a world the line would go nowhere. The sounds may also still be loading.
            if (!toldEmpty && mc.player != null) {
                toldEmpty = true;
                ChatUtil.error("Playlist has nothing to play. None of the picked songs are in the game's sounds.");
            }
            return;
        }
        if (!audible()) {
            wait = RETRY_TICKS;
            // A quiet place keeps the game's own music quiet as well and needs no line.
            if (!turnedUp() && !toldSilent && mc.player != null) {
                toldSilent = true;
                ChatUtil.error("Playlist plays nothing whilst its Volume or your music volume is at nought.");
            }
            return;
        }
        Song song = new Song(next);
        if (manager.play(song) != SoundEngine.PlayResult.STARTED) {
            manager.stop(song);
            wait = RETRY_TICKS;
            return;
        }
        playing = song;
        lastPlayed = next.id();
        Notice.show(messages, this, Component.literal("§7is playing §f" + title(next.id())));
    }

    // The game fades its music to what the place wants such as the quiet of a pale garden. Its
    // own fade stops with the rest of its music whilst this is on.
    private void fade(SoundManager manager) {
        float wanted = mc.getMusicVolume();
        float next = gain == UNSET ? wanted : gain + Mth.clamp(wanted - gain, -FADE_STEP, FADE_STEP);
        if (next != gain) {
            gain = next;
            manager.updateCategoryVolume(SoundSource.MUSIC, gain);
        }
    }

    // The game would start a song silently and play it unheard.
    private boolean audible() {
        return turnedUp() && gain > 0;
    }

    private boolean turnedUp() {
        return volume.getValue() > 0 && mc.options.getFinalSoundSourceVolume(SoundSource.MUSIC) > 0;
    }

    private Track pick() {
        List<Track> pool = new ArrayList<>();
        for (Track track : tracks()) {
            if (songs.size() == 0 ? track.event().getPath().startsWith(MUSIC) : songs.contains(track.id())) {
                pool.add(track);
            }
        }
        if (pool.size() > 1) {
            pool.removeIf(track -> track.id().equals(lastPlayed));
        }
        return pool.isEmpty() ? null : pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
    }

    private Collection<String> trackIds() {
        return tracks().stream().map(Track::id).toList();
    }

    private List<Track> tracks() {
        SoundManager manager = mc.getSoundManager();
        WeighedSoundEvents marker = manager.getSoundEvent(GAME_MUSIC);
        if (marker == readWith && !tracks.isEmpty()) {
            return tracks;
        }
        readWith = marker;
        List<Track> found = new ArrayList<>();
        Set<Identifier> seen = new HashSet<>();
        for (Identifier event : manager.getAvailableSounds()) {
            String path = event.getPath();
            WeighedSoundEvents events = manager.getSoundEvent(event);
            if (events == null || !path.startsWith(MUSIC) && !path.startsWith(DISC)) {
                continue;
            }
            // A list that names another sound event holds it as a stand in that is left out
            // here. That event is read on its own.
            for (Weighted<Sound> entry : events.list) {
                if (entry instanceof Sound sound && sound.getType() == Sound.Type.FILE && seen.add(sound.getLocation())) {
                    found.add(new Track(event, sound));
                }
            }
        }
        found.sort(Comparator.comparing(track -> title(track.id())));
        tracks = List.copyOf(found);
        return tracks;
    }

    private static String title(String id) {
        Identifier location = Identifier.tryParse(id);
        if (location == null) {
            return id;
        }
        String path = location.getPath();
        String key = path.startsWith(RECORDS)
            ? DISC_TITLES + location.getNamespace() + "." + path.substring(RECORDS.length())
            : location.toShortLanguageKey().replace('/', '.');
        Language language = Language.getInstance();
        String title = language.has(key) ? language.getOrDefault(key)
            : path.substring(path.lastIndexOf('/') + 1).replace('_', ' ');
        return title.length() > LONGEST_TITLE ? title.substring(0, LONGEST_TITLE) : title;
    }
}
