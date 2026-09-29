package com.jellypudding.offlineclient.modules.hunting;

import com.jellypudding.offlineclient.config.FindLog;
import com.jellypudding.offlineclient.config.FindSource;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.gui.FindsScreen;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.render.FindLines;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.AlertSound;
import com.jellypudding.offlineclient.util.BoundedMap;
import com.jellypudding.offlineclient.util.ChunkScanner;
import com.jellypudding.offlineclient.util.Notice;
import com.jellypudding.offlineclient.util.OwnDigs;
import com.jellypudding.offlineclient.util.Tally;
import com.jellypudding.offlineclient.util.WorldWatch;
import com.jellypudding.offlineclient.worldgen.CaveTraces;
import com.jellypudding.offlineclient.worldgen.CaveTraces.Trace;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.portal.PortalShape;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// Finds where players dug into cave air and where they broke nether portals. Structures and
// caves carved before the cave generators changed are cave air. A broken block or a portal
// block of a broken frame leaves plain air there.
public final class CaveTracks extends Module implements FindSource {

    private static final String MINED = "mined block";
    private static final String PORTAL = "broken portal";
    // A portal frame holds its inside and one block of obsidian round it.
    private static final int FRAME = 2;
    private static final int PERCENT = 100;
    // Finds whose plain air was filled in are looked for this often.
    private static final int FILLED_CHECK_TICKS = 20;
    // Far more shapes than a session sees. It only bounds the memory.
    private static final int SHAPES_KEPT = 20_000;
    // A burst of finds as a cave comes into view is told as one message now and then.
    private static final int QUIET_SECONDS = 5;
    // A small dig and the open air round it reach a few blocks past the chunk that keeps its
    // trace. A broken portal is kept beside its own edge.
    private static final int TRACE_BORDER = 4;

    private final NumberSetting range = new NumberSetting("Range",
        "Chunk radius to search around you.", 6, 1, 16, 1, " chunks").max(ChunkMap.MAX_VIEW_DISTANCE);
    private final BoolSetting mined = new BoolSetting("Mined blocks",
        "Marks small holes players dug into old caves and structures.", true);
    private final NumberSetting digSize = new NumberSetting("Dig size",
        "How many blocks one dug space may hold. One marks lone blocks only.", 2, 1, 16, 1, " blocks")
        .min(1).under(mined);
    private final NumberSetting clearRadius = new NumberSetting("Clear radius",
        "How far a hole must be from the open air of a newer cave or the surface.", 2, 0, 10, 1,
        " blocks").min(0).under(mined);
    private final BoolSetting portals = new BoolSetting("Portals",
        "Marks the outlines of broken nether portals in old caves and structures.", true).startFolded();
    private final NumberSetting largestWidth = new NumberSetting("Largest width",
        "How wide a portal outline may be with its frame.", 8, 4, PortalShape.MAX_WIDTH + FRAME, 1, " blocks")
        .min(4).max(PortalShape.MAX_WIDTH + FRAME).under(portals);
    private final NumberSetting largestHeight = new NumberSetting("Largest height",
        "How tall a portal outline may be with its frame.", 8, 5, PortalShape.MAX_HEIGHT + FRAME, 1, " blocks")
        .min(5).max(PortalShape.MAX_HEIGHT + FRAME).under(portals);
    private final BoolSetting cornersOptional = new BoolSetting("Corners optional",
        "Also marks outlines whose four corners were never built.", true).under(portals);
    private final BoolSetting closedEdges = new BoolSetting("Closed edges",
        "Leaves out outlines with open air just past an edge.", true).under(portals);
    private final NumberSetting solidShare = new NumberSetting("Solid share",
        "How much of the inside of an outline may be blocks.", 20, 0, 100, 5, "%").min(0).max(PERCENT)
        .under(portals);
    private final NumberSetting openShare = new NumberSetting("Open share",
        "How much of an outline may have open air beside it.", 15, 0, 100, 5, "%").min(0).max(PERCENT)
        .under(portals);
    private final BoolSetting ignoreOwn = new BoolSetting("Ignore own digging",
        "Leaves out the blocks you broke yourself this session.", true);
    private final Notice notice = new Notice(this, Notice.Where.CHAT, QUIET_SECONDS);
    private final AlertSound alarm = new AlertSound("Rings when a mined block or broken portal is found.",
        SoundEvents.BELL_BLOCK);
    private final BoxStyle style = BoxStyle.shapeOnly(BoxStyle.Shape.BOTH);
    private final ColorSetting minedColor = new ColorSetting("Mined colour",
        "Colour of holes players dug.", 330, false).under(mined);
    private final ColorSetting portalColor = new ColorSetting("Portal colour",
        "Colour of broken portal outlines.", 280, false).under(portals);
    private final FindLog finds = new FindLog(this);
    private final FindLines marks = new FindLines(finds, false);

    private final ChunkScanner<Trace> scanner = new ChunkScanner<Trace>(2).borderReach(TRACE_BORDER);
    private final WorldWatch world = new WorldWatch();
    // The whole shape of each find seen this session. A find from an older session is drawn
    // as its block until its chunk is scanned again.
    private final Map<Long, AABB> shapes = new BoundedMap<>(SHAPES_KEPT);
    private CaveTraces.Rules rules;
    private boolean ignoring;
    private List<Trace> seen = List.of();
    private int ticks;

    public CaveTracks() {
        super("CaveTracks", "Finds blocks players mined out of caves and the outlines of broken portals.",
            Category.HUNTING);
        addSettings(range, mined, digSize, clearRadius, portals, largestWidth, largestHeight, cornersOptional,
            closedEdges, solidShare, openShare, ignoreOwn);
        addSettings(notice.settings());
        addSettings(alarm.settings());
        addSettings(style.settings());
        addSettings(minedColor, portalColor);
        addSettings(marks.settings());
        addSettings(FindsScreen.settingsFor(finds));
        searchTags("cave air", "portal", "broken portal", "mined", "tunnel");
    }

    @Override
    public FindLog findLog() {
        return finds;
    }

    @Override
    public String getSuffix() {
        return count(visible().size());
    }

    @Override
    protected void onEnable() {
        scanner.reset();
        seen = List.of();
    }

    @Override
    protected void onDisable() {
        scanner.reset();
        seen = List.of();
        alarm.stop();
    }

    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        scanner.markChanged(event.getPacket());
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            return;
        }
        if (world.changed()) {
            seen = List.of();
        }
        CaveTraces.Rules now = new CaveTraces.Rules(mined.isOn(), digSize.getInt(), clearRadius.getInt(),
            portals.isOn(), largestWidth.getInt(), largestHeight.getInt(), cornersOptional.isOn(),
            closedEdges.isOn(), solidShare.getValue() / PERCENT, openShare.getValue() / PERCENT);
        // The scan reads the rules once a chunk. A change throws the cached chunks away.
        if (!now.equals(rules) || ignoreOwn.isOn() != ignoring) {
            rules = now;
            ignoring = ignoreOwn.isOn();
            scanner.reset();
        }
        LongSet own = ignoreOwn.isOn() ? OwnDigs.snapshot() : LongSet.of();
        CaveTraces.Rules scanRules = rules;
        scanner.update(range.getInt(), (view, out) -> CaveTraces.scan(view, scanRules, own, out));
        List<Trace> results = scanner.results();
        if (results != seen) {
            seen = results;
            results.forEach(this::found);
        }
        if (++ticks % FILLED_CHECK_TICKS == 0) {
            forgetFilled();
        }
    }

    private void found(Trace trace) {
        shapes.put(trace.key().asLong(), trace.box());
        String kind = trace.kind() == CaveTraces.Kind.MINED ? MINED : PORTAL;
        String detail = trace.kind() == CaveTraces.Kind.MINED
            ? (trace.cells() == 1 ? "a block" : Tally.counted(trace.cells(), "block")) + " mined out of a cave"
            : "a broken portal " + (int) Math.max(trace.box().getXsize(), trace.box().getZsize()) + " wide and "
                + (int) trace.box().getYsize() + " tall";
        if (finds.add(trace.key(), kind, detail)) {
            notice.tell(kind, detail, trace.key());
            alarm.ring();
        }
    }

    // A find whose plain air is gone was filled in or built over. Only loaded spots are looked at.
    private void forgetFilled() {
        for (FindLog.Find find : new ArrayList<>(finds.here())) {
            BlockPos pos = find.pos();
            if (mc.level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4) && !mc.level.getBlockState(pos).is(Blocks.AIR)) {
                finds.remove(find);
                shapes.remove(find.pos().asLong());
            }
        }
    }

    // The finds of the kinds switched on in the dimension you are in.
    private List<FindLog.Find> visible() {
        if (mc.level == null) {
            return List.of();
        }
        return finds.filtered(List.of(mined.isOn(), portals.isOn()),
            find -> MINED.equals(find.kind()) ? mined.isOn() : portals.isOn());
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!inGame()) {
            return;
        }
        DrawBatch batch = event.getBatch();
        List<FindLog.Find> visible = visible();
        for (FindLog.Find find : visible) {
            if (marks.inDrawRange(find)) {
                AABB shape = shapes.get(find.pos().asLong());
                style.draw(batch, shape == null ? DrawBatch.blockBox(find.pos()) : shape, colorOf(find), true);
            }
        }
        marks.draw(batch, visible, this::colorOf);
    }

    private int colorOf(FindLog.Find find) {
        return MINED.equals(find.kind()) ? minedColor.getColor() : portalColor.getColor();
    }
}
