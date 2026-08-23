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
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

public final class ChestEsp extends Module {

    private record Target(AABB box, int color) {
    }

    private final BoolSetting chests = new BoolSetting("Chests",
        "Highlight chests and trapped chests.", true);
    private final BoolSetting enderChests = new BoolSetting("Ender chests",
        "Highlight ender chests.", true);
    private final BoolSetting shulkers = new BoolSetting("Shulkers",
        "Highlight shulker boxes.", true);
    private final BoolSetting barrels = new BoolSetting("Barrels",
        "Highlight barrels.", true);
    private final NumberSetting radius = new NumberSetting("Radius",
        "Chunk radius to scan around you.", 4, 1, 8, 1, " chunks");

    private final List<Target> targets = new ArrayList<>();

    public ChestEsp() {
        super("ChestESP", "See containers through walls.", Category.RENDER);
        addSettings(chests, enderChests, shulkers, barrels, radius);
    }

    @Override
    public String getSuffix() {
        return String.valueOf(targets.size());
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
                LevelChunk chunk = (LevelChunk) mc.level.getChunkSource()
                    .getChunk(centerX + dx, centerZ + dz, ChunkStatus.FULL, false);
                if (chunk == null) {
                    continue;
                }
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
                                box = box.minmax(boxAt(
                                    pos.relative(ChestBlock.getConnectedDirection(state))));
                            }
                        }
                    }
                    targets.add(new Target(box, color));
                }
            }
        }
    }

    private static AABB boxAt(BlockPos pos) {
        return new AABB(pos.getX() + 0.05, pos.getY() + 0.05, pos.getZ() + 0.05,
            pos.getX() + 0.95, pos.getY() + 0.9, pos.getZ() + 0.95);
    }

    private int colorFor(BlockEntity blockEntity) {
        if (blockEntity instanceof EnderChestBlockEntity) {
            return enderChests.isOn() ? 0xFFB44CFF : 0;
        }
        if (blockEntity instanceof ChestBlockEntity) {
            return chests.isOn() ? 0xFF40FF70 : 0;
        }
        if (blockEntity instanceof ShulkerBoxBlockEntity) {
            return shulkers.isOn() ? 0xFFFFD040 : 0;
        }
        if (blockEntity instanceof BarrelBlockEntity) {
            return barrels.isOn() ? 0xFFC08040 : 0;
        }
        return 0;
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        DrawBatch batch = event.getBatch();
        for (Target target : targets) {
            batch.outlineBox(target.box(), target.color(), true);
        }
    }
}
