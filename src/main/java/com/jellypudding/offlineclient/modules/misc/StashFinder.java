package com.jellypudding.offlineclient.modules.misc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.KeyPressEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.KeybindSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.BlockEntityTypes;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class StashFinder extends Module {

    public enum Notify { CHAT, TOAST, BOTH }

    // The server is part of the identity. Two servers share their dimension keys.
    private record Stash(String server, String dimension, int x, int z,
                         Map<String, Integer> counts, boolean tracer) {

        int total() {
            int sum = 0;
            for (int count : counts.values()) {
                sum += count;
            }
            return sum;
        }

        boolean sameChunk(String server, String dimension, int x, int z) {
            return this.server.equals(server) && this.dimension.equals(dimension)
                && this.x == x && this.z == z;
        }
    }

    private static final int SCANS_PER_TICK = 4;

    // Ticks before a chunk is looked at again. Chests placed after the first
    // look are picked up on the next.
    private static final int RESCAN_TICKS = 200;

    private static final int MAX_SCANNED = 20_000;

    private static final int DROP_PER_TRIM = 4_000;

    private static final int MAX_STASHES = 1024;

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
        0, 0, 10000, 100, " blocks").min(0).max(100000);
    private final BoolSetting notify = new BoolSetting("Notify",
        "Say something when a stash is found.", true);
    private final EnumSetting<Notify> notifyMode = new EnumSetting<>("Notify mode",
        "Where the message goes.", Notify.BOTH)
        .describe(Notify.CHAT, "A chat line only.")
        .describe(Notify.TOAST, "A toast in the corner only.")
        .describe(Notify.BOTH, "Both a chat line and a toast.")
        .under(notify);
    private final BoolSetting tracers = new BoolSetting("Tracers",
        "Draws a line to every recorded chunk.", true);
    private final ColorSetting tracerColor = new ColorSetting("Tracer colour",
        "Colour of those lines.", 48, 1f, 1f, false)
        .under(tracers);
    private final NumberSetting tracerHide = new NumberSetting("Hide within",
        "A tracer is dropped once you are this close to the chunk.", 16, 1, 50, 1, " blocks")
        .min(1).max(200)
        .under(tracers);
    private final NumberSetting tracerRange = new NumberSetting("Tracer range",
        "Chunks further away than this get no tracer.", 2000, 50, 10000, 50, " blocks")
        .min(10).max(1000000)
        .under(tracers);
    private final BoolSetting columns = new BoolSetting("Chunk columns",
        "Draws four tall lines at the centre of every recorded chunk.", false);
    private final ColorSetting columnColor = new ColorSetting("Column colour",
        "Colour of those lines.", 48, 1f, 1f, false)
        .under(columns);
    private final KeybindSetting clearKey = new KeybindSetting("Clear key",
        "Press to drop every tracer.", KeybindSetting.UNBOUND);

    // Kept for the whole session even whilst turned off and saved to disk.
    private final List<Stash> stashes = new ArrayList<>();

    // When each chunk in the current dimension was last counted. Oldest first.
    private final Map<Long, Integer> scanned = new LinkedHashMap<>();
    private int tick;

    private String dimension = "";
    private boolean loaded;
    private boolean dirty;

    // A new world object means a new server or a new dimension.
    private ClientLevel lastLevel;

    public StashFinder() {
        super("StashFinder", "Points out chunks packed with containers as you travel.", Category.MISC);
        addSettings(minimum, containers, ignoredSupports, minimumDistance, notify, notifyMode,
            tracers, tracerColor, tracerHide, tracerRange, columns, columnColor, clearKey);
        searchTags("stash", "base finder", "chests", "loot");
    }

    @Override
    public String getSuffix() {
        return count(stashes.size());
    }

    @Subscribe
    private void onKeyPress(KeyPressEvent event) {
        if (event.getAction() != GLFW.GLFW_PRESS || mc.gui.screen() != null
            || !clearKey.isBound() || event.getKey() != clearKey.getValue()) {
            return;
        }
        for (int i = 0; i < stashes.size(); i++) {
            Stash stash = stashes.get(i);
            stashes.set(i, new Stash(stash.server(), stash.dimension(), stash.x(), stash.z(),
                stash.counts(), false));
        }
        dirty = true;
        ChatUtil.message("§bStashFinder §7dropped every tracer.");
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            return;
        }
        if (!loaded) {
            loaded = true;
            load();
        }
        if (mc.level != lastLevel) {
            // Chunk coordinates mean something different in every world.
            lastLevel = mc.level;
            dimension = mc.level.dimension().identifier().toString();
            scanned.clear();
        }

        tick++;
        int radius = mc.options.getEffectiveRenderDistance();
        int centerX = mc.player.chunkPosition().x();
        int centerZ = mc.player.chunkPosition().z();
        int budget = SCANS_PER_TICK;

        for (int dx = -radius; dx <= radius && budget > 0; dx++) {
            for (int dz = -radius; dz <= radius && budget > 0; dz++) {
                int x = centerX + dx;
                int z = centerZ + dz;
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
        }
        trim();
        if (dirty) {
            dirty = false;
            save();
        }
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
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
            if (!counts(blockEntity)) {
                continue;
            }
            Identifier id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType());
            counts.merge(id == null ? "unknown" : id.getPath(), 1, Integer::sum);
        }
        int total = 0;
        for (int each : counts.values()) {
            total += each;
        }
        if (total < minimum.getInt()) {
            return;
        }

        String server = serverName();
        Stash fresh = new Stash(server, dimension, middleX, middleZ, counts, true);
        for (int i = 0; i < stashes.size(); i++) {
            Stash old = stashes.get(i);
            if (!old.sameChunk(server, dimension, middleX, middleZ)) {
                continue;
            }
            // Only a changed haul is worth saying again.
            if (old.counts().equals(counts)) {
                return;
            }
            stashes.set(i, new Stash(server, dimension, middleX, middleZ, counts, old.tracer()));
            dirty = true;
            report(fresh);
            return;
        }
        stashes.add(fresh);
        if (stashes.size() > MAX_STASHES) {
            stashes.removeFirst();
        }
        dirty = true;
        report(fresh);
    }

    // A container resting on a listed block belongs to a generated structure.
    private boolean counts(BlockEntity blockEntity) {
        if (!containers.contains(blockEntity.getType())) {
            return false;
        }
        BlockPos below = blockEntity.getBlockPos().below();
        return !ignoredSupports.contains(mc.level.getBlockState(below).getBlock());
    }

    private String serverName() {
        ServerData server = mc.getCurrentServer();
        return server == null ? "" : server.ip;
    }

    private void report(Stash stash) {
        if (!notify.isOn()) {
            return;
        }
        int distance = (int) Math.sqrt(mc.player.distanceToSqr(stash.x(), mc.player.getY(), stash.z()));
        String where = stash.x() + " " + stash.z();
        if (!notifyMode.is(Notify.TOAST)) {
            ChatUtil.message("§bStashFinder §7found §f" + describe(stash) + "§7 at §f" + where
                + "§7 in " + shortName(stash.dimension()) + " and that is §f" + distance
                + "§7 blocks away.");
        }
        if (!notifyMode.is(Notify.CHAT)) {
            mc.gui.toastManager().addToast(new SystemToast(
                SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                Component.literal("Found stash"),
                Component.literal(stash.total() + " containers at " + where)));
        }
    }

    // The types found with a count each such as "12 chest 3 barrel".
    private static String describe(Stash stash) {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, Integer> entry : stash.counts().entrySet()) {
            text.append(text.isEmpty() ? "" : " ").append(entry.getValue()).append(" ")
                .append(entry.getKey().replace('_', ' '));
        }
        return text.toString();
    }

    private static String shortName(String dimension) {
        int colon = dimension.indexOf(':');
        return colon == -1 ? dimension : dimension.substring(colon + 1);
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!inGame() || (!tracers.isOn() && !columns.isOn())) {
            return;
        }
        String server = serverName();
        double eye = mc.player.getEyeY();
        for (Stash stash : stashes) {
            if (!stash.tracer() || !stash.server().equals(server)
                || !stash.dimension().equals(dimension)) {
                continue;
            }
            double away = Math.sqrt(mc.player.distanceToSqr(stash.x(), mc.player.getY(), stash.z()));
            if (away < tracerHide.getValue() || away > tracerRange.getValue()) {
                continue;
            }
            if (tracers.isOn()) {
                event.getBatch().tracer(new Vec3(stash.x() + 0.5, eye, stash.z() + 0.5),
                    tracerColor.getColor(), true);
            }
            if (columns.isOn()) {
                drawColumn(event, stash);
            }
        }
    }

    private void drawColumn(Render3DEvent event, Stash stash) {
        double bottom = mc.level.getMinY();
        double top = mc.level.getMaxY();
        int colour = columnColor.getColor();
        for (int corner = 0; corner < 4; corner++) {
            double x = stash.x() + (corner < 2 ? 0 : 1);
            double z = stash.z() + (corner % 2 == 0 ? 0 : 1);
            event.getBatch().line(new Vec3(x, bottom, z), new Vec3(x, top, z), colour, true);
        }
    }

    private static Path file() {
        return OfflineClient.MC.gameDirectory.toPath().resolve("offlineclient")
            .resolve("stashes.json");
    }

    private void load() {
        Path path = file();
        if (!Files.exists(path)) {
            return;
        }
        try {
            JsonElement root = JsonParser.parseString(Files.readString(path));
            if (!root.isJsonArray()) {
                return;
            }
            for (JsonElement element : root.getAsJsonArray()) {
                JsonObject object = element.getAsJsonObject();
                Map<String, Integer> counts = new LinkedHashMap<>();
                for (Map.Entry<String, JsonElement> entry
                    : object.getAsJsonObject("counts").entrySet()) {
                    counts.put(entry.getKey(), entry.getValue().getAsInt());
                }
                stashes.add(new Stash(object.get("server").getAsString(),
                    object.get("dimension").getAsString(), object.get("x").getAsInt(),
                    object.get("z").getAsInt(), counts, object.get("tracer").getAsBoolean()));
            }
        } catch (Exception error) {
            OfflineClient.LOG.error("Failed to read the stash list", error);
        }
    }

    private void save() {
        JsonArray array = new JsonArray();
        for (Stash stash : stashes) {
            JsonObject object = new JsonObject();
            object.addProperty("server", stash.server());
            object.addProperty("dimension", stash.dimension());
            object.addProperty("x", stash.x());
            object.addProperty("z", stash.z());
            object.addProperty("tracer", stash.tracer());
            JsonObject counts = new JsonObject();
            for (Map.Entry<String, Integer> entry : stash.counts().entrySet()) {
                counts.addProperty(entry.getKey(), entry.getValue());
            }
            object.add("counts", counts);
            array.add(object);
        }
        try {
            Path path = file();
            Files.createDirectories(path.getParent());
            Files.writeString(path, array.toString());
        } catch (IOException error) {
            OfflineClient.LOG.error("Failed to write the stash list", error);
        }
    }
}
