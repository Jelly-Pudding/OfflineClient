package com.jellypudding.offlineclient.modules.hunting;

import com.jellypudding.offlineclient.config.FindLog;
import com.jellypudding.offlineclient.config.FindSource;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render2DEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.gui.FindsScreen;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.render.WorldToScreen;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.TextSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.LoadedChunks;
import com.jellypudding.offlineclient.util.Notice;
import com.jellypudding.offlineclient.util.RenderUtil;
import com.jellypudding.offlineclient.util.SignWords;
import com.jellypudding.offlineclient.util.TextCut;
import com.jellypudding.offlineclient.util.WorldWatch;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

// A 26.3 server flags every sign it loads from a world saved before 26.3 as allowed to run operator
// commands and sends the flag with the text. Only an operator can give a newer sign that flag. The
// igloo and taiga village templates carry it too.
public final class SignReader extends Module implements FindSource {

    // Four front lines then four back lines as the server sent them. All eight are empty for a sign
    // that is gone or wiped.
    private record Text(List<String> lines) {

        private static Text of(SignBlockEntity sign) {
            List<String> lines = new ArrayList<>(TEXT_LINES);
            for (SignTextSlot side : SIDES) {
                for (Component message : sign.getText(side).getMessages(false)) {
                    // The server can send longer text than an edit carries.
                    lines.add(TextCut.cut(message.getString(), SignWords.MOST_LINE_CHARS));
                }
            }
            return new Text(List.copyOf(lines));
        }

        private boolean blank() {
            return lines.stream().allMatch(String::isBlank);
        }

        private List<String> side(SignTextSlot side) {
            int from = side == SignTextSlot.FRONT ? 0 : SignText.LINES;
            return lines.subList(from, from + SignText.LINES);
        }

        private String shown() {
            String text = lines.stream().map(line -> ChatFormatting.stripFormatting(line).strip())
                .filter(line -> !line.isEmpty()).collect(Collectors.joining(LINE_BREAK));
            return text.isEmpty() ? BLANK_WORDS : text.replace(EARLIER_CHAR, '<');
        }
    }

    // The newest text comes first and the first text seen stays last however many came between.
    private record Memory(List<Text> texts) {

        private static Memory of(FindLog.Find find) {
            List<String> content = find.content();
            List<Text> texts = new ArrayList<>();
            if (!content.isEmpty() && content.size() % TEXT_LINES == 0) {
                for (int i = 0; i < content.size(); i += TEXT_LINES) {
                    texts.add(new Text(List.copyOf(content.subList(i, i + TEXT_LINES))));
                }
                return new Memory(texts);
            }
            // A find without exact lines has only its words. They fill the front and then the back.
            for (String words : EARLIER_MARK.split(find.detail())) {
                List<String> lines = new ArrayList<>(Collections.nCopies(TEXT_LINES, ""));
                if (!words.equals(BLANK_WORDS)) {
                    String[] parts = LINE_MARK.split(words);
                    for (int i = 0; i < Math.min(parts.length, TEXT_LINES); i++) {
                        lines.set(i, parts[i]);
                    }
                }
                texts.add(new Text(List.copyOf(lines)));
            }
            return new Memory(texts);
        }

        private Text newest() {
            return texts.getFirst();
        }

        private Text original() {
            for (int i = texts.size() - 1; i >= 0; i--) {
                if (!texts.get(i).blank()) {
                    return texts.get(i);
                }
            }
            return null;
        }

        private Memory withNewest(Text text) {
            List<Text> next = new ArrayList<>(texts);
            next.set(0, text);
            return new Memory(next);
        }

        private Memory after(Text text) {
            List<Text> next = new ArrayList<>(texts);
            next.addFirst(text);
            if (next.size() > KEPT_TEXTS + 1) {
                next.remove(next.size() - 2);
            }
            return new Memory(next);
        }

        private List<String> content() {
            List<String> content = new ArrayList<>(texts.size() * TEXT_LINES);
            texts.forEach(text -> content.addAll(text.lines()));
            return content;
        }

        private String detail() {
            return texts.stream().map(Text::shown).collect(Collectors.joining(EARLIER));
        }
    }

    // The lines before the front count are its front and the rest its back. First is what it said
    // when first seen or null whilst it still says that.
    private record Sign(BlockPos pos, AABB box, List<String> lines, int front, boolean old, String first) {
    }

    private record Ghost(BlockPos pos, String first) {
    }

    private static final List<SignTextSlot> SIDES = List.of(SignTextSlot.FRONT, SignTextSlot.BACK);
    private static final int TEXT_LINES = SignText.LINES * 2;

    private static final int SCAN_TICKS = 10;

    private static final int LINE_GAP = 2;

    private static final int TEXT = 0xFFFFFFFF;
    private static final int FIRST_TEXT = 0xFF8A8A96;

    private static final String SIGN = "sign";
    private static final String OLD_SIGN = "old sign";
    private static final String BLANK_WORDS = "blank";

    private static final String LINE_BREAK = " / ";
    private static final Pattern LINE_MARK = Pattern.compile(LINE_BREAK, Pattern.LITERAL);
    // Each earlier text follows the newer ones behind this mark. The character never stays in a text.
    private static final char EARLIER_CHAR = '«';
    private static final String EARLIER = " " + EARLIER_CHAR + " ";
    private static final Pattern EARLIER_MARK = Pattern.compile(EARLIER, Pattern.LITERAL);
    private static final int KEPT_TEXTS = 4;

    private final NumberSetting range = new NumberSetting("Range",
        "How far away a sign still shows its text and box.", 48, 8, 256, 8, " blocks").min(1);
    private final BoolSetting skipBlank = new BoolSetting("Skip blank",
        "Leaves out blank signs you never saw writing on.", true);
    private final TextSetting skipWords = new TextSetting("Skip words",
        "Leaves out new signs with any of these words on them. Separate the words with spaces.",
        "spawn hi hello hey discord <---- ---->");
    private final BoolSetting oldSigns = new BoolSetting("Old signs",
        "Colours signs placed before the server moved to 26.3 and calls them old in chat."
            + " Igloo and taiga village signs carry the same mark.", true);
    private final ColorSetting oldColor = new ColorSetting("Old colour",
        "Colour of the text and box of old signs.", 280, false).under(oldSigns);
    private final BoolSetting changedSigns = new BoolSetting("Changed signs",
        "Colours signs that no longer say what they said when you first saw them and writes that text under them.",
        true);
    private final ColorSetting changedColor = new ColorSetting("Changed colour",
        "Colour of the text and box of changed signs.", 25, false).under(changedSigns);
    private final BoolSetting goneSigns = new BoolSetting("Gone signs",
        "Draws a ghost where a sign you saw is gone with what it said first.", true);
    private final ColorSetting goneColor = new ColorSetting("Gone colour",
        "Colour of the ghosts of gone signs.", 200, false).under(goneSigns);
    private final BoxStyle style = new BoxStyle(BoxStyle.Shape.LINES, 50);
    private final NumberSetting scale = new NumberSetting("Scale",
        "Size of the labels.", 1, 0.5, 3, 0.1).min(0.1);
    private final EnumSetting<Notice.Where> messages = Notice.row(
        "Where it posts each new sign with writing on it along with its coordinates.", Notice.Where.CHAT);
    private final BoolSetting changes = new BoolSetting("Changes",
        "Says when a sign you saw before reads differently or is gone.", true)
        .under(messages, () -> !messages.is(Notice.Where.OFF));
    private final FindLog finds = new FindLog(this);

    private final WorldWatch world = new WorldWatch();
    private List<Sign> signs = List.of();
    private List<Ghost> ghosts = List.of();
    // Paper sends the signs of a crowded chunk just after the chunk. Only a sign missing from two
    // scans running is gone.
    private Set<BlockPos> missing = Set.of();
    private int wait;

    public SignReader() {
        super("SignReader", "Shows the text of nearby signs through walls and remembers what each one said.",
            Category.HUNTING);
        addSettings(range, skipBlank, skipWords, oldSigns, oldColor, changedSigns, changedColor, goneSigns, goneColor);
        addSettings(style.settings());
        addSettings(scale, messages, changes);
        addSettings(FindsScreen.settingsFor(finds));
        searchTags("signs", "sign text", "old signs", "sign history", "base", "broken signs", "restore");
    }

    @Override
    public FindLog findLog() {
        return finds;
    }

    @Override
    public String getSuffix() {
        return count(finds.count());
    }

    // AutoSign writes them back. Null when no sign with writing is remembered there.
    public List<String> remembered(BlockPos pos, SignTextSlot side) {
        FindLog.Find find = finds.at(pos);
        Text first = find == null ? null : Memory.of(find).original();
        return first == null ? null : first.side(side);
    }

    @Override
    protected void onEnable() {
        clear();
        if (inGame()) {
            world.accept();
        }
    }

    @Override
    protected void onDisable() {
        clear();
        world.forget();
    }

    private void clear() {
        signs = List.of();
        ghosts = List.of();
        missing = Set.of();
        wait = 0;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (world.changed()) {
            // Positions mean something different in every world.
            clear();
        }
        // A sign judged before the saved finds are in would pass for new and lose its earlier texts.
        if (!finds.ready() || --wait > 0) {
            return;
        }
        wait = SCAN_TICKS;
        scan();
    }

    private void scan() {
        List<String> words = SignWords.words(skipWords.getValue());
        List<Sign> found = new ArrayList<>();
        Set<Long> scanned = new HashSet<>();
        // Every sign block in the scanned chunks and whether it has writing.
        Map<BlockPos, Boolean> standing = new HashMap<>();
        LoadedChunks.forEach(chunk -> {
            scanned.add(ChunkPos.pack(chunk.getPos().x(), chunk.getPos().z()));
            for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                if (blockEntity instanceof SignBlockEntity sign) {
                    read(sign, words, standing, found);
                }
            }
        });
        signs = found;
        followMissing(scanned, standing);
    }

    // A sign already remembered is followed whatever it reads.
    private void read(SignBlockEntity sign, List<String> words, Map<BlockPos, Boolean> standing, List<Sign> out) {
        BlockPos pos = sign.getBlockPos();
        Text now = Text.of(sign);
        boolean blank = now.blank();
        standing.put(pos, !blank);
        List<String> lines = SignWords.lines(sign, SignTextSlot.FRONT);
        int front = lines.size();
        SignWords.addLines(sign.getText(SignTextSlot.BACK), lines);
        FindLog.Find known = finds.at(pos);
        if (!blank && known == null && SignWords.hasAnyWord(lines, words)) {
            return;
        }
        boolean old = oldSigns.isOn() && sign.allowOpFeatures;
        Memory memory = blank ? (known == null ? null : Memory.of(known)) : remember(pos, now, old, known);
        if (blank && memory == null && skipBlank.isOn()) {
            return;
        }
        Text first = memory == null ? null : memory.original();
        String said = first != null && !first.equals(now) ? first.shown() : null;
        // A wall sign is a thin plate.
        out.add(new Sign(pos, BlockUtil.outlineBox(pos, sign.getBlockState()), lines, front, old, said));
    }

    private Memory remember(BlockPos pos, Text now, boolean old, FindLog.Find known) {
        String kind = old ? OLD_SIGN : SIGN;
        if (known == null) {
            Memory memory = new Memory(List.of(now));
            if (finds.add(pos, kind, memory.detail(), memory.content())) {
                post("§7found " + ChatUtil.withArticle(kind) + " at §f" + BlockUtil.text(pos) + " §7reading §f"
                    + shown(now.shown()) + "§7.");
            }
            return memory;
        }
        Memory memory = Memory.of(known);
        // A find without exact lines only has words to go by. Reading the same words it takes the
        // exact lines without a word in chat.
        boolean wordsOnly = known.content().isEmpty();
        if (memory.newest().equals(now) || (wordsOnly && memory.newest().shown().equals(now.shown()))) {
            Memory same = wordsOnly ? memory.withNewest(now) : memory;
            finds.add(pos, kind, same.detail(), same.content());
            return same;
        }
        Memory next = memory.after(now);
        finds.add(pos, kind, next.detail(), next.content());
        if (changes.isOn()) {
            String before = memory.newest().blank() ? "§7that was gone or blank now reads §f"
                : "§7changed from §f" + shown(memory.newest().shown()) + " §7to §f";
            post("§7" + ChatUtil.withArticle(kind) + " at §f" + BlockUtil.text(pos) + " " + before + shown(now.shown())
                + "§7.");
        }
        return next;
    }

    private void followMissing(Set<Long> scanned, Map<BlockPos, Boolean> standing) {
        Set<BlockPos> missingNow = new HashSet<>();
        List<Ghost> gone = new ArrayList<>();
        for (FindLog.Find find : List.copyOf(finds.here())) {
            BlockPos pos = find.pos();
            if (!scanned.contains(ChunkPos.pack(pos)) || Boolean.TRUE.equals(standing.get(pos))) {
                continue;
            }
            Memory memory = Memory.of(find);
            Text first = memory.original();
            if (memory.newest().blank()) {
                if (first != null && !standing.containsKey(pos)) {
                    gone.add(new Ghost(pos, first.shown()));
                }
                continue;
            }
            if (!missing.contains(pos)) {
                missingNow.add(pos);
                continue;
            }
            Memory next = memory.after(new Text(Collections.nCopies(TEXT_LINES, "")));
            finds.add(pos, find.kind(), next.detail(), next.content());
            if (changes.isOn()) {
                String fate = standing.containsKey(pos) ? "was wiped" : "is gone";
                post("§7" + ChatUtil.withArticle(find.kind()) + " at §f" + BlockUtil.text(pos) + " §7that read §f"
                    + shown(memory.newest().shown()) + " §7" + fate + ".");
            }
        }
        missing = missingNow;
        ghosts = gone;
    }

    private void post(String text) {
        Notice.post(messages, this, Component.literal(text));
    }

    private static String shown(String text) {
        return text.replace(LINE_BREAK, " §7/ §f");
    }

    private boolean near(Vec3 spot, double reach) {
        return mc.player.distanceToSqr(spot) <= reach * reach;
    }

    // Null for the colours of the box.
    private Integer tint(Sign sign) {
        if (sign.first() != null && changedSigns.isOn()) {
            return changedColor.getColor();
        }
        return sign.old() ? oldColor.getColor() : null;
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!inGame() || (signs.isEmpty() && ghosts.isEmpty())) {
            return;
        }
        DrawBatch batch = event.getBatch();
        double reach = range.getValue();
        for (Sign sign : signs) {
            if (!near(sign.box().getCenter(), reach)) {
                continue;
            }
            Integer colour = tint(sign);
            if (colour != null) {
                style.draw(batch, sign.box(), colour, true);
            } else {
                style.draw(batch, sign.box(), true);
            }
        }
        if (goneSigns.isOn()) {
            for (Ghost ghost : ghosts) {
                AABB box = DrawBatch.blockBox(ghost.pos());
                if (near(box.getCenter(), reach)) {
                    style.draw(batch, box, goneColor.getColor(), true);
                }
            }
        }
    }

    @Subscribe
    private void onRender2D(Render2DEvent event) {
        if (!inGame() || (signs.isEmpty() && ghosts.isEmpty()) || !WorldToScreen.update()) {
            return;
        }
        double reach = range.getValue();
        float size = scale.getFloat();
        double step = (mc.font.lineHeight + LINE_GAP) * size;
        for (Sign sign : signs) {
            String first = changedSigns.isOn() ? sign.first() : null;
            if ((sign.lines().isEmpty() && first == null) || !near(sign.box().getCenter(), reach)) {
                continue;
            }
            Vec3 screen = WorldToScreen.project(sign.box().getCenter());
            if (screen == null) {
                continue;
            }
            // The back reads fainter unless the whole sign is tinted.
            Integer colour = tint(sign);
            int front = colour != null ? colour : TEXT;
            int back = colour != null ? colour : RenderUtil.MUTED_TEXT;
            List<String> lines = sign.lines();
            int rows = lines.size() + (first == null ? 0 : 1);
            double top = screen.y - step * (rows - 1) / 2;
            for (int i = 0; i < lines.size(); i++) {
                RenderUtil.label(event.getContext(), mc.font, screen.x, top + step * i, size,
                    List.of(lines.get(i)), List.of(i < sign.front() ? front : back));
            }
            if (first != null) {
                RenderUtil.label(event.getContext(), mc.font, screen.x, top + step * lines.size(), size,
                    List.of(first), List.of(FIRST_TEXT));
            }
        }
        if (!goneSigns.isOn()) {
            return;
        }
        for (Ghost ghost : ghosts) {
            Vec3 middle = Vec3.atCenterOf(ghost.pos());
            Vec3 screen = near(middle, reach) ? WorldToScreen.project(middle) : null;
            if (screen != null) {
                RenderUtil.label(event.getContext(), mc.font, screen.x, screen.y, size, List.of(ghost.first()),
                    List.of(goneColor.getColor()));
            }
        }
    }
}
