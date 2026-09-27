package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.config.ContainerMarks;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.KeyPressEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.KeybindSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.FaceMode;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.WorldWatch;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;

// Opens the containers around you one at a time. ChestStealer's rules decide what
// comes out and InventoryTweaks' dump filter decides what goes in.
public final class ChestAura extends Module {

    public enum Mode { TAKE, STORE }

    // Ticks a clicked container gets to open before it is passed over.
    private static final int OPEN_TIMEOUT = 20;

    // Ticks between closing one container and clicking the next.
    private static final int BETWEEN_TICKS = 5;

    // How far another player's crosshair reaches when deciding what they look at.
    private static final double LOOK_REACH = 5;

    // Feet this close to the top of a container stand on it.
    private static final double STAND_BAND = 0.5;

    private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
        "What happens in each container.", Mode.TAKE)
        .describe(Mode.TAKE, "Takes out whatever ChestStealer would take.")
        .describe(Mode.STORE, "Puts in whatever the InventoryTweaks dump filter picks.");
    private final NumberSetting range = new NumberSetting("Range",
        "How far from your eyes a container may be.", 4.5, 1, 6, 0.1, " blocks");
    private final EnumSetting<FaceMode> face = FaceMode.setting(FaceMode.SERVER);
    private final KeybindSetting markKey = new KeybindSetting("Mark key",
        "Press whilst looking at a container to have the aura leave it alone or open it again."
            + " Works whilst the aura is off.",
        KeybindSetting.UNBOUND);

    // Every container finished with or passed over. A double chest adds both halves.
    private final Set<BlockPos> done = new HashSet<>();
    private final WorldWatch world = new WorldWatch();

    // The container clicked last. Null between visits.
    private BlockPos visiting;
    private boolean opened;
    private int waited;
    private int rest;
    private int finished;

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
        addSettings(mode, range, face, markKey);
        searchTags("chest aura", "loot", "steal", "stash", "container");
        watch(marker);
    }

    @Override
    public String getSuffix() {
        return count(finished, "done");
    }

    // Containers already finished with are skipped until the aura is switched off and on.
    @Override
    protected void onEnable() {
        done.clear();
        finished = 0;
        rest = 0;
        world.accept();
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
        if (world.changed()) {
            endVisit();
            done.clear();
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
        if (rest > 0) {
            rest--;
            return;
        }
        if (mc.gui.screen() != null || mc.player.isUsingItem() || !worthOpening(stealer)) {
            return;
        }
        BlockPos target = nextTarget(stealer);
        if (target != null) {
            open(target);
        }
    }

    private boolean worthOpening(ChestStealer stealer) {
        return mode.is(Mode.TAKE) ? stealer.roomToTake() : stealer.somethingToStore();
    }

    private void open(BlockPos pos) {
        visiting = pos;
        opened = false;
        waited = 0;
        if (!BlockUtil.interact(pos, face.getValue())) {
            finish(true);
        }
    }

    private void visit(ChestStealer stealer) {
        if (!opened) {
            // The items follow the screen in a packet of their own. The menu counts from nought until then.
            if (stealer.handles(mc.gui.screen()) && mc.player.containerMenu.getStateId() != 0) {
                opened = true;
                stealer.startVisit();
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
        ChestStealer.Step step = mode.is(Mode.TAKE) ? stealer.take() : stealer.store();
        if (step == ChestStealer.Step.DONE) {
            finished++;
            finish(true);
        } else if (step == ChestStealer.Step.FULL) {
            // A full inventory leaves the container for later. A full container is done with.
            finish(mode.is(Mode.STORE));
        }
    }

    // Closes the container. One finished for good is not opened again.
    private void finish(boolean forGood) {
        ChestStealer stealer = Modules.get(ChestStealer.class);
        if (visiting != null && stealer != null && stealer.handles(mc.gui.screen())) {
            mc.player.closeContainer();
        }
        if (forGood && visiting != null) {
            done.add(visiting);
            BlockPos other = BlockUtil.otherChestHalf(visiting, BlockUtil.state(visiting));
            if (other != null) {
                done.add(other);
            }
        }
        endVisit();
        rest = BETWEEN_TICKS;
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

    // The nearest container in reach that the stealer works and nobody else is using.
    private BlockPos nextTarget(ChestStealer stealer) {
        return BlockUtil.nearestWithin(range.getValue(),
            pos -> mc.level.getBlockEntity(pos) instanceof BlockEntity entity && openable(entity, stealer));
    }

    private boolean openable(BlockEntity entity, ChestStealer stealer) {
        MenuType<?> menu = menuFor(entity);
        if (menu == null || !stealer.handles(menu)) {
            return false;
        }
        BlockPos pos = entity.getBlockPos();
        BlockPos other = BlockUtil.otherChestHalf(pos, entity.getBlockState());
        if (done.contains(pos) || ContainerMarks.get().isMarked(pos) || ContainerMarks.get().isMarked(other)) {
            return false;
        }
        if (entity instanceof ChestBlockEntity && (ChestBlock.isChestBlockedAt(mc.level, pos)
            || other != null && ChestBlock.isChestBlockedAt(mc.level, other))) {
            return false;
        }
        return !inUse(entity, other) && !watched(pos, other);
    }

    // The screen a container opens. Null for anything the aura leaves alone such as an ender chest.
    private static MenuType<?> menuFor(BlockEntity entity) {
        if (entity instanceof ChestBlockEntity) {
            return BlockUtil.otherChestHalf(entity.getBlockPos(), entity.getBlockState()) == null
                ? MenuType.GENERIC_9x3 : MenuType.GENERIC_9x6;
        }
        if (entity instanceof BarrelBlockEntity) {
            return MenuType.GENERIC_9x3;
        }
        if (entity instanceof ShulkerBoxBlockEntity) {
            return MenuType.SHULKER_BOX;
        }
        if (entity instanceof HopperBlockEntity) {
            return MenuType.HOPPER;
        }
        // A dropper is a kind of dispenser and opens the same screen.
        return entity instanceof DispenserBlockEntity ? MenuType.GENERIC_3x3 : null;
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
