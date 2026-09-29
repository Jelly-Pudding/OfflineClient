package com.jellypudding.offlineclient.modules.hunting;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render2DEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
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
import com.jellypudding.offlineclient.util.BoundedMap;
import com.jellypudding.offlineclient.util.LoadedChunks;
import com.jellypudding.offlineclient.util.Notice;
import com.jellypudding.offlineclient.util.RenderUtil;
import com.jellypudding.offlineclient.util.ServerWatch;
import com.jellypudding.offlineclient.util.SignWords;
import com.jellypudding.offlineclient.util.WorldWatch;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Reads the text the server sends with every sign in loaded chunks. A 26.3 server flags every
// sign it loads from a world saved before 26.3 as allowed to run operator commands and sends the
// flag with the text. Only an operator can give a newer sign that flag. The igloo and taiga
// village templates carry it too.
public final class SignReader extends Module {

    // A sign in a loaded chunk. The lines before the front count are its front and the rest its back.
    private record Sign(BlockPos pos, AABB box, List<String> lines, int front, boolean old) {
    }

    // Twice a second is quick enough for a sign that was just written.
    private static final int SCAN_TICKS = 10;

    // Far more signs than a session meets in one dimension. It only bounds the memory.
    private static final int MAX_POSTED = 16384;

    // The background of a label reaches this far past its line of text.
    private static final int LINE_GAP = 2;

    private static final int TEXT = 0xFFFFFFFF;

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

    // The text each sign was last posted with in every dimension of the current server.
    private final Map<ResourceKey<Level>, Map<BlockPos, String>> posted = new HashMap<>();
    private final ServerWatch server = new ServerWatch();
    // Rebuilt by every scan.
    private List<Sign> signs = List.of();
    private int written;
    private int wait;
    private final WorldWatch world = new WorldWatch();

    public SignReader() {
        super("SignReader", "Shows the text of nearby signs through walls and lists new ones in chat.",
            Category.HUNTING);
        addSettings(range, skipBlank, skipWords, oldSigns, oldColor);
        addSettings(style.settings());
        addSettings(scale, messages);
        searchTags("signs", "sign text", "old signs", "base");
    }

    @Override
    public String getSuffix() {
        return count(written);
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
        posted.clear();
        server.forget();
        world.forget();
    }

    private void clear() {
        signs = List.of();
        written = 0;
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
        if (--wait > 0) {
            return;
        }
        wait = SCAN_TICKS;
        scan();
    }

    private void scan() {
        Map<BlockPos, String> seen = postedHere();
        List<String> words = SignWords.words(skipWords.getValue());
        List<Sign> found = new ArrayList<>();
        LoadedChunks.forEachBlockEntity(blockEntity -> {
            if (blockEntity instanceof SignBlockEntity sign) {
                read(sign, words, seen, found);
            }
        });
        signs = found;
        written = (int) found.stream().filter(sign -> !sign.lines().isEmpty()).count();
    }

    private Map<BlockPos, String> postedHere() {
        if (server.changed()) {
            // Two servers share their dimension names.
            posted.clear();
        }
        return posted.computeIfAbsent(mc.level.dimension(), key -> new BoundedMap<>(MAX_POSTED));
    }

    private void read(SignBlockEntity sign, List<String> words, Map<BlockPos, String> seen, List<Sign> out) {
        List<String> lines = SignWords.lines(sign, SignTextSlot.FRONT);
        int front = lines.size();
        SignWords.addLines(sign.getText(SignTextSlot.BACK), lines);
        if (lines.isEmpty() ? skipBlank.isOn() : SignWords.hasAnyWord(lines, words)) {
            return;
        }
        BlockPos pos = sign.getBlockPos();
        boolean old = oldSigns.isOn() && sign.allowOpFeatures;
        if (!lines.isEmpty()) {
            post(pos, lines, old, seen);
        }
        out.add(new Sign(pos, boxOf(sign), lines, front, old));
    }

    // Once for each sign and again whenever its text changes.
    private void post(BlockPos pos, List<String> lines, boolean old, Map<BlockPos, String> seen) {
        String text = String.join(" / ", lines);
        if (text.equals(seen.put(pos, text))) {
            return;
        }
        Notice.post(messages, this, Component.literal("§7found " + (old ? "an old sign" : "a sign") + " at §f"
            + BlockUtil.text(pos) + " §7reading §f" + String.join(" §7/ §f", lines) + "§7."));
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
        if (!inGame() || written == 0 || !WorldToScreen.update()) {
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
            double top = screen.y - step * (lines.size() - 1) / 2;
            for (int i = 0; i < lines.size(); i++) {
                RenderUtil.label(event.getContext(), mc.font, screen.x, top + step * i, size,
                    List.of(lines.get(i)), List.of(i < sign.front() ? front : back));
            }
        }
    }
}
