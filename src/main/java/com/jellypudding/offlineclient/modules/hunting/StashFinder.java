package com.jellypudding.offlineclient.modules.hunting;

import com.jellypudding.offlineclient.config.FindLog;
import com.jellypudding.offlineclient.config.FindSource;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.gui.FindsScreen;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.FindLines;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.AlertSound;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.Notice;
import com.jellypudding.offlineclient.util.Tally;
import com.jellypudding.offlineclient.util.WorldWatch;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.BlockEntityTypes;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class StashFinder extends Module implements FindSource {

    // What every find of this module is called in messages and waypoints.
    private static final String KIND = "stash";

    // The fewest chunks looked at each tick. A long view distance needs more.
    private static final int SCANS_PER_TICK = 4;

    // Ticks before a chunk is looked at again. Chests placed after the first
    // look are picked up on the next.
    private static final int RESCAN_TICKS = 200;

    private static final int MAX_SCANNED = 20_000;

    private static final int DROP_PER_TRIM = 4_000;

    private final NumberSetting minimum = new NumberSetting("Minimum",
        "How many containers a chunk needs before it counts as a stash.", 6, 2, 64, 1)
        .min(1);
    private final RegistryListSetting<BlockEntityType<?>> containers = new RegistryListSetting<>(
        "Containers", "Which container blocks are counted. Click to pick them.",
        BuiltInRegistries.BLOCK_ENTITY_TYPE,
        List.of(BlockEntityTypes.BARREL, BlockEntityTypes.BLAST_FURNACE,
            BlockEntityTypes.BREWING_STAND, BlockEntityTypes.CAMPFIRE, BlockEntityTypes.CHEST,
            BlockEntityTypes.CHISELED_BOOKSHELF, BlockEntityTypes.CRAFTER,
            BlockEntityTypes.DISPENSER, BlockEntityTypes.DECORATED_POT, BlockEntityTypes.DROPPER,
            BlockEntityTypes.ENDER_CHEST, BlockEntityTypes.FURNACE, BlockEntityTypes.HOPPER,
            BlockEntityTypes.SHULKER_BOX, BlockEntityTypes.SMOKER,
            BlockEntityTypes.TRAPPED_CHEST));
    private final RegistryListSetting<Block> ignoredSupports = new RegistryListSetting<>(
        "Ignored supports", "A container standing on one of these is not counted. Click to pick them.",
        BuiltInRegistries.BLOCK, List.of(Blocks.TUFF_BRICKS, Blocks.BARREL));
    private final NumberSetting minimumDistance = new NumberSetting("Minimum distance",
        "Chunks closer than this to the world origin are never recorded.",
        0, 0, 10000, 100, " blocks").min(0);
    private final FindLog finds = FindLog.byChunk(this);
    private final Notice notice = new Notice(this, Notice.Where.BOTH);
    private final AlertSound alarm = new AlertSound("Rings when a new stash is found.", SoundEvents.BELL_BLOCK);
    private final FindLines marks = new FindLines(finds, true);
    private final ColorSetting color = new ColorSetting("Colour",
        "Colour of the lines and columns that point at stashes.", 48, 1f, 1f, false);

    // When each chunk in the current dimension was last counted. Oldest first.
    private final Map<Long, Integer> scanned = new LinkedHashMap<>();
    private int tick;
    // Where the walk over the square around you stopped. The next tick carries on from there.
    private int cursor;

    private final WorldWatch world = new WorldWatch();

    public StashFinder() {
        super("StashFinder", "Points out chunks packed with containers as you travel.", Category.HUNTING);
        addSettings(minimum, containers, ignoredSupports, minimumDistance);
        addSettings(notice.settings());
        addSettings(alarm.settings());
        addSettings(marks.settings());
        addSettings(color);
        addSettings(FindsScreen.settingsFor(finds));
        searchTags("stash", "base finder", "chests", "loot");
    }

    @Override
    public FindLog findLog() {
        return finds;
    }

    @Override
    public String getSuffix() {
        return count(finds.count());
    }

    @Override
    protected void onDisable() {
        alarm.stop();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            return;
        }
        if (world.changed()) {
            // Chunk coordinates mean something different in every world.
            scanned.clear();
        }

        tick++;
        int radius = mc.options.getEffectiveRenderDistance();
        int side = 2 * radius + 1;
        int area = side * side;
        int centerX = mc.player.chunkPosition().x();
        int centerZ = mc.player.chunkPosition().z();
        // Enough looks to get round the whole square within the rescan time.
        int budget = Math.max(SCANS_PER_TICK, area / RESCAN_TICKS + 1);

        for (int walked = 0; walked < area && budget > 0; walked++) {
            cursor = (cursor + 1) % area;
            int x = centerX - radius + cursor / side;
            int z = centerZ - radius + cursor % side;
            long key = ChunkPos.pack(x, z);
            Integer last = scanned.get(key);
            if (last != null && tick - last < RESCAN_TICKS) {
                continue;
            }
            LevelChunk chunk = mc.level.getChunkSource()
                .getChunk(x, z, ChunkStatus.FULL, false);
            if (chunk == null) {
                continue;
            }
            // Back to the end of the queue. The trim drops the stalest first.
            scanned.remove(key);
            scanned.put(key, tick);
            budget--;
            check(new ChunkPos(x, z), chunk);
        }
        trim();
    }

    private void trim() {
        if (scanned.size() <= MAX_SCANNED) {
            return;
        }
        Iterator<Long> iterator = scanned.keySet().iterator();
        for (int i = 0; i < DROP_PER_TRIM && iterator.hasNext(); i++) {
            iterator.next();
            iterator.remove();
        }
    }

    private void check(ChunkPos pos, LevelChunk chunk) {
        int middleX = pos.getMiddleBlockX();
        int middleZ = pos.getMiddleBlockZ();
        if (Math.sqrt(middleX * (double) middleX + middleZ * (double) middleZ)
            < minimumDistance.getValue()) {
            return;
        }
        Tally counts = new Tally();
        BlockPos where = null;
        for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
            if (!counts(blockEntity)) {
                continue;
            }
            counts.add(ChatUtil.words(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType())));
            where = nearerMiddle(where, blockEntity.getBlockPos(), middleX, middleZ);
        }
        if (counts.total() < minimum.getInt()) {
            return;
        }

        String detail = counts.words();
        FindLog.Find old = finds.at(where);
        // Only a changed haul is worth saying again.
        if (old != null && old.detail().equals(detail)) {
            return;
        }
        if (finds.add(where, KIND, detail)) {
            alarm.ring();
        }
        notice.tell(KIND, detail, where);
    }

    // The counted container nearest the middle of the chunk stands for the stash. Walking
    // there leads to the containers and the columns rise over them.
    private static BlockPos nearerMiddle(BlockPos best, BlockPos candidate, int middleX, int middleZ) {
        if (best == null) {
            return candidate.immutable();
        }
        return offMiddle(candidate, middleX, middleZ) < offMiddle(best, middleX, middleZ)
            ? candidate.immutable() : best;
    }

    private static int offMiddle(BlockPos pos, int middleX, int middleZ) {
        int dx = pos.getX() - middleX;
        int dz = pos.getZ() - middleZ;
        return dx * dx + dz * dz;
    }

    // A container resting on a listed block belongs to a generated structure.
    private boolean counts(BlockEntity blockEntity) {
        if (!containers.contains(blockEntity.getType())) {
            return false;
        }
        BlockPos below = blockEntity.getBlockPos().below();
        return !ignoredSupports.contains(mc.level.getBlockState(below).getBlock());
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (inGame()) {
            marks.draw(event.getBatch(), find -> color.getColor());
        }
    }
}
