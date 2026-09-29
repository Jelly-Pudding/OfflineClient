package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.render.JoinedCells;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.Buckets;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.HeldKey;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.HotbarLoan;
import com.jellypudding.offlineclient.util.LavaReach;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.Tally;
import com.jellypudding.offlineclient.util.TickRate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// Casts a cobblestone mountain from the top of a tower. Each layer pours lava into a
// spout and scoops it back once it stops. Water poured on the plug then runs over the
// flow and turns it to cobblestone. Lava never climbs and all of it stays below you.
public final class LavaCast extends Module {

    public enum Spout { FRONT, AIMED }

    private enum Stage {
        LAND("getting onto the block under you"),
        CLIMB("climbing the tower"),
        POUR("pouring the lava"),
        FLOW("waiting for the lava to stop"),
        SWAP("swapping the lava for water"),
        DOUSE("waiting for the water to stop"),
        SCOOP("scooping the water"),
        DRAIN("waiting for the water to drain");

        private final String doing;

        Stage(String doing) {
            this.doing = doing;
        }
    }

    // What the cast is doing for the flow time command. The layer time is minus one until
    // its lava goes in. The estimate is minus one then too or for a flow too big to follow.
    public record Progress(int layer, int layers, String doing, long layerSeconds, long flowEstimate) {
    }

    // Server ticks the block updates may take to arrive on top of a flow step.
    private static final int SLACK = 20;

    // Ticks a climb or a pour or a scoop may keep failing before the cast gives up.
    private static final int PATIENCE = 40;

    // A flow joined to more blocks than this is not followed any further.
    private static final int MAX_CELLS = 8192;

    // Feet resting on the tower top can read a hair below it.
    private static final double STANDING_SLACK = 1.0E-3;

    // The time packet comes once a second. A server quiet for longer than this has stalled
    // and the flow clocks stop with it.
    private static final long LAG_MILLIS = 1500;

    // A longer gap between two client ticks is a pause or a hitch and counts as this much.
    private static final long LONGEST_TICK_MILLIS = 100;

    private static final double MILLIS_PER_SECOND = 1000;

    // A flow bigger than this costs too much to outline every frame.
    private static final int MAX_DRAWN_CELLS = 2048;

    private final EnumSetting<Spout> spoutMode = new EnumSetting<>("Spout",
        "Where each layer of lava goes in. It is taken when the cast starts.", Spout.FRONT)
        .describe(Spout.FRONT, "In front of the block you stand on. The lava runs down that side of your tower.")
        .describe(Spout.AIMED, "On top of the block you look at. The lava runs down every side of it.");
    private final NumberSetting height = new NumberSetting("Height",
        "How many blocks you pillar up before the first layer.", 10, 0, 64, 1, " blocks").min(0);
    private final NumberSetting layers = new NumberSetting("Layers",
        "How many layers of lava and water to pour.", 5, 1, 64, 1).min(1);
    private final BoolSetting climb = new BoolSetting("Climb",
        "Plugs the spout and climbs a block after each layer. Off pours every layer from the same spot.", true)
        .under(layers, () -> true);
    private final BoolSetting wideTop = new BoolSetting("Wide top",
        "Plugs the open sides of the spout as well. The next layer then spreads across a cross before it runs down.",
        false).under(climb);
    private final NumberSetting widenAfter = new NumberSetting("Widen after",
        "Layers poured from a single block before the cross starts.", 2, 1, 16, 1, " layers").min(1)
        .under(wideTop);
    private final BoolSetting stopHeight = new BoolSetting("Stop height",
        "Ends the cast once your feet reach the height limit.", false);
    private final NumberSetting heightLimit = new NumberSetting("Height limit",
        "The height your feet stop climbing at.", 200, -64, 320, 1)
        .min(DimensionType.MIN_Y).max(DimensionType.MAX_Y).under(stopHeight);
    private final BoolSetting lavaFinish = new BoolSetting("Lava finish",
        "Leaves the last layer as lava instead of stone.", false);
    private final RegistryListSetting<Block> blocks = new RegistryListSetting<>("Blocks",
        "The blocks the tower and the spout are built from. An empty list allows any that cannot burn.",
        BuiltInRegistries.BLOCK,
        List.of(Blocks.COBBLESTONE, Blocks.COBBLED_DEEPSLATE, Blocks.STONE, Blocks.DEEPSLATE,
            Blocks.ANDESITE, Blocks.DIORITE, Blocks.GRANITE, Blocks.DIRT));
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Send a look packet towards each block.", true);
    private final BoolSetting showSpout = new BoolSetting("Show spout",
        "Draws the block the lava is poured into.", true);
    private final BoxStyle spoutStyle = new BoxStyle("Spout", BoxStyle.Shape.BOTH, 25).under(showSpout);
    private final BoolSetting showFlow = new BoolSetting("Show flow",
        "Outlines the lava and then the water whilst each runs.", true);
    private final BoxStyle flowStyle = new BoxStyle("Flow", BoxStyle.Shape.LINES, 15).under(showFlow);

    private final HotbarLoan loan = new HotbarLoan();
    private final HeldKey sneakKey = new HeldKey(options -> options.keyShift);
    private final JoinedCells<BlockPos> joined = new JoinedCells<>(BlockPos::asLong);

    // Every block of the fluid seen joined to the spout since the stage began.
    private final Set<BlockPos> seen = new HashSet<>();

    // The same blocks as a list for the outline. A new list each time the flow grows.
    private List<BlockPos> flowCells = List.of();

    // Set by the staircase just before it hands over. The way back down its steps.
    private Direction stairsDown;

    private Stage stage;
    private BlockPos tower;
    private Vec3i spoutOffset;
    private BlockPos waterSpot;
    private boolean fromStairs;
    private int pillarClimbed;
    private int layersDone;
    private double quiet;
    private double serverTicks;
    private long lastTickAt;
    private long layerStartedAt;
    private long castStartedAt;
    private int flowEstimate;
    private int tries;

    public LavaCast() {
        super("LavaCast", "Pours lava then water from a tower to cast a cobblestone mountain.",
            Category.WORLD);
        addSettings(spoutMode, height, layers, climb, wideTop, widenAfter, stopHeight, heightLimit,
            lavaFinish, blocks, rotate, showSpout);
        addSettings(spoutStyle.settings());
        addSettings(showFlow);
        addSettings(flowStyle.settings());
        searchTags("lava cast", "lavacast", "mountain", "cobblestone", "lava mountain");
    }

    @Override
    public boolean savesEnabledState() {
        return false;
    }

    @Override
    public String getSuffix() {
        if (stage == null) {
            return null;
        }
        String layer = Math.min(layersDone + 1, layers.getInt()) + "/" + layers.getInt();
        return layerStartedAt == 0 ? layer : layer + " " + secondsSince(layerStartedAt) + "s";
    }

    // Null whilst no cast runs.
    public Progress progress() {
        if (stage == null) {
            return null;
        }
        long seconds = layerStartedAt == 0 ? -1 : secondsSince(layerStartedAt);
        return new Progress(Math.min(layersDone + 1, layers.getInt()), layers.getInt(), stage.doing, seconds,
            flowEstimate < 0 ? -1 : Math.round(LavaReach.seconds(flowEstimate)));
    }

    // Blocks above your feet a cast from the top of a staircase climbs through. Each layer
    // after the first climbs one and a climb needs your head room above it.
    public int climbRoom() {
        return climb.isOn() ? layers.getInt() : 0;
    }

    // Starts the cast at the top of a staircase. The lava goes onto the step below and runs
    // down the stairs. The plug then becomes the next step.
    public void castDownStairs(Direction down) {
        stairsDown = down;
        setEnabled(true);
    }

    @Override
    protected void onEnable() {
        fromStairs = stairsDown != null;
        Direction down = stairsDown;
        stairsDown = null;
        stage = null;
        seen.clear();
        flowCells = List.of();
        loan.forget();
        if (!inGame()) {
            setEnabled(false);
            return;
        }
        Staircase stairs = Modules.active(Staircase.class);
        if (stairs != null) {
            stairs.setEnabled(false);
        }
        Modules.stopFlight();
        if (InventoryUtil.findSlot(Items.LAVA_BUCKET, InventoryUtil.WHOLE_INVENTORY) == -1
            || InventoryUtil.findSlot(Items.WATER_BUCKET, InventoryUtil.WHOLE_INVENTORY) == -1) {
            disable("LavaCast needs a lava bucket and a water bucket.");
            return;
        }
        if (BlockUtil.waterEvaporates(mc.player.blockPosition())) {
            disable("Water boils away here and a lava cast needs water.");
            return;
        }
        boolean airborne = !mc.player.onGround();
        tower = airborne ? mc.player.blockPosition().below() : mc.player.getOnPos();
        spoutOffset = spoutOffset(fromStairs ? down : mc.player.getDirection());
        if (spoutOffset == null) {
            disable("Aim at the top of a block to start LavaCast.");
            return;
        }
        pillarClimbed = 0;
        layersDone = 0;
        flowEstimate = -1;
        layerStartedAt = 0;
        castStartedAt = System.currentTimeMillis();
        lastTickAt = castStartedAt;
        if (airborne) {
            begin(Stage.LAND);
        } else {
            pillarOn();
        }
    }

    // Where the spout sits from the tower top. Null when the aim is not on top of a block.
    private Vec3i spoutOffset(Direction facing) {
        if (fromStairs || spoutMode.is(Spout.FRONT)) {
            return facing.getUnitVec3i();
        }
        BlockHitResult hit = BlockUtil.aimedBlock();
        if (hit == null || hit.getDirection() != Direction.UP) {
            return null;
        }
        return hit.getBlockPos().above().subtract(tower);
    }

    @Override
    protected void onDisable() {
        stage = null;
        seen.clear();
        flowCells = List.of();
        joined.clear();
        loan.release();
        sneakKey.letGo();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            setEnabled(false);
            return;
        }
        // A dead player still ticks and the server ignores every click it makes.
        if (mc.player.isDeadOrDying()) {
            disable("LavaCast stopped because you died.");
            return;
        }
        serverTicks = serverTicksPassed();
        // Sneaking keeps you from walking or being washed off the tower.
        sneakKey.hold();
        if (stage != Stage.LAND && stage != Stage.CLIMB && !onTower()) {
            disable("LavaCast stopped because you left the top of the tower.");
            return;
        }
        switch (stage) {
            case LAND -> land();
            case CLIMB -> climb();
            case POUR -> pour();
            case FLOW -> {
                if (settled(Fluids.LAVA, spout())) {
                    lavaSettled();
                }
            }
            case SWAP -> swap();
            case DOUSE -> {
                if (settled(Fluids.WATER, waterSpot)) {
                    begin(Stage.SCOOP);
                }
            }
            case SCOOP -> scoop();
            case DRAIN -> drain();
        }
    }

    // Leaving the game ends the cast at once. A cast left on would carry on after the next join.
    @Subscribe
    private void onClientTick(ClientTickEvent event) {
        if (mc.level == null) {
            setEnabled(false);
        }
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (stage == null) {
            return;
        }
        DrawBatch batch = event.getBatch();
        if (showSpout.isOn()) {
            spoutStyle.draw(batch, spout(), false);
        }
        if (showFlow.isOn() && (stage == Stage.FLOW || stage == Stage.DOUSE) && flowCells.size() <= MAX_DRAWN_CELLS) {
            joined.update(flowCells);
            for (BlockPos pos : flowCells) {
                flowStyle.drawJoined(batch, new AABB(pos), joined.hiddenSides(pos), false);
            }
        }
    }

    private void begin(Stage next) {
        stage = next;
        quiet = 0;
        tries = 0;
        if (next == Stage.FLOW || next == Stage.DOUSE) {
            seen.clear();
            flowCells = List.of();
        }
    }

    private BlockPos spout() {
        return tower.offset(spoutOffset);
    }

    // Server ticks since the last client tick. Timer and a slow server both change how many
    // fit in one. None pass whilst the server has stalled.
    private double serverTicksPassed() {
        long now = System.currentTimeMillis();
        long gap = Math.min(now - lastTickAt, LONGEST_TICK_MILLIS);
        lastTickAt = now;
        if (TickRate.INSTANCE.lagging(LAG_MILLIS)) {
            return 0;
        }
        return gap * TickRate.INSTANCE.tps() / MILLIS_PER_SECOND;
    }

    private long secondsSince(long startedAt) {
        return Math.round((System.currentTimeMillis() - startedAt) / MILLIS_PER_SECOND);
    }

    // Puts a block under your feet when the cast starts in the air. It becomes the tower top.
    private void land() {
        if (mc.player.onGround() && onTower()) {
            pillarOn();
            return;
        }
        if (++tries > PATIENCE || mc.player.getY() < tower.getY() + 1 - STANDING_SLACK) {
            disable("LavaCast could not put a block under you.");
            return;
        }
        if (BlockUtil.isReplaceable(tower)) {
            placeBlock(tower);
        }
    }

    // Climbs the next block of the first pillar or starts pouring once it is done. The
    // height limit cuts the pillar short.
    private void pillarOn() {
        int target = fromStairs ? 0 : height.getInt();
        if (pillarClimbed >= target || heightReached()) {
            begin(Stage.POUR);
        } else if (!roomToClimb()) {
            disable("LavaCast hit a ceiling.");
        } else {
            begin(Stage.CLIMB);
        }
    }

    // Jumps and puts a block where your feet were. The block under you then becomes the tower top.
    private void climb() {
        BlockPos top = tower.above();
        if (BlockUtil.isSolid(top)) {
            if (mc.player.onGround()) {
                tower = top;
                if (layersDone == 0) {
                    pillarClimbed++;
                    pillarOn();
                } else {
                    begin(Stage.POUR);
                }
            }
            return;
        }
        if (++tries > PATIENCE) {
            disable("LavaCast cannot climb any higher here.");
            return;
        }
        if (mc.player.onGround()) {
            BlockUtil.centerPlayer(tower);
            mc.player.jumpFromGround();
        } else if (mc.player.getY() >= top.getY() + 1 && BlockUtil.blockFits(top) && !placeBlock(top)) {
            disable("LavaCast ran out of blocks.");
        }
    }

    // Makes sure the spout has a floor and pours the lava in.
    private void pour() {
        BlockPos spout = spout();
        if (!BlockUtil.isReplaceable(spout) || (climb.isOn() && !BlockUtil.isReplaceable(spout.above()))) {
            disable("LavaCast needs open space for the lava and the water.");
            return;
        }
        BlockPos floor = spout.below();
        if (BlockUtil.isReplaceable(floor)) {
            if (!placeBlock(floor) && ++tries > PATIENCE) {
                disable("LavaCast could not put a floor under the spout.");
            }
            return;
        }
        if (climb.isOn() && !hasBlock()) {
            disable("LavaCast ran out of blocks.");
            return;
        }
        BlockUtil.centerPlayer(tower);
        if (LavaReach.touches(spout, mc.player.getBoundingBox())) {
            disable("LavaCast will not pour lava that could reach you.");
            return;
        }
        Vec3 aim = Buckets.pourAim(spout);
        if (aim == null) {
            disable("LavaCast cannot reach the spout.");
            return;
        }
        int estimate = LavaReach.flowSteps(spout);
        if (Buckets.use(loan, Items.LAVA_BUCKET, aim)) {
            flowEstimate = estimate;
            layerStartedAt = System.currentTimeMillis();
            String time = estimate < 0 ? "" : " It should stop in about §f"
                + ChatUtil.duration(LavaReach.seconds(estimate)) + "§7.";
            ChatUtil.message("§bLavaCast §7poured layer §f" + (layersDone + 1) + "§7 of §f" + layers.getInt()
                + "§7." + time);
            begin(Stage.FLOW);
        } else if (++tries > PATIENCE) {
            disable("LavaCast could not pour the lava.");
        }
    }

    private void lavaSettled() {
        if (seen.isEmpty()) {
            disable("LavaCast stopped because no lava came out of the spout.");
        } else if (lavaFinish.isOn() && finalLayer()) {
            finish("§bLavaCast §7finished §f" + Tally.counted(layersDone + 1, "layer")
                + "§7 with lava on top in §f"
                + ChatUtil.duration((System.currentTimeMillis() - castStartedAt) / MILLIS_PER_SECOND) + "§7.");
        } else {
            begin(Stage.SWAP);
        }
    }

    // Scoops the lava and plugs the spout and pours the water in one tick. The lava beside
    // the spout is still there and the water runs out over it. Without a plug the water
    // goes into the spout itself.
    private void swap() {
        BlockPos spout = spout();
        BlockUtil.centerPlayer(tower);
        if (mc.level.getFluidState(spout).isSourceOfType(Fluids.LAVA)) {
            Vec3 aim = Buckets.scoopAim(spout);
            if (aim == null || !Buckets.fill(loan, aim)) {
                if (++tries > PATIENCE) {
                    disable("LavaCast could not scoop the lava back up.");
                }
                return;
            }
        }
        boolean plugged = climb.isOn() && (BlockUtil.isSolid(spout) || placeBlock(spout));
        if (plugged && wideTop.isOn() && layersDone + 1 >= widenAfter.getInt()) {
            widen(spout);
        }
        waterSpot = plugged ? spout.above() : spout;
        Vec3 aim = Buckets.pourAim(waterSpot);
        if (aim == null || !Buckets.use(loan, Items.WATER_BUCKET, aim)) {
            disable("LavaCast could not pour the water.");
            return;
        }
        begin(Stage.DOUSE);
    }

    // Plugs every open side of the spout. The next layer spreads across the cross before
    // it falls and runs down more sides of the top.
    private void widen(BlockPos spout) {
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos arm = spout.relative(side);
            if (BlockUtil.blockFits(arm)) {
                placeBlock(arm);
            }
        }
    }

    private void scoop() {
        if (!BlockUtil.isWaterSource(waterSpot)) {
            begin(Stage.DRAIN);
            return;
        }
        Vec3 aim = Buckets.scoopAim(waterSpot);
        if (aim != null && Buckets.fill(loan, aim)) {
            begin(Stage.DRAIN);
        } else if (++tries > PATIENCE) {
            disable("LavaCast could not scoop the water back up.");
        }
    }

    // Waits for the water left behind to run dry before the next layer.
    private void drain() {
        int before = seen.size();
        seen.removeIf(pos -> !mc.level.getFluidState(pos).getType().isSame(Fluids.WATER));
        quiet = seen.size() < before ? 0 : quiet + serverTicks;
        if (!seen.isEmpty() && quiet <= Fluids.WATER.getTickDelay(mc.level) + SLACK) {
            return;
        }
        layerDone();
    }

    private void layerDone() {
        layersDone++;
        ChatUtil.message("§bLavaCast §7set layer §f" + layersDone + "§7 of §f" + layers.getInt() + "§7 in §f"
            + ChatUtil.duration((System.currentTimeMillis() - layerStartedAt) / MILLIS_PER_SECOND) + "§7.");
        layerStartedAt = 0;
        flowEstimate = -1;
        String total = ChatUtil.duration((System.currentTimeMillis() - castStartedAt) / MILLIS_PER_SECOND);
        if (layersDone >= layers.getInt()) {
            finish("§bLavaCast §7finished §f" + Tally.counted(layersDone, "layer") + "§7 in §f" + total + "§7.");
        } else if (!climb.isOn()) {
            begin(Stage.POUR);
        } else if (heightReached()) {
            finish("§bLavaCast §7reached the height limit after §f" + Tally.counted(layersDone, "layer") + "§7.");
        } else if (!roomToClimb()) {
            disable("LavaCast hit a ceiling after " + Tally.counted(layersDone, "layer") + ".");
        } else {
            begin(Stage.CLIMB);
        }
    }

    // True when no layer can follow this one.
    private boolean finalLayer() {
        return layersDone + 1 >= layers.getInt() || (climb.isOn() && (heightReached() || !roomToClimb()));
    }

    private boolean heightReached() {
        return stopHeight.isOn() && tower.getY() + 1 >= heightLimit.getInt();
    }

    // Space for your head once you stand a block higher.
    private boolean roomToClimb() {
        return BlockUtil.isReplaceable(tower.above(2)) && BlockUtil.isReplaceable(tower.above(3));
    }

    private void finish(String message) {
        ChatUtil.message(message);
        setEnabled(false);
    }

    // True once no new block has joined the fluid for one step of its flow and the slack.
    // Lava steps every 30 ticks or 10 where it runs fast and water every 5. The quiet time
    // counts server ticks and not client ones.
    private boolean settled(Fluid fluid, BlockPos start) {
        quiet = grew(fluid, start) ? 0 : quiet + serverTicks;
        return quiet > fluid.getTickDelay(mc.level) + SLACK;
    }

    // Walks every block of the fluid joined to the start. True when any is new.
    private boolean grew(Fluid fluid, BlockPos start) {
        if (!mc.level.getFluidState(start).getType().isSame(fluid)) {
            return false;
        }
        int before = seen.size();
        Set<BlockPos> reached = new HashSet<>();
        ArrayDeque<BlockPos> open = new ArrayDeque<>();
        reached.add(start);
        open.add(start);
        while (!open.isEmpty() && reached.size() < MAX_CELLS) {
            BlockPos pos = open.poll();
            seen.add(pos);
            for (Direction side : Direction.values()) {
                BlockPos next = pos.relative(side);
                if (!reached.contains(next) && mc.level.getFluidState(next).getType().isSame(fluid)) {
                    reached.add(next);
                    open.add(next);
                }
            }
        }
        if (seen.size() == before) {
            return false;
        }
        flowCells = List.copyOf(seen);
        return true;
    }

    // Standing anywhere on the tower top. Sneaking lets your middle hang past its edge.
    private boolean onTower() {
        AABB box = mc.player.getBoundingBox();
        return box.minY >= tower.getY() + 1 - STANDING_SLACK
            && box.maxX > tower.getX() && box.minX < tower.getX() + 1
            && box.maxZ > tower.getZ() && box.minZ < tower.getZ() + 1;
    }

    private boolean hasBlock() {
        return InventoryUtil.findSlot(stack -> stack.getItem() instanceof BlockItem item
            && allowed(item.getBlock()), InventoryUtil.WHOLE_INVENTORY) != -1;
    }

    private boolean placeBlock(BlockPos pos) {
        int slot = InventoryUtil.findSlot(stack -> stack.getItem() instanceof BlockItem item
            && allowed(item.getBlock()) && BlockUtil.isBuildingBlock(item.getBlock(), pos),
            InventoryUtil.WHOLE_INVENTORY);
        if (slot == -1 || !loan.select(slot)) {
            return false;
        }
        boolean placed = BlockUtil.placeAny(pos, rotate.isOn(), true);
        loan.giveBack();
        return placed;
    }

    // Lava sets fire to anything that burns beside it.
    private boolean allowed(Block block) {
        if (block.defaultBlockState().ignitedByLava()) {
            return false;
        }
        return blocks.size() == 0 || blocks.contains(block);
    }
}
