package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.TextSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import com.jellypudding.offlineclient.util.ItemUtil;
import com.jellypudding.offlineclient.util.NoteSong;
import com.jellypudding.offlineclient.util.NoteSong.Note;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Plays Note Block Studio songs on the note blocks around you. Blocks are
 * tuned with right clicks first and then struck on the beat. Songs live in
 * the offlineclient songs folder. The bind pauses and resumes.
 */
public final class Notebot extends Module {

    public enum Instruments { EXACT, ANY }

    private enum Stage { SCAN, TUNE, RECHECK, PLAY, PREVIEW }

    private static final int TUNED_COLOR = 0xFF30E030;
    private static final int UNTUNED_COLOR = 0xFFE03030;
    private static final int HIT_COLOR = 0xFFFFA000;

    // Note block pitches wrap after this many clicks.
    private static final int PITCHES = 25;

    private final TextSetting song = new TextSetting("Song",
        "File name in the songs folder. Blank picks one at random.", "");
    private final EnumSetting<Instruments> instruments = new EnumSetting<>("Instruments",
        "How strictly the block under a note block must match.", Instruments.EXACT)
        .describe(Instruments.EXACT, "Each note needs a block with the right instrument.")
        .describe(Instruments.ANY, "Any note block will do. Every note plays on whatever is there.");
    private final NumberSetting tunePerTick = new NumberSetting("Tune per tick",
        "How many note blocks to click in one tick whilst tuning. Paper servers want one.",
        1, 1, 20, 1).min(1);
    private final NumberSetting recheckDelay = new NumberSetting("Recheck delay",
        "Ticks to wait after tuning before the blocks are read back.", 10, 1, 40, 1, " ticks").min(1);
    private final BoolSetting foldNotes = new BoolSetting("Fold notes",
        "Moves notes outside the two octave range by an octave instead of dropping them.", true);
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Turn towards the note block on the server side.", true);
    private final BoolSetting swing = new BoolSetting("Swing",
        "Swings the arm on every hit.", true);
    private final BoolSetting playNext = new BoolSetting("Play next",
        "Starts another song from the folder when one ends.", false);
    private final BoolSetting preview = new BoolSetting("Preview",
        "Plays the song through your own speakers instead of note blocks.", false);
    private final BoolSetting render = new BoolSetting("Show blocks",
        "Outlines the note blocks the song uses.", true);

    private NoteSong loaded;
    private Stage stage;
    // Holds a slot that cannot finish a note block whilst the song plays.
    private final SlotSwap slots = new SlotSwap();

    private boolean paused;
    private int tick;
    private int waitTicks;

    // Which note block plays each note.
    private final Map<Note, BlockPos> assigned = new LinkedHashMap<>();

    // Clicks still owed to each block whilst tuning.
    private final Map<BlockPos, Integer> tuning = new HashMap<>();

    // Blocks struck this tick for the renderer.
    private final Set<BlockPos> struck = new HashSet<>();

    private final Random random = new Random();

    public Notebot() {
        super("Notebot", "Plays songs on the note blocks around you.", Category.MISC);
        addSettings(song, instruments, tunePerTick, recheckDelay, foldNotes, rotate, swing,
            playNext, preview, render);
        searchTags("note block", "music", "nbs");
    }

    @Override
    public boolean savesEnabledState() {
        return false;
    }

    @Override
    public String getSuffix() {
        if (loaded == null || stage == null) {
            return null;
        }
        return switch (stage) {
            case SCAN, TUNE, RECHECK -> "tuning";
            case PLAY, PREVIEW -> (paused ? "paused " : "") + tick + "/" + loaded.lastTick();
        };
    }

    // The bind pauses a running song. Off the module it switches on as usual.
    @Override
    public void onKeybind() {
        if (!isEnabled() || (stage != Stage.PLAY && stage != Stage.PREVIEW)) {
            toggle();
            return;
        }
        paused = !paused;
        ChatUtil.message("§bNotebot §7" + (paused ? "paused." : "resumed."));
    }

    @Override
    protected void onEnable() {
        loaded = null;
        stage = null;
        paused = false;
        assigned.clear();
        tuning.clear();
        if (inGame()) {
            loadSong();
        }
    }

    @Override
    protected void onDisable() {
        loaded = null;
        stage = null;
        assigned.clear();
        tuning.clear();
        struck.clear();
        slots.restore();
    }

    private static Path songsFolder() {
        return OfflineClient.MC.gameDirectory.toPath().resolve("offlineclient").resolve("songs");
    }

    private void loadSong() {
        Path folder = songsFolder();
        try {
            Files.createDirectories(folder);
        } catch (IOException ignored) {
        }
        Path file = pickFile(folder);
        if (file == null) {
            return;
        }
        try {
            loaded = NoteSong.read(file).foldedIntoRange(foldNotes.isOn());
        } catch (IOException e) {
            ChatUtil.error("Could not read " + file.getFileName() + ".");
            setEnabled(false);
            return;
        }
        tick = 0;
        paused = false;
        stage = preview.isOn() ? Stage.PREVIEW : Stage.SCAN;
        ChatUtil.message("§bNotebot §7loaded §f" + loaded.title()
            + (loaded.author().isBlank() ? "" : " §7by §f" + loaded.author()) + "§7.");
    }

    // The named file or a random one. Null when nothing fits and the module is off.
    private Path pickFile(Path folder) {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(folder)) {
            stream.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".nbs")).forEach(files::add);
        } catch (IOException ignored) {
        }
        if (files.isEmpty()) {
            ChatUtil.error("No .nbs songs in " + folder + ".");
            setEnabled(false);
            return null;
        }
        if (song.isBlank()) {
            return files.get(random.nextInt(files.size()));
        }
        String wanted = song.getValue().trim().toLowerCase(Locale.ROOT);
        for (Path file : files) {
            String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
            if (name.equals(wanted) || name.equals(wanted + ".nbs")) {
                return file;
            }
        }
        StringBuilder names = new StringBuilder();
        for (Path file : files) {
            names.append(names.isEmpty() ? "" : " ").append(file.getFileName());
        }
        ChatUtil.error("No song called " + song.getValue() + ". Available: " + names);
        setEnabled(false);
        return null;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        struck.clear();
        if (loaded == null) {
            if (inGame()) {
                loadSong();
            }
            return;
        }
        if (!inGame()) {
            return;
        }
        switch (stage) {
            case SCAN -> scan();
            case TUNE -> tune();
            case RECHECK -> recheck();
            case PLAY -> play();
            case PREVIEW -> previewTick();
        }
    }

    private void scan() {
        Map<Note, List<BlockPos>> found = findNoteBlocks();
        if (found.isEmpty()) {
            ChatUtil.error("No note blocks in reach with air above them.");
            setEnabled(false);
            return;
        }
        assign(found);
        if (assigned.isEmpty()) {
            ChatUtil.error("None of the note blocks in reach can play this song.");
            setEnabled(false);
            return;
        }
        int missing = loaded.needed().size() - assigned.size();
        if (missing > 0) {
            ChatUtil.message("§bNotebot §7is short of §f" + missing + "§7 note blocks. Those notes are skipped.");
        }
        planTuning();
        stage = Stage.TUNE;
    }

    // Every note block in reach keyed by what it plays right now.
    private Map<Note, List<BlockPos>> findNoteBlocks() {
        Map<Note, List<BlockPos>> found = new LinkedHashMap<>();
        double reach = mc.player.blockInteractionRange();
        for (BlockPos pos : BlockUtil.positionsWithin(reach + 1)) {
            BlockState state = BlockUtil.state(pos);
            if (!state.is(Blocks.NOTE_BLOCK) || !BlockUtil.state(pos.above()).isAir()) {
                continue;
            }
            if (!mc.player.isWithinBlockInteractionRange(pos, 1)) {
                continue;
            }
            found.computeIfAbsent(noteOf(state), k -> new ArrayList<>()).add(pos.immutable());
        }
        return found;
    }

    private Note noteOf(BlockState state) {
        NoteBlockInstrument instrument = instruments.is(Instruments.EXACT)
            ? state.getValue(NoteBlock.INSTRUMENT) : null;
        return new Note(instrument, state.getValue(NoteBlock.NOTE));
    }

    // A note the song needs but the file stored with an instrument the scan ignores.
    private Note wanted(Note note) {
        return instruments.is(Instruments.EXACT) ? note : new Note(null, note.pitch());
    }

    /**
     * Blocks already on the right pitch are kept as they are. The rest are
     * handed out by instrument to the notes still missing.
     */
    private void assign(Map<Note, List<BlockPos>> found) {
        assigned.clear();
        List<Note> missing = new ArrayList<>();
        for (Note note : loaded.needed()) {
            Note key = wanted(note);
            List<BlockPos> ready = found.get(key);
            if (ready != null && !ready.isEmpty()) {
                assigned.put(note, ready.removeFirst());
            } else {
                missing.add(note);
            }
        }
        Map<NoteBlockInstrument, List<BlockPos>> spare = new HashMap<>();
        for (Map.Entry<Note, List<BlockPos>> entry : found.entrySet()) {
            spare.computeIfAbsent(entry.getKey().instrument(), k -> new ArrayList<>()).addAll(entry.getValue());
        }
        Iterator<Note> it = missing.iterator();
        while (it.hasNext()) {
            Note note = it.next();
            List<BlockPos> blocks = spare.get(wanted(note).instrument());
            if (blocks != null && !blocks.isEmpty()) {
                assigned.put(note, blocks.removeFirst());
                it.remove();
            }
        }
    }

    private void planTuning() {
        tuning.clear();
        for (Map.Entry<Note, BlockPos> entry : assigned.entrySet()) {
            BlockState state = BlockUtil.state(entry.getValue());
            if (!state.is(Blocks.NOTE_BLOCK)) {
                continue;
            }
            int now = state.getValue(NoteBlock.NOTE);
            int target = entry.getKey().pitch();
            if (now != target) {
                tuning.put(entry.getValue(), Math.floorMod(target - now, PITCHES));
            }
        }
    }

    private void tune() {
        if (tuning.isEmpty()) {
            waitTicks = recheckDelay.getInt();
            stage = Stage.RECHECK;
            return;
        }
        if (swing.isOn()) {
            mc.player.swing(InteractionHand.MAIN_HAND);
        }
        int clicked = 0;
        Iterator<Map.Entry<BlockPos, Integer>> it = tuning.entrySet().iterator();
        while (it.hasNext() && clicked < tunePerTick.getInt()) {
            Map.Entry<BlockPos, Integer> entry = it.next();
            BlockPos pos = entry.getKey();
            if (rotate.isOn()) {
                BlockUtil.faceVector(Vec3.atCenterOf(pos));
            }
            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
            struck.add(pos);
            entry.setValue(entry.getValue() - 1);
            if (entry.getValue() <= 0) {
                it.remove();
            }
            clicked++;
        }
    }

    // The server may have refused some clicks. The blocks are read back once more.
    private void recheck() {
        if (--waitTicks > 0) {
            return;
        }
        planTuning();
        if (!tuning.isEmpty()) {
            stage = Stage.TUNE;
            return;
        }
        if (mc.player.getAbilities().instabuild) {
            ChatUtil.error("Notebot cannot strike note blocks in creative. They break instead.");
            setEnabled(false);
            return;
        }
        holdSafeItem();
        ChatUtil.message("§bNotebot §7tuned. Playing.");
        tick = 0;
        stage = Stage.PLAY;
    }

    /**
     * The server turns a strike into a break whenever the held item would finish
     * the block in one go. The gentlest thing in the hotbar is held instead.
     */
    private void holdSafeItem() {
        BlockState note = Blocks.NOTE_BLOCK.defaultBlockState();
        int best = -1;
        float slowest = ItemUtil.miningSpeed(mc.player.getInventory().getSelectedItem(), note);
        for (int i = 0; i < InventoryUtil.HOTBAR_SIZE; i++) {
            float speed = ItemUtil.miningSpeed(mc.player.getInventory().getItem(i), note);
            if (speed < slowest) {
                slowest = speed;
                best = i;
            }
        }
        if (best != -1) {
            slots.select(best);
        }
    }

    private void play() {
        if (paused) {
            return;
        }
        if (tick > loaded.lastTick()) {
            finish();
            return;
        }
        List<Note> notes = loaded.notesAt(tick++);
        if (notes.isEmpty()) {
            return;
        }
        boolean turned = false;
        for (Note note : notes) {
            BlockPos pos = assigned.get(note);
            if (pos == null) {
                continue;
            }
            if (rotate.isOn() && !turned) {
                BlockUtil.faceVector(Vec3.atCenterOf(pos));
                turned = true;
            }
            // A left click plays the block without harming it.
            mc.player.connection.send(new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, Direction.UP));
            mc.player.connection.send(new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, pos, Direction.UP));
            struck.add(pos);
        }
        if (swing.isOn() && !struck.isEmpty()) {
            mc.player.swing(InteractionHand.MAIN_HAND);
        }
    }

    private void previewTick() {
        if (paused) {
            return;
        }
        if (tick > loaded.lastTick()) {
            finish();
            return;
        }
        for (Note note : loaded.notesAt(tick++)) {
            NoteBlockInstrument instrument = note.instrument() == null
                ? NoteBlockInstrument.HARP : note.instrument();
            mc.player.playSound(instrument.getSoundEvent().value(), 2f,
                NoteBlock.getPitchFromNote(note.pitch()));
        }
    }

    private void finish() {
        if (playNext.isOn()) {
            loaded = null;
            assigned.clear();
            loadSong();
            return;
        }
        ChatUtil.message("§bNotebot §7finished §f" + loaded.title() + "§7.");
        setEnabled(false);
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!render.isOn() || loaded == null) {
            return;
        }
        for (BlockPos pos : assigned.values()) {
            int color = struck.contains(pos) ? HIT_COLOR
                : tuning.containsKey(pos) ? UNTUNED_COLOR : TUNED_COLOR;
            event.getBatch().outlineBox(new AABB(pos).deflate(0.01), color, false);
        }
    }
}
