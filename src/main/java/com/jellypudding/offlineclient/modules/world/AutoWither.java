package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.RightClickEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.BreakTime;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.ChatWarning;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.Hands;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.HotbarLoan;
import com.jellypudding.offlineclient.util.ServerInfo;
import com.jellypudding.offlineclient.util.Tally;
import com.jellypudding.offlineclient.util.UseBudget;
import com.jellypudding.offlineclient.util.WorldWatch;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

// Builds a wither from four soul blocks in a T and three wither skeleton skulls on top.
// The skulls go last and the wither spawns as the third one lands. The two spaces
// beside the bottom block must be air or the pattern never matches.
public final class AutoWither extends Module {

    public enum Trigger { ON_ENABLE, RIGHT_CLICK, REPEAT }

    public enum Pick { NEAREST, FURTHEST, RANDOM }

    public enum Where { FRONT, CROSSHAIR }

    public enum Arms { AUTO, ACROSS_VIEW, EAST_WEST, NORTH_SOUTH }

    // The soul blocks make the T and the skulls sit on top. An item counts when it places the block.
    private enum Kind {
        SOUL_BLOCK("soul sand and soul soil"),
        SKULL("wither skeleton skulls");

        private final String noun;

        Kind(String noun) {
            this.noun = noun;
        }

        boolean isBlock(BlockState state) {
            if (this == SKULL) {
                return state.is(Blocks.WITHER_SKELETON_SKULL) || state.is(Blocks.WITHER_SKELETON_WALL_SKULL);
            }
            return state.is(BlockTags.WITHER_SUMMON_BASE_BLOCKS);
        }

        boolean isItem(ItemStack stack) {
            return stack.getItem() instanceof BlockItem item && isBlock(item.getBlock().defaultBlockState());
        }

        int carried() {
            return InventoryUtil.count(this::isItem, InventoryUtil.WHOLE_INVENTORY);
        }
    }

    private record Part(BlockPos pos, Kind kind) {
    }

    // The parts in build order and the two spaces beside the bottom block. The problem is
    // null when the wither can go there and the reason it cannot otherwise.
    private record Layout(List<Part> parts, List<BlockPos> sides, String problem) {

        boolean fits() {
            return problem == null;
        }
    }

    private static final int SOUL_BLOCKS = 4;
    private static final int SKULLS = 3;

    // How far in front of your feet the bottom block goes.
    private static final int FRONT_DISTANCE = 2;

    // Ticks a block may keep failing to go down before the build gives up.
    private static final int PATIENCE = 20;

    // Times the parts the server turned down are put back before the build gives up.
    private static final int MAX_REBUILDS = 3;

    // Ticks before the ground round you is searched again after no spot was found.
    private static final int SEARCH_TICKS = 20;

    // Withers in a row a repeat may fail to build before it ends. A server that turns every
    // block down would otherwise be asked again for ever.
    private static final int FAILED_IN_A_ROW = 2;

    // A wither not yet started is drawn at this share of the usual strength.
    private static final float GHOST_STRENGTH = 0.5f;
    private static final int BLOCKED_COLOR = 0xFFFF4040;

    private static final String IN_THE_WAY = "Something is in the way of the wither.";
    private static final String OUT_OF_REACH = "The wither would be out of reach.";
    private static final String CORNERS_TAKEN = "The two spaces beside the bottom block have to be empty.";

    private final EnumSetting<Trigger> trigger = new EnumSetting<>("Trigger",
        "When the wither is built.", Trigger.ON_ENABLE)
        .describe(Trigger.ON_ENABLE, "Once when you turn it on.")
        .describe(Trigger.RIGHT_CLICK, "Each time you press use. It stays on and the use key does nothing else meanwhile.")
        .describe(Trigger.REPEAT, "One after another on the ground round you for as long as the blocks last."
            + " Two that fail in a row end it.");
    private final EnumSetting<Pick> pick = new EnumSetting<>("Spot",
        "Which of the spots round you that fit a wither is built on.", Pick.NEAREST)
        .describe(Pick.NEAREST, "The one nearest to you.")
        .describe(Pick.FURTHEST, "The one furthest from you that you still reach.")
        .describe(Pick.RANDOM, "Any one of them.")
        .under(trigger, Trigger.REPEAT);
    private final NumberSetting searchRange = new NumberSetting("Search range",
        "How far from your eyes the bottom block of the next wither may be.", 4, 1, 6, 0.5, " blocks")
        .under(trigger, Trigger.REPEAT);
    private final NumberSetting witherDelay = new NumberSetting("Wither delay",
        "Ticks to wait after one wither before the next is started.", 20, 0, 200, 1, " ticks")
        .under(trigger, Trigger.REPEAT);
    private final EnumSetting<Where> where = new EnumSetting<>("Where",
        "Where the wither is built.", Where.FRONT)
        .describe(Where.FRONT, "Two blocks in front of you on the level you stand on.")
        .describe(Where.CROSSHAIR, "Against the block you look at. With Air place a look at open air builds it at the end of your reach.")
        .visibleWhen(() -> !trigger.is(Trigger.REPEAT));
    private final EnumSetting<Arms> arms = new EnumSetting<>("Arms",
        "Which way the arms of the T point.", Arms.AUTO)
        .describe(Arms.AUTO, "Across your view when that fits and edge on otherwise.")
        .describe(Arms.ACROSS_VIEW, "Always across your view.")
        .describe(Arms.EAST_WEST, "Always east and west.")
        .describe(Arms.NORTH_SOUTH, "Always north and south.");
    private final BoolSetting airPlace = new BoolSetting("Air place",
        "Builds with nothing to lean on. Off needs a block beside or under the bottom block.", true);
    private final BoolSetting lastSkull = new BoolSetting("Last skull",
        "Places the middle skull too. Off leaves it for you and the wither waits until you do.", true);
    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks to wait between blocks.", 1, 0, 10, 1, " ticks");
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Sends a look packet towards each block.", true);
    private final BoolSetting render = new BoolSetting("Show blocks",
        "Outlines the blocks still to be placed. With Right click it outlines where the next wither goes and turns red when it cannot.", true);
    private final BoxStyle style = new BoxStyle(BoxStyle.Shape.BOTH, 280f).under(render);

    private final HotbarLoan loan = new HotbarLoan();
    private final WorldWatch world = new WorldWatch();
    private final ChatWarning noSpot = new ChatWarning();

    // Soul blocks from the bottom up and then the skulls.
    private final List<Part> parts = new ArrayList<>();

    // The two spaces beside the bottom block.
    private final List<BlockPos> corners = new ArrayList<>();

    // Where the next wither would go whilst waiting for a right click.
    private Layout preview;

    private int next;
    private int timer;
    private int failures;
    private int rebuilds;

    // The player tick of the last click.
    private int lastClick;

    // Ticks before the next wither of a repeat is started.
    private int pause;

    // Withers of a repeat that failed one after the other.
    private int failedWithers;

    public AutoWither() {
        super("AutoWither", "Builds and spawns a wither.", Category.WORLD);
        addSettings(trigger, pick, searchRange, witherDelay, where, arms, airPlace, lastSkull, delay, rotate,
            render);
        addSettings(style.settings());
        searchTags("wither", "boss", "soul sand", "skull", "auto wither");
    }

    @Override
    public boolean savesEnabledState() {
        return false;
    }

    // The blocks left whilst building and otherwise how many withers the inventory makes.
    @Override
    public String getSuffix() {
        if (!parts.isEmpty()) {
            return count(parts.size() - next, "left");
        }
        if (!inGame() || mc.player.hasInfiniteMaterials()) {
            return null;
        }
        int withers = withers();
        return withers == 0 ? null : Tally.counted(withers, "wither");
    }

    @Override
    protected void onEnable() {
        forget();
        loan.forget();
        noSpot.clear();
        pause = 0;
        failedWithers = 0;
        if (!inGame()) {
            setEnabled(false);
            return;
        }
        world.accept();
        if (trigger.is(Trigger.ON_ENABLE)) {
            String problem = start();
            if (problem != null) {
                disable(problem);
            }
        }
    }

    @Override
    protected void onDisable() {
        loan.giveBack();
        forget();
    }

    private void forget() {
        parts.clear();
        corners.clear();
        preview = null;
        next = 0;
        timer = 0;
        failures = 0;
        rebuilds = 0;
        lastClick = 0;
    }

    // Fires without a world as well. Leaving the server ends the build and the waiting.
    @Subscribe
    private void onClientTick(ClientTickEvent event) {
        if (mc.level == null) {
            setEnabled(false);
        }
    }

    @Subscribe
    private void onTick(TickEvent event) {
        preview = null;
        if (world.changed() || mc.player.isDeadOrDying()) {
            abandon();
            return;
        }
        if (parts.isEmpty()) {
            idle();
            return;
        }
        if (timer > 0) {
            timer--;
            return;
        }
        // The build takes a hotbar slot and a turn and waits whilst something else has them.
        if (Hands.taken()) {
            return;
        }
        if (clearCorner()) {
            return;
        }
        while (next < parts.size()) {
            Part part = parts.get(next);
            if (built(part)) {
                next++;
                continue;
            }
            // The last part spawns the wither. Every click before it is answered first and a
            // part the server turned down goes back up.
            if (next == parts.size() - 1) {
                if (mc.player.tickCount - lastClick < ServerInfo.answerTicks()) {
                    return;
                }
                int missing = firstMissing();
                if (missing < next) {
                    if (++rebuilds > MAX_REBUILDS) {
                        stop("The server keeps turning the wither down.");
                        return;
                    }
                    next = missing;
                    continue;
                }
            }
            if (!BlockUtil.blockFits(part.pos())) {
                stop(IN_THE_WAY);
                return;
            }
            if (!BlockUtil.serverReaches(part.pos())) {
                stop(OUT_OF_REACH);
                return;
            }
            if (part.kind().carried() == 0) {
                stop("You ran out of " + part.kind().noun + ".");
                return;
            }
            // Paper drops use packets past its limit. The rest go down on a later tick.
            if (UseBudget.remaining() == 0) {
                return;
            }
            if (!place(part)) {
                if (++failures > PATIENCE) {
                    stop("The wither could not be built.");
                }
                return;
            }
            failures = 0;
            lastClick = mc.player.tickCount;
            next++;
            if (delay.getInt() > 0) {
                timer = delay.getInt();
                return;
            }
        }
        failedWithers = 0;
        finish();
    }

    // Every use press builds a wither in right click mode. A press mid build is swallowed
    // too. It would place whatever the build left in hand.
    @Subscribe
    private void onRightClick(RightClickEvent event) {
        if (!inGame() || !trigger.is(Trigger.RIGHT_CLICK)) {
            return;
        }
        // Without this delay vanilla clicks again in the same tick and on every tick the button is held.
        mc.rightClickDelay = InputUtil.USE_DELAY;
        event.cancel();
        if (parts.isEmpty()) {
            String problem = start();
            if (problem != null) {
                ChatUtil.error(problem);
            }
        }
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!render.isOn()) {
            return;
        }
        DrawBatch batch = event.getBatch();
        for (int i = next; i < parts.size(); i++) {
            style.draw(batch, box(parts.get(i)), true);
        }
        Layout ghost = preview;
        if (ghost == null) {
            return;
        }
        int blocked = ColorUtil.fade(BLOCKED_COLOR, GHOST_STRENGTH);
        for (Part part : ghost.parts()) {
            if (ghost.fits()) {
                style.drawFading(batch, box(part), GHOST_STRENGTH, true);
            } else {
                style.draw(batch, box(part), blocked, blocked, true);
            }
        }
    }

    // A skull is drawn at its own size and sits in the middle of its space.
    private static AABB box(Part part) {
        if (part.kind() == Kind.SKULL) {
            return Blocks.WITHER_SKELETON_SKULL.defaultBlockState()
                .getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).bounds().move(part.pos());
        }
        return DrawBatch.blockBox(part.pos());
    }

    // Lays the next wither out and starts on it. Null once it is under way and the reason
    // it cannot be built otherwise.
    private String start() {
        String unready = unready();
        if (unready != null) {
            return unready;
        }
        Layout layout = layout();
        if (layout == null) {
            return trigger.is(Trigger.REPEAT) ? "No ground in reach fits a wither." : "Look at a block first.";
        }
        if (!layout.fits()) {
            return layout.problem();
        }
        forget();
        parts.addAll(layout.parts());
        corners.addAll(layout.sides());
        return null;
    }

    private void idle() {
        if (trigger.is(Trigger.ON_ENABLE)) {
            setEnabled(false);
            return;
        }
        if (trigger.is(Trigger.REPEAT)) {
            repeat();
            return;
        }
        if (!render.isOn()) {
            return;
        }
        Layout layout = layout();
        String unready = unready();
        preview = layout == null || unready == null || !layout.fits()
            ? layout : new Layout(layout.parts(), layout.sides(), unready);
    }

    // Starts the next wither once the wait is over. The blocks running out ends the run. With
    // nowhere to build the ground is searched again a little later.
    private void repeat() {
        if (pause > 0) {
            pause--;
            return;
        }
        String unready = unready();
        if (unready != null) {
            disable(unready);
            return;
        }
        String problem = start();
        if (problem == null) {
            noSpot.clear();
            return;
        }
        noSpot.say(problem);
        pause = SEARCH_TICKS;
    }

    // Why no wither can be built anywhere right now. Null when one can.
    private String unready() {
        if (mc.level.getDifficulty() == Difficulty.PEACEFUL) {
            return "A wither never spawns on peaceful.";
        }
        return carriesEnough() ? null : shortOf();
    }

    private void finish() {
        loan.giveBack();
        forget();
        pause = witherDelay.getInt();
        if (trigger.is(Trigger.ON_ENABLE)) {
            setEnabled(false);
        }
    }

    // A repeat goes on to the next wither after one that failed. Two in a row end it.
    private void stop(String reason) {
        if (trigger.is(Trigger.ON_ENABLE) || trigger.is(Trigger.REPEAT) && ++failedWithers >= FAILED_IN_A_ROW) {
            disable(reason);
            return;
        }
        ChatUtil.error(reason);
        finish();
    }

    // A death or a new world ends the build. The borrowed stack goes home unless you are
    // dying. Respawning hands out a fresh inventory.
    private void abandon() {
        loan.release();
        forget();
        if (trigger.is(Trigger.ON_ENABLE)) {
            setEnabled(false);
        }
    }

    private BlockPos stem() {
        if (where.is(Where.FRONT)) {
            return mc.player.blockPosition().relative(mc.player.getDirection(), FRONT_DISTANCE);
        }
        BlockHitResult hit = BlockUtil.aimedBlock();
        if (hit != null) {
            return BlockUtil.placeSpot(hit);
        }
        return airPlace.isOn() ? BlockUtil.airSpot(BlockUtil.serverBlockReach()) : null;
    }

    // The wither at the spot the settings choose. Null when there is no spot to build on.
    private Layout layout() {
        if (trigger.is(Trigger.REPEAT)) {
            return foundSpot();
        }
        BlockPos stem = stem();
        return stem == null ? null : layoutAt(stem);
    }

    // One of the spots round you where a whole wither fits standing on solid ground. Null when
    // there is none. The search runs nearest first.
    private Layout foundSpot() {
        List<Layout> found = new ArrayList<>();
        for (BlockPos stem : BlockUtil.positionsWithin(searchRange.getValue())) {
            if (!BlockUtil.isSolid(stem.below())) {
                continue;
            }
            Layout layout = layoutAt(stem);
            if (layout.fits()) {
                found.add(layout);
            }
        }
        if (found.isEmpty()) {
            return null;
        }
        return switch (pick.getValue()) {
            case NEAREST -> found.getFirst();
            case FURTHEST -> found.getLast();
            case RANDOM -> found.get(ThreadLocalRandom.current().nextInt(found.size()));
        };
    }

    // The wither with its bottom block at the stem. The arms turn the first way that fits.
    private Layout layoutAt(BlockPos stem) {
        Direction facing = mc.player.getDirection();
        List<Direction> tries = switch (arms.getValue()) {
            // The T faces you when it fits. Edge on is the fallback.
            case AUTO -> List.of(facing.getClockWise(), facing);
            case ACROSS_VIEW -> List.of(facing.getClockWise());
            case EAST_WEST -> List.of(Direction.EAST);
            case NORTH_SOUTH -> List.of(Direction.SOUTH);
        };
        Layout first = null;
        for (Direction across : tries) {
            Layout layout = layout(stem, across);
            if (layout.fits()) {
                return layout;
            }
            if (first == null) {
                first = layout;
            }
        }
        return first;
    }

    // Lays the T out with its arms along the given side.
    private Layout layout(BlockPos stem, Direction across) {
        BlockPos centre = stem.above();
        BlockPos left = centre.relative(across);
        BlockPos right = centre.relative(across.getOpposite());
        List<Part> planned = new ArrayList<>(List.of(new Part(stem, Kind.SOUL_BLOCK),
            new Part(centre, Kind.SOUL_BLOCK), new Part(left, Kind.SOUL_BLOCK), new Part(right, Kind.SOUL_BLOCK),
            new Part(left.above(), Kind.SKULL), new Part(right.above(), Kind.SKULL)));
        // The wither only spawns once the middle skull lands.
        if (lastSkull.isOn()) {
            planned.add(new Part(centre.above(), Kind.SKULL));
        }
        List<BlockPos> sides = List.of(stem.relative(across), stem.relative(across.getOpposite()));
        return new Layout(planned, sides, problemWith(planned, sides));
    }

    private String problemWith(List<Part> planned, List<BlockPos> sides) {
        for (Part part : planned) {
            if (built(part)) {
                continue;
            }
            if (!BlockUtil.blockFits(part.pos())) {
                return IN_THE_WAY;
            }
            if (!BlockUtil.serverReaches(part.pos())) {
                return OUT_OF_REACH;
            }
        }
        for (BlockPos side : sides) {
            if (!BlockUtil.state(side).isAir() && !clearable(side)) {
                return CORNERS_TAKEN;
            }
        }
        // Every later part leans on one placed before it. Only the bottom block needs a neighbour.
        Part bottom = planned.getFirst();
        if (!airPlace.isOn() && !built(bottom) && BlockUtil.findPlaceSupport(bottom.pos()) == null) {
            return "Nothing to build the wither against. Air place builds it anyway.";
        }
        return null;
    }

    // Grass or a flower beside the bottom block breaks in one hit. True whilst one is cleared.
    private boolean clearCorner() {
        for (BlockPos side : corners) {
            if (BlockUtil.state(side).isAir()) {
                continue;
            }
            if (!clearable(side)) {
                stop(CORNERS_TAKEN);
                return true;
            }
            mc.gameMode.startDestroyBlock(side, Direction.UP);
            timer = delay.getInt();
            return true;
        }
        return false;
    }

    private static boolean clearable(BlockPos pos) {
        return BreakTime.clearsInOneHit(pos) && BlockUtil.inReach(pos);
    }

    private static boolean built(Part part) {
        return part.kind().isBlock(BlockUtil.state(part.pos()));
    }

    // The first part that does not stand. The size of the plan once every part does.
    private int firstMissing() {
        for (int i = 0; i < parts.size(); i++) {
            if (!built(parts.get(i))) {
                return i;
            }
        }
        return parts.size();
    }

    // A skull goes on the top of the soul block under it and stands upright.
    private boolean place(Part part) {
        int slot = InventoryUtil.findSlot(part.kind()::isItem, InventoryUtil.WHOLE_INVENTORY);
        if (slot == -1 || !loan.select(slot)) {
            return false;
        }
        if (part.kind() == Kind.SKULL) {
            return BlockUtil.place(part.pos(), Direction.DOWN, rotate.isOn(), true);
        }
        if (airPlace.isOn()) {
            return BlockUtil.placeAny(part.pos(), rotate.isOn(), true);
        }
        Direction support = BlockUtil.findPlaceSupport(part.pos());
        return support != null && BlockUtil.place(part.pos(), support, rotate.isOn(), true);
    }

    private int skullsNeeded() {
        return lastSkull.isOn() ? SKULLS : SKULLS - 1;
    }

    // How many withers the inventory holds the parts for.
    private int withers() {
        return Math.min(Kind.SOUL_BLOCK.carried() / SOUL_BLOCKS, Kind.SKULL.carried() / skullsNeeded());
    }

    // Creative hands out as many blocks as it takes. One of each is enough there.
    private boolean carriesEnough() {
        if (mc.player.hasInfiniteMaterials()) {
            return Kind.SOUL_BLOCK.carried() > 0 && Kind.SKULL.carried() > 0;
        }
        return withers() > 0;
    }

    private String shortOf() {
        if (mc.player.hasInfiniteMaterials()) {
            return "You need a soul sand or soul soil and a wither skeleton skull.";
        }
        return "You need " + SOUL_BLOCKS + " soul sand or soul soil and "
            + Tally.counted(skullsNeeded(), "wither skeleton skull") + ".";
    }
}
