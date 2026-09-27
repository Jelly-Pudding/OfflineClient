package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.Buckets;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.HeldKey;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.HotbarLoan;
import com.jellypudding.offlineclient.util.LavaReach;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// Casts a cobblestone mountain from the top of a tower. Each layer pours lava beside the
// block you stand on and scoops it back once it stops. Water poured a block higher runs
// over the flow and turns it to cobblestone. Lava never climbs and all of it stays below you.
public final class LavaCast extends Module {

    private enum Stage { CLIMB, POUR, FLOW, SWAP, DOUSE, SCOOP, DRAIN }

    // Ticks the server's block updates may take to arrive on top of a flow step.
    private static final int SLACK = 20;

    // Ticks a climb or a pour or a scoop may keep failing before the cast gives up.
    private static final int PATIENCE = 40;

    // A flow joined to more blocks than this is not followed any further.
    private static final int MAX_CELLS = 8192;

    // Feet resting on the tower top can read a hair below it.
    private static final double STANDING_SLACK = 1.0E-3;

    private final NumberSetting height = new NumberSetting("Height",
        "How many blocks you pillar up before the first layer.", 10, 0, 64, 1, " blocks").min(0);
    private final NumberSetting layers = new NumberSetting("Layers",
        "How many layers of lava and water to pour. You climb a block after each one.", 5, 1, 64, 1)
        .min(1);
    private final RegistryListSetting<Block> blocks = new RegistryListSetting<>("Blocks",
        "The blocks the tower and the spout are built from. An empty list allows any that cannot burn.",
        BuiltInRegistries.BLOCK,
        List.of(Blocks.COBBLESTONE, Blocks.COBBLED_DEEPSLATE, Blocks.STONE, Blocks.DEEPSLATE,
            Blocks.ANDESITE, Blocks.DIORITE, Blocks.GRANITE, Blocks.DIRT));
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Send a look packet towards each block.", true);

    private final HotbarLoan loan = new HotbarLoan();
    private final HeldKey sneakKey = new HeldKey(options -> options.keyShift);

    // Every block of the fluid seen joined to the spout since the stage began.
    private final Set<BlockPos> seen = new HashSet<>();

    private Stage stage;
    private Direction facing;
    private BlockPos tower;
    private int climbsLeft;
    private int layersDone;
    private int quiet;
    private int tries;

    public LavaCast() {
        super("LavaCast", "Pours lava then water from a tower to cast a cobblestone mountain.",
            Category.WORLD);
        addSettings(height, layers, blocks, rotate);
        searchTags("lava cast", "lavacast", "mountain", "cobblestone");
    }

    @Override
    public boolean savesEnabledState() {
        return false;
    }

    @Override
    public String getSuffix() {
        return stage == null ? null : Math.min(layersDone + 1, layers.getInt()) + "/" + layers.getInt();
    }

    @Override
    protected void onEnable() {
        stage = null;
        seen.clear();
        loan.forget();
        if (!inGame()) {
            setEnabled(false);
            return;
        }
        if (!mc.player.onGround()) {
            disable("Stand on the ground to start LavaCast.");
            return;
        }
        if (InventoryUtil.findSlot(Items.LAVA_BUCKET, InventoryUtil.WHOLE_INVENTORY) == -1
            || InventoryUtil.findSlot(Items.WATER_BUCKET, InventoryUtil.WHOLE_INVENTORY) == -1) {
            disable("LavaCast needs a lava bucket and a water bucket.");
            return;
        }
        if (BlockUtil.waterEvaporates(mc.player.blockPosition())) {
            disable("Water boils away here and a lava cast needs water.");
            return;
        }
        facing = mc.player.getDirection();
        tower = mc.player.getOnPos();
        climbsLeft = height.getInt();
        layersDone = 0;
        begin(climbsLeft > 0 ? Stage.CLIMB : Stage.POUR);
    }

    @Override
    protected void onDisable() {
        stage = null;
        seen.clear();
        loan.giveBack();
        sneakKey.letGo();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            setEnabled(false);
            return;
        }
        // Sneaking keeps you from walking or being washed off the tower.
        sneakKey.hold();
        if (stage != Stage.CLIMB && !onTower()) {
            disable("LavaCast stopped because you left the top of the tower.");
            return;
        }
        switch (stage) {
            case CLIMB -> climb();
            case POUR -> pour();
            case FLOW -> {
                if (settled(Fluids.LAVA, spout())) {
                    begin(Stage.SWAP);
                }
            }
            case SWAP -> swap();
            case DOUSE -> {
                if (settled(Fluids.WATER, spout().above())) {
                    begin(Stage.SCOOP);
                }
            }
            case SCOOP -> scoop();
            case DRAIN -> drain();
        }
    }

    private void begin(Stage next) {
        stage = next;
        quiet = 0;
        tries = 0;
        if (next == Stage.FLOW || next == Stage.DOUSE) {
            seen.clear();
        }
    }

    // The space in front of the block you stand on. The lava goes in here.
    private BlockPos spout() {
        return tower.relative(facing);
    }

    // Jumps and puts a block where your feet were. The block under you then becomes the tower top.
    private void climb() {
        BlockPos top = tower.above();
        if (BlockUtil.isSolid(top)) {
            if (mc.player.onGround()) {
                tower = top;
                climbsLeft--;
                begin(climbsLeft > 0 ? Stage.CLIMB : Stage.POUR);
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
        if (!BlockUtil.isReplaceable(spout) || !BlockUtil.isReplaceable(spout.above())) {
            disable("LavaCast needs open space in front of the block you stand on.");
            return;
        }
        if (!hasBlock()) {
            disable("LavaCast ran out of blocks.");
            return;
        }
        BlockPos floor = spout.below();
        if (BlockUtil.isReplaceable(floor)) {
            if (!placeBlock(floor) && ++tries > PATIENCE) {
                disable("LavaCast could not put a floor under the spout.");
            }
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
        if (Buckets.use(loan, Items.LAVA_BUCKET, aim)) {
            begin(Stage.FLOW);
        } else if (++tries > PATIENCE) {
            disable("LavaCast could not pour the lava.");
        }
    }

    // Scoops the lava and plugs the spout and pours water on the plug in one tick. The
    // lava beside the spout is still there and the water runs out over it.
    private void swap() {
        BlockPos spout = spout();
        if (!hasBlock()) {
            disable("LavaCast ran out of blocks to plug the spout.");
            return;
        }
        BlockUtil.centerPlayer(tower);
        if (mc.level.getFluidState(spout).isSourceOfType(Fluids.LAVA)) {
            Vec3 aim = Buckets.scoopAim(spout);
            if (aim == null || !Buckets.use(loan, Items.BUCKET, aim)) {
                if (++tries > PATIENCE) {
                    disable("LavaCast could not scoop the lava back up.");
                }
                return;
            }
        }
        if (BlockUtil.isReplaceable(spout) && !placeBlock(spout)) {
            disable("LavaCast could not plug the spout.");
            return;
        }
        Vec3 aim = Buckets.pourAim(spout.above());
        if (aim == null || !Buckets.use(loan, Items.WATER_BUCKET, aim)) {
            disable("LavaCast could not pour the water.");
            return;
        }
        begin(Stage.DOUSE);
    }

    private void scoop() {
        BlockPos water = spout().above();
        if (!BlockUtil.isWaterSource(water)) {
            begin(Stage.DRAIN);
            return;
        }
        Vec3 aim = Buckets.scoopAim(water);
        if (aim != null && Buckets.use(loan, Items.BUCKET, aim)) {
            begin(Stage.DRAIN);
        } else if (++tries > PATIENCE) {
            disable("LavaCast could not scoop the water back up.");
        }
    }

    // Waits for the water left behind to run dry before the next layer.
    private void drain() {
        int before = seen.size();
        seen.removeIf(pos -> !mc.level.getFluidState(pos).getType().isSame(Fluids.WATER));
        quiet = seen.size() < before ? 0 : quiet + 1;
        if (!seen.isEmpty() && quiet <= Fluids.WATER.getTickDelay(mc.level) + SLACK) {
            return;
        }
        layersDone++;
        if (layersDone >= layers.getInt()) {
            ChatUtil.message("§bLavaCast §7finished §f" + layersDone + "§7 layers.");
            setEnabled(false);
            return;
        }
        climbsLeft = 1;
        begin(Stage.CLIMB);
    }

    // True once no new block has joined the fluid for one step of its flow and the slack.
    // Lava steps every 30 ticks or 10 where it runs fast and water every 5.
    private boolean settled(Fluid fluid, BlockPos start) {
        quiet = grew(fluid, start) ? 0 : quiet + 1;
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
        return seen.size() > before;
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
