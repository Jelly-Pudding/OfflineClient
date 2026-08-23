package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class StashFinder extends Module {

    private record Stash(ResourceKey<Level> dimension, ChunkPos pos, int containers) {
    }

    private static final int SCANS_PER_TICK = 4;

    private static final int MAX_SCANNED = 20_000;

    private static final int DROP_PER_TRIM = 4_000;

    private static final int MAX_STASHES = 256;

    private final NumberSetting minimum = new NumberSetting("Minimum",
        "How many containers a chunk needs before it counts as a stash.", 6, 2, 64, 1)
        .min(1);
    private final BoolSetting extraTypes = new BoolSetting("Barrels and shulkers",
        "Counts barrels and shulker boxes as well as chests.", true);

    // Kept for the whole session even whilst turned off.
    private final List<Stash> stashes = new ArrayList<>();

    // Chunks already counted in the current dimension. Insertion ordered.
    private final Set<Long> scanned = new LinkedHashSet<>();

    private ResourceKey<Level> dimension;

    public StashFinder() {
        super("StashFinder", "Points out chunks packed with containers as you travel.", Category.MISC);
        addSettings(minimum, extraTypes);
        searchTags("stash", "base finder", "chests", "loot");
    }

    @Override
    public String getSuffix() {
        return String.valueOf(stashes.size());
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            return;
        }
        if (mc.level.dimension() != dimension) {
            // Chunk coordinates mean something different in each dimension.
            dimension = mc.level.dimension();
            scanned.clear();
        }

        int radius = mc.options.getEffectiveRenderDistance();
        int centerX = mc.player.chunkPosition().x();
        int centerZ = mc.player.chunkPosition().z();
        int budget = SCANS_PER_TICK;

        for (int dx = -radius; dx <= radius && budget > 0; dx++) {
            for (int dz = -radius; dz <= radius && budget > 0; dz++) {
                int x = centerX + dx;
                int z = centerZ + dz;
                long key = ChunkPos.pack(x, z);
                if (!scanned.add(key)) {
                    continue;
                }
                LevelChunk chunk = mc.level.getChunkSource()
                    .getChunk(x, z, ChunkStatus.FULL, false);
                if (chunk == null) {
                    scanned.remove(key);
                    continue;
                }
                budget--;
                check(new ChunkPos(x, z), chunk);
            }
        }
        trim();
    }

    private void trim() {
        if (scanned.size() <= MAX_SCANNED) {
            return;
        }
        Iterator<Long> iterator = scanned.iterator();
        for (int i = 0; i < DROP_PER_TRIM && iterator.hasNext(); i++) {
            iterator.next();
            iterator.remove();
        }
    }

    private void check(ChunkPos pos, LevelChunk chunk) {
        int containers = 0;
        for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
            if (counts(blockEntity)) {
                containers++;
            }
        }
        if (containers < minimum.getInt() || known(pos)) {
            return;
        }
        stashes.add(new Stash(dimension, pos, containers));
        if (stashes.size() > MAX_STASHES) {
            stashes.removeFirst();
        }
        report(pos, containers);
    }

    private boolean known(ChunkPos pos) {
        for (Stash stash : stashes) {
            if (stash.dimension() == dimension && stash.pos().equals(pos)) {
                return true;
            }
        }
        return false;
    }

    private boolean counts(BlockEntity blockEntity) {
        if (blockEntity instanceof ChestBlockEntity) {
            return true;
        }
        if (!extraTypes.isOn()) {
            return false;
        }
        return blockEntity instanceof BarrelBlockEntity || blockEntity instanceof ShulkerBoxBlockEntity;
    }

    private void report(ChunkPos pos, int containers) {
        int x = pos.getMiddleBlockX();
        int z = pos.getMiddleBlockZ();
        int distance = (int) Math.sqrt(mc.player.distanceToSqr(x, mc.player.getY(), z));
        ChatUtil.message("§bStashFinder §7found §f" + containers + "§7 containers at §f"
            + x + " " + z + "§7 in " + dimension.identifier().getPath()
            + " and that is §f" + distance + "§7 blocks away.");
    }
}
