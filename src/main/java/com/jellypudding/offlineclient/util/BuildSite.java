package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.config.Blueprint;
import com.jellypudding.offlineclient.config.BuildFrame;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CarvedPumpkinBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.InfestedBlock;
import net.minecraft.world.level.block.WitherSkullBlock;
import net.minecraft.world.level.block.WitherWallSkullBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

// A build standing at a spot in a world. It keeps count of the clicks made on each cell and looks
// the whole build over a slice a tick. A caller may hand it a memory of the cells seen done and a
// cell out of view that was last seen done then counts as done.
public final class BuildSite {

    public enum State {
        // The build has no say here or the cell is filtered out or cannot be judged or placed by hand.
        OUTSIDE,
        DONE,
        // The block is still to come or needs another click of the same item.
        MISSING,
        // Another block stands here or the right one the wrong way round.
        WRONG,
        // The build wants the cell empty and something stands in it.
        SPARE
    }

    public enum Wait {
        NONE,
        // Sand or gravel with nothing under it would fall.
        GROUND,
        // The head of a golem or a wither whose body is not whole yet.
        BODY
    }

    public record Rules(Predicate<Block> prints, boolean standIns, boolean strict, int attempts) {
    }

    // Skipped cells ran out of attempts and unknown ones lie in chunks that are not loaded and are
    // not remembered done.
    public record Census(int done, int missing, int wrong, int spare, int skipped, int unknown) {

        public int total() {
            return done + missing + wrong + skipped;
        }

        public int percent() {
            return total() == 0 ? 0 : done * 100 / total();
        }
    }

    @FunctionalInterface
    public interface CellVisitor {
        // The position is only valid inside the call.
        void visit(BlockPos pos, State state);
    }

    // Blocks nobody carries without a special tool and the plain block each one is made of.
    private static final Map<Block, Block> STAND_INS = Map.of(
        Blocks.GRASS_BLOCK, Blocks.DIRT, Blocks.PODZOL, Blocks.DIRT, Blocks.MYCELIUM, Blocks.DIRT,
        Blocks.DIRT_PATH, Blocks.DIRT, Blocks.FARMLAND, Blocks.DIRT,
        Blocks.BUDDING_AMETHYST, Blocks.AMETHYST_BLOCK, Blocks.FROSTED_ICE, Blocks.ICE,
        Blocks.PETRIFIED_OAK_SLAB, Blocks.OAK_SLAB);

    // A golem or a wither is no wider than this from its head and no deeper.
    private static final int HEAD_REACH = 2;
    private static final int COUNT_CELLS = 8192;

    private static final int FORGIVE_TICKS = 1200;
    private static final int SWEEP_TICKS = 200;
    private static final int HEAD_WATCH_TICKS = 100;

    // The tick of the last click on a cell and how many it has had with nothing to show for them.
    // Judged is set once the answer to the last one has been looked at. Broke is set when the
    // last one broke a mistake out of the cell.
    private record Clicks(int at, int count, boolean judged, boolean broke) {
    }

    private record Head(long cell, int at, long[] stood) {
    }

    private final Level level;
    private final Blueprint blueprint;
    private final BuildFrame frame;
    private final BoundingBox box;
    // Every kind of the palette turned the way the build stands. Null takes any block.
    private final BlockState[] wanted;
    // What only the kind decides. Whether it is the half of a door or a bed that comes with the
    // other one and whether some item puts it down by hand and which plain block may stand in.
    private final boolean[] comesWithHalf;
    private final boolean[] hasItem;
    private final BlockState[] standIn;
    // The cells last seen done by their place in the blueprint. Null for a caller that keeps no
    // such memory.
    private final BitSet seenDone;

    private final Long2ObjectOpenHashMap<Clicks> clicks = new Long2ObjectOpenHashMap<>();
    private final List<Head> heads = new ArrayList<>();
    // Cells a golem or a wither took with it. The build has no more say in them.
    private final LongOpenHashSet retired = new LongOpenHashSet();
    private int ticks;
    private int answerTicks;
    private int landed;

    private int cursor;
    private int done;
    private int missing;
    private int wrong;
    private int spare;
    private int skipped;
    private int unknown;
    private Census census;

    public BuildSite(Level level, Blueprint blueprint, BuildFrame frame) {
        this(level, blueprint, frame, null);
    }

    public BuildSite(Level level, Blueprint blueprint, BuildFrame frame, BitSet seenDone) {
        this.level = level;
        this.blueprint = blueprint;
        this.frame = frame;
        this.seenDone = seenDone;
        this.box = frame.box(blueprint);
        int kinds = blueprint.palette().size();
        this.wanted = new BlockState[kinds];
        this.comesWithHalf = new boolean[kinds];
        this.hasItem = new boolean[kinds];
        this.standIn = new BlockState[kinds];
        for (int kind = 0; kind < kinds; kind++) {
            BlockState want = frame.toWorld(blueprint.palette().get(kind));
            wanted[kind] = want;
            if (want != null && !want.isAir()) {
                comesWithHalf[kind] = StateMatch.comesWithOtherHalf(want);
                hasItem[kind] = StateMatch.itemFor(want) != null;
                standIn[kind] = standIn(want);
            }
        }
    }

    public Blueprint blueprint() {
        return blueprint;
    }

    public BuildFrame frame() {
        return frame;
    }

    public BoundingBox box() {
        return box;
    }

    public boolean mayHold(Block block) {
        for (BlockState want : wanted) {
            if (want == null || want.is(block)) {
                return true;
            }
        }
        return false;
    }

    // Once a tick before anything else is asked. The answer ticks say how long a click waits for
    // the server. A golem that walked off is built again only when the caller asks for it.
    public void tick(Rules rules, int answerTicks, boolean again) {
        this.answerTicks = answerTicks;
        if (++ticks % SWEEP_TICKS == 0) {
            clicks.values().removeIf(made -> ticks - made.at() >= FORGIVE_TICKS);
        }
        watchHeads(again);
        judgeClicks(rules);
    }

    public int landed() {
        return landed;
    }

    // A click whose block stands once the answer is in has landed and its cell starts afresh.
    // One with nothing to show keeps its count. A break that left the cell empty was no attempt
    // at the block that goes there and one the server turned down was.
    private void judgeClicks(Rules rules) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        ObjectIterator<Long2ObjectMap.Entry<Clicks>> entries = clicks.long2ObjectEntrySet().fastIterator();
        while (entries.hasNext()) {
            Long2ObjectMap.Entry<Clicks> entry = entries.next();
            Clicks made = entry.getValue();
            if (made.judged() || settling(made)) {
                continue;
            }
            pos.set(entry.getLongKey());
            int count = made.count();
            if (made.broke()) {
                if (level.getBlockState(pos).canBeReplaced()) {
                    count--;
                }
            } else if (tookHold(pos, rules)) {
                // A cell that only waited with a head was never clicked itself.
                landed += Math.min(count, 1);
                count = 0;
            }
            if (count == 0) {
                entries.remove();
            } else {
                entry.setValue(new Clicks(made.at(), count, true, false));
            }
        }
    }

    // A head and blocks round it that are gone together have walked off as a golem or a wither.
    // Their cells are let go for good or get fresh attempts for the next one. A head that is
    // gone alone was turned down and is clicked again like any other block.
    private void watchHeads(boolean again) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        Iterator<Head> watched = heads.iterator();
        while (watched.hasNext()) {
            Head head = watched.next();
            if (ticks - head.at() < answerTicks) {
                continue;
            }
            if (level.getBlockState(pos.set(head.cell())).canBeReplaced()) {
                watched.remove();
                letGo(head, pos, again);
            } else if (ticks - head.at() > HEAD_WATCH_TICKS) {
                watched.remove();
            }
        }
    }

    private void letGo(Head head, BlockPos.MutableBlockPos pos, boolean again) {
        boolean walkedOff = false;
        for (long cell : head.stood()) {
            if (level.getBlockState(pos.set(cell)).canBeReplaced()) {
                walkedOff = true;
                letGo(cell, again);
            }
        }
        if (walkedOff) {
            letGo(head.cell(), again);
        }
    }

    private void letGo(long cell, boolean again) {
        clicks.remove(cell);
        if (!again) {
            retired.add(cell);
        }
    }

    public static BlockState standIn(BlockState state) {
        Block plain = state.getBlock() instanceof InfestedBlock infested ? infested.getHostBlock()
            : STAND_INS.get(state.getBlock());
        return plain == null ? null : plain.withPropertiesOf(state);
    }

    private int kindAt(BlockPos pos) {
        if (!box.isInside(pos) || !retired.isEmpty() && retired.contains(pos.asLong())) {
            return Blueprint.NO_SAY;
        }
        int cell = cellAt(pos);
        return cell == Blueprint.NO_CELL ? Blueprint.NO_SAY : blueprint.indexOf(cell);
    }

    private int cellAt(BlockPos pos) {
        return blueprint.cellAt(frame.localX(pos.getX(), pos.getZ()), frame.localY(pos.getY()),
            frame.localZ(pos.getX(), pos.getZ()));
    }

    public boolean remembered(BlockPos pos) {
        if (seenDone == null) {
            return false;
        }
        int cell = cellAt(pos);
        return cell != Blueprint.NO_CELL && seenDone.get(cell);
    }

    // Null where the build takes any block. Only for a cell the build has a say in.
    public BlockState wantedAt(BlockPos pos) {
        return wanted[kindAt(pos)];
    }

    public State stateAt(BlockPos pos, Rules rules) {
        int kind = kindAt(pos);
        return kind == Blueprint.NO_SAY ? State.OUTSIDE : judge(pos, kind, rules);
    }

    // Every cell judged in view goes into the memory. One in a chunk that is not loaded reads as
    // void air and keeps what was seen there.
    private State judge(BlockPos pos, int kind, Rules rules) {
        BlockState stands = level.getBlockState(pos);
        if (stands.is(Blocks.VOID_AIR)) {
            return State.OUTSIDE;
        }
        State state = compare(stands, kind, rules);
        if (seenDone != null) {
            seenDone.set(cellAt(pos), state == State.DONE);
        }
        return state;
    }

    private State compare(BlockState stands, int kind, Rules rules) {
        BlockState want = wanted[kind];
        if (want == null) {
            return stands.canBeReplaced() ? State.MISSING : State.DONE;
        }
        if (want.isAir()) {
            return stands.isAir() || !stands.getFluidState().isEmpty() ? State.OUTSIDE : State.SPARE;
        }
        // The half of a door or a bed that comes with the other one is never clicked itself. A
        // block no item puts down by hand such as fire or water is left alone altogether.
        boolean comes = comesWithHalf[kind];
        boolean placeable = hasItem[kind] || rules.standIns() && standIn[kind] != null;
        if (!rules.prints().test(want.getBlock()) || !comes && !placeable) {
            return State.OUTSIDE;
        }
        StateMatch.Fit fit = fit(stands, kind, rules);
        if (fit.settled()) {
            return State.DONE;
        }
        if (fit == StateMatch.Fit.SHORT || stands.canBeReplaced()) {
            return comes ? State.OUTSIDE : State.MISSING;
        }
        return State.WRONG;
    }

    // A stand in that stands where the real block is wanted is as good as the real one.
    private StateMatch.Fit fit(BlockState stands, int kind, Rules rules) {
        StateMatch.Fit fit = StateMatch.fit(stands, wanted[kind], rules.strict());
        return fit == StateMatch.Fit.WRONG && rules.standIns() && standIn[kind] != null
            ? StateMatch.fit(stands, standIn[kind], rules.strict()) : fit;
    }

    public void forEachWithin(Vec3 centre, double range, Rules rules, CellVisitor visitor) {
        int reach = (int) Math.ceil(range);
        BlockPos middle = BlockPos.containing(centre);
        int fromX = Math.max(box.minX(), middle.getX() - reach);
        int toX = Math.min(box.maxX(), middle.getX() + reach);
        int fromY = Math.max(box.minY(), middle.getY() - reach);
        int toY = Math.min(box.maxY(), middle.getY() + reach);
        int fromZ = Math.max(box.minZ(), middle.getZ() - reach);
        int toZ = Math.min(box.maxZ(), middle.getZ() + reach);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = fromY; y <= toY; y++) {
            for (int z = fromZ; z <= toZ; z++) {
                for (int x = fromX; x <= toX; x++) {
                    int kind = kindAt(pos.set(x, y, z));
                    if (kind != Blueprint.NO_SAY) {
                        visitor.visit(pos, judge(pos, kind, rules));
                    }
                }
            }
        }
    }

    // Why a block has to wait before it goes down. Sand and gravel want something under them.
    // A head that the build sets on the body of a golem or a wither goes down last of all. The
    // body is whole then whatever order the rest went in. A carved pumpkin or a skull on
    // anything else is a block like any other.
    public Wait waits(BlockPos pos, BlockState want, Rules rules) {
        if (want == null) {
            return Wait.NONE;
        }
        Block block = want.getBlock();
        if (block instanceof FallingBlock && FallingBlock.isFree(level.getBlockState(pos.below()))) {
            return Wait.GROUND;
        }
        return headsBody(pos, block) && bodyUnfinished(pos, rules) ? Wait.BODY : Wait.NONE;
    }

    private static boolean isHead(Block block) {
        return block instanceof CarvedPumpkinBlock || isWitherHead(block);
    }

    private static boolean isWitherHead(Block block) {
        return block instanceof WitherSkullBlock || block instanceof WitherWallSkullBlock;
    }

    // Every golem and wither shape of the game has its head right on top of a body block.
    private boolean headsBody(BlockPos pos, Block head) {
        if (!isHead(head)) {
            return false;
        }
        int below = kindAt(pos.below());
        BlockState body = below == Blueprint.NO_SAY ? null : wanted[below];
        if (body == null) {
            return false;
        }
        return isWitherHead(head) ? body.is(BlockTags.WITHER_SUMMON_BASE_BLOCKS)
            : body.is(Blocks.SNOW_BLOCK) || body.is(Blocks.IRON_BLOCK) || body.is(BlockTags.COPPER);
    }

    private boolean bodyUnfinished(BlockPos head, Rules rules) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = -HEAD_REACH; y <= 0; y++) {
            for (int x = -HEAD_REACH; x <= HEAD_REACH; x++) {
                for (int z = -HEAD_REACH; z <= HEAD_REACH; z++) {
                    int kind = kindAt(pos.setWithOffset(head, x, y, z));
                    if (kind == Blueprint.NO_SAY || wanted[kind] != null && isHead(wanted[kind].getBlock())) {
                        continue;
                    }
                    State state = judge(pos, kind, rules);
                    if (state == State.MISSING || state == State.WRONG) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // Counts a click on a cell. The cell is left alone until the server has answered. A head
    // that goes onto a body is watched from here on and the blocks round it wait for the answer
    // with it. Should they all vanish nothing is put back before that is known.
    public void clicked(BlockPos pos) {
        long cell = pos.asLong();
        stamp(cell, false);
        int kind = kindAt(pos);
        if (kind == Blueprint.NO_SAY || wanted[kind] == null || !headsBody(pos, wanted[kind].getBlock())) {
            return;
        }
        LongArrayList stood = new LongArrayList();
        BlockPos.MutableBlockPos near = new BlockPos.MutableBlockPos();
        for (int y = -HEAD_REACH; y <= 0; y++) {
            for (int x = -HEAD_REACH; x <= HEAD_REACH; x++) {
                for (int z = -HEAD_REACH; z <= HEAD_REACH; z++) {
                    near.setWithOffset(pos, x, y, z);
                    long beside = near.asLong();
                    if (beside == cell || kindAt(near) == Blueprint.NO_SAY
                        || level.getBlockState(near).canBeReplaced()) {
                        continue;
                    }
                    stood.add(beside);
                    Clicks waited = clicks.get(beside);
                    clicks.put(beside, new Clicks(ticks, waited == null ? 0 : waited.count(), false, false));
                }
            }
        }
        heads.removeIf(head -> head.cell() == cell);
        heads.add(new Head(cell, ticks, stood.toLongArray()));
    }

    // Counts a mistake broken out of a cell. The cell is left alone until the server has
    // answered. A block that is back by then was not the player's to break.
    public void broke(BlockPos pos) {
        stamp(pos.asLong(), true);
    }

    private void stamp(long cell, boolean broke) {
        Clicks before = clicks.get(cell);
        clicks.put(cell, new Clicks(ticks, before == null ? 1 : before.count() + 1, false, broke));
    }

    public boolean settling(BlockPos pos) {
        Clicks made = clicks.get(pos.asLong());
        return made != null && settling(made);
    }

    private boolean settling(Clicks made) {
        return ticks - made.at() < answerTicks;
    }

    // True for a cell clicked as often as the caller allows with nothing to show for it. It gets
    // fresh attempts after a while. Once the wanted block stands the count starts afresh. A
    // second slab or a third candle is no failed attempt.
    public boolean exhausted(BlockPos pos, Rules rules) {
        long cell = pos.asLong();
        Clicks made = clicks.get(cell);
        if (made == null || settling(made)) {
            return false;
        }
        if (ticks - made.at() >= FORGIVE_TICKS || tookHold(pos, rules)) {
            clicks.remove(cell);
            return false;
        }
        return made.count() >= rules.attempts();
    }

    private boolean tookHold(BlockPos pos, Rules rules) {
        int kind = kindAt(pos);
        if (kind == Blueprint.NO_SAY) {
            return false;
        }
        BlockState stands = level.getBlockState(pos);
        return wanted[kind] == null ? !stands.canBeReplaced() : fit(stands, kind, rules) != StateMatch.Fit.WRONG;
    }

    public void forgetClicks() {
        clicks.clear();
    }

    public boolean anySettling() {
        for (Clicks made : clicks.values()) {
            if (settling(made)) {
                return true;
            }
        }
        return false;
    }

    public void count(Rules rules) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int end = Math.min(blueprint.cellCount(), cursor + COUNT_CELLS);
        for (; cursor < end; cursor++) {
            if (blueprint.indexOf(cursor) == Blueprint.NO_SAY) {
                continue;
            }
            int x = blueprint.xOf(cursor);
            int z = blueprint.zOf(cursor);
            tally(pos.set(frame.worldX(x, z), frame.worldY(blueprint.yOf(cursor)), frame.worldZ(x, z)), rules);
        }
        if (cursor < blueprint.cellCount()) {
            return;
        }
        census = new Census(done, missing, wrong, spare, skipped, unknown);
        cursor = 0;
        done = 0;
        missing = 0;
        wrong = 0;
        spare = 0;
        skipped = 0;
        unknown = 0;
    }

    private void tally(BlockPos pos, Rules rules) {
        int kind = kindAt(pos);
        if (kind == Blueprint.NO_SAY) {
            return;
        }
        if (level.getBlockState(pos).is(Blocks.VOID_AIR)) {
            if (remembered(pos)) {
                done++;
            } else {
                unknown++;
            }
            return;
        }
        switch (judge(pos, kind, rules)) {
            case DONE -> done++;
            case MISSING -> {
                if (exhausted(pos, rules)) {
                    skipped++;
                } else {
                    missing++;
                }
            }
            case WRONG -> wrong++;
            case SPARE -> spare++;
            case OUTSIDE -> {
            }
        }
    }

    // Null until the whole build has been looked over once.
    public Census census() {
        return census;
    }

    // True once the last full look found nothing missing and no click waits for its answer. A
    // caller that fixes mistakes wants none left either and one that clears spare blocks none of those.
    public boolean stands(boolean fixes, boolean clearsSpare) {
        return census != null && census.missing() == 0 && census.unknown() == 0 && !anySettling()
            && (!fixes || census.wrong() == 0 && (!clearsSpare || census.spare() == 0));
    }
}
