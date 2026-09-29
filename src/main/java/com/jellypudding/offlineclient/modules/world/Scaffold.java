package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.ListMode;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatWarning;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import com.jellypudding.offlineclient.util.UseBudget;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

// Keeps a floor under the player's feet or a roof over their head. Targets come from the
// current position and where the speed puts the player over the next few ticks.
public final class Scaffold extends Module {

    public enum Layer { FLOOR, ROOF }

    // Ticks a placed block stays drawn whilst it fades out.
    private static final int FADE_TICKS = 8;

    // A roof block with nothing to lean on borrows a neighbour above or beside it. Below is the gap.
    private static final List<Direction> ABOVE_THEN_SIDES = List.of(
        Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST);

    private final RegistryListSetting<Block> blocks = new RegistryListSetting<>("Blocks",
        "The blocks the list mode below applies to.",
        BuiltInRegistries.BLOCK,
        List.of(Blocks.COBBLESTONE, Blocks.COBBLED_DEEPSLATE, Blocks.NETHERRACK,
            Blocks.DIRT, Blocks.STONE, Blocks.DEEPSLATE, Blocks.OBSIDIAN));
    private final EnumSetting<ListMode> listMode = ListMode.setting("List mode", ListMode.WHITELIST,
        "Only listed blocks go down. An empty list allows any building block.",
        "Any building block goes down except the listed ones.");
    private final EnumSetting<Layer> layer = new EnumSetting<>("Layer",
        "Whether it builds under your feet or over your head.", Layer.FLOOR)
        .describe(Layer.FLOOR, "Keeps a floor under your feet as you walk.")
        .describe(Layer.ROOF, "Keeps a roof over your head as you walk.");
    private final NumberSetting gap = new NumberSetting("Gap",
        "How many empty blocks are left between your head and the roof.", 1, 0, 6, 1, " blocks")
        .under(layer, Layer.ROOF);
    private final NumberSetting thickness = new NumberSetting("Thickness",
        "How many blocks deep the floor or the roof is.", 1, 1, 5, 1, " blocks");
    private final NumberSetting radius = new NumberSetting("Radius",
        "Also fills every spot within this distance on the same level for a platform.", 0, 0, 6, 0.5, " blocks");
    private final BoolSetting onSurface = new BoolSetting("On surface",
        "Also widens and thickens it whilst you stand on solid ground. Off only adds to it over a drop.", true)
        .visibleWhen(() -> radius.getValue() > 0 || thickness.getInt() > 1);
    private final BoolSetting keepLevel = new BoolSetting("Keep level",
        "Stays on the level you last stood on whilst you jump. Jumping along then bridges flat.", false);
    private final BoolSetting tower = new BoolSetting("Tower",
        "Hold jump to build straight up.", true)
        .visibleWhen(() -> layer.is(Layer.FLOOR));
    private final NumberSetting towerSpeed = new NumberSetting("Tower speed",
        "How hard each tower jump pushes you up.", 0.42, 0.3, 0.5, 0.01)
        .min(0.1).under(tower);
    private final BoolSetting towerWhilstMoving = new BoolSetting("Tower whilst moving",
        "Keeps the tower boost on whilst you walk. Off means jump only lifts you when you stand still.", false)
        .under(tower);
    private final NumberSetting lookAhead = new NumberSetting("Look ahead",
        "Ticks of movement to build ahead of you.", 2, 0, 5, 1, " ticks");
    private final BoolSetting airPlace = new BoolSetting("Air place",
        "Places straight into the air with nothing to lean on. With it on a tower climbs without landing.",
        false);
    private final NumberSetting blocksPerTick = new NumberSetting("Blocks per tick",
        "Most blocks placed in one tick. Paper takes nine in a short burst and the rest wait their turn.",
        3, 1, 9, 1, " blocks");
    private final NumberSetting reach = new NumberSetting("Reach",
        "When nothing touches the spot under you the nearest spot with support within this range is filled instead.",
        4, 0, 8, 0.5, " blocks")
        .under(airPlace, () -> !airPlace.isOn() && layer.is(Layer.FLOOR));
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Turn towards each block on the server side.", true);
    private final BoolSetting swing = new BoolSetting("Swing",
        "Swings your arm on each placement.", false);
    private final BoolSetting autoSwitch = new BoolSetting("Auto switch",
        "Switches to a block in your hotbar when neither hand holds one. Off only places from your hands.", true);
    private final BoolSetting swapBack = new BoolSetting("Swap back",
        "Returns to the slot you had after every placement.", true)
        .under(autoSwitch);
    private final BoolSetting onlyOnClick = new BoolSetting("Only on click",
        "Only place whilst you hold the use key down.", false);
    private final BoolSetting down = new BoolSetting("Down",
        "Sneak to build one level lower. When off sneaking pauses Scaffold.", true)
        .visibleWhen(() -> layer.is(Layer.FLOOR));
    private final BoolSetting showPlaced = new BoolSetting("Show placed",
        "Draws each placed block for a moment.", true);
    private final BoxStyle style = new BoxStyle(BoxStyle.Shape.BOTH, 278).under(showPlaced);

    private final SlotSwap slots = new SlotSwap();

    // Blocks placed lately and the ticks each has left to show.
    private final List<Placed> placed = new ArrayList<>();

    private boolean descending;

    private boolean rotatedThisTick;

    // Running out is told once and again only after a block has gone down since.
    private final ChatWarning warning = new ChatWarning();

    // The layer's level whilst you last stood on the ground. Keep level builds there mid jump.
    private int keptY = Integer.MAX_VALUE;
    private Layer keptLayer = Layer.FLOOR;

    public Scaffold() {
        super("Scaffold", "Places blocks under you or over your head as you walk.", Category.WORLD);
        addSettings(blocks, listMode, layer, gap, thickness, radius, onSurface, keepLevel, tower, towerSpeed,
            towerWhilstMoving, lookAhead, airPlace, reach, blocksPerTick, rotate, swing, autoSwitch, swapBack,
            onlyOnClick, down, showPlaced);
        addSettings(style.settings());
        searchTags("bridge", "auto bridge", "tower", "roof", "ceiling", "platform");
    }

    // PlayerMixin lifts the sneak edge clamp whilst this is true.
    public boolean isDescending() {
        return isEnabled() && descending;
    }

    @Override
    protected void onDisable() {
        descending = false;
        keptY = Integer.MAX_VALUE;
        warning.clear();
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
        boolean floor = layer.is(Layer.FLOOR);

        if (floor && sneaking && !jumping && !down.isOn()) {
            return;
        }
        if (onlyOnClick.isOn() && !InputUtil.physicallyHeld(mc.options.keyUse)) {
            return;
        }
        descending = floor && sneaking && !jumping && down.isOn();
        boolean towering = floor && tower.isOn() && jumping && !sneaking && towerAllowed();

        Vec3 pos = mc.player.position();
        Vec3 velocity = mc.player.getDeltaMovement();
        int y = levelY(towering);
        List<BlockPos> path = path(pos, velocity, y);
        boolean standing = !BlockUtil.isReplaceable(path.getFirst());

        int limit = Math.min(blocksPerTick.getInt(), UseBudget.remaining());
        int count = 0;
        boolean placedUnderFeet = false;
        for (BlockPos target : path) {
            if (count >= limit) {
                break;
            }
            if (open(target) && place(target)) {
                count++;
                placedUnderFeet |= target.equals(path.getFirst());
            }
        }
        if (count < limit && (onSurface.isOn() || !standing)) {
            for (BlockPos target : extras(path, pos, y)) {
                if (count >= limit) {
                    break;
                }
                if (open(target) && placeSupported(target)) {
                    count++;
                }
            }
        }

        if (towering) {
            towerUp(velocity, placedUnderFeet);
        }
    }

    // The level the layer goes on this tick. Keep level holds it whilst airborne unless you drop below it.
    private int levelY(boolean towering) {
        int natural = layer.is(Layer.FLOOR)
            ? Mth.floor(mc.player.getY()) - (descending ? 2 : 1)
            : Mth.floor(mc.player.getBoundingBox().maxY) + 1 + gap.getInt();
        boolean airborne = keepLevel.isOn() && !towering && !descending && !mc.player.onGround();
        keptY = airborne && layer.is(keptLayer) ? Math.min(keptY, natural) : natural;
        keptLayer = layer.getValue();
        return keptY;
    }

    // A block placed under a moving player often lands beside them instead.
    private boolean towerAllowed() {
        return !mc.player.getAbilities().flying
            && (towerWhilstMoving.isOn() || mc.player.input.getMoveVector().lengthSquared() <= 1.0E-6f);
    }

    private boolean open(BlockPos target) {
        return BlockUtil.isReplaceable(target) && !occupied(target);
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

    // The spot under or over you now and where your speed takes you over the next ticks.
    private List<BlockPos> path(Vec3 pos, Vec3 velocity, int y) {
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
        return result;
    }

    // The platform around the path nearest first and then each deeper layer in the same order.
    // A deeper block leans on the one just placed over or under it.
    private List<BlockPos> extras(List<BlockPos> path, Vec3 pos, int y) {
        List<BlockPos> level = new ArrayList<>(path);
        if (radius.getValue() > 0) {
            addPlatform(level, pos, y);
        }
        List<BlockPos> extras = new ArrayList<>(level.subList(path.size(), level.size()));
        Direction deeper = layer.is(Layer.FLOOR) ? Direction.DOWN : Direction.UP;
        for (int depth = 1; depth < thickness.getInt(); depth++) {
            for (BlockPos spot : level) {
                extras.add(spot.relative(deeper, depth));
            }
        }
        return extras;
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

    // The path may borrow a neighbour or the nearest supported spot to reach out into open air.
    private boolean place(BlockPos target) {
        if (airPlace.isOn() || BlockUtil.findPlaceSupport(target) != null) {
            return placeSupported(target);
        }
        // Nothing solid touches the target. A supported neighbour is filled first.
        boolean floor = layer.is(Layer.FLOOR);
        for (Direction side : floor ? BlockUtil.BELOW_THEN_SIDES : ABOVE_THEN_SIDES) {
            BlockPos helper = target.relative(side);
            if (!open(helper)) {
                continue;
            }
            Direction helperSupport = BlockUtil.findPlaceSupport(helper);
            if (helperSupport != null) {
                return placeWith(helper, helperSupport);
            }
        }
        BlockPos nearest = floor ? nearestSupported(target) : null;
        return nearest != null && placeWith(nearest, BlockUtil.findPlaceSupport(nearest));
    }

    // Against a neighbour or straight into the air with Air place. A spot with neither waits.
    private boolean placeSupported(BlockPos target) {
        if (airPlace.isOn()) {
            return placeWith(target, null);
        }
        Direction support = BlockUtil.findPlaceSupport(target);
        return support != null && placeWith(target, support);
    }

    // The supported spot within reach that sits closest to the target.
    // Used in open air where even the neighbours have nothing to lean on.
    private BlockPos nearestSupported(BlockPos target) {
        double range = reach.getValue();
        if (range <= 0) {
            return null;
        }
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : BlockUtil.positionsWithin(range)) {
            if (!open(pos)) {
                continue;
            }
            Direction support = BlockUtil.findPlaceSupport(pos);
            if (support == null || !BlockUtil.serverReaches(pos.relative(support))) {
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

    // Places at the spot against the support or straight into the air without one. A click the
    // server would not reach is never sent.
    private boolean placeWith(BlockPos target, Direction support) {
        if (!BlockUtil.serverReaches(support == null ? target : target.relative(support))) {
            return false;
        }
        InteractionHand hand = handFor(target);
        if (hand == null) {
            warning.say("Scaffold has no allowed blocks in your hands or hotbar.");
            return false;
        }
        // Only the first block of a tick turns. A burst of look packets is a plain tell.
        boolean turn = rotate.isOn() && !rotatedThisTick;
        rotatedThisTick |= turn;
        boolean done = support == null
            ? BlockUtil.placeDirect(target, Vec3.atCenterOf(target), turn, swing.isOn(), hand)
            : BlockUtil.place(target, support, turn, swing.isOn(), hand);
        if (autoSwitch.isOn() && swapBack.isOn()) {
            slots.restoreIfMine();
        } else {
            slots.forget();
        }
        if (done) {
            placed.add(new Placed(target.immutable(), FADE_TICKS));
            warning.clear();
        }
        return done;
    }

    // The main hand when it holds an allowed block and then the offhand. Auto switch takes one
    // from the hotbar when neither does. Null when there is nothing to place.
    private InteractionHand handFor(BlockPos target) {
        if (holdsAllowed(mc.player.getMainHandItem(), target)) {
            return InteractionHand.MAIN_HAND;
        }
        if (holdsAllowed(mc.player.getOffhandItem(), target)) {
            return InteractionHand.OFF_HAND;
        }
        int slot = autoSwitch.isOn() ? BlockUtil.findBlockSlot(block -> allowed(block, target)) : -1;
        if (slot == -1) {
            return null;
        }
        slots.select(slot);
        return InteractionHand.MAIN_HAND;
    }

    private boolean holdsAllowed(ItemStack stack, BlockPos target) {
        return stack.getItem() instanceof BlockItem item && allowed(item.getBlock(), target);
    }

    // Any entity standing in the square gets the placement refused by the server.
    private boolean occupied(BlockPos pos) {
        if (BlockUtil.intersectsPlayer(pos)) {
            return true;
        }
        return !BlockUtil.unobstructed(pos);
    }

    // A listed block still has to be something worth standing on. An empty allow
    // list falls back to any plain building block.
    private boolean allowed(Block block, BlockPos target) {
        if (!BlockUtil.buildsAt(block, target)) {
            return false;
        }
        return blocks.size() == 0 && listMode.is(ListMode.WHITELIST)
            || listMode.getValue().admits(blocks.contains(block));
    }

    // Jumping from the ground lifts you. With Air place a block that just went under the feet
    // lifts you again mid air and the tower climbs without ever landing.
    private void towerUp(Vec3 velocity, boolean placedUnderFeet) {
        if (mc.player.onGround() || airPlace.isOn() && placedUnderFeet) {
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
