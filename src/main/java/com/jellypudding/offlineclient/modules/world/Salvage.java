package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.ExclusivityGroup;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.path.MiningTrip;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.ChunkScanner;
import com.jellypudding.offlineclient.util.NearestCut;
import com.jellypudding.offlineclient.util.Tally;
import com.jellypudding.offlineclient.worldgen.NaturalBlocks;
import com.jellypudding.offlineclient.worldgen.PlacedOnly;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

// Walks to the blocks players put down near you and mines them one by one.
public final class Salvage extends Module {

    public enum Targets { NEVER_NATURAL, PLACED, LIST }

    // The most found blocks drawn at once with the nearest first.
    private static final int MAX_DRAWN = 512;

    private final EnumSetting<Targets> targets = new EnumSetting<>("Targets",
        "Which blocks count as put down by a player.", Targets.NEVER_NATURAL)
        .describe(Targets.NEVER_NATURAL, "Blocks the world never makes such as concrete and shulker boxes.")
        .describe(Targets.PLACED, "Anything that is not natural terrain. Villages and mineshafts count too.")
        .describe(Targets.LIST, "Only the blocks in the list below.");
    private final RegistryListSetting<Block> blocks = new RegistryListSetting<>("Blocks",
        "The blocks to mine. Click to pick them.", BuiltInRegistries.BLOCK, List.of())
        .under(targets, Targets.LIST);
    private final NumberSetting range = new NumberSetting("Range",
        "How many chunks out from your own to search.", 1, 0, 8, 1, " chunks")
        .min(0).max(ChunkMap.MAX_VIEW_DISTANCE);
    private final NumberSetting height = new NumberSetting("Height",
        "How far above and below your feet to search.", 30, 1, 128, 1, " blocks").min(0);
    private final EnumSetting<MiningTrip.Movement> movement = MiningTrip.movementSetting();
    private final BoolSetting collectDrops = new BoolSetting("Collect drops",
        "Walks over what each block dropped before it moves on.", true)
        .under(movement, MiningTrip.Movement.WALK);
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Turns towards each block on the server side.", true);
    private final BoolSetting showFound = new BoolSetting("Show found",
        "Draws a box on every block still to mine.", true);
    private final BoxStyle foundBox = new BoxStyle("Found", BoxStyle.Shape.LINES, 280).under(showFound);
    private final BoxStyle targetBox = new BoxStyle("Target", BoxStyle.Shape.BOTH, 0);

    private record Find(BlockPos pos, Block block) {
    }

    private final ChunkScanner<Find> scanner = new ChunkScanner<>(0);
    private final NearestCut<Find> shown = new NearestCut<>((find, eye) -> find.pos().distToCenterSqr(eye));
    private final MiningTrip miner = new MiningTrip();

    // Built on the main thread whenever the choice changes and read by the scanner thread.
    private volatile Predicate<Block> placed = block -> false;
    private Targets matchedTargets;
    private Set<Block> matchedList = Set.of();

    private List<Find> results = List.of();
    private int remaining;
    private boolean scanStarted;
    private boolean announced;

    public Salvage() {
        super("Salvage", "Walks to and mines every block players put down near you.", Category.WORLD);
        addSettings(targets, blocks, range, height, movement, collectDrops, rotate, showFound);
        addSettings(foundBox.settings());
        addSettings(targetBox.settings());
        searchTags("clear builds", "base", "grief", "placed blocks");
    }

    @Override
    public boolean savesEnabledState() {
        return false;
    }

    @Override
    public ExclusivityGroup getExclusivityGroup() {
        return ExclusivityGroup.MINING;
    }

    @Override
    public String getSuffix() {
        return count(remaining);
    }

    @Override
    protected void onEnable() {
        matchedTargets = null;
        miner.reset();
    }

    @Override
    protected void onDisable() {
        miner.stop();
        scanner.reset();
        shown.clear();
        results = List.of();
        remaining = 0;
    }

    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        scanner.markChanged(event.getPacket());
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.player.isSpectator()) {
            return;
        }
        refreshTest();
        scan();
        // Nothing is said or mined before the first full search has finished.
        scanStarted |= !scanner.idle();
        if (!announced) {
            if (!scanStarted || !scanner.idle()) {
                return;
            }
            announced = true;
            if (!announce()) {
                return;
            }
        }
        mine();
    }

    private void scan() {
        Predicate<Block> test = placed;
        scanner.update(range.getInt(), (view, out) -> view.forEachMatching(state -> test.test(state.getBlock()),
            (x, y, z, state) -> out.add(new Find(new BlockPos(x, y, z), state.getBlock()))));
        results = scanner.results();
        remaining = (int) results.stream().filter(find -> inWindow(find.pos())).count();
        shown.update(results, MAX_DRAWN);
    }

    private void mine() {
        boolean walk = movement.is(MiningTrip.Movement.WALK);
        Supplier<BlockPos> finder = walk ? this::nearestFound : () -> miner.nearestInReach(this::wanted);
        boolean working = miner.collect(collectDrops.isOn()).tick(this::wanted, finder, rotate.isOn(), walk);
        // A chunk being searched again after a break leaves a gap in the list for a moment.
        if (!working && walk && scanner.idle()) {
            ChatUtil.message("§bSalvage §7mined every block it could reach.");
            setEnabled(false);
        }
    }

    // A new choice of blocks starts the search over.
    private void refreshTest() {
        Targets now = targets.getValue();
        Set<Block> list = Set.copyOf(blocks.resolved());
        if (now == matchedTargets && list.equals(matchedList)) {
            return;
        }
        matchedTargets = now;
        matchedList = list;
        placed = switch (now) {
            case NEVER_NATURAL -> PlacedOnly::contains;
            case PLACED -> block -> !NaturalBlocks.contains(block);
            case LIST -> list::contains;
        };
        scanner.reset();
        shown.clear();
        results = List.of();
        scanStarted = false;
        announced = false;
    }

    // Says what the first full search found. False when a walk has nothing to do and stops.
    private boolean announce() {
        List<Find> near = results.stream().filter(find -> inWindow(find.pos())).toList();
        if (near.isEmpty()) {
            if (movement.is(MiningTrip.Movement.WALK)) {
                disable("Salvage found nothing to mine near you.");
                return false;
            }
            ChatUtil.message("§bSalvage §7found nothing yet and mines whatever comes in reach.");
            return true;
        }
        ChatUtil.message("§bSalvage §7found §f" + Tally.counted(near.size(), "block") + "§7 of §f"
            + Tally.counted(kindsOf(near).size(), "kind") + "§7.");
        return true;
    }

    private Set<Block> kindsOf(List<Find> finds) {
        Set<Block> kinds = new HashSet<>();
        for (Find find : finds) {
            if (inWindow(find.pos())) {
                kinds.add(find.block());
            }
        }
        return kinds;
    }

    private boolean wanted(BlockPos pos) {
        return placed.test(BlockUtil.state(pos).getBlock()) && BlockUtil.diggable(pos) && inWindow(pos)
            && !BlockUtil.isStandingOn(pos);
    }

    private boolean inWindow(BlockPos pos) {
        return Math.abs(pos.getY() - mc.player.getBlockY()) <= height.getInt();
    }

    private BlockPos nearestFound() {
        Vec3 eye = mc.player.getEyePosition();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Find find : results) {
            double distance = find.pos().distToCenterSqr(eye);
            if (distance < bestDistance && !miner.shuns(find.pos()) && wanted(find.pos())) {
                bestDistance = distance;
                best = find.pos();
            }
        }
        return best;
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        BlockPos target = miner.target();
        if (showFound.isOn()) {
            for (Find find : shown.result()) {
                if (!find.pos().equals(target) && inWindow(find.pos())) {
                    foundBox.draw(event.getBatch(), find.pos(), true);
                }
            }
        }
        if (target != null) {
            targetBox.draw(event.getBatch(), target, true);
        }
    }
}
