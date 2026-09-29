package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.config.ContainerMarks;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.KeyPressEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.KeybindSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.FaceMode;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.JoinWatch;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.RotationPriority;
import com.jellypudding.offlineclient.util.SwingMode;
import com.jellypudding.offlineclient.util.WorldWatch;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.ContainerEntity;
import net.minecraft.world.entity.vehicle.minecart.MinecartHopper;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BrewingStandBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlastFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.CrafterBlockEntity;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.entity.SmokerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;

// Opens the containers around you one at a time. ChestStealer's rules decide what
// comes out and InventoryTweaks' dump filter decides what goes in.
public final class ChestAura extends Module {

    public enum Mode { TAKE, STORE, BOTH }

    // Ticks a clicked container gets to open before it is passed over.
    private static final int OPEN_TIMEOUT = 20;

    // How far another player's crosshair reaches when deciding what they look at.
    private static final double LOOK_REACH = 5;

    // Feet this close to the top of a container stand on it.
    private static final double STAND_BAND = 0.5;

    private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
        "What happens in each container.", Mode.TAKE)
        .describe(Mode.TAKE, "Takes out whatever ChestStealer would take.")
        .describe(Mode.STORE, "Puts in whatever the InventoryTweaks dump filter picks.")
        .describe(Mode.BOTH, "Leaves your junk behind and takes what ChestStealer wants.");
    private final NumberSetting range = new NumberSetting("Range",
        "How far from your eyes a container may be.", 4.5, 1, 6, 0.1, " blocks");
    private final NumberSetting rest = new NumberSetting("Rest",
        "Ticks to wait after closing one container before opening the next.", 5, 0, 40, 1, " ticks")
        .min(0);
    private final RegistryListSetting<Block> containers = new RegistryListSetting<>("Containers",
        "The container blocks the aura opens. Click to pick them. A trapped chest sets off its redstone"
            + " when opened and starts left out.",
        BuiltInRegistries.BLOCK, BuiltInRegistries.BLOCK.stream()
            .filter(block -> opens(block) && block != Blocks.TRAPPED_CHEST).toList())
        .only(ChestAura::opens);
    private final BoolSetting vehicles = new BoolSetting("Carts and boats",
        "Also opens minecarts and boats that carry a chest or a hopper.", true);
    private final BoolSetting stopWhenFull = new BoolSetting("Stop when full",
        "Stops opening containers once your inventory is full. Off still visits them to store"
            + " extras and trade up with ChestStealer limits.", false);
    private final BoolSetting offOnLeave = new BoolSetting("Off on leave",
        "Switches the aura off whenever you leave a server. It never opens chests the moment you join.",
        true);
    private final EnumSetting<FaceMode> face = FaceMode.setting(FaceMode.SERVER);
    private final KeybindSetting markKey = new KeybindSetting("Mark key",
        "Press whilst looking at a container to have the aura leave it alone or open it again."
            + " Works whilst the aura is off.",
        KeybindSetting.UNBOUND);

    // The container a visit clicks. A block or a vehicle that carries one.
    private sealed interface Target permits BlockTarget, VehicleTarget {
    }

    private record BlockTarget(BlockPos pos) implements Target {
    }

    private record VehicleTarget(int id) implements Target {
    }

    // Every container finished with or passed over. A double chest adds both halves.
    private final Set<Target> done = new HashSet<>();
    // Containers a full inventory walked away from. They are tried again once a slot frees up.
    private final Set<Target> later = new HashSet<>();
    private final WorldWatch world = new WorldWatch();
    private final JoinWatch joins = new JoinWatch();

    // The container clicked last. Null between visits.
    private Target visiting;
    private boolean opened;
    // The live menu as the container was clicked. Only a menu opened after it is the visit's own.
    private int clickedMenu;
    // Set once a visit in both modes has stored what it can and moves on to taking.
    private boolean stored;
    private int waited;
    private int resting;
    private int finished;
    private boolean fullTold;

    // Marks go down before the aura is switched on. The key is read at all times.
    private final Object marker = new Object() {
        @Subscribe
        private void onKeyPress(KeyPressEvent event) {
            if (event.getAction() == InputConstants.PRESS && markKey.isBound()
                && event.getKey() == markKey.getValue() && inGame() && mc.gui.screen() == null) {
                markAimed();
            }
        }
    };

    public ChestAura() {
        super("ChestAura", "Opens the containers around you and empties or fills them.",
            Category.PLAYER);
        addSettings(mode, range, rest, containers, vehicles, stopWhenFull, offOnLeave, face, markKey);
        searchTags("chest aura", "loot", "steal", "stash", "container");
        watch(marker);
    }

    // With Off on leave it starts every game switched off as well.
    @Override
    public boolean savesEnabledState() {
        return !offOnLeave.isOn();
    }

    @Override
    public String getSuffix() {
        return count(finished, "done");
    }

    // Containers already finished with are skipped until the aura is switched off and on.
    @Override
    protected void onEnable() {
        done.clear();
        later.clear();
        finished = 0;
        resting = 0;
        fullTold = false;
        world.accept();
        joins.accept();
    }

    @Override
    protected void onDisable() {
        endVisit();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            return;
        }
        if (joins.joined() && offOnLeave.isOn()) {
            disable("ChestAura switched off after joining.");
            return;
        }
        if (world.changed()) {
            endVisit();
            done.clear();
            later.clear();
        }
        ChestStealer stealer = Modules.get(ChestStealer.class);
        if (stealer == null || mc.player.isSpectator() || mc.player.isDeadOrDying()) {
            endVisit();
            return;
        }
        if (visiting != null) {
            visit(stealer);
            return;
        }
        noticeFull(stealer);
        if (resting > 0) {
            resting--;
            return;
        }
        // Opening a container would make the server close the one ChestLink keeps.
        if (mc.gui.screen() != null || mc.player.isUsingItem() || InventoryUtil.menuKept()
            || !worthOpening(stealer)) {
            return;
        }
        open(stealer);
    }

    // One line each time the inventory fills. A freed slot brings back what it walked away from.
    private void noticeFull(ChestStealer stealer) {
        if (stealer.roomToTake()) {
            fullTold = false;
            later.clear();
        } else if (!mode.is(Mode.STORE) && !fullTold) {
            fullTold = true;
            ChatUtil.error("Your inventory is full.");
        }
    }

    private boolean worthOpening(ChestStealer stealer) {
        if (!mode.is(Mode.TAKE) && stealer.somethingToStore()) {
            return true;
        }
        if (mode.is(Mode.STORE)) {
            return false;
        }
        return stealer.roomToTake()
            || !stopWhenFull.isOn() && (stealer.extrasToStore() || stealer.mayUpgrade());
    }

    // The nearest block or vehicle in reach that the stealer works and nobody else is using.
    private void open(ChestStealer stealer) {
        BlockPos block = BlockUtil.nearestWithin(range.getValue(),
            pos -> mc.level.getBlockEntity(pos) instanceof BlockEntity entity && openable(entity, stealer));
        Entity vehicle = vehicles.isOn() ? nearestVehicle(stealer) : null;
        Vec3 eye = mc.player.getEyePosition();
        boolean vehicleCloser = vehicle != null && (block == null
            || vehicle.getBoundingBox().distanceToSqr(eye) < Vec3.atCenterOf(block).distanceToSqr(eye));
        if (vehicleCloser) {
            begin(new VehicleTarget(vehicle.getId()));
            clicked(openVehicle(vehicle));
        } else if (block != null) {
            begin(new BlockTarget(block));
            clicked(BlockUtil.interact(block, face.getValue()));
        }
    }

    private void begin(Target target) {
        visiting = target;
        clickedMenu = mc.player.containerMenu.containerId;
        opened = false;
        stored = false;
        waited = 0;
    }

    private void clicked(boolean taken) {
        if (!taken) {
            finish(true);
        }
    }

    // A chest boat carries you away unless you sneak. The sneak lasts only for the click.
    private boolean openVehicle(Entity vehicle) {
        Vec3 aim = vehicle.getBoundingBox().getCenter();
        face.getValue().face(aim, RotationPriority.PLACE);
        EntityHitResult hit = new EntityHitResult(vehicle, aim);
        boolean used = InputUtil.whileSneaking(() -> mc.gameMode
            .interact(mc.player, vehicle, hit, InteractionHand.MAIN_HAND).consumesAction());
        if (used) {
            SwingMode.swingArm(InteractionHand.MAIN_HAND);
        }
        return used;
    }

    private void visit(ChestStealer stealer) {
        if (!opened) {
            // The items follow the screen in a packet of their own. The menu counts from nought until then.
            if (ownScreen(stealer) && mc.player.containerMenu.getStateId() != 0) {
                opened = true;
                stealer.startVisit(!mode.is(Mode.TAKE));
            } else if (++waited > OPEN_TIMEOUT) {
                finish(true);
                return;
            } else {
                return;
            }
        }
        // Closed by hand or by the server. It is not opened again.
        if (!stealer.handles(mc.gui.screen())) {
            finish(true);
            return;
        }
        ChestStealer.Step step = step(stealer);
        if (step == ChestStealer.Step.DONE) {
            finished++;
            finish(true);
        } else if (step == ChestStealer.Step.FULL) {
            // A full inventory leaves the container for later. A full container is done with. One
            // whose items the server sent back whilst there was room is done with too.
            boolean refused = mode.is(Mode.STORE) || stealer.roomToTake();
            if (!refused) {
                later.add(visiting);
            }
            finish(refused);
        }
    }

    // A click that opened nothing leaves the wait open to a screen you open yourself. Your ender
    // chest is never one the aura clicked.
    private boolean ownScreen(ChestStealer stealer) {
        Screen screen = mc.gui.screen();
        return stealer.handles(screen) && mc.player.containerMenu.containerId != clickedMenu
            && !InventoryUtil.isEnderChest(screen);
    }

    // Both modes store first. The junk that goes in makes room for what comes out.
    private ChestStealer.Step step(ChestStealer stealer) {
        if (mode.is(Mode.TAKE)) {
            return stealer.take();
        }
        if (!stored) {
            ChestStealer.Step step = stealer.store();
            if (mode.is(Mode.STORE) || step == ChestStealer.Step.MOVED || step == ChestStealer.Step.WAITING) {
                return step;
            }
            stored = true;
        }
        return stealer.take();
    }

    // Closes the container. One finished for good is not opened again.
    private void finish(boolean forGood) {
        ChestStealer stealer = Modules.get(ChestStealer.class);
        if (visiting != null && stealer != null && stealer.handles(mc.gui.screen())) {
            mc.player.closeContainer();
        }
        if (forGood && visiting != null) {
            done.add(visiting);
            if (visiting instanceof BlockTarget(BlockPos pos)) {
                BlockPos other = BlockUtil.otherChestHalf(pos, BlockUtil.state(pos));
                if (other != null) {
                    done.add(new BlockTarget(other));
                }
            }
        }
        endVisit();
        resting = rest.getInt();
    }

    private void endVisit() {
        if (opened) {
            ChestStealer stealer = Modules.get(ChestStealer.class);
            if (stealer != null) {
                stealer.endVisit();
            }
        }
        visiting = null;
        opened = false;
        waited = 0;
    }

    private boolean skipped(Target target) {
        return done.contains(target) || later.contains(target);
    }

    private boolean openable(BlockEntity entity, ChestStealer stealer) {
        MenuType<?> menu = menuFor(entity);
        if (menu == null || !stealer.handles(menu) || !containers.contains(entity.getBlockState().getBlock())
            || mode.is(Mode.STORE) && !InventoryUtil.keeps(menu)) {
            return false;
        }
        BlockPos pos = entity.getBlockPos();
        BlockPos other = BlockUtil.otherChestHalf(pos, entity.getBlockState());
        if (skipped(new BlockTarget(pos)) || ContainerMarks.get().isMarked(pos)
            || ContainerMarks.get().isMarked(other)) {
            return false;
        }
        if (entity instanceof ChestBlockEntity && (ChestBlock.isChestBlockedAt(mc.level, pos)
            || other != null && ChestBlock.isChestBlockedAt(mc.level, other))) {
            return false;
        }
        return !inUse(entity, other) && !watched(pos, other);
    }

    // The nearest cart or boat with a container in reach. One another player rides is theirs.
    private Entity nearestVehicle(ChestStealer stealer) {
        Vec3 eye = mc.player.getEyePosition();
        double best = range.getValue() * range.getValue();
        Entity nearest = null;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof ContainerEntity) || !entity.isAlive()
                || skipped(new VehicleTarget(entity.getId())) || !stealer.handles(menuFor(entity))
                || entity.hasPassenger(rider -> rider instanceof Player && rider != mc.player)) {
                continue;
            }
            double distance = entity.getBoundingBox().distanceToSqr(eye);
            if (distance <= best) {
                best = distance;
                nearest = entity;
            }
        }
        return nearest;
    }

    // A block the aura knows how to open and read.
    private static boolean opens(Block block) {
        return block instanceof ChestBlock || block instanceof BarrelBlock || block instanceof ShulkerBoxBlock
            || block instanceof HopperBlock || block instanceof DispenserBlock
            || block instanceof AbstractFurnaceBlock || block instanceof BrewingStandBlock
            || block instanceof CrafterBlock;
    }

    // The screen a container opens. Null for anything the aura leaves alone such as an ender chest.
    private static MenuType<?> menuFor(BlockEntity entity) {
        return switch (entity) {
            case ChestBlockEntity chest -> BlockUtil.otherChestHalf(chest.getBlockPos(), chest.getBlockState()) == null
                ? MenuType.GENERIC_9x3 : MenuType.GENERIC_9x6;
            case BarrelBlockEntity ignored -> MenuType.GENERIC_9x3;
            case ShulkerBoxBlockEntity ignored -> MenuType.SHULKER_BOX;
            case HopperBlockEntity ignored -> MenuType.HOPPER;
            // A dropper is a kind of dispenser and opens the same screen.
            case DispenserBlockEntity ignored -> MenuType.GENERIC_3x3;
            case BlastFurnaceBlockEntity ignored -> MenuType.BLAST_FURNACE;
            case SmokerBlockEntity ignored -> MenuType.SMOKER;
            case FurnaceBlockEntity ignored -> MenuType.FURNACE;
            case BrewingStandBlockEntity ignored -> MenuType.BREWING_STAND;
            case CrafterBlockEntity ignored -> MenuType.CRAFTER_3x3;
            default -> null;
        };
    }

    // A hopper cart opens a hopper screen. Every other cart or boat opens a chest of three rows.
    private static MenuType<?> menuFor(Entity vehicle) {
        return vehicle instanceof MinecartHopper ? MenuType.HOPPER : MenuType.GENERIC_9x3;
    }

    // Someone else has it open. A chest lid or a shulker shell is lifting or a barrel shows its open face.
    private boolean inUse(BlockEntity entity, BlockPos other) {
        if (entity instanceof ChestBlockEntity chest) {
            return chest.getOpenNess(1) > 0
                || other != null && mc.level.getBlockEntity(other) instanceof ChestBlockEntity half
                && half.getOpenNess(1) > 0;
        }
        if (entity instanceof ShulkerBoxBlockEntity box) {
            return box.getProgress(1) > 0;
        }
        BlockState state = entity.getBlockState();
        return state.hasProperty(BarrelBlock.OPEN) && state.getValue(BarrelBlock.OPEN);
    }

    // Another player stands on the container or has their crosshair on it.
    private boolean watched(BlockPos pos, BlockPos other) {
        for (AbstractClientPlayer player : mc.level.players()) {
            if (player == mc.player) {
                continue;
            }
            if (standsOn(player, pos) || standsOn(player, other) || looksAt(player, pos, other)) {
                return true;
            }
        }
        return false;
    }

    private static boolean standsOn(Player player, BlockPos pos) {
        if (pos == null) {
            return false;
        }
        double top = pos.getY() + 1;
        AABB band = new AABB(pos.getX(), top - STAND_BAND, pos.getZ(),
            pos.getX() + 1, top + STAND_BAND, pos.getZ() + 1);
        return player.getY() >= top - STAND_BAND && player.getBoundingBox().intersects(band);
    }

    private boolean looksAt(Player player, BlockPos pos, BlockPos other) {
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getViewVector(1).scale(LOOK_REACH));
        BlockHitResult hit = mc.level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE,
            ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.BLOCK
            && (hit.getBlockPos().equals(pos) || hit.getBlockPos().equals(other));
    }

    private void markAimed() {
        BlockHitResult aimed = BlockUtil.aimedBlock();
        BlockEntity entity = aimed == null ? null : mc.level.getBlockEntity(aimed.getBlockPos());
        if (entity == null || menuFor(entity) == null) {
            ChatUtil.error("Look at a container to mark it.");
            return;
        }
        BlockPos pos = entity.getBlockPos();
        BlockPos other = BlockUtil.otherChestHalf(pos, entity.getBlockState());
        if (ContainerMarks.get().toggle(pos, other)) {
            ChatUtil.message("ChestAura leaves this container alone.");
        } else {
            ChatUtil.message("ChestAura may open this container again.");
        }
    }
}
