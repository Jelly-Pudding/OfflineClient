package com.jellypudding.offlineclient.modules.render;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.setting.Setting;
import com.jellypudding.offlineclient.util.ChunkScanner;
import com.jellypudding.offlineclient.util.ColorUtil;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

// Every loaded chunk in range is scanned once on a background thread.
// It is scanned again only when the server sends a change for it.
public final class Search extends Module {

    private static final Direction[] SIDES = Direction.values();

    // The block found and where it stands. Built on the scanner thread.
    private record Target(int x, int y, int z, Block block) {
    }

    // Hand tuned colours for the usual targets.
    private static final Map<Block, Integer> PRESET_COLORS = Map.ofEntries(
        Map.entry(Blocks.DIAMOND_ORE, 0xFF40E0FF),
        Map.entry(Blocks.DEEPSLATE_DIAMOND_ORE, 0xFF40E0FF),
        Map.entry(Blocks.ANCIENT_DEBRIS, 0xFFB08060),
        Map.entry(Blocks.EMERALD_ORE, 0xFF40FF80),
        Map.entry(Blocks.DEEPSLATE_EMERALD_ORE, 0xFF40FF80),
        Map.entry(Blocks.GOLD_ORE, 0xFFFFD040),
        Map.entry(Blocks.DEEPSLATE_GOLD_ORE, 0xFFFFD040),
        Map.entry(Blocks.NETHER_GOLD_ORE, 0xFFFFD040),
        Map.entry(Blocks.IRON_ORE, 0xFFD8C0A8),
        Map.entry(Blocks.DEEPSLATE_IRON_ORE, 0xFFD8C0A8),
        Map.entry(Blocks.REDSTONE_ORE, 0xFFFF4040),
        Map.entry(Blocks.DEEPSLATE_REDSTONE_ORE, 0xFFFF4040),
        Map.entry(Blocks.LAPIS_ORE, 0xFF4060FF),
        Map.entry(Blocks.DEEPSLATE_LAPIS_ORE, 0xFF4060FF),
        Map.entry(Blocks.COAL_ORE, 0xFF909090),
        Map.entry(Blocks.DEEPSLATE_COAL_ORE, 0xFF909090),
        Map.entry(Blocks.COPPER_ORE, 0xFFFF8050),
        Map.entry(Blocks.DEEPSLATE_COPPER_ORE, 0xFFFF8050),
        Map.entry(Blocks.NETHER_QUARTZ_ORE, 0xFFF0F0E0),
        Map.entry(Blocks.SPAWNER, 0xFFC050FF),
        Map.entry(Blocks.TRIAL_SPAWNER, 0xFFC050FF),
        Map.entry(Blocks.END_PORTAL_FRAME, 0xFF60FFC0),
        Map.entry(Blocks.BEACON, 0xFF80D0FF),
        Map.entry(Blocks.ENCHANTING_TABLE, 0xFFE070FF));

    private final NumberSetting range = new NumberSetting("Range",
        "Chunk radius to scan around you.", 4, 1, 8, 1, " chunks").max(16);
    private final NumberSetting limit = new NumberSetting("Limit",
        "Most blocks drawn at once with the nearest first.", 2000, 100, 5000, 100).min(1);
    private final RegistryListSetting<Block> blocks = new RegistryListSetting<>("Blocks",
        "The blocks to find.", BuiltInRegistries.BLOCK,
        List.of(Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE, Blocks.ANCIENT_DEBRIS,
            Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE,
            Blocks.SPAWNER, Blocks.TRIAL_SPAWNER, Blocks.END_PORTAL_FRAME,
            Blocks.BEACON, Blocks.ENCHANTING_TABLE));
    private final BoolSetting automatic = new BoolSetting("Automatic colours",
        "Blocks with no look of their own take a colour worked out from the block.", true);
    private final BoxStyle defaultStyle = new BoxStyle("Default", BoxStyle.Shape.LINES, 167);
    private final BoolSetting defaultTracer = new BoolSetting("Default tracer",
        "Draw a line to every group of blocks with no look of its own.", false);
    private final ColorSetting defaultTracerColor = new ColorSetting("Default tracer colour",
        "Colour of those lines.", 167, 1f, 1f, false)
        .under(defaultTracer, () -> defaultTracer.isOn() && !automatic.isOn());

    // The look of every block that has one. Kept after a block leaves the list.
    private final Map<Block, BlockLook> looks = new LinkedHashMap<>();

    // One saved entry so the rows can be rebuilt after the config has loaded.
    private final Setting<Void> store = new Setting<Void>("Block looks",
        "Where the look of each block is kept.", null) {

        @Override
        public JsonElement toJson() {
            JsonObject out = new JsonObject();
            for (Map.Entry<Block, BlockLook> entry : looks.entrySet()) {
                if (entry.getValue().isOwn()) {
                    out.add(BuiltInRegistries.BLOCK.getKey(entry.getKey()).toString(),
                        entry.getValue().toJson());
                }
            }
            return out;
        }

        @Override
        public void fromJson(JsonElement json) {
            if (!json.isJsonObject()) {
                return;
            }
            for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject().entrySet()) {
                Identifier id = Identifier.tryParse(entry.getKey());
                if (id != null && BuiltInRegistries.BLOCK.containsKey(id)) {
                    lookFor(BuiltInRegistries.BLOCK.getValue(id)).fromJson(entry.getValue());
                }
            }
        }

        @Override
        public void reset() {
            looks.clear();
        }
    }.visibleWhen(() -> false);

    // Read from the scanner thread and replaced whole.
    private volatile Set<Block> wanted = Set.of();

    private final ChunkScanner<Target> scanner = new ChunkScanner<>();
    // Set from the settings screen and read on the next tick.
    private volatile boolean listChanged = true;
    private List<Target> drawn = List.of();

    // The scan list and the chunk the last draw list was cut for.
    private List<Target> cutFrom;
    private long cutChunk;

    // Where every drawn block stands and which run of touching blocks it belongs to.
    private final Long2ObjectOpenHashMap<Block> blockAt = new Long2ObjectOpenHashMap<>();
    private final Long2IntOpenHashMap groupOf = new Long2IntOpenHashMap();
    private final List<Vec3> groupCenters = new ArrayList<>();

    public Search() {
        super("Search", "Highlights chosen blocks through walls.", Category.RENDER);
        addSettings(range, limit, blocks, automatic);
        addSettings(defaultStyle.settings());
        addSettings(defaultTracer, defaultTracerColor, store);
        searchTags("block esp", "ore esp");
        blocks.onChange(() -> listChanged = true);
        groupOf.defaultReturnValue(-1);
    }

    // The per block rows are built as blocks are picked so they follow the list.
    @Override
    public List<Setting<?>> getSettings() {
        for (Block block : blocks.resolved()) {
            lookFor(block);
        }
        List<Setting<?>> all = new ArrayList<>(super.getSettings());
        for (BlockLook look : looks.values()) {
            look.addTo(all);
        }
        return all;
    }

    @Override
    public Setting<?> getSetting(String settingName) {
        String asked = settingName.replace(" ", "").toLowerCase(Locale.ROOT);
        for (Setting<?> setting : getSettings()) {
            if (setting.getName().replace(" ", "").toLowerCase(Locale.ROOT).equals(asked)) {
                return setting;
            }
        }
        return null;
    }

    @Override
    public String getSuffix() {
        return count(drawn.size());
    }

    @Override
    protected void onEnable() {
        listChanged = true;
        reset();
    }

    @Override
    protected void onDisable() {
        reset();
    }

    private void reset() {
        scanner.reset();
        drawn = List.of();
        cutFrom = null;
        blockAt.clear();
        groupOf.clear();
        groupCenters.clear();
    }

    private BlockLook lookFor(Block block) {
        return looks.computeIfAbsent(block, BlockLook::new);
    }

    // True if the wanted blocks changed.
    private boolean snapshot() {
        Set<Block> next = new HashSet<>(blocks.resolved());
        for (Block block : next) {
            lookFor(block);
        }
        boolean differs = !next.equals(wanted);
        if (differs) {
            wanted = Set.copyOf(next);
        }
        return differs;
    }

    // A hue derived from the id stays stable between sessions.
    private static int autoColor(Block block) {
        Integer preset = PRESET_COLORS.get(block);
        if (preset != null) {
            return preset;
        }
        int hue = Math.floorMod(BuiltInRegistries.BLOCK.getKey(block).toString().hashCode(), 360);
        return ColorUtil.hsv(hue, 0.65f, 1f);
    }

    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        scanner.markChanged(event.getPacket());
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            return;
        }
        if (listChanged) {
            listChanged = false;
            if (snapshot()) {
                reset();
            }
        }

        Set<Block> query = wanted;
        scanner.update(range.getInt(), (view, out) -> view.forEachMatching(
            state -> query.contains(state.getBlock()),
            (x, y, z, state) -> out.add(new Target(x, y, z, state.getBlock()))));

        rebuildDrawList();
    }

    // The nearest blocks win when there are more than the limit allows.
    private void rebuildDrawList() {
        List<Target> all = scanner.results();
        int max = limit.getInt();
        long chunk = ChunkPos.pack(mc.player.blockPosition());
        if (all == cutFrom && chunk == cutChunk && drawn.size() == Math.min(max, all.size())) {
            return;
        }
        cutFrom = all;
        cutChunk = chunk;
        if (all.size() > max) {
            Vec3 eye = mc.player.getEyePosition();
            all = new ArrayList<>(all);
            all.sort((a, b) -> Double.compare(distanceSqr(eye, a), distanceSqr(eye, b)));
            all = all.subList(0, max);
        }
        drawn = all;
        regroup(drawn);
    }

    private static double distanceSqr(Vec3 eye, Target target) {
        double dx = target.x() + 0.5 - eye.x;
        double dy = target.y() + 0.5 - eye.y;
        double dz = target.z() + 0.5 - eye.z;
        return dx * dx + dy * dy + dz * dz;
    }

    // Blocks of one kind that touch even at a corner count as one find.
    private void regroup(List<Target> targets) {
        blockAt.clear();
        groupOf.clear();
        groupCenters.clear();
        for (Target target : targets) {
            blockAt.put(key(target.x(), target.y(), target.z()), target.block());
        }
        LongArrayList pending = new LongArrayList();
        for (Target target : targets) {
            long start = key(target.x(), target.y(), target.z());
            if (groupOf.get(start) != -1) {
                continue;
            }
            int id = groupCenters.size();
            groupOf.put(start, id);
            pending.add(start);
            int minX = target.x();
            int minY = target.y();
            int minZ = target.z();
            int maxX = minX;
            int maxY = minY;
            int maxZ = minZ;
            while (!pending.isEmpty()) {
                long pos = pending.removeLong(pending.size() - 1);
                int x = BlockPos.getX(pos);
                int y = BlockPos.getY(pos);
                int z = BlockPos.getZ(pos);
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                minZ = Math.min(minZ, z);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
                maxZ = Math.max(maxZ, z);
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            long next = key(x + dx, y + dy, z + dz);
                            if (next != pos && blockAt.get(next) == target.block()
                                && groupOf.get(next) == -1) {
                                groupOf.put(next, id);
                                pending.add(next);
                            }
                        }
                    }
                }
            }
            groupCenters.add(new Vec3((minX + maxX + 1) / 2.0,
                (minY + maxY + 1) / 2.0, (minZ + maxZ + 1) / 2.0));
        }
    }

    private static long key(int x, int y, int z) {
        return BlockPos.asLong(x, y, z);
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        DrawBatch batch = event.getBatch();
        boolean[] traced = new boolean[groupCenters.size()];
        for (Target target : drawn) {
            BlockLook look = looks.get(target.block());
            boolean own = look != null && look.isOwn();
            BoxStyle style = own ? look.style() : defaultStyle;
            AABB box = new AABB(target.x(), target.y(), target.z(),
                target.x() + 1, target.y() + 1, target.z() + 1);
            int hidden = sharedSides(target);
            if (own || !automatic.isOn()) {
                style.drawJoined(batch, box, hidden, true);
            } else {
                int color = autoColor(target.block());
                style.drawJoined(batch, box, hidden, color, color, true);
            }

            boolean wantsTracer = own ? look.tracesLines() : defaultTracer.isOn();
            int group = groupOf.get(key(target.x(), target.y(), target.z()));
            if (!wantsTracer || group < 0 || group >= traced.length || traced[group]) {
                continue;
            }
            traced[group] = true;
            batch.tracer(groupCenters.get(group), tracerColor(look, own, target.block()), true);
        }
    }

    private int tracerColor(BlockLook look, boolean own, Block block) {
        if (own) {
            return look.tracerColor();
        }
        return automatic.isOn() ? autoColor(block) : defaultTracerColor.getColor();
    }

    // A bit for every side another block of the same kind is pressed against.
    private int sharedSides(Target target) {
        long key = key(target.x(), target.y(), target.z());
        int hidden = 0;
        for (Direction side : SIDES) {
            if (blockAt.get(BlockPos.offset(key, side)) == target.block()) {
                hidden |= DrawBatch.sideBit(side);
            }
        }
        return hidden;
    }

    // The look of one block. Its own rows show whilst the toggle is ticked.
    private final class BlockLook {

        private final BoolSetting own;
        private final BoxStyle style;
        private final BoolSetting tracer;
        private final ColorSetting tracerColor;

        private BlockLook(Block block) {
            String label = sentence(BuiltInRegistries.BLOCK.getKey(block).getPath());
            own = new BoolSetting(label, "Give " + label.toLowerCase(Locale.ROOT)
                + " a look of its own instead of the default.", false)
                .visibleWhen(() -> blocks.contains(block));
            style = new BoxStyle(label, BoxStyle.Shape.LINES, 167).under(own);
            tracer = new BoolSetting(label + " tracer",
                "Draw a line to every group of these.", false).under(own);
            tracerColor = new ColorSetting(label + " tracer colour",
                "Colour of those lines.", 167, 1f, 1f, false)
                .under(tracer, () -> own.isOn() && tracer.isOn());
        }

        private boolean isOwn() {
            return own.isOn();
        }

        private BoxStyle style() {
            return style;
        }

        private boolean tracesLines() {
            return tracer.isOn();
        }

        private int tracerColor() {
            return tracerColor.getColor();
        }

        private void addTo(List<Setting<?>> out) {
            out.add(own);
            for (Setting<?> setting : style.settings()) {
                out.add(setting);
            }
            out.add(tracer);
            out.add(tracerColor);
        }

        private JsonElement toJson() {
            JsonObject out = new JsonObject();
            JsonArray rows = new JsonArray();
            for (Setting<?> setting : style.settings()) {
                rows.add(setting.toJson());
            }
            out.add("style", rows);
            out.add("tracer", tracer.toJson());
            out.add("tracerColour", tracerColor.toJson());
            return out;
        }

        private void fromJson(JsonElement json) {
            if (!json.isJsonObject()) {
                return;
            }
            JsonObject o = json.getAsJsonObject();
            own.setValue(true);
            if (o.has("style") && o.get("style").isJsonArray()) {
                JsonArray rows = o.getAsJsonArray("style");
                Setting<?>[] settings = style.settings();
                for (int i = 0; i < settings.length && i < rows.size(); i++) {
                    settings[i].fromJson(rows.get(i));
                }
            }
            if (o.has("tracer")) {
                tracer.fromJson(o.get("tracer"));
            }
            if (o.has("tracerColour")) {
                tracerColor.fromJson(o.get("tracerColour"));
            }
        }
    }

    // A block id such as deepslate_diamond_ore reads as a sentence case name.
    private static String sentence(String id) {
        if (id.isEmpty()) {
            return id;
        }
        String words = id.replace('_', ' ');
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }
}
