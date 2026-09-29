package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.config.BuildTemplate;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.RightClickEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ChoiceListSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.GridSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.ChatWarning;
import com.jellypudding.offlineclient.util.FaceMode;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import com.jellypudding.offlineclient.util.ServerInfo;
import com.jellypudding.offlineclient.util.SwingMode;
import com.jellypudding.offlineclient.util.UseBudget;
import com.jellypudding.offlineclient.util.WorldWatch;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

// Builds a painted pattern or a saved template where you aim. One press of the use key starts
// a build and the next stops it. A block the server turns down is tried again.
public final class AutoBuild extends Module {

    public enum Source { PATTERN, TEMPLATE }

    public enum Orientation { AUTO, UPRIGHT, FLAT }

    private static final int MAX_DRAWN = 1024;

    private static final String PATTERN_NAME = "the pattern";

    private final EnumSetting<Source> source = new EnumSetting<>("Source", "What gets built.", Source.PATTERN)
        .describe(Source.PATTERN, "The pattern painted below. It stands up as a wall or lies flat as a floor.")
        .describe(Source.TEMPLATE, "A saved template. Files live in offlineclient/templates.");
    private final GridSetting pattern = new GridSetting("Pattern",
        "The cells to fill. The dotted middle cell goes where you aim and a pattern that would cut into the"
            + " block you look at moves out to rest on it. Click the size to change it.",
        "#####", "#####", "#####", "#####", "#####")
        .under(source, Source.PATTERN);
    private final EnumSetting<Orientation> orientation = new EnumSetting<>("Orientation",
        "Whether the pattern stands up or lies flat.", Orientation.AUTO)
        .describe(Orientation.AUTO, "Stands up whilst you look ahead and lies flat once you look far up or down.")
        .describe(Orientation.UPRIGHT, "Always stands up like a wall facing you.")
        .describe(Orientation.FLAT, "Always lies flat like a floor with the top row on the far side.")
        .under(source, Source.PATTERN);
    private final NumberSetting tilt = new NumberSetting("Tilt",
        "How far you look up or down before the pattern lies flat.", 40, 10, 80, 5, " degrees")
        .min(0).max(90).under(orientation, Orientation.AUTO);
    private final ChoiceListSetting template = new ChoiceListSetting("Template",
        "Which template to build. The first one picked wins.", BuildTemplate::names)
        .under(source, Source.TEMPLATE);
    private final BoolSetting savedBlocks = new BoolSetting("Use saved blocks",
        "Places the blocks the template names. Off builds it from any plain block in your hotbar.", true)
        .under(source, Source.TEMPLATE);
    private final RegistryListSetting<Block> skip = new RegistryListSetting<>("Skip blocks",
        "Blocks it never takes when it picks what to build with.", BuiltInRegistries.BLOCK, List.of());
    private final NumberSetting distance = new NumberSetting("Distance",
        "How far ahead of your eyes the build sits when you aim at open air.", 3, 1, 6, 0.5, " blocks");
    private final NumberSetting range = new NumberSetting("Range",
        "How far from your eyes a block may go. The server takes one block past your normal reach.",
        5.5, 1, 6, 0.1, " blocks");
    private final NumberSetting blocksPerTick = new NumberSetting("Blocks per tick",
        "Most blocks placed in one tick. Paper takes nine in a short burst and the rest wait their turn.",
        3, 1, 9, 1, " blocks");
    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks to wait after each batch of blocks.", 0, 0, 20, 1, " ticks");
    private final NumberSetting attempts = new NumberSetting("Attempts",
        "How many times a block the server turns down is tried before it is skipped.", 3, 1, 10, 1);
    private final BoolSetting lineOfSight = new BoolSetting("Line of sight",
        "Never places through a wall. Safer against anti cheats and slower.", false);
    private final BoolSetting strictOrder = new BoolSetting("Strict order",
        "Places the blocks in order and waits at one that cannot go down yet. Slower and tidier.", false);
    private final EnumSetting<FaceMode> faceTarget = FaceMode.setting(FaceMode.SERVER);
    private final EnumSetting<SwingMode> swing = SwingMode.setting(SwingMode.PACKET);
    private final BoolSetting show = new BoolSetting("Show build",
        "Outlines where the build goes before you start and what is left whilst it runs.", true);
    private final BoxStyle style = new BoxStyle(BoxStyle.Shape.LINES, 120).under(show);

    private final SlotSwap slots = new SlotSwap();
    private final WorldWatch world = new WorldWatch();
    private BuildTemplate loaded;

    // A template that failed to load. It is read again only for a new pick or a switch on or a use press.
    private String unreadable;

    // Every block of the running build in the order it goes down. Empty whilst idle.
    private final Map<BlockPos, Block> cells = new LinkedHashMap<>();
    // The tick each block was last clicked and how many clicks it has had.
    private final Map<BlockPos, Integer> clickedAt = new HashMap<>();
    private final Map<BlockPos, Integer> tries = new HashMap<>();
    private String building;
    private int ticks;
    private int wait;
    private int filled;
    private final ChatWarning warning = new ChatWarning();

    // What a press would build right now. Drawn whilst idle.
    private Collection<BlockPos> preview = List.of();

    // The use key repeats a held press every few ticks. Only a fresh press starts or stops a build.
    private boolean useHeld;
    private boolean ownsHold;

    public AutoBuild() {
        super("AutoBuild", "Builds a painted pattern or a saved template where you aim.", Category.WORLD);
        addSettings(source, pattern, orientation, tilt, template, savedBlocks, skip, distance, range,
            blocksPerTick, delay, attempts, lineOfSight, strictOrder, faceTarget, swing, show);
        addSettings(style.settings());
        searchTags("insta build", "template", "pattern", "grid", "wall", "floor", "structure");
    }

    @Override
    public String getSuffix() {
        String name = source.is(Source.TEMPLATE) && loaded != null ? loaded.name() : null;
        return building == null ? name : suffix(name, filled * 100 / cells.size() + "%");
    }

    @Override
    protected void onEnable() {
        stopBuild();
        loaded = null;
        unreadable = null;
        world.accept();
        if (!inGame()) {
            return;
        }
        String missing = missingShape();
        if (missing != null) {
            ChatUtil.error(missing);
            return;
        }
        ChatUtil.message("§bAutoBuild §7hold a block and press §f"
            + mc.options.keyUse.getTranslatedKeyMessage().getString() + "§7 to build §f" + shapeName()
            + "§7 where you aim.");
    }

    @Override
    protected void onDisable() {
        stopBuild();
        preview = List.of();
    }

    private void stopBuild() {
        cells.clear();
        clickedAt.clear();
        tries.clear();
        building = null;
        wait = 0;
        filled = 0;
        warning.clear();
        slots.restoreIfMine();
    }

    private String shapeName() {
        return source.is(Source.TEMPLATE) ? loaded.name() : PATTERN_NAME;
    }

    // Why there is nothing to build. Null once there is.
    private String missingShape() {
        if (source.is(Source.TEMPLATE)) {
            return loadChosen() ? null : "Pick a template for AutoBuild first.";
        }
        return pattern.cellsOn().isEmpty() ? "Paint at least one cell of the AutoBuild pattern first." : null;
    }

    // A fresh pick in the list swaps the template between builds.
    private boolean loadChosen() {
        Iterator<String> picked = template.chosen().iterator();
        if (!picked.hasNext()) {
            loaded = null;
            return false;
        }
        String name = picked.next();
        if ((loaded == null || !loaded.name().equals(name)) && !name.equals(unreadable)) {
            loaded = BuildTemplate.load(name);
            unreadable = loaded == null ? name : null;
        }
        return loaded != null;
    }

    // Food and tools keep their use and a door or chest under the crosshair still opens.
    private boolean pressBuilds() {
        if (mc.player.isSpectator() || mc.player.isHandsBusy() || mc.hitResult instanceof EntityHitResult) {
            return false;
        }
        ItemStack held = mc.player.getMainHandItem();
        if (!held.isEmpty() && !(held.getItem() instanceof BlockItem)) {
            return false;
        }
        BlockHitResult aimed = BlockUtil.aimedBlock();
        return aimed == null || mc.player.isSecondaryUseActive()
            || !BlockUtil.opensOnClick(BlockUtil.state(aimed.getBlockPos()));
    }

    // Runs after Freecam and anything else that holds clicks back and before AirPlace.
    @Subscribe(priority = -5)
    private void onRightClick(RightClickEvent event) {
        if (event.isCancelled() || !inGame()) {
            return;
        }
        boolean fresh = !useHeld;
        useHeld = true;
        if (!fresh) {
            if (ownsHold) {
                event.cancel();
            }
            return;
        }
        if (building != null) {
            stopBuild();
            ChatUtil.message("§bAutoBuild §7stopped.");
            take(event);
            return;
        }
        if (!pressBuilds()) {
            return;
        }
        take(event);
        loaded = null;
        unreadable = null;
        String missing = missingShape();
        if (missing != null) {
            ChatUtil.error(missing);
            return;
        }
        Map<BlockPos, Block> layout = layOut();
        if (layout == null) {
            ChatUtil.error("AutoBuild cannot build outside the world.");
            return;
        }
        start(layout);
    }

    // The press is ours. Vanilla would drop a stray block at the crosshair.
    private void take(RightClickEvent event) {
        event.cancel();
        ownsHold = true;
        mc.rightClickDelay = InputUtil.USE_DELAY;
    }

    private void start(Map<BlockPos, Block> layout) {
        stopBuild();
        cells.putAll(layout);
        building = shapeName();
        ticks = 0;
    }

    // Every block of the build where you aim now in the order it goes down. Null outside the world.
    private Map<BlockPos, Block> layOut() {
        BlockHitResult look = BlockUtil.look(Math.max(distance.getValue(), mc.player.blockInteractionRange()));
        boolean onBlock = look.getType() == HitResult.Type.BLOCK;
        BlockPos anchor = onBlock ? BlockUtil.placeSpot(look) : BlockUtil.airSpot(distance.getValue());
        if (anchor == null) {
            return null;
        }
        Direction front = mc.player.getDirection();
        if (source.is(Source.TEMPLATE)) {
            return loaded.layOut(anchor, front);
        }
        boolean flat = liesFlat();
        BuildTemplate painted = paintedShape();
        Map<BlockPos, Block> laid = painted.layOut(anchor, front, flat);
        if (onBlock) {
            laid = painted.layOut(rested(laid.keySet(), anchor, look.getDirection(), front, flat), front, flat);
        }
        return nearestFirst(laid, anchor);
    }

    private boolean liesFlat() {
        return switch (orientation.getValue()) {
            case UPRIGHT -> false;
            case FLAT -> true;
            case AUTO -> Math.abs(mc.player.getXRot()) > tilt.getValue();
        };
    }

    // The painted cells as an upright shape round the middle cell. A template counts left as plus x.
    private BuildTemplate paintedShape() {
        int half = pattern.size() / 2;
        List<BuildTemplate.Entry> entries = new ArrayList<>();
        for (GridSetting.Cell cell : pattern.cellsOn()) {
            entries.add(new BuildTemplate.Entry(half - cell.column(), half - cell.row(), 0, null));
        }
        return BuildTemplate.of(PATTERN_NAME, entries);
    }

    // A face that runs across the pattern would cut it in two. The pattern moves out of the block
    // until its nearest painted cell rests against the face.
    private static BlockPos rested(Collection<BlockPos> laid, BlockPos anchor, Direction face,
                                   Direction front, boolean flat) {
        Direction.Axis across = flat ? Direction.Axis.Y : front.getAxis();
        if (face.getAxis() == across) {
            return anchor;
        }
        int inside = 0;
        for (BlockPos pos : laid) {
            BlockPos offset = pos.subtract(anchor);
            int out = offset.getX() * face.getStepX() + offset.getY() * face.getStepY()
                + offset.getZ() * face.getStepZ();
            inside = Math.max(inside, -out);
        }
        return anchor.relative(face, inside);
    }

    // The build grows out from where you aim. Each new block has one to lean on.
    private static Map<BlockPos, Block> nearestFirst(Map<BlockPos, Block> laid, BlockPos anchor) {
        List<BlockPos> order = new ArrayList<>(laid.keySet());
        order.sort(Comparator.comparingDouble(pos -> pos.distSqr(anchor)));
        Map<BlockPos, Block> sorted = new LinkedHashMap<>();
        for (BlockPos pos : order) {
            sorted.put(pos, laid.get(pos));
        }
        return sorted;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            return;
        }
        useHeld = mc.options.keyUse.isDown();
        ownsHold &= useHeld;
        if (world.changed() || mc.player.isDeadOrDying()) {
            if (building != null) {
                ChatUtil.message("§bAutoBuild §7stopped.");
            }
            stopBuild();
            preview = List.of();
            return;
        }
        if (building == null) {
            preview = show.isOn() && pressBuilds() && missingShape() == null ? previewCells() : List.of();
            return;
        }
        ticks++;
        build();
    }

    private Collection<BlockPos> previewCells() {
        Map<BlockPos, Block> layout = layOut();
        return layout == null ? List.of() : layout.keySet();
    }

    private void build() {
        int settle = ServerInfo.answerTicks();
        List<BlockPos> ready = new ArrayList<>();
        boolean settling = false;
        filled = 0;
        for (BlockPos pos : cells.keySet()) {
            boolean empty = BlockUtil.isReplaceable(pos);
            if (!empty) {
                filled++;
            }
            Integer clicked = clickedAt.get(pos);
            if (clicked != null && ticks - clicked < settle) {
                settling = true;
            } else if (empty && tries.getOrDefault(pos, 0) < attempts.getInt()) {
                ready.add(pos);
            }
        }
        if (ready.isEmpty() && !settling) {
            finish();
            return;
        }
        if (wait > 0) {
            wait--;
            return;
        }
        place(ready);
    }

    private void place(List<BlockPos> ready) {
        int allowed = Math.min(blocksPerTick.getInt(), UseBudget.remaining());
        int placed = 0;
        boolean outOfBlocks = false;
        Block lacking = null;
        for (BlockPos pos : ready) {
            if (placed >= allowed) {
                break;
            }
            BlockUtil.Placement placement = placementFor(pos);
            if (placement != null) {
                Block wanted = source.is(Source.TEMPLATE) && savedBlocks.isOn() ? cells.get(pos) : null;
                if (!hold(wanted, pos)) {
                    outOfBlocks = true;
                    lacking = wanted;
                } else if (placement.place(faceTarget.getValue(), swing.getValue())) {
                    clickedAt.put(pos, ticks);
                    tries.merge(pos, 1, Integer::sum);
                    placed++;
                    continue;
                }
            }
            if (strictOrder.isOn()) {
                break;
            }
        }
        if (placed > 0) {
            wait = delay.getInt();
            warning.clear();
        } else if (outOfBlocks) {
            warning.say(lacking == null ? "AutoBuild has no plain blocks in the hotbar."
                : "AutoBuild needs " + BlockUtil.blockName(lacking) + " in the hotbar.");
        }
    }

    // Null whilst the spot is taken or out of reach.
    private BlockUtil.Placement placementFor(BlockPos pos) {
        return BlockUtil.blockFits(pos)
            ? BlockUtil.reachablePlacement(pos, range.getValue(), lineOfSight.isOn()) : null;
    }

    // The block the template names or else a plain building block that is not skipped.
    private boolean hold(Block wanted, BlockPos pos) {
        Predicate<Block> fits = wanted != null
            ? block -> block == wanted
            : block -> BlockUtil.buildsAt(block, pos) && !skip.contains(block);
        int slot = BlockUtil.findBlockSlot(fits);
        if (slot == -1) {
            return false;
        }
        slots.select(slot);
        return true;
    }

    // Every block still empty at the end ran out of attempts.
    private void finish() {
        int skipped = 0;
        for (BlockPos pos : cells.keySet()) {
            if (BlockUtil.isReplaceable(pos)) {
                skipped++;
            }
        }
        String done = "§bAutoBuild §7finished §f" + building + "§7.";
        ChatUtil.message(skipped == 0 ? done
            : done + " Skipped §f" + skipped + "§7 blocks the server kept turning down.");
        stopBuild();
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!show.isOn()) {
            return;
        }
        int drawn = 0;
        for (BlockPos pos : building != null ? cells.keySet() : preview) {
            if (drawn >= MAX_DRAWN) {
                break;
            }
            if (BlockUtil.isReplaceable(pos)) {
                style.draw(event.getBatch(), pos, true);
                drawn++;
            }
        }
    }
}
