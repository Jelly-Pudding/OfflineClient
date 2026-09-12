package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.ExclusivityGroup;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.modules.combat.KillAura;
import com.jellypudding.offlineclient.modules.movement.NoKnockback;
import com.jellypudding.offlineclient.modules.movement.Speed;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.AxisWalker;
import com.jellypudding.offlineclient.util.BlockMiner;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.ItemUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.ProjectileUtil;
import com.jellypudding.offlineclient.util.RotationManager;
import com.jellypudding.offlineclient.util.RotationPriority;
import com.jellypudding.offlineclient.util.TickRate;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

// Builds a highway along one line at a fixed height doing the nearest job each tick.
// Clears first then floor then walls then ceiling. Unsupported blocks go into the air.
public final class HighwayBuilder extends Module {

    // Finished depths that must lie ahead before the module walks on.
    private static final int LEAD = 2;

    // How far ahead of the player the work reaches and how far behind it is checked.
    private static final int AHEAD = 6;
    private static final int BEHIND = 2;

    // Ticks of no progress before the module gives up walking on its own.
    private static final int IDLE_LIMIT = 200;

    // A walking pace in blocks per tick whilst the view is left free.
    private static final double WALK_SPEED = 0.13;

    // How long the server may go quiet before the module waits for it.
    private static final long LAG_MILLIS = 1500;

    // Arrow speed in blocks per tick at a full draw and the ticks that draw takes.
    private static final double BOW_SPEED = 3;
    private static final int FULL_DRAW = 20;
    private static final int CRYSTAL_SHOTS = 3;
    private static final double CRYSTAL_NEAR = 12;
    private static final double CRYSTAL_FAR = 24;

    // The most boxes the render draws in one frame.
    private static final int MAX_DRAWN = 512;

    // Ticks a shulker restock may take before it is written off.
    private static final int RESTOCK_LIMIT = 200;

    // Ticks a grind may run before it is abandoned.
    private static final int GRIND_LIMIT = 2400;

    public enum Movement { AUTO, MANUAL }

    public enum Rotation { NONE, MINE, PLACE, BOTH }

    private enum Stage { NONE, TAKE, BREAK, GRIND }

    private final NumberSetting width = new NumberSetting("Width",
        "How wide the highway is.", 4, 1, 5, 1, " blocks").min(1).max(9);
    private final NumberSetting height = new NumberSetting("Height",
        "How tall the tunnel is.", 3, 2, 5, 1, " blocks").min(2).max(9);
    private final BoolSetting diagonal = new BoolSetting("Diagonal",
        "Lets the line run at forty five degrees when you face that way.", false);
    private final EnumSetting<Movement> movement = new EnumSetting<>("Movement",
        "Who walks.", Movement.AUTO)
        .describe(Movement.AUTO, "The module walks on once the stretch ahead is finished.")
        .describe(Movement.MANUAL, "You walk. The module builds around wherever you are along the line.");
    private final BoolSetting freeLook = new BoolSetting("Free look",
        "Leaves your view alone. The walking and building follow the line on their own.", false)
        .under(movement, Movement.AUTO);
    private final BoolSetting skipUnreachable = new BoolSetting("Skip unreachable",
        "Walks on past blocks you cannot reach from the line instead of waiting for them.", true)
        .under(movement, Movement.AUTO);
    private final BoolSetting reLevel = new BoolSetting("Relevel",
        "Jumps and paves under your feet when you drop below the floor.", true);
    private final BoolSetting pauseOnLag = new BoolSetting("Pause on lag",
        "Waits whilst the server has gone quiet for over a second.", true);
    private final RegistryListSetting<Block> blocks = new RegistryListSetting<>("Blocks",
        "The blocks allowed in the build. The one in your hand wins and otherwise the first in the hotbar.",
        BuiltInRegistries.BLOCK,
        List.of(Blocks.OBSIDIAN, Blocks.NETHERRACK, Blocks.BLACKSTONE, Blocks.BASALT,
            Blocks.COBBLESTONE, Blocks.COBBLED_DEEPSLATE));
    private final BoolSetting floor = new BoolSetting("Floor",
        "Fills the floor under the highway.", true);
    private final BoolSetting replaceFloor = new BoolSetting("Replace floor",
        "Digs out floor blocks that are not on the list and paves over them.", false)
        .under(floor);
    private final BoolSetting walls = new BoolSetting("Walls",
        "Builds a wall down each side of the highway.", false);
    private final NumberSetting wallHeight = new NumberSetting("Wall height",
        "How tall each wall is. One gives a railing at foot level.", 3, 1, 5, 1, " blocks")
        .min(1).max(9).under(walls);
    private final BoolSetting replaceWalls = new BoolSetting("Replace walls",
        "Digs out wall blocks that are not on the list and rebuilds them.", false)
        .under(walls);
    private final BoolSetting mineAboveWalls = new BoolSetting("Mine above walls",
        "Clears the rest of the column over each wall so the sides stay open.", true)
        .under(walls);
    private final BoolSetting ceiling = new BoolSetting("Ceiling",
        "Roofs the highway over.", false);
    private final BoolSetting replaceCeiling = new BoolSetting("Replace ceiling",
        "Digs out ceiling blocks that are not on the list and roofs over them.", false)
        .under(ceiling);
    private final BoolSetting fillLiquids = new BoolSetting("Fill liquids",
        "Plugs any water or lava in the stretch before anything else.", true);
    private final BoolSetting torches = new BoolSetting("Torches",
        "Puts a torch on the left edge as you go. Needs torches in your hotbar.", false);
    private final NumberSetting torchSpacing = new NumberSetting("Torch spacing",
        "Blocks between one torch and the next.", 8, 2, 16, 1, " blocks").min(1)
        .under(torches);
    private final NumberSetting torchHeight = new NumberSetting("Torch height",
        "Blocks of air under each torch. Zero stands it on the floor and more hangs it on the wall.",
        0, 0, 4, 1, " blocks").min(0).max(8).under(torches);
    private final EnumSetting<Rotation> rotation = new EnumSetting<>("Rotation",
        "When a look packet is sent.", Rotation.BOTH)
        .describe(Rotation.NONE, "Never. The server keeps your own view.")
        .describe(Rotation.MINE, "Only towards blocks being broken.")
        .describe(Rotation.PLACE, "Only towards blocks being placed.")
        .describe(Rotation.BOTH, "Towards everything the module touches.");
    private final NumberSetting breakDelay = new NumberSetting("Break delay",
        "Ticks between one block break and the next.", 0, 0, 20, 1, " ticks").min(0);
    private final NumberSetting breaksPerTick = new NumberSetting("Breaks per tick",
        "How many one hit blocks come out in a tick whilst no mining rotation is sent.",
        1, 1, 16, 1).min(1).max(100);
    private final BoolSetting doubleMine = new BoolSetting("Double mine",
        "Breaks a second block with packets whilst the first is mined by hand.", true);
    private final BoolSetting fastBreak = new BoolSetting("Fast break",
        "Sends the second block its stop packet early instead of waiting for full progress.", true)
        .under(doubleMine);
    private final NumberSetting savePickaxes = new NumberSetting("Save pickaxes",
        "Turns the module off once you are down to this many pickaxes. Zero never stops.",
        1, 0, 36, 1).min(0).max(36);
    private final NumberSetting placeRange = new NumberSetting("Place range",
        "The furthest a block is placed from your eyes.", 4.5, 1, 5.5, 0.1).min(1).max(5.5);
    private final NumberSetting placeDelay = new NumberSetting("Place delay",
        "Ticks between one placement and the next.", 0, 0, 20, 1, " ticks").min(0);
    private final NumberSetting placementsPerTick = new NumberSetting("Placements per tick",
        "How many blocks may go down in one tick.", 1, 1, 8, 1).min(1);
    private final BoolSetting restock = new BoolSetting("Restock hotbar",
        "Moves blocks and a pickaxe up from your inventory when the hotbar runs dry.", true);
    private final NumberSetting inventoryDelay = new NumberSetting("Inventory delay",
        "Ticks between one inventory click and the next.", 3, 0, 20, 1, " ticks").min(0);
    private final BoolSetting searchShulkers = new BoolSetting("Search shulkers",
        "Sets a shulker box down behind you and takes the build blocks out of it.", true);
    private final BoolSetting searchEnderChest = new BoolSetting("Search ender chest",
        "Sets your ender chest down behind you and takes the build blocks out once the shulkers are empty. A Silk Touch pickaxe gets the chest back.",
        false);
    private final BoolSetting mineEnderChests = new BoolSetting("Mine ender chests",
        "With obsidian on the list and no blocks left an ender chest goes down behind you and is mined for obsidian.",
        true);
    private final NumberSetting saveEnderChests = new NumberSetting("Save ender chests",
        "Ender chests never used below this count.", 2, 0, 64, 1).min(0)
        .under(mineEnderChests);
    private final NumberSetting grindAmount = new NumberSetting("Grind amount",
        "How much obsidian one grind gathers before building goes on.", 64, 8, 256, 8).min(1)
        .under(mineEnderChests);
    private final BoolSetting grindBlockade = new BoolSetting("Grind blockade",
        "Walls you in with whatever blocks you have whilst the chests are ground.", true)
        .under(mineEnderChests);
    private final BoolSetting ejectShulkers = new BoolSetting("Eject useless shulkers",
        "Drops any shulker box holding no blocks or pickaxes or food.", true);
    private final NumberSetting emptySlots = new NumberSetting("Minimum empty slots",
        "Free slots to keep clear after a restock.", 3, 0, 9, 1).min(0);
    private final BoolSetting throwTrash = new BoolSetting("Throw out trash",
        "Turns round and drops the listed items so the inventory keeps room.", false);
    private final RegistryListSetting<Item> trash = new RegistryListSetting<>("Trash",
        "The items thrown out. Click to pick them.", BuiltInRegistries.ITEM,
        List.of(Items.NETHERRACK, Items.QUARTZ, Items.GOLD_NUGGET, Items.GOLDEN_SWORD,
            Items.GLOWSTONE_DUST, Items.GLOWSTONE, Items.BLACKSTONE, Items.BASALT,
            Items.GHAST_TEAR, Items.SOUL_SAND, Items.SOUL_SOIL, Items.ROTTEN_FLESH,
            Items.MAGMA_BLOCK))
        .under(throwTrash);
    private final BoolSetting crystalTraps = new BoolSetting("Shoot crystal traps",
        "Puts an arrow through any end crystal sitting in the way ahead.", true);
    private final BoolSetting stopWhenEmpty = new BoolSetting("Stop when empty",
        "Turns the module off once you run out of blocks.", true);
    private final BoolSetting disconnectOnStop = new BoolSetting("Disconnect on stop",
        "Leaves the server with the run totals whenever the module stops itself.", false);
    private final BoolSetting outline = new BoolSetting("Outline",
        "Draws the stretch being worked on.", true);
    private final ColorSetting outlineColor = new ColorSetting("Outline colour",
        "Colour of the outline.", 200, false).under(outline);
    private final BoolSetting renderMine = new BoolSetting("Show blocks to mine",
        "Draws every block still to come out of the stretch.", true);
    private final BoxStyle mineBox = new BoxStyle("Mine", BoxStyle.Shape.BOTH, 0)
        .under(renderMine);
    private final BoolSetting renderPlace = new BoolSetting("Show blocks to place",
        "Draws every block still to go into the stretch.", true);
    private final BoxStyle placeBox = new BoxStyle("Place", BoxStyle.Shape.BOTH, 220)
        .under(renderPlace);

    private final AxisWalker walker = new AxisWalker();
    private final SlotSwap slots = new SlotSwap();

    private int idleTicks;
    private double bestTravelled;
    private boolean walking;

    private BlockPos mineTarget;
    private BlockPos placeTarget;

    private int sinceBreak;
    private int sincePlace;
    private int sinceInventory;
    private int blocksBroken;
    private int blocksPlaced;

    // The block the hand was on last tick. Used only to count what really broke.
    private BlockPos wasMining;

    private PacketDig spare;

    private Stage stage = Stage.NONE;
    private BlockPos shulkerPos;

    // How much obsidian the running grind is heading for.
    private int grindGoal;
    private int stageTicks;

    private final Set<Integer> ignoredCrystals = new HashSet<>();
    private Entity crystal;
    private int crystalShots;
    private boolean drawing;

    // Every block of the shell around the player this tick.
    private final List<BlockPos> stretch = new ArrayList<>();

    // A second block taken down with packets whilst the hand works the first.
    private static final class PacketDig {

        private final BlockPos pos;
        private final Direction side;
        private float progress;

        private PacketDig(BlockPos pos, Direction side) {
            this.pos = pos;
            this.side = side;
        }
    }

    public HighwayBuilder() {
        super("HighwayBuilder", "Digs and builds a highway along one line at a fixed height.", Category.WORLD);
        addSettings(width, height, diagonal, movement, freeLook, skipUnreachable, reLevel,
            pauseOnLag, blocks, floor, replaceFloor, walls, wallHeight, replaceWalls,
            mineAboveWalls, ceiling, replaceCeiling, fillLiquids, torches, torchSpacing,
            torchHeight, rotation, breakDelay, breaksPerTick, doubleMine, fastBreak,
            savePickaxes, placeRange, placeDelay, placementsPerTick, restock, inventoryDelay,
            searchShulkers, searchEnderChest, mineEnderChests, saveEnderChests, grindAmount,
            grindBlockade, ejectShulkers, emptySlots, throwTrash, trash,
            crystalTraps, stopWhenEmpty, disconnectOnStop, outline,
            outlineColor, renderMine);
        addSettings(mineBox.settings());
        addSettings(renderPlace);
        addSettings(placeBox.settings());
        searchTags("highway", "nether", "tunnel", "road", "railings");
    }

    @Override
    public String getSuffix() {
        if (!walker.isLocked()) {
            return null;
        }
        return walker.heading() + " " + (int) Math.max(0, bestTravelled);
    }

    // Bringing this back at launch would start digging at once.
    @Override
    public boolean savesEnabledState() {
        return false;
    }

    @Override
    public ExclusivityGroup getExclusivityGroup() {
        return ExclusivityGroup.MINING;
    }

    @Override
    protected void onEnable() {
        walker.clear();
        slots.forget();
        mineTarget = null;
        placeTarget = null;
        spare = null;
        crystal = null;
        drawing = false;
        ignoredCrystals.clear();
        blocksBroken = 0;
        blocksPlaced = 0;
        sinceBreak = 0;
        sincePlace = 0;
        sinceInventory = 0;
        stage = Stage.NONE;
        shulkerPos = null;
        if (inGame()) {
            lockAxis();
            warnAboutModules();
        }
    }

    @Override
    protected void onDisable() {
        BlockMiner.release();
        slots.restoreIfMine();
        stopWalking();
        releaseBow();
        walker.clear();
        mineTarget = null;
        placeTarget = null;
        spare = null;
        stage = Stage.NONE;
        shulkerPos = null;
        stretch.clear();
        if (blocksBroken > 0 || blocksPlaced > 0) {
            ChatUtil.message("§bHighwayBuilder §7covered §f" + (int) bestTravelled
                + "§7 blocks and broke §f" + blocksBroken + "§7 and placed §f" + blocksPlaced + "§7.");
        }
    }

    private void warnAboutModules() {
        if (diagonal.isOn() && Modules.enabled(Speed.class)) {
            ChatUtil.error("Turn Speed off on a diagonal or you will drift off the line.");
        }
        if (!Modules.enabled(NoKnockback.class)) {
            ChatUtil.message("§bHighwayBuilder §7works better with §fNoKnockback§7 on.");
        }
    }

    private void lockAxis() {
        walker.lock(diagonal.isOn());
        idleTicks = 0;
        bestTravelled = 0;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        countBreak();
        mineTarget = null;
        placeTarget = null;
        if (!inGame()) {
            stopWalking();
            return;
        }
        if (!walker.isLocked()) {
            lockAxis();
        }
        if (paused()) {
            stopWalking();
            BlockMiner.release();
            return;
        }
        if (!safeToWork()) {
            return;
        }
        boolean auto = movement.is(Movement.AUTO);
        // Manual hands the walking back rather than leaving the key held.
        if (!auto) {
            stopWalking();
        }
        walker.holdAxis(auto && !freeLook.isOn());
        trackProgress();

        double here = walker.travelled();
        gatherStretch(here - BEHIND, here + AHEAD);
        if (shootCrystal()) {
            stopWalking();
            return;
        }
        if (levelUp()) {
            stopWalking();
            return;
        }
        if (tidyInventory()) {
            stopWalking();
            return;
        }
        driveSpare();
        if (work()) {
            if (auto) {
                stopWalking();
            }
            return;
        }
        // Running out of blocks turns the module off inside a fill.
        if (!isEnabled()) {
            return;
        }
        if (auto) {
            walk(here);
        }
    }

    // Anything else with a claim on the hands takes the tick.
    private boolean paused() {
        if (pauseOnLag.isOn() && TickRate.INSTANCE.lagging(LAG_MILLIS)) {
            return true;
        }
        if (Modules.eating()) {
            return true;
        }
        KillAura aura = Modules.active(KillAura.class);
        return aura != null && aura.getTarget() != null;
    }

    private void countBreak() {
        if (wasMining != null && inGame() && BlockUtil.state(wasMining).isAir()) {
            blocksBroken++;
        }
        wasMining = null;
    }

    private boolean safeToWork() {
        if (mc.player.isSpectator() || mc.player.isPassenger()) {
            stop("HighwayBuilder stopped because you cannot build from there.");
            return false;
        }
        if (!reLevel.isOn() && Math.abs(mc.player.getY() - walker.floorY()) > 1.5) {
            stop("HighwayBuilder stopped because you left the highway floor.");
            return false;
        }
        if (savePickaxes.getInt() > 0 && pickaxeCount() <= savePickaxes.getInt()) {
            stop("HighwayBuilder stopped because you are nearly out of pickaxes.");
            return false;
        }
        if (movement.is(Movement.AUTO) && idleTicks > IDLE_LIMIT) {
            stop("HighwayBuilder stopped because it could not get any further.");
            return false;
        }
        return true;
    }

    private int pickaxeCount() {
        int total = 0;
        for (int i = 0; i < InventoryUtil.WHOLE_INVENTORY; i++) {
            if (mc.player.getInventory().getItem(i).is(ItemTags.PICKAXES)) {
                total++;
            }
        }
        return total;
    }

    private void trackProgress() {
        double travelled = walker.travelled();
        if (travelled > bestTravelled + 0.05) {
            bestTravelled = travelled;
            idleTicks = 0;
        } else {
            idleTicks++;
        }
    }

    // Collects every block of the tunnel and its shell between two depths along the
    // line by sieving the box around the player by distance along and across it.
    private void gatherStretch(double from, double to) {
        stretch.clear();
        int reach = AHEAD + width.getInt() + 2;
        BlockPos centre = mc.player.blockPosition();
        int tall = height.getInt();
        double nearEdge = leftLane() - 1.5;
        double farEdge = rightLane() + 1.5;
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                int x = centre.getX() + dx;
                int z = centre.getZ() + dz;
                double along = walker.alongOf(x + 0.5, z + 0.5);
                if (along < from || along > to) {
                    continue;
                }
                double across = walker.acrossOf(x + 0.5, z + 0.5);
                if (across < nearEdge || across > farEdge) {
                    continue;
                }
                for (int up = -1; up <= tall; up++) {
                    stretch.add(new BlockPos(x, walker.floorY() + up, z));
                }
            }
        }
    }

    // Does the nearest job across the stretch. Liquid goes first then clearing wins
    // over filling. True when the tick was spent.
    private boolean work() {
        if (fillLiquids.isOn()) {
            BlockPos plug = nearest(this::isLiquid, placeRange.getValue());
            if (plug != null) {
                placeTarget = plug;
                return placeRound(plug);
            }
        }
        BlockPos dig = nearest(this::needsClearing, mc.player.blockInteractionRange());
        if (dig != null) {
            return mine(dig);
        }
        BlockMiner.release();
        BlockPos fill = nearest(this::needsFilling, placeRange.getValue());
        if (fill != null) {
            placeTarget = fill;
            return placeRound(fill);
        }
        BlockPos torch = nearest(this::needsTorch, placeRange.getValue());
        if (torch != null) {
            return placeTorch(torch);
        }
        slots.restoreIfMine();
        return false;
    }

    private boolean mine(BlockPos dig) {
        if (sinceBreak < breakDelay.getInt()) {
            sinceBreak++;
            BlockMiner.release();
            return true;
        }
        mineTarget = dig;
        idleTicks = 0;
        slots.restoreIfMine();
        // With no mining rotation the server takes the packets on their own.
        if (!rotation.isAny(Rotation.MINE, Rotation.BOTH)) {
            return packetMine();
        }
        if (!BlockMiner.mine(dig, true)) {
            mineTarget = null;
            return false;
        }
        wasMining = dig;
        sinceBreak = 0;
        if (doubleMine.isOn() && spare == null) {
            takeSpare(dig);
        }
        return true;
    }

    // Sends the pair for a run of one hit blocks in the same tick.
    private boolean packetMine() {
        int sent = 0;
        for (BlockPos pos : stretch) {
            if (sent >= breaksPerTick.getInt()) {
                break;
            }
            if (!needsClearing(pos) || BlockUtil.distanceTo(pos) > mc.player.blockInteractionRange()) {
                continue;
            }
            if (!BlockUtil.canInstantBreak(pos)) {
                continue;
            }
            BlockMiner.breakInstantly(pos);
            blocksBroken++;
            sent++;
        }
        if (sent == 0 && mineTarget != null) {
            BlockMiner.breakInstantly(mineTarget);
            wasMining = mineTarget;
            sent = 1;
        }
        sinceBreak = 0;
        return sent > 0;
    }

    // Picks a second block for the packet miner. The one the hand is on is skipped.
    private void takeSpare(BlockPos busy) {
        double reach = mc.player.blockInteractionRange();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : stretch) {
            if (pos.equals(busy) || !needsClearing(pos)) {
                continue;
            }
            double distance = BlockUtil.distanceTo(pos);
            if (distance <= reach && distance < bestDistance) {
                bestDistance = distance;
                best = pos;
            }
        }
        if (best == null) {
            return;
        }
        Direction side = BlockUtil.facingSide(best);
        mc.player.connection.send(new ServerboundPlayerActionPacket(
            ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, best, side));
        spare = new PacketDig(best, side);
    }

    // Feeds the second block its progress and stops it once it is due.
    private void driveSpare() {
        if (spare == null) {
            return;
        }
        if (!needsClearing(spare.pos) || BlockUtil.distanceTo(spare.pos) > mc.player.blockInteractionRange()) {
            spare = null;
            return;
        }
        spare.progress += BlockUtil.breakDelta(mc.player.getMainHandItem(), spare.pos);
        float due = fastBreak.isOn() ? 0.7f : 1f;
        if (spare.progress < due) {
            return;
        }
        mc.player.connection.send(new ServerboundPlayerActionPacket(
            ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, spare.pos, spare.side));
        blocksBroken++;
        spare = null;
    }

    // The closest block of the stretch within the reach that passes the test.
    private BlockPos nearest(Predicate<BlockPos> wanted, double reach) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : stretch) {
            if (!wanted.test(pos)) {
                continue;
            }
            double distance = BlockUtil.distanceTo(pos);
            if (distance <= reach && distance < bestDistance) {
                bestDistance = distance;
                best = pos;
            }
        }
        return best;
    }

    private enum Part { TUNNEL, FLOOR, WALL, ABOVE_WALL, CEILING, OUTSIDE }

    // Walls off leaves the whole column beside the tunnel alone.
    private int wallTall() {
        return walls.isOn() ? Math.min(wallHeight.getInt(), height.getInt()) : height.getInt();
    }

    // Which part of the highway a block belongs to by where its middle falls.
    private Part partOf(BlockPos pos) {
        int up = pos.getY() - walker.floorY();
        double across = walker.acrossOf(pos);
        boolean inside = across >= leftLane() - 0.5 && across <= rightLane() + 0.5;
        boolean beside = !inside && across >= leftLane() - 1.5 && across <= rightLane() + 1.5;
        int tall = height.getInt();
        if (inside && up >= 0 && up < tall) {
            return Part.TUNNEL;
        }
        if (inside && up == -1) {
            return Part.FLOOR;
        }
        if (beside && up >= 0 && up < wallTall()) {
            return Part.WALL;
        }
        if (beside && up >= wallTall() && up < tall) {
            return Part.ABOVE_WALL;
        }
        if (up == tall && (inside || (beside && walls.isOn() && wallTall() == tall))) {
            return Part.CEILING;
        }
        // The blocks the walls stand on are part of the floor once walls are wanted.
        if (beside && up == -1 && walls.isOn()) {
            return Part.FLOOR;
        }
        return Part.OUTSIDE;
    }

    // Anything in the tunnel or a shell block off the list when that shell is replaced.
    private boolean needsClearing(BlockPos pos) {
        if (!BlockUtil.diggable(pos) || BlockUtil.isStandingOn(pos)) {
            return false;
        }
        Part part = partOf(pos);
        if (part == Part.TUNNEL) {
            // Torches light the way and never block it.
            return !isTorch(BlockUtil.state(pos).getBlock());
        }
        if (part == Part.ABOVE_WALL) {
            return mineAboveWalls.isOn() && !isTorch(BlockUtil.state(pos).getBlock());
        }
        if (allowed(BlockUtil.state(pos).getBlock())) {
            return false;
        }
        return switch (part) {
            case FLOOR -> floor.isOn() && replaceFloor.isOn();
            case WALL -> walls.isOn() && replaceWalls.isOn();
            case CEILING -> ceiling.isOn() && replaceCeiling.isOn();
            default -> false;
        };
    }

    private boolean needsFilling(BlockPos pos) {
        if (!BlockUtil.isReplaceable(pos) || BlockUtil.intersectsPlayer(pos)) {
            return false;
        }
        return switch (partOf(pos)) {
            case FLOOR -> floor.isOn();
            case WALL -> walls.isOn();
            case CEILING -> ceiling.isOn();
            default -> false;
        };
    }

    // Liquid anywhere in the shell is plugged whatever part it sits in.
    private boolean isLiquid(BlockPos pos) {
        if (mc.level.getFluidState(pos).isEmpty() || BlockUtil.intersectsPlayer(pos)) {
            return false;
        }
        return BlockUtil.isReplaceable(pos) && partOf(pos) != Part.OUTSIDE;
    }

    // Places up to the round size at the target and around it.
    private boolean placeRound(BlockPos first) {
        if (sincePlace < placeDelay.getInt()) {
            sincePlace++;
            return true;
        }
        if (!place(first)) {
            return false;
        }
        sincePlace = 0;
        int placed = 1;
        while (placed < placementsPerTick.getInt()) {
            BlockPos next = nearest(pos -> !pos.equals(first)
                && (needsFilling(pos) || (fillLiquids.isOn() && isLiquid(pos))), placeRange.getValue());
            if (next == null || !place(next)) {
                break;
            }
            placed++;
        }
        return true;
    }

    // A block goes against a neighbour if there is one and into the air if there is not.
    private boolean place(BlockPos target) {
        int slot = BlockUtil.findBlockSlot(this::allowed);
        if (slot == -1) {
            if (stopWhenEmpty.isOn()) {
                stop("HighwayBuilder stopped because you ran out of blocks.");
            }
            return false;
        }
        slots.select(slot);
        boolean placed = BlockUtil.placeAny(target, rotation.isAny(Rotation.PLACE, Rotation.BOTH), true);
        if (placed) {
            idleTicks = 0;
            blocksPlaced++;
        }
        return placed;
    }

    // The torch never sits above the top of the tunnel.
    private int torchUp() {
        return Math.min(torchHeight.getInt(), height.getInt() - 1);
    }

    // The torch spots sit on the left edge every few blocks at the chosen height. On the
    // floor a torch stands on the block below. Higher up it hangs on the wall beside it.
    private boolean needsTorch(BlockPos pos) {
        if (!torches.isOn() || pos.getY() - walker.floorY() != torchUp()) {
            return false;
        }
        double across = walker.acrossOf(pos);
        if (across < leftLane() - 0.5 || across >= leftLane() + 0.5) {
            return false;
        }
        int depth = walker.depthOf(pos);
        if (depth <= 0 || depth % torchSpacing.getInt() != 0) {
            return false;
        }
        return BlockUtil.isReplaceable(pos) && !BlockUtil.intersectsPlayer(pos) && torchSupport(pos) != null;
    }

    private Direction torchSupport(BlockPos pos) {
        if (BlockUtil.isSolid(pos.below())) {
            return Direction.DOWN;
        }
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos beside = pos.relative(side);
            if (BlockUtil.isSolid(beside) && partOf(beside) == Part.WALL) {
                return side;
            }
        }
        return null;
    }

    private boolean placeTorch(BlockPos pos) {
        int slot = BlockUtil.findBlockSlot(block -> block == Blocks.TORCH || block == Blocks.SOUL_TORCH);
        if (slot == -1) {
            return false;
        }
        Direction support = torchSupport(pos);
        if (support == null) {
            return false;
        }
        slots.select(slot);
        boolean placed = BlockUtil.place(pos, support, rotation.isAny(Rotation.PLACE, Rotation.BOTH), true);
        slots.restoreIfMine();
        return placed;
    }

    // Jumps and paves underfoot after a fall. The line keeps its height.
    private boolean levelUp() {
        if (!reLevel.isOn()) {
            return false;
        }
        double drop = walker.floorY() - mc.player.getY();
        if (drop < 0.5) {
            return false;
        }
        if (mc.player.onGround()) {
            mc.player.jumpFromGround();
            return true;
        }
        BlockPos under = mc.player.blockPosition().below();
        if (mc.player.getDeltaMovement().y < 0 && BlockUtil.isReplaceable(under)) {
            place(under);
        }
        return true;
    }

    // Moves what the build needs up into the hotbar and drops what it does not.
    private boolean tidyInventory() {
        if (stage == Stage.GRIND) {
            return enderChestGrind();
        }
        if (stage != Stage.NONE) {
            return shulkerRestock();
        }
        if (sinceInventory < inventoryDelay.getInt()) {
            sinceInventory++;
            return false;
        }
        if (restock.isOn() && restockHotbar()) {
            return true;
        }
        if (searchShulkers.isOn() && shulkerRestock()) {
            return true;
        }
        if (mineEnderChests.isOn() && enderChestGrind()) {
            return true;
        }
        if (searchEnderChest.isOn() && enderChestRestock()) {
            return true;
        }
        if (ejectShulkers.isOn() && dropUselessShulker()) {
            return true;
        }
        return throwTrash.isOn() && dropTrash();
    }

    // Puts a shulker down behind you and empties it then mines it back.
    private boolean shulkerRestock() {
        if (stage == Stage.NONE) {
            return startRestock();
        }
        if (++stageTicks > RESTOCK_LIMIT || shulkerPos == null) {
            endRestock();
            return false;
        }
        BlockState container = BlockUtil.state(shulkerPos);
        boolean standing = container.getBlock() instanceof ShulkerBoxBlock || container.is(Blocks.ENDER_CHEST);
        if (stage == Stage.BREAK) {
            if (!standing) {
                endRestock();
                return false;
            }
            // Without Silk Touch an ender chest breaks into obsidian. It is left standing instead.
            if (container.is(Blocks.ENDER_CHEST) && !holdSilkTouch(container)) {
                endRestock();
                return false;
            }
            BlockMiner.mine(shulkerPos, rotation.isAny(Rotation.MINE, Rotation.BOTH));
            return true;
        }
        if (!standing) {
            endRestock();
            return false;
        }
        if (!(mc.gui.screen() instanceof AbstractContainerScreen<?>)) {
            BlockUtil.useOn(shulkerPos, rotation.isAny(Rotation.PLACE, Rotation.BOTH), true);
            return true;
        }
        return emptyShulker();
    }

    private boolean startRestock() {
        if (hasBlocksSomewhere() || mc.player.getInventory().getFreeSlot() == -1) {
            return false;
        }
        int slot = InventoryUtil.findSlot(this::usefulShulker, InventoryUtil.WHOLE_INVENTORY);
        if (slot == -1) {
            return false;
        }
        if (slot >= InventoryUtil.HOTBAR_SIZE) {
            return moveToHotbar(slot);
        }
        BlockPos spot = restockSpot();
        if (spot == null) {
            return false;
        }
        slots.select(slot);
        if (!BlockUtil.placeAny(spot, rotation.isAny(Rotation.PLACE, Rotation.BOTH), true)) {
            return false;
        }
        shulkerPos = spot;
        stage = Stage.TAKE;
        stageTicks = 0;
        return true;
    }

    // Sets an ender chest down and empties it the way a shulker is emptied.
    private boolean enderChestRestock() {
        if (hasBlocksSomewhere() || mc.player.getInventory().getFreeSlot() == -1) {
            return false;
        }
        int slot = InventoryUtil.findSlot(Items.ENDER_CHEST, InventoryUtil.WHOLE_INVENTORY);
        if (slot == -1) {
            return false;
        }
        if (slot >= InventoryUtil.HOTBAR_SIZE) {
            return moveToHotbar(slot);
        }
        BlockPos spot = restockSpot();
        if (spot == null) {
            return false;
        }
        slots.select(slot);
        if (!BlockUtil.placeAny(spot, rotation.isAny(Rotation.PLACE, Rotation.BOTH), true)) {
            return false;
        }
        shulkerPos = spot;
        stage = Stage.TAKE;
        stageTicks = 0;
        return true;
    }

    // Sets ender chests down behind you and mines them back for obsidian until enough is held.
    private boolean enderChestGrind() {
        if (stage == Stage.NONE) {
            if (hasBlocksSomewhere() || !allowed(Blocks.OBSIDIAN)
                || enderChests() <= saveEnderChests.getInt()) {
                return false;
            }
            BlockPos spot = restockSpot();
            if (spot == null) {
                return false;
            }
            shulkerPos = spot;
            grindGoal = obsidian() + grindAmount.getInt();
            stage = Stage.GRIND;
            stageTicks = 0;
            return true;
        }
        BlockState state = BlockUtil.state(shulkerPos);
        boolean chestDown = state.is(Blocks.ENDER_CHEST);
        if (++stageTicks > GRIND_LIMIT || obsidian() >= grindGoal
            || (!chestDown && enderChests() <= saveEnderChests.getInt())) {
            endRestock();
            return false;
        }
        if (grindBlockade.isOn() && buildBlockade()) {
            return true;
        }
        if (chestDown) {
            if (!holdPlainPickaxe(state)) {
                ChatUtil.error("HighwayBuilder needs a pickaxe without Silk Touch to grind ender chests.");
                endRestock();
                return false;
            }
            BlockMiner.mine(shulkerPos, rotation.isAny(Rotation.MINE, Rotation.BOTH));
            return true;
        }
        BlockMiner.release();
        if (!BlockUtil.isReplaceable(shulkerPos)) {
            endRestock();
            return false;
        }
        int slot = InventoryUtil.findSlot(Items.ENDER_CHEST, InventoryUtil.WHOLE_INVENTORY);
        if (slot >= InventoryUtil.HOTBAR_SIZE) {
            return moveToHotbar(slot);
        }
        slots.select(slot);
        BlockUtil.placeAny(shulkerPos, rotation.isAny(Rotation.PLACE, Rotation.BOTH), true);
        return true;
    }

    // Fills the sides round you at foot and head height apart from the chest spot.
    // True when a block went down this tick.
    private boolean buildBlockade() {
        BlockPos feet = mc.player.blockPosition();
        for (int up = 0; up < 2; up++) {
            for (Direction side : Direction.Plane.HORIZONTAL) {
                BlockPos pos = feet.above(up).relative(side);
                if (pos.equals(shulkerPos) || !BlockUtil.isReplaceable(pos)) {
                    continue;
                }
                int slot = BlockUtil.findBlockSlot();
                if (slot == -1) {
                    return false;
                }
                slots.select(slot);
                return BlockUtil.placeAny(pos, rotation.isAny(Rotation.PLACE, Rotation.BOTH), true);
            }
        }
        return false;
    }

    // Selects a pickaxe of the given kind for the block. True once one is in hand.
    private boolean holdPickaxe(BlockState state, boolean silkTouch) {
        int slot = ItemUtil.bestToolSlot(state, 1,
            stack -> (ItemUtil.enchantLevel(Enchantments.SILK_TOUCH, stack) > 0) == silkTouch,
            InventoryUtil.HOTBAR_SIZE);
        if (slot == -1) {
            return false;
        }
        slots.select(slot);
        return true;
    }

    private boolean holdSilkTouch(BlockState state) {
        return holdPickaxe(state, true);
    }

    private boolean holdPlainPickaxe(BlockState state) {
        return holdPickaxe(state, false);
    }

    private int enderChests() {
        return InventoryUtil.count(Items.ENDER_CHEST, InventoryUtil.WHOLE_INVENTORY);
    }

    private int obsidian() {
        return InventoryUtil.count(Items.OBSIDIAN, InventoryUtil.WHOLE_INVENTORY);
    }

    // Quick moves what the build needs and closes once there is nothing left.
    private boolean emptyShulker() {
        if (sinceInventory < inventoryDelay.getInt()) {
            sinceInventory++;
            return true;
        }
        AbstractContainerMenu menu = mc.player.containerMenu;
        int containerSlots = menu.slots.size() - InventoryUtil.WHOLE_INVENTORY;
        if (containerSlots > 0 && freeSlots() > emptySlots.getInt()) {
            for (int i = 0; i < containerSlots; i++) {
                Slot slot = menu.slots.get(i);
                if (!slot.hasItem() || !neededItem(slot.getItem())) {
                    continue;
                }
                mc.gameMode.handleContainerInput(menu.containerId, i, 0,
                    ContainerInput.QUICK_MOVE, mc.player);
                sinceInventory = 0;
                return true;
            }
        }
        mc.player.closeContainer();
        stage = Stage.BREAK;
        stageTicks = 0;
        return true;
    }

    private void endRestock() {
        stage = Stage.NONE;
        shulkerPos = null;
        BlockMiner.release();
    }

    // The block behind you at floor level or one beside it.
    private BlockPos restockSpot() {
        BlockPos feet = mc.player.blockPosition();
        List<BlockPos> tries = new ArrayList<>();
        tries.add(feet.relative(mc.player.getDirection().getOpposite()));
        for (Direction side : Direction.Plane.HORIZONTAL) {
            tries.add(feet.relative(side));
        }
        for (BlockPos spot : tries) {
            if (BlockUtil.isReplaceable(spot) && !BlockUtil.intersectsPlayer(spot)
                && BlockUtil.isSolid(spot.below())) {
                return spot;
            }
        }
        return null;
    }

    private boolean hasBlocksSomewhere() {
        return InventoryUtil.findSlot(stack -> stack.getItem() instanceof BlockItem item
            && allowed(item.getBlock()), InventoryUtil.WHOLE_INVENTORY) != -1;
    }

    private int freeSlots() {
        int free = 0;
        for (int i = 0; i < InventoryUtil.WHOLE_INVENTORY; i++) {
            if (mc.player.getInventory().getItem(i).isEmpty()) {
                free++;
            }
        }
        return free;
    }

    private boolean neededItem(ItemStack stack) {
        return (stack.getItem() instanceof BlockItem item && allowed(item.getBlock()))
            || stack.is(ItemTags.PICKAXES) || stack.has(DataComponents.FOOD);
    }

    private boolean usefulShulker(ItemStack stack) {
        if (!(stack.getItem() instanceof BlockItem item)
            || !(item.getBlock() instanceof ShulkerBoxBlock)) {
            return false;
        }
        ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
        if (contents == null) {
            return false;
        }
        return contents.nonEmptyItemCopyStream().anyMatch(this::neededItem);
    }

    // A shulker with nothing the build wants is dead weight.
    private boolean dropUselessShulker() {
        for (int i = 0; i < InventoryUtil.WHOLE_INVENTORY; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!(stack.getItem() instanceof BlockItem item)
                || !(item.getBlock() instanceof ShulkerBoxBlock) || usefulShulker(stack)) {
                continue;
            }
            mc.gameMode.handleContainerInput(0, InventoryUtil.networkSlot(i), 1,
                ContainerInput.THROW, mc.player);
            sinceInventory = 0;
            return true;
        }
        return false;
    }

    private boolean restockHotbar() {
        if (BlockUtil.findBlockSlot(this::allowed) == -1) {
            int from = InventoryUtil.findSlot(stack -> stack.getItem() instanceof BlockItem item
                && allowed(item.getBlock()), InventoryUtil.WHOLE_INVENTORY);
            if (from >= InventoryUtil.HOTBAR_SIZE) {
                return moveToHotbar(from);
            }
        }
        if (InventoryUtil.hotbarSlot(stack -> stack.is(ItemTags.PICKAXES)) == -1) {
            int from = InventoryUtil.findSlot(stack -> stack.is(ItemTags.PICKAXES),
                InventoryUtil.WHOLE_INVENTORY);
            if (from >= InventoryUtil.HOTBAR_SIZE) {
                return moveToHotbar(from);
            }
        }
        return false;
    }

    private boolean moveToHotbar(int from) {
        InventoryUtil.swap(InventoryUtil.networkSlot(from), InventoryUtil.networkSlot(spareHotbarSlot()));
        sinceInventory = 0;
        return true;
    }

    // An empty slot first then a tool then trash then the smallest stack of blocks.
    private int spareHotbarSlot() {
        int smallest = -1;
        int smallestCount = Integer.MAX_VALUE;
        int tool = -1;
        int rubbish = -1;
        for (int i = 0; i < InventoryUtil.HOTBAR_SIZE; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.isEmpty()) {
                return i;
            }
            if (trash.contains(stack.getItem())) {
                rubbish = i;
            } else if (stack.isDamageableItem() && !stack.is(ItemTags.PICKAXES)) {
                tool = i;
            } else if (stack.getItem() instanceof BlockItem && stack.getCount() < smallestCount) {
                smallestCount = stack.getCount();
                smallest = i;
            }
        }
        if (tool != -1) {
            return tool;
        }
        if (rubbish != -1) {
            return rubbish;
        }
        return smallest == -1 ? InventoryUtil.HOTBAR_SIZE - 1 : smallest;
    }

    // The biggest trash block stack stays behind for plugging liquid and corners.
    private boolean dropTrash() {
        int keep = -1;
        int keepCount = 0;
        for (int i = 0; i < InventoryUtil.WHOLE_INVENTORY; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.getItem() instanceof BlockItem && trash.contains(stack.getItem())
                && stack.getCount() > keepCount) {
                keepCount = stack.getCount();
                keep = i;
            }
        }
        for (int i = 0; i < InventoryUtil.WHOLE_INVENTORY; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (i == keep || stack.isEmpty() || !trash.contains(stack.getItem())) {
                continue;
            }
            // Facing back and up keeps the pile off the line ahead.
            RotationManager.requestExact(mc.player.getYRot() + 180, -25, RotationPriority.IDLE);
            mc.gameMode.handleContainerInput(0, InventoryUtil.networkSlot(i), 1,
                ContainerInput.THROW, mc.player);
            sinceInventory = 0;
            return true;
        }
        return false;
    }

    // An end crystal in the way is a trap. Three arrows and then it is left alone.
    private boolean shootCrystal() {
        if (!crystalTraps.isOn()) {
            releaseBow();
            return false;
        }
        if (crystal != null && (!crystal.isAlive() || outOfBand(crystal))) {
            ignoredCrystals.add(crystal.getId());
            crystal = null;
            crystalShots = 0;
        }
        if (crystal == null) {
            crystal = findCrystal();
            crystalShots = 0;
        }
        if (crystal == null) {
            releaseBow();
            return false;
        }
        int slot = InventoryUtil.hotbarSlot(stack -> stack.is(Items.BOW));
        if (slot == -1) {
            ignoredCrystals.add(crystal.getId());
            crystal = null;
            return false;
        }
        BlockMiner.release();
        slots.select(slot);
        aimAtCrystal();
        if (mc.player.getTicksUsingItem() >= FULL_DRAW) {
            releaseBow();
            crystalShots++;
            if (crystalShots >= CRYSTAL_SHOTS) {
                ignoredCrystals.add(crystal.getId());
                crystal = null;
            }
            return true;
        }
        InputUtil.hold(mc.options.keyUse);
        drawing = true;
        return true;
    }

    private void releaseBow() {
        if (drawing) {
            InputUtil.release(mc.options.keyUse);
            drawing = false;
        }
    }

    private Entity findCrystal() {
        Entity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof EndCrystal) || ignoredCrystals.contains(entity.getId())) {
                continue;
            }
            if (outOfBand(entity) || !mc.player.hasLineOfSight(entity)) {
                continue;
            }
            double distance = mc.player.distanceTo(entity);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = entity;
            }
        }
        return best;
    }

    private boolean outOfBand(Entity entity) {
        double distance = mc.player.distanceTo(entity);
        return distance < CRYSTAL_NEAR || distance > CRYSTAL_FAR;
    }

    private void aimAtCrystal() {
        Vec3 eye = mc.player.getEyePosition();
        Vec3 at = crystal.getBoundingBox().getCenter();
        double dx = at.x - eye.x;
        double dz = at.z - eye.z;
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
        RotationManager.requestExact(yaw, bowPitch(eye, at), RotationPriority.ATTACK);
    }

    // The lower of the two arcs that reach the point with no drag worked in.
    private static float bowPitch(Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        double flat = Math.sqrt(dx * dx + dz * dz);
        double rise = to.y - from.y;
        double g = ProjectileUtil.ARROW_GRAVITY;
        double v2 = BOW_SPEED * BOW_SPEED;
        double root = v2 * v2 - g * (g * flat * flat + 2 * rise * v2);
        if (root < 0 || flat < 0.01) {
            return (float) -Math.toDegrees(Math.atan2(rise, Math.max(flat, 0.01)));
        }
        double angle = Math.atan((v2 - Math.sqrt(root)) / (g * flat));
        return (float) -Math.toDegrees(angle);
    }

    // Walks once the stretch just ahead is finished or only holds work out of reach.
    private void walk(double here) {
        slots.restoreIfMine();
        boolean go = stretchDone(here + 1, here + LEAD);
        if (freeLook.isOn()) {
            stopWalking();
            if (go) {
                walker.walkAlong(WALK_SPEED);
            }
            return;
        }
        walking = go;
        if (go) {
            mc.options.keyUp.setDown(true);
        } else {
            InputUtil.release(mc.options.keyUp);
        }
    }

    private boolean stretchDone(double from, double to) {
        double reach = mc.player.blockInteractionRange();
        for (BlockPos pos : stretch) {
            double along = walker.alongOf(pos);
            if (along < from || along > to) {
                continue;
            }
            if (!needsClearing(pos) && !needsFilling(pos)) {
                continue;
            }
            if (!skipUnreachable.isOn() || BlockUtil.distanceTo(pos) <= reach) {
                return false;
            }
        }
        return true;
    }

    private void stopWalking() {
        if (walking) {
            walking = false;
            InputUtil.release(mc.options.keyUp);
        }
    }

    // An empty list means any plain building block will do.
    private boolean allowed(Block block) {
        if (blocks.size() == 0) {
            return BlockUtil.isBuildingBlock(block, BlockPos.ZERO);
        }
        return blocks.contains(block);
    }

    private static boolean isTorch(Block block) {
        return block == Blocks.TORCH || block == Blocks.WALL_TORCH
            || block == Blocks.SOUL_TORCH || block == Blocks.SOUL_WALL_TORCH;
    }

    private int leftLane() {
        return -((width.getInt() - 1) / 2);
    }

    private int rightLane() {
        return width.getInt() - 1 + leftLane();
    }

    private void stop(String reason) {
        ChatUtil.error(reason);
        String totals = "§7Went §f" + (int) bestTravelled + "§7 blocks. Broke §f"
            + blocksBroken + "§7 and placed §f" + blocksPlaced + "§7.";
        boolean leave = disconnectOnStop.isOn() && inGame();
        setEnabled(false);
        if (leave) {
            mc.player.connection.getConnection().disconnect(
                Component.literal("§b[§3Offline§b] §fHighwayBuilder stopped.\n§7" + reason + "\n" + totals));
        }
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!walker.isLocked() || !inGame()) {
            return;
        }
        if (outline.isOn()) {
            drawStretch(event);
        }
        drawJobs(event);
    }

    private void drawJobs(Render3DEvent event) {
        int drawn = 0;
        for (BlockPos pos : stretch) {
            if (drawn >= MAX_DRAWN) {
                return;
            }
            if (renderMine.isOn() && needsClearing(pos)) {
                mineBox.draw(event.getBatch(), pos, false);
                drawn++;
            } else if (renderPlace.isOn()
                && (needsFilling(pos) || (fillLiquids.isOn() && isLiquid(pos)))) {
                placeBox.draw(event.getBatch(), pos, false);
                drawn++;
            }
        }
    }

    // A frame around the tunnel from just behind the player to the end of the reach.
    private void drawStretch(Render3DEvent event) {
        int color = ColorUtil.withAlpha(outlineColor.getColor(), 255);
        double here = Math.floor(walker.travelled());
        double from = here - BEHIND;
        double to = here + AHEAD + 1;
        double left = leftLane() - 0.5;
        double right = rightLane() + 0.5;
        int tall = height.getInt();
        Vec3[] corners = {
            walker.pointAt(from, left, 0), walker.pointAt(from, right, 0),
            walker.pointAt(to, right, 0), walker.pointAt(to, left, 0)
        };
        for (int i = 0; i < 4; i++) {
            Vec3 a = corners[i];
            Vec3 b = corners[(i + 1) % 4];
            event.getBatch().line(a, b, color, true);
            event.getBatch().line(a.add(0, tall, 0), b.add(0, tall, 0), color, true);
            event.getBatch().line(a, a.add(0, tall, 0), color, true);
        }
    }
}
