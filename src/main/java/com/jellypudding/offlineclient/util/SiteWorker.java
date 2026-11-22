package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.modules.player.AutoTool;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

// Does a tick of work on the build sites in reach. A block that stands where the build wants
// another comes out first when the caller fixes mistakes. Then the missing blocks go down in the
// order asked for with the blocks the player carries.
public final class SiteWorker {

    private static final Minecraft MC = OfflineClient.MC;

    public enum Order {
        NEAREST, FURTHEST, BOTTOM_UP, TOP_DOWN;

        private Comparator<Task> comparator() {
            Comparator<Task> near = Comparator.comparingDouble(Task::distanceSq);
            return switch (this) {
                case NEAREST -> near;
                case FURTHEST -> near.reversed();
                case BOTTOM_UP -> Comparator.<Task>comparingInt(task -> task.pos().getY()).thenComparing(near);
                case TOP_DOWN -> Comparator.<Task>comparingInt(task -> -task.pos().getY()).thenComparing(near);
            };
        }
    }

    // How the caller wants the work done. The plain test says which blocks may fill a cell that
    // takes any block. Spare blocks are the ones standing where a build wants nothing. Containers
    // says whether a mistake that holds something such as a chest may be broken.
    public record Rules(BuildSite.Rules site, StatePlacer.Rules clicks, TakeFrom takeFrom, Predicate<Block> plain,
                        Order order, boolean strictOrder, boolean fix, boolean spare, boolean containers,
                        int blocksPerTick, int delay) {
    }

    public enum Trouble {
        // The player carries none of the block. A null block stands for any plain block.
        LACKING,
        // Nothing in reach to click that would put the block there.
        NO_CLICK,
        // No click from here gives the block the way it is wanted.
        NO_STATE,
        // Sand or gravel with nothing under it yet.
        NEEDS_GROUND,
        // The head of a golem or a wither whose body is not whole yet.
        NEEDS_BODY
    }

    public record HoldUp(Trouble trouble, Block block) {
    }

    public enum Kept {
        // It holds something such as a chest or a sign and the caller did not allow breaking those.
        CONTAINER,
        // It would take far too long to break with the tool at hand.
        SLOW
    }

    public record Left(Kept why, Block block, BlockPos pos) {
    }

    private record Task(BuildSite site, BlockPos pos, BuildSite.State state, double distanceSq) {
    }

    // The state is null for a cell that takes any block.
    private record Pick(BlockSupply.Source source, BlockState state) {
    }

    private static final int RELEASE_TICKS = 20;
    // The longest a mistake may take to break. Obsidian with a diamond pickaxe is under it and
    // obsidian by hand far over.
    private static final int SLOW_BREAK_TICKS = 300;
    private static final int SLOW_RETRY_TICKS = 200;

    private final StatePlacer placer = new StatePlacer();
    private final BlockSupply supply = new BlockSupply();
    private Task breaking;
    private Block breakingBlock;
    private final Long2IntOpenHashMap slowUntil = new Long2IntOpenHashMap();
    private final LongOpenHashSet told = new LongOpenHashSet();
    private Left left;
    private int ticks;
    private int wait;
    private int idle;
    private HoldUp holdUp;

    // True when a block went down or one is coming out.
    public boolean work(List<BuildSite> sites, Rules rules) {
        ticks++;
        placer.tick();
        holdUp = null;
        Vec3 eye = MC.player.getEyePosition();
        double range = rules.clicks().range();
        List<Task> missing = new ArrayList<>();
        List<Task> wrong = new ArrayList<>();
        for (BuildSite site : sites) {
            site.forEachWithin(eye, range + 1, rules.site(), (pos, state) -> {
                boolean breaks = state == BuildSite.State.WRONG || state == BuildSite.State.SPARE && rules.spare();
                if (state != BuildSite.State.MISSING && !(breaks && rules.fix()) || !BlockUtil.reaches(pos, range)) {
                    return;
                }
                Task task = new Task(site, pos.immutable(), state, pos.distToCenterSqr(eye));
                (state == BuildSite.State.MISSING ? missing : wrong).add(task);
            });
        }
        return breakNext(wrong, rules) || place(missing, rules);
    }

    // Null when the last tick put a block down or had nothing to do.
    public HoldUp holdUp() {
        return holdUp;
    }

    // A mistake that was left standing and has not been handed out before. Null when there is none.
    public Left takeLeft() {
        Left taken = left;
        left = null;
        return taken;
    }

    public void stop() {
        stopBreaking();
        supply.release(true);
        placer.reset();
        wait = 0;
        idle = 0;
        holdUp = null;
    }

    // Makes room for something else that needs the hand such as a meal or a fight. The borrowed
    // stack goes home and the slot stays where the other put it.
    public void standAside() {
        stopBreaking();
        supply.release(false);
        placer.reset();
        holdUp = null;
    }

    // Drops the bookkeeping without touching the inventory. For a respawn or another world.
    // Both hand out a fresh inventory and a stack borrowed in the last one is nothing to give back.
    public void forget() {
        stopBreaking();
        supply.forget();
        placer.reset();
        slowUntil.clear();
        told.clear();
        left = null;
        wait = 0;
        idle = 0;
        holdUp = null;
    }

    // The highest mistake comes out first. Sand and gravel never drop onto a cleared spot.
    private boolean breakNext(List<Task> wrong, Rules rules) {
        // The block is gone on this side. Whether the server let it go shows once its answer
        // is in and a break it turned down is one attempt used up.
        if (breaking != null && !BlockUtil.state(breaking.pos()).is(breakingBlock)) {
            breaking.site().broke(breaking.pos());
            stopBreaking();
        }
        List<Task> ready = new ArrayList<>();
        for (Task task : wrong) {
            if (breakable(task, rules)) {
                ready.add(task);
            }
        }
        if (breaking != null && ready.stream().noneMatch(task -> task.pos().equals(breaking.pos()))) {
            stopBreaking();
        }
        if (breaking == null && !ready.isEmpty()) {
            // A tool does the breaking. The borrowed block goes home before the first swing.
            supply.release(true);
            ready.sort(Order.TOP_DOWN.comparator());
            for (Task task : ready) {
                if (quickEnough(task)) {
                    breaking = task;
                    breakingBlock = BlockUtil.state(task.pos()).getBlock();
                    break;
                }
            }
        }
        if (breaking == null) {
            return false;
        }
        // Two blocks mined at once restart each other. Another module's block goes first.
        if (!BlockMiner.minesOther(breaking.pos()) && !BlockMiner.mine(breaking.pos(),
            rules.clicks().face() != FaceMode.OFF, rules.clicks().swing())) {
            breaking = null;
            return false;
        }
        return true;
    }

    // A mistake that holds something is left unless the caller allows it. A wrong block only goes
    // when the right one is carried or the cell would be dug for nothing.
    private boolean breakable(Task task, Rules rules) {
        BlockPos pos = task.pos();
        BuildSite site = task.site();
        if (slowUntil.get(pos.asLong()) > ticks || !BlockUtil.diggable(pos) || BlockUtil.isStandingOn(pos)
            || site.settling(pos) || site.exhausted(pos, rules.site())
            || rules.clicks().needSight() && !BlockUtil.canSee(pos)) {
            return false;
        }
        BlockState stands = BlockUtil.state(pos);
        if (stands.hasBlockEntity() && !rules.containers()) {
            leave(Kept.CONTAINER, stands, pos);
            return false;
        }
        if (task.state() == BuildSite.State.WRONG && pick(site.wantedAt(pos), pos, rules) == null) {
            heldUp(Trouble.LACKING, site.wantedAt(pos));
            return false;
        }
        return true;
    }

    // The tool is AutoTool's pick whilst it is on and what the hand holds otherwise.
    private boolean quickEnough(Task task) {
        BlockPos pos = task.pos();
        BlockState stands = BlockUtil.state(pos);
        int slot = Modules.enabled(AutoTool.class) ? Modules.toolSlot(stands) : -1;
        ItemStack tool = slot == -1 ? MC.player.getMainHandItem() : MC.player.getInventory().getItem(slot);
        if (BreakTime.breakDelta(tool, pos) * SLOW_BREAK_TICKS >= 1) {
            return true;
        }
        slowUntil.put(pos.asLong(), ticks + SLOW_RETRY_TICKS);
        leave(Kept.SLOW, stands, pos);
        return false;
    }

    // Each mistake left standing is handed out once.
    private void leave(Kept why, BlockState stands, BlockPos pos) {
        if (left == null && told.add(pos.asLong())) {
            left = new Left(why, stands.getBlock(), pos);
        }
    }

    private void stopBreaking() {
        if (breaking != null) {
            BlockMiner.release(breaking.pos());
            breaking = null;
        }
    }

    private boolean place(List<Task> missing, Rules rules) {
        if (wait > 0) {
            wait--;
            return false;
        }
        missing.sort(rules.order().comparator());
        // Paper drops use packets past its limit. The rest of the batch waits for a later tick.
        int allowed = Math.min(rules.blocksPerTick(), UseBudget.remaining());
        int placed = 0;
        for (Task task : missing) {
            if (placed >= allowed) {
                break;
            }
            BuildSite site = task.site();
            if (site.settling(task.pos()) || site.exhausted(task.pos(), rules.site())) {
                continue;
            }
            if (placeAt(site, task.pos(), rules)) {
                placed++;
            } else if (rules.strictOrder()) {
                break;
            }
        }
        if (placed > 0) {
            wait = rules.delay();
            idle = 0;
            holdUp = null;
        } else if (++idle == RELEASE_TICKS) {
            supply.release(true);
        }
        return placed > 0;
    }

    private boolean placeAt(BuildSite site, BlockPos pos, Rules rules) {
        BlockState want = site.wantedAt(pos);
        BuildSite.Wait waits = site.waits(pos, want, rules.site());
        if (waits != BuildSite.Wait.NONE) {
            heldUp(waits == BuildSite.Wait.GROUND ? Trouble.NEEDS_GROUND : Trouble.NEEDS_BODY, want);
            return false;
        }
        Pick pick = pick(want, pos, rules);
        if (pick == null) {
            heldUp(Trouble.LACKING, want);
            return false;
        }
        // The server refuses a block where somebody stands. The spot is tried again once they move.
        if (pick.state() == null ? !BlockUtil.unobstructed(pos) : !BlockUtil.unobstructed(pos, pick.state())) {
            return false;
        }
        BlockSupply.Source source = pick.source();
        StatePlacer.Plan plan = placer.plan(pos, pick.state(), source.stack(), source.hand(), rules.clicks());
        if (plan == null) {
            if (placer.hitch() != StatePlacer.Hitch.NONE) {
                heldUp(placer.hitch() == StatePlacer.Hitch.NO_CLICK ? Trouble.NO_CLICK : Trouble.NO_STATE, want);
            }
            return false;
        }
        if (!plan.ready() || !supply.hold(source)) {
            return false;
        }
        placer.click(plan, source.hand(), rules.clicks());
        supply.clicked();
        site.clicked(pos);
        return true;
    }

    // The first trouble of the tick is the one that gets told.
    private void heldUp(Trouble trouble, BlockState want) {
        if (holdUp == null) {
            holdUp = new HoldUp(trouble, want == null ? null : want.getBlock());
        }
    }

    // The stack for a cell. The wanted block comes first and then its stand in when the caller
    // allows one. A cell that takes any block gets a plain building block that stays where it is put.
    private Pick pick(BlockState want, BlockPos pos, Rules rules) {
        if (want == null) {
            BlockSupply.Source source = supply.find(stack -> stack.getItem() instanceof BlockItem item
                && rules.plain().test(item.getBlock()) && BlockUtil.buildsAt(item.getBlock(), pos),
                plainFrom(rules.takeFrom()), null);
            return source == null ? null : new Pick(source, null);
        }
        Pick real = pickFor(want, rules);
        if (real != null || !rules.site().standIns()) {
            return real;
        }
        BlockState plain = BuildSite.standIn(want);
        return plain == null ? null : pickFor(plain, rules);
    }

    // Where a block for a cell that takes any may come from. Never the backpack. What lies
    // there was not put at hand to be built with.
    public static TakeFrom plainFrom(TakeFrom from) {
        return from == TakeFrom.INVENTORY ? TakeFrom.HOTBAR : from;
    }

    private Pick pickFor(BlockState state, Rules rules) {
        BlockItem item = StateMatch.itemFor(state);
        if (item == null) {
            return null;
        }
        BlockSupply.Source source = supply.find(stack -> stack.is(item), rules.takeFrom(), item);
        return source == null ? null : new Pick(source, state);
    }
}
