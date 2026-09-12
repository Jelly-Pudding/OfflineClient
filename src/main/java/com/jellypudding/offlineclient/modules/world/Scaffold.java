package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

// Keeps a block under the player's feet. Targets come from the current
// position and where the speed puts the player over the next few ticks.
public final class Scaffold extends Module {

    public enum ListMode { ONLY_LISTED, EXCEPT_LISTED }

    private static final Direction[] BRIDGE_SIDES = {
        Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };
    private static final int MAX_PLACES_PER_TICK = 2;

    // How far from the eyes a support face may sit for the click to land.
    private static final double CLICK_REACH_SQ = 36;

    // Ticks a placed block stays drawn whilst it fades out.
    private static final int FADE_TICKS = 8;

    private final RegistryListSetting<Block> blocks = new RegistryListSetting<>("Blocks",
        "The blocks the list mode below applies to.",
        BuiltInRegistries.BLOCK,
        List.of(Blocks.COBBLESTONE, Blocks.COBBLED_DEEPSLATE, Blocks.NETHERRACK,
            Blocks.DIRT, Blocks.STONE, Blocks.DEEPSLATE, Blocks.OBSIDIAN));
    private final EnumSetting<ListMode> listMode = new EnumSetting<>("List mode",
        "How the block list is used.", ListMode.ONLY_LISTED)
        .describe(ListMode.ONLY_LISTED, "Only listed blocks go under your feet. An empty list allows any building block.")
        .describe(ListMode.EXCEPT_LISTED, "Any building block goes under your feet except the listed ones.");
    private final BoolSetting tower = new BoolSetting("Tower",
        "Hold jump to build straight up.", true);
    private final NumberSetting towerSpeed = new NumberSetting("Tower speed",
        "How hard each tower jump pushes you up.", 0.42, 0.3, 0.5, 0.01)
        .min(0.1).max(1).under(tower);
    private final BoolSetting towerWhilstMoving = new BoolSetting("Tower whilst moving",
        "Keeps the tower boost on whilst you walk. Off means jump only lifts you when you stand still.", false)
        .under(tower);
    private final NumberSetting lookAhead = new NumberSetting("Look ahead",
        "Ticks of movement to build ahead of you.", 2, 0, 5, 1, " ticks").min(0).max(10);
    private final BoolSetting airPlace = new BoolSetting("Air place",
        "Places straight into the air with nothing to lean on. Needs a server that allows it.", false);
    private final NumberSetting radius = new NumberSetting("Radius",
        "Also fills every spot within this distance on the same level for a platform.", 0, 0, 6, 0.5, " blocks")
        .min(0).max(6).under(airPlace);
    private final NumberSetting blocksPerTick = new NumberSetting("Blocks per tick",
        "How many blocks may go down in one tick.", 3, 1, 10, 1, " blocks")
        .min(1).under(airPlace);
    private final NumberSetting reach = new NumberSetting("Reach",
        "When nothing touches the spot under you the nearest spot with support within this range is filled instead.",
        4, 0, 8, 0.5, " blocks").min(0).max(8).unless(airPlace);
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Turn towards each block on the server side.", true);
    private final BoolSetting swing = new BoolSetting("Swing",
        "Swings your arm on each placement.", false);
    private final BoolSetting autoSwitch = new BoolSetting("Auto switch",
        "Switches to a block in your hotbar. Off only places whilst you already hold one.", true);
    private final BoolSetting swapBack = new BoolSetting("Swap back",
        "Returns to the slot you had after every placement.", true)
        .under(autoSwitch);
    private final BoolSetting onlyOnClick = new BoolSetting("Only on click",
        "Only place whilst you hold the use key down.", false);
    private final BoolSetting down = new BoolSetting("Down",
        "Sneak to build one level lower. When off sneaking pauses Scaffold.", true);
    private final BoolSetting showPlaced = new BoolSetting("Show placed",
        "Draws each placed block for a moment.", true);
    private final BoxStyle style = new BoxStyle(BoxStyle.Shape.BOTH, 278).under(showPlaced);

    private final SlotSwap slots = new SlotSwap();

    // Blocks placed lately and the ticks each has left to show.
    private final List<Placed> placed = new ArrayList<>();

    private boolean descending;

    private boolean rotatedThisTick;

    public Scaffold() {
        super("Scaffold", "Places blocks under you as you walk.", Category.WORLD);
        addSettings(blocks, listMode, tower, towerSpeed, towerWhilstMoving, lookAhead, airPlace,
            radius, blocksPerTick, reach, rotate, swing, autoSwitch, swapBack, onlyOnClick, down,
            showPlaced);
        addSettings(style.settings());
        searchTags("bridge", "auto bridge", "tower");
    }

    // PlayerMixin lifts the sneak edge clamp whilst this is true.
    public boolean isDescending() {
        return isEnabled() && descending;
    }

    @Override
    protected void onDisable() {
        descending = false;
        placed.clear();
        slots.restoreIfMine();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        descending = false;
        rotatedThisTick = false;
        agePlaced();
        if (!inGame() || mc.player.isSpectator() || mc.player.isPassenger()) {
            return;
        }
        // The input object is swapped for a silent one by Freecam.
        Input keys = mc.player.input.keyPresses;
        boolean jumping = keys.jump();
        boolean sneaking = keys.shift();

        Vec3 pos = mc.player.position();
        Vec3 velocity = mc.player.getDeltaMovement();

        if (sneaking && !jumping && !down.isOn()) {
            return;
        }
        if (onlyOnClick.isOn() && !InputUtil.physicallyHeld(mc.options.keyUse)) {
            return;
        }
        descending = sneaking && !jumping && down.isOn();

        int targetY = Mth.floor(pos.y) - 1;
        if (descending) {
            targetY--;
        }

        int limit = airPlace.isOn() ? blocksPerTick.getInt() : MAX_PLACES_PER_TICK;
        int count = 0;
        for (BlockPos target : targets(pos, velocity, targetY)) {
            if (count >= limit) {
                break;
            }
            if (!BlockUtil.isReplaceable(target) || occupied(target)) {
                continue;
            }
            if (place(target)) {
                count++;
            }
        }

        if (tower.isOn() && jumping && !sneaking) {
            towerUp(velocity);
        }
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!showPlaced.isOn()) {
            return;
        }
        for (Placed entry : placed) {
            float strength = (float) entry.ticksLeft / FADE_TICKS;
            style.drawFading(event.getBatch(), DrawBatch.blockBox(entry.pos), strength, false);
        }
    }

    private void agePlaced() {
        Iterator<Placed> it = placed.iterator();
        while (it.hasNext()) {
            Placed entry = it.next();
            if (--entry.ticksLeft <= 0) {
                it.remove();
            }
        }
    }

    private List<BlockPos> targets(Vec3 pos, Vec3 velocity, int y) {
        int ahead = lookAhead.getInt();
        List<BlockPos> result = new ArrayList<>(ahead + 1);
        for (int ticksAhead = 0; ticksAhead <= ahead; ticksAhead++) {
            double x = pos.x + velocity.x * ticksAhead;
            double z = pos.z + velocity.z * ticksAhead;
            BlockPos target = new BlockPos(Mth.floor(x), y, Mth.floor(z));
            if (!result.contains(target)) {
                result.add(target);
            }
        }
        if (airPlace.isOn() && radius.getValue() > 0) {
            addPlatform(result, pos, y);
        }
        return result;
    }

    // Every spot on the level within the radius of the player. Nearest first.
    private void addPlatform(List<BlockPos> result, Vec3 pos, int y) {
        double range = radius.getValue();
        List<BlockPos> ring = new ArrayList<>();
        for (int x = Mth.floor(pos.x - range); x <= Mth.floor(pos.x + range); x++) {
            for (int z = Mth.floor(pos.z - range); z <= Mth.floor(pos.z + range); z++) {
                BlockPos spot = new BlockPos(x, y, z);
                if (!result.contains(spot) && pos.distanceTo(Vec3.atCenterOf(spot)) <= range) {
                    ring.add(spot);
                }
            }
        }
        ring.sort(Comparator.comparingDouble(spot -> pos.distanceToSqr(Vec3.atCenterOf(spot))));
        result.addAll(ring);
    }

    private boolean place(BlockPos target) {
        if (airPlace.isOn()) {
            return placeWith(target, null);
        }
        Direction support = BlockUtil.findPlaceSupport(target);
        if (support != null) {
            return placeWith(target, support);
        }
        // Nothing solid touches the target. A supported neighbour is filled first.
        for (Direction side : BRIDGE_SIDES) {
            BlockPos helper = target.relative(side);
            if (!BlockUtil.isReplaceable(helper) || occupied(helper)) {
                continue;
            }
            Direction helperSupport = BlockUtil.findPlaceSupport(helper);
            if (helperSupport != null) {
                return placeWith(helper, helperSupport);
            }
        }
        BlockPos nearest = nearestSupported(target);
        return nearest != null && placeWith(nearest, BlockUtil.findPlaceSupport(nearest));
    }

    // The supported spot within reach that sits closest to the target.
    // Used in open air where even the neighbours have nothing to lean on.
    private BlockPos nearestSupported(BlockPos target) {
        double range = reach.getValue();
        if (range <= 0) {
            return null;
        }
        Vec3 eye = mc.player.getEyePosition();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : BlockUtil.positionsWithin(range)) {
            if (!BlockUtil.isReplaceable(pos) || occupied(pos)) {
                continue;
            }
            Direction support = BlockUtil.findPlaceSupport(pos);
            if (support == null) {
                continue;
            }
            Vec3 hit = BlockUtil.hitPoint(pos.relative(support), support.getOpposite());
            if (eye.distanceToSqr(hit) > CLICK_REACH_SQ) {
                continue;
            }
            double distance = pos.distSqr(target);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = pos.immutable();
            }
        }
        return best;
    }

    // Places at the spot against the support or straight into the air without one.
    private boolean placeWith(BlockPos target, Direction support) {
        int slot = BlockUtil.findBlockSlot(block -> allowed(block, target));
        if (slot == -1) {
            return false;
        }
        if (!autoSwitch.isOn()) {
            if (!holdsAllowedBlock(target)) {
                return false;
            }
            slot = InventoryUtil.selectedSlot();
        }

        slots.select(slot);
        // Only the first block of a tick turns. A burst of look packets is a plain tell.
        boolean turn = rotate.isOn() && !rotatedThisTick;
        rotatedThisTick |= turn;
        boolean done = support == null
            ? BlockUtil.placeDirect(target, turn, swing.isOn())
            : BlockUtil.place(target, support, turn, swing.isOn());
        if (autoSwitch.isOn() && swapBack.isOn()) {
            slots.restoreIfMine();
        } else {
            slots.forget();
        }
        if (done) {
            placed.add(new Placed(target.immutable(), FADE_TICKS));
        }
        return done;
    }

    private boolean holdsAllowedBlock(BlockPos target) {
        return mc.player.getInventory().getSelectedItem().getItem() instanceof BlockItem item
            && allowed(item.getBlock(), target);
    }

    // Any entity standing in the square gets the placement refused by the server.
    private boolean occupied(BlockPos pos) {
        if (BlockUtil.intersectsPlayer(pos)) {
            return true;
        }
        return !mc.level.isUnobstructed(Blocks.STONE.defaultBlockState(), pos,
            CollisionContext.empty());
    }

    // A listed block still has to be something worth standing on. An empty allow
    // list falls back to any plain building block.
    private boolean allowed(Block block, BlockPos target) {
        if (!BlockUtil.isBuildingBlock(block, target)) {
            return false;
        }
        if (listMode.is(ListMode.EXCEPT_LISTED)) {
            return !blocks.contains(block);
        }
        return blocks.size() == 0 || blocks.contains(block);
    }

    private void towerUp(Vec3 velocity) {
        if (mc.player.getAbilities().flying) {
            return;
        }
        // A block placed under a moving player often lands beside them instead.
        if (!towerWhilstMoving.isOn() && mc.player.input.getMoveVector().lengthSquared() > 1.0E-6f) {
            return;
        }
        if (mc.player.onGround()) {
            mc.player.setDeltaMovement(velocity.x, towerSpeed.getValue(), velocity.z);
            return;
        }
        if (velocity.y > 0) {
            BlockPos justBelow = BlockPos.containing(
                mc.player.getX(), mc.player.getY() - 0.01, mc.player.getZ());
            if (BlockUtil.isSolid(justBelow)) {
                mc.player.setDeltaMovement(velocity.x, 0, velocity.z);
            }
        }
    }

    private static final class Placed {

        private final BlockPos pos;
        private int ticksLeft;

        private Placed(BlockPos pos, int ticksLeft) {
            this.pos = pos;
            this.ticksLeft = ticksLeft;
        }
    }
}
