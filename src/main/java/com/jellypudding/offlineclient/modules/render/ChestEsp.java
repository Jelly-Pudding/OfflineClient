package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.ContainerEntity;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.CrafterBlockEntity;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.entity.TrappedChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

public final class ChestEsp extends Module {

    private record Target(AABB box, int color) {
    }

    private static final int CHEST_COLOR = 0xFF40FF70;
    private static final int TRAPPED_COLOR = 0xFFFF5050;
    private static final int ENDER_COLOR = 0xFFB44CFF;
    private static final int SHULKER_COLOR = 0xFFFFD040;
    private static final int BARREL_COLOR = 0xFFC08040;
    private static final int FURNACE_COLOR = 0xFFA0A0A0;
    private static final int HOPPER_COLOR = 0xFF707070;
    private static final int DISPENSER_COLOR = 0xFF9090A0;
    private static final int VEHICLE_COLOR = 0xFF60C0FF;

    private final BoolSetting chests = new BoolSetting("Chests",
        "Highlight chests.", true);
    private final BoolSetting trappedChests = new BoolSetting("Trapped chests",
        "Highlight trapped chests in their own colour.", true);
    private final BoolSetting enderChests = new BoolSetting("Ender chests",
        "Highlight ender chests.", true);
    private final BoolSetting shulkers = new BoolSetting("Shulkers",
        "Highlight shulker boxes.", true);
    private final BoolSetting barrels = new BoolSetting("Barrels",
        "Highlight barrels.", true);
    private final BoolSetting furnaces = new BoolSetting("Furnaces",
        "Highlight furnaces and smokers and blast furnaces.", false);
    private final BoolSetting hoppers = new BoolSetting("Hoppers",
        "Highlight hoppers.", false);
    private final BoolSetting dispensers = new BoolSetting("Dispensers",
        "Highlight dispensers and droppers and crafters.", false);
    private final BoolSetting vehicles = new BoolSetting("Vehicles",
        "Highlight chest boats and chest and hopper minecarts.", true);
    private final BoolSetting tracers = new BoolSetting("Tracers",
        "Draw a line from you to each container.", false);
    private final NumberSetting radius = new NumberSetting("Radius",
        "Chunk radius to scan around you.", 4, 1, 8, 1, " chunks");

    private final List<Target> targets = new ArrayList<>();

    public ChestEsp() {
        super("ChestESP", "See containers through walls.", Category.RENDER);
        addSettings(chests, trappedChests, enderChests, shulkers, barrels, furnaces, hoppers,
            dispensers, vehicles, tracers, radius);
        searchTags("storage esp", "container esp");
    }

    @Override
    public String getSuffix() {
        return count(targets.size());
    }

    @Override
    protected void onDisable() {
        targets.clear();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        targets.clear();
        if (!inGame()) {
            return;
        }
        int r = radius.getInt();
        int centerX = mc.player.chunkPosition().x();
        int centerZ = mc.player.chunkPosition().z();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                LevelChunk chunk = mc.level.getChunkSource()
                    .getChunk(centerX + dx, centerZ + dz, ChunkStatus.FULL, false);
                if (chunk != null) {
                    collectBlocks(chunk);
                }
            }
        }
        if (vehicles.isOn()) {
            collectVehicles();
        }
    }

    private void collectBlocks(LevelChunk chunk) {
        for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
            int color = colorFor(blockEntity);
            if (color == 0) {
                continue;
            }
            BlockPos pos = blockEntity.getBlockPos();
            AABB box = boxAt(pos);
            // A double chest is one container drawn from its right half.
            if (blockEntity instanceof ChestBlockEntity) {
                BlockState state = blockEntity.getBlockState();
                if (state.hasProperty(ChestBlock.TYPE)) {
                    ChestType type = state.getValue(ChestBlock.TYPE);
                    if (type == ChestType.LEFT) {
                        continue;
                    }
                    if (type == ChestType.RIGHT) {
                        box = box.minmax(boxAt(pos.relative(ChestBlock.getConnectedDirection(state))));
                    }
                }
            }
            targets.add(new Target(box, color));
        }
    }

    private void collectVehicles() {
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity instanceof ContainerEntity) {
                targets.add(new Target(entity.getBoundingBox(), VEHICLE_COLOR));
            }
        }
    }

    private static AABB boxAt(BlockPos pos) {
        return new AABB(pos.getX() + 0.05, pos.getY() + 0.05, pos.getZ() + 0.05,
            pos.getX() + 0.95, pos.getY() + 0.9, pos.getZ() + 0.95);
    }

    // Zero for a container the settings leave out. Subclasses are tested before their parents.
    private int colorFor(BlockEntity blockEntity) {
        if (blockEntity instanceof TrappedChestBlockEntity) {
            return trappedChests.isOn() ? TRAPPED_COLOR : 0;
        }
        if (blockEntity instanceof ChestBlockEntity) {
            return chests.isOn() ? CHEST_COLOR : 0;
        }
        if (blockEntity instanceof EnderChestBlockEntity) {
            return enderChests.isOn() ? ENDER_COLOR : 0;
        }
        if (blockEntity instanceof ShulkerBoxBlockEntity) {
            return shulkers.isOn() ? SHULKER_COLOR : 0;
        }
        if (blockEntity instanceof BarrelBlockEntity) {
            return barrels.isOn() ? BARREL_COLOR : 0;
        }
        if (blockEntity instanceof AbstractFurnaceBlockEntity) {
            return furnaces.isOn() ? FURNACE_COLOR : 0;
        }
        if (blockEntity instanceof HopperBlockEntity) {
            return hoppers.isOn() ? HOPPER_COLOR : 0;
        }
        if (blockEntity instanceof DispenserBlockEntity || blockEntity instanceof CrafterBlockEntity) {
            return dispensers.isOn() ? DISPENSER_COLOR : 0;
        }
        return 0;
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        DrawBatch batch = event.getBatch();
        for (Target target : targets) {
            batch.outlineBox(target.box(), target.color(), true);
            if (tracers.isOn()) {
                batch.tracer(target.box().getCenter(), target.color(), true);
            }
        }
    }
}
