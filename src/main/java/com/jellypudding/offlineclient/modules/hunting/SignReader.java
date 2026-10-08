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
import com.jellypudding.offlineclient.util.WorldWatch;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

// Reads the text the server sends with every sign in loaded chunks. A 26.3 server flags every
// sign it loads from a world saved before 26.3 as allowed to run operator commands and sends the
// flag with the text. Only an operator can give a newer sign that flag. The igloo and taiga
// village templates carry it too. Every sign with writing is a find that also keeps the last
// few texts it showed before.
public final class SignReader extends Module implements FindSource {

    // A sign in a loaded chunk. The lines before the front count are its front and the rest its back.
    // Earlier is the text it showed before its last change or null.
    private record Sign(BlockPos pos, AABB box, List<String> lines, int front, boolean old, String earlier) {
    }

    // The words of a find. The newest text comes first and the texts it replaced follow newest first.
    private record Texts(List<String> all) {

        private static Texts of(String words) {
            return new Texts(List.of(EARLIER_MARK.split(words)));
        }

        private String newest() {
            return all.getFirst();
        }

        // Null for a sign never seen to change.
        private String earlier() {
            return all.size() > 1 ? all.get(1) : null;
        }

        // The words once the sign reads the text. The oldest drop off past the kept number.
        private String after(String text) {
            List<String> texts = new ArrayList<>(all);
            texts.addFirst(text);
            return String.join(EARLIER, texts.subList(0, Math.min(texts.size(), KEPT_TEXTS + 1)));
        }
    }

    // Twice a second is quick enough for a sign that was just written.
    private static final int SCAN_TICKS = 10;

    // The background of a label reaches this far past its line of text.
    private static final int LINE_GAP = 2;

    private static final int TEXT = 0xFFFFFFFF;
    private static final int EARLIER_TEXT = 0xFF8A8A96;

    private static final String SIGN = "sign";
    private static final String OLD_SIGN = "old sign";

    // The lines of a sign read as one text with this between them.
    private static final String LINE_BREAK = " / ";
    // Each earlier text follows the newer ones behind this mark. The character never stays in a text.
    private static final char EARLIER_CHAR = '«';
    private static final String EARLIER = " " + EARLIER_CHAR + " ";
    private static final Pattern EARLIER_MARK = Pattern.compile(EARLIER, Pattern.LITERAL);
    // How many texts a sign keeps from before its newest one.
    private static final int KEPT_TEXTS = 4;

    private final NumberSetting range = new NumberSetting("Range",
        "How far away a sign still shows its text and box.", 48, 8, 256, 8, " blocks").min(1);
    private final BoolSetting skipBlank = new BoolSetting("Skip blank",
        "Leaves out signs with nothing written on them.", true);
    private final TextSetting skipWords = new TextSetting("Skip words",
        "Leaves out signs with any of these words on them. Separate the words with spaces.",
        "spawn hi hello hey discord <---- ---->");
    private final BoolSetting oldSigns = new BoolSetting("Old signs",
        "Colours signs placed before the server moved to 26.3 and calls them old in chat."
            + " Igloo and taiga village signs carry the same mark.", true);
    private final ColorSetting oldColor = new ColorSetting("Old colour",
        "Colour of the text and box of old signs.", 280, false).under(oldSigns);
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
    // Rebuilt by every scan.
    private List<Sign> signs = List.of();
    // Known signs the last scan found missing or blank. Paper sends the signs of a crowded chunk
    // just after the chunk. Only a sign missing from two scans running is gone.
    private Set<BlockPos> missing = Set.of();
    private int wait;

    public SignReader() {
        super("SignReader", "Shows the text of nearby signs through walls and remembers what each one said.",
            Category.HUNTING);
        addSettings(range, skipBlank, skipWords, oldSigns, oldColor);
        addSettings(style.settings());
        addSettings(scale, messages, changes);
        addSettings(FindsScreen.settingsFor(finds));
        searchTags("signs", "sign text", "old signs", "sign history", "base");
    }

    @Override
    public FindLog findLog() {
        return finds;
    }

    @Override
    public String getSuffix() {
        return count(finds.count());
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
        missing = Set.of();
        wait = 0;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            return;
        }
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
        Set<BlockPos> written = new HashSet<>();
        LoadedChunks.forEach(chunk -> {
            scanned.add(ChunkPos.pack(chunk.getPos().x(), chunk.getPos().z()));
            for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                if (blockEntity instanceof SignBlockEntity sign) {
                    read(sign, words, written, found);
                }
            }
        });
        signs = found;
        forgetGone(scanned, written);
    }

    // A sign the skip settings leave out is neither drawn nor put in the finds.
    private void read(SignBlockEntity sign, List<String> words, Set<BlockPos> written, List<Sign> out) {
        List<String> lines = SignWords.lines(sign, SignTextSlot.FRONT);
        int front = lines.size();
        SignWords.addLines(sign.getText(SignTextSlot.BACK), lines);
        BlockPos pos = sign.getBlockPos();
        if (!lines.isEmpty()) {
            written.add(pos);
        }
        if (lines.isEmpty() ? skipBlank.isOn() : SignWords.hasAnyWord(lines, words)) {
            return;
        }
        boolean old = oldSigns.isOn() && sign.allowOpFeatures;
        String earlier = lines.isEmpty() ? null : remember(pos, lines, old);
        out.add(new Sign(pos, boxOf(sign), lines, front, old, earlier));
    }

    // Puts the sign in the finds. A sign whose text changed keeps the text it replaced. Gives the
    // text the sign showed before its last change or null.
    private String remember(BlockPos pos, List<String> lines, boolean old) {
        String text = String.join(LINE_BREAK, lines).replace(EARLIER_CHAR, '<');
        String kind = old ? OLD_SIGN : SIGN;
        FindLog.Find known = finds.at(pos);
        if (known == null) {
            if (finds.add(pos, kind, text)) {
                Notice.post(messages, this, Component.literal("§7found " + ChatUtil.withArticle(kind) + " at §f"
                    + BlockUtil.text(pos) + " §7reading §f" + shown(text) + "§7."));
            }
            return null;
        }
        Texts texts = Texts.of(known.detail());
        if (texts.newest().equals(text)) {
            finds.add(pos, kind, known.detail());
            return texts.earlier();
        }
        finds.add(pos, kind, texts.after(text));
        if (changes.isOn()) {
            Notice.post(messages, this, Component.literal("§7" + ChatUtil.withArticle(kind) + " at §f"
                + BlockUtil.text(pos) + " §7changed from §f" + shown(texts.newest()) + " §7to §f" + shown(text) + "§7."));
        }
        return texts.newest();
    }

    // A known sign that is missing from a scanned chunk or left blank leaves the finds.
    private void forgetGone(Set<Long> scanned, Set<BlockPos> written) {
        Set<BlockPos> missingNow = new HashSet<>();
        for (FindLog.Find find : List.copyOf(finds.here())) {
            BlockPos pos = find.pos();
            if (!scanned.contains(ChunkPos.pack(pos)) || written.contains(pos)) {
                continue;
            }
            if (!missing.contains(pos)) {
                missingNow.add(pos);
                continue;
            }
            finds.remove(find);
            if (changes.isOn()) {
                String fate = mc.level.getBlockEntity(pos) instanceof SignBlockEntity ? "was wiped" : "is gone";
                Notice.post(messages, this, Component.literal("§7" + ChatUtil.withArticle(find.kind()) + " at §f"
                    + BlockUtil.text(pos) + " §7that read §f" + shown(Texts.of(find.detail()).newest()) + " §7" + fate
                    + "."));
            }
        }
        missing = missingNow;
    }

    // A text for chat with the breaks between its lines greyed.
    private static String shown(String text) {
        return text.replace(LINE_BREAK, " §7/ §f");
    }

    // The outline the game gives the sign block. A wall sign is a thin plate.
    private AABB boxOf(SignBlockEntity sign) {
        BlockPos pos = sign.getBlockPos();
        VoxelShape shape = sign.getBlockState().getShape(mc.level, pos);
        return shape.isEmpty() ? DrawBatch.blockBox(pos) : shape.bounds().move(pos);
    }

    private boolean near(Sign sign, double reach) {
        return mc.player.distanceToSqr(sign.box().getCenter()) <= reach * reach;
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!inGame() || signs.isEmpty()) {
            return;
        }
        DrawBatch batch = event.getBatch();
        double reach = range.getValue();
        for (Sign sign : signs) {
            if (!near(sign, reach)) {
                continue;
            }
            if (sign.old()) {
                style.draw(batch, sign.box(), oldColor.getColor(), true);
            } else {
                style.draw(batch, sign.box(), true);
            }
        }
    }

    @Subscribe
    private void onRender2D(Render2DEvent event) {
        if (!inGame() || signs.isEmpty() || !WorldToScreen.update()) {
            return;
        }
        double reach = range.getValue();
        float size = scale.getFloat();
        double step = (mc.font.lineHeight + LINE_GAP) * size;
        for (Sign sign : signs) {
            List<String> lines = sign.lines();
            if (lines.isEmpty() || !near(sign, reach)) {
                continue;
            }
            Vec3 screen = WorldToScreen.project(sign.box().getCenter());
            if (screen == null) {
                continue;
            }
            // The back reads fainter unless the whole sign is tinted as old.
            int front = sign.old() ? oldColor.getColor() : TEXT;
            int back = sign.old() ? front : RenderUtil.MUTED_TEXT;
            int rows = lines.size() + (sign.earlier() == null ? 0 : 1);
            double top = screen.y - step * (rows - 1) / 2;
            for (int i = 0; i < lines.size(); i++) {
                RenderUtil.label(event.getContext(), mc.font, screen.x, top + step * i, size,
                    List.of(lines.get(i)), List.of(i < sign.front() ? front : back));
            }
            if (sign.earlier() != null) {
                RenderUtil.label(event.getContext(), mc.font, screen.x, top + step * lines.size(), size,
                    List.of(sign.earlier()), List.of(EARLIER_TEXT));
            }
        }
    }
}
