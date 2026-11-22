package com.jellypudding.offlineclient.modules.render;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.render.CaveSurface;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.render.HeightBand;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ChoiceListSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.setting.Setting;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.ChunkScanner;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.NearestCut;
import com.jellypudding.offlineclient.worldgen.OreKind;
import com.jellypudding.offlineclient.worldgen.OreTables;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Every loaded chunk in range is scanned once on a background thread.
// It is scanned again only when the server changes it or the search changes.
public final class BlockEsp extends Module {

    // Built on the scanner thread.
    private record Target(int x, int y, int z, BlockState state) {

        Block block() {
            return state.getBlock();
        }
    }

    private record Constraint(Property<?> property, Set<String> shown) {

        boolean admits(BlockState state) {
            return shown.contains(property.value(state).toString());
        }
    }

    // What the scanner thread looks for. Replaced whole and never changed.
    private record Search(Set<Block> blocks, Map<Block, List<Constraint>> states) {

        static final Search NOTHING = new Search(Set.of(), Map.of());

        boolean wants(BlockState state) {
            Block block = state.getBlock();
            if (!blocks.contains(block)) {
                return false;
            }
            for (Constraint constraint : states.getOrDefault(block, List.of())) {
                if (!constraint.admits(state)) {
                    return false;
                }
            }
            return true;
        }
    }

    private static final Map<Block, Integer> PRESET_COLORS = Map.ofEntries(
        Map.entry(Blocks.SPAWNER, 0xFFC050FF),
        Map.entry(Blocks.TRIAL_SPAWNER, 0xFFC050FF),
        Map.entry(Blocks.END_PORTAL_FRAME, 0xFF60FFC0),
        Map.entry(Blocks.BEACON, 0xFF80D0FF),
        Map.entry(Blocks.ENCHANTING_TABLE, 0xFFE070FF),
        Map.entry(Blocks.SUSPICIOUS_SAND, 0xFFFFE8A0),
        Map.entry(Blocks.SUSPICIOUS_GRAVEL, 0xFFB0A8C8));

    private final NumberSetting range = new NumberSetting("Range",
        "Chunk radius to scan around you.", 4, 1, 8, 1, " chunks").max(ChunkMap.MAX_VIEW_DISTANCE);
    private final NumberSetting limit = new NumberSetting("Limit",
        "Most blocks drawn at once with the nearest first.", 2000, 100, 5000, 100).min(1);
    private final HeightBand band = new HeightBand("blocks");
    private final RegistryListSetting<Block> blocks = new RegistryListSetting<>("Blocks",
        "The blocks to find. Each box fits the block's own shape.", BuiltInRegistries.BLOCK,
        List.of(Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE, Blocks.ANCIENT_DEBRIS,
            Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE,
            Blocks.SPAWNER, Blocks.TRIAL_SPAWNER, Blocks.END_PORTAL_FRAME,
            Blocks.BEACON, Blocks.ENCHANTING_TABLE,
            Blocks.SUSPICIOUS_SAND, Blocks.SUSPICIOUS_GRAVEL));
    private final BoolSetting automatic = new BoolSetting("Automatic colours",
        "Blocks with no look of their own take a colour worked out from the block.", true);
    private final BoxStyle defaultStyle = new BoxStyle("Default", BoxStyle.Shape.LINES, 167);
    private final BoolSetting defaultTracer = new BoolSetting("Default tracer",
        "Draws a line to every group of blocks with no look of its own.", false);
    private final ColorSetting defaultTracerColor = new ColorSetting("Default tracer colour",
        "Colour of those lines.", 167, 1f, 1f, false)
        .under(defaultTracer, () -> defaultTracer.isOn() && !automatic.isOn());
    private final BoolSetting caves = new BoolSetting("Caves",
        "Shows cave air as one surface. Old caves and dungeons and mineshafts and strongholds hold it."
            + " Caves made by 26.3 hold plain air.", false);
    private final NumberSetting caveLimit = new NumberSetting("Cave limit",
        "Most cave air blocks drawn at once with the nearest chunks first.", 10000, 1000, 50000, 1000).min(1)
        .under(caves);
    private final BoxStyle caveStyle = new BoxStyle("Cave", BoxStyle.Shape.SIDES, 200).under(caves);

    // Kept after a block leaves the list.
    private final Map<Block, BlockLook> looks = new LinkedHashMap<>();

    // One saved entry. The rows are rebuilt from it after the config has loaded.
    private final Setting<Void> store = new Setting<Void>("Block looks",
        "Where the look of each block is kept.", null) {

        @Override
        public JsonElement toJson() {
            JsonObject out = new JsonObject();
            for (Map.Entry<Block, BlockLook> entry : looks.entrySet()) {
                if (entry.getValue().isKept()) {
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
    }.internal();

    // Read from the scanner thread and replaced whole.
    private volatile Search search = Search.NOTHING;
    private HeightBand.Rows scannedRows;

    private final ChunkScanner<Target> scanner = new ChunkScanner<>();
    // Set from the settings screen and read on the next tick.
    private volatile boolean searchChanged = true;
    private final NearestCut<Target> drawn = new NearestCut<>(BlockEsp::distanceSqr);
    // Cave air has a scan and a limit of its own and never pushes a listed block out.
    private final CaveSurface caveSurface = new CaveSurface();
    private boolean cavesShown;

    private final Long2ObjectOpenHashMap<Block> blockAt = new Long2ObjectOpenHashMap<>();
    private final Long2IntOpenHashMap groupOf = new Long2IntOpenHashMap();
    private final List<Vec3> groupCenters = new ArrayList<>();
    // The drawn blocks that fill their whole space. Only these join with their neighbours.
    private final Long2ObjectOpenHashMap<Block> fullAt = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<AABB> fitted = new Long2ObjectOpenHashMap<>();

    public BlockEsp() {
        super("BlockESP", "Highlights chosen blocks through walls.", Category.RENDER);
        addSettings(range, limit);
        addSettings(band.settings());
        addSettings(blocks, automatic);
        addSettings(defaultStyle.settings());
        addSettings(defaultTracer, defaultTracerColor, store, caves, caveLimit);
        addSettings(caveStyle.settings());
        searchTags("search", "block search", "ore esp", "archaeology", "suspicious sand", "suspicious gravel",
            "cave finder", "cave air", "block state");
        blocks.onChange(() -> searchChanged = true);
        groupOf.defaultReturnValue(-1);
    }

    // The per block rows are built as blocks are picked and follow the list.
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
    public String getSuffix() {
        return count(drawn.result().size());
    }

    @Override
    protected void onEnable() {
        searchChanged = true;
        reset();
    }

    @Override
    protected void onDisable() {
        reset();
    }

    private void reset() {
        resetBlocks();
        caveSurface.reset();
    }

    private void resetBlocks() {
        scanner.reset();
        drawn.clear();
        blockAt.clear();
        groupOf.clear();
        groupCenters.clear();
        fullAt.clear();
        fitted.clear();
    }

    private BlockLook lookFor(Block block) {
        return looks.computeIfAbsent(block, BlockLook::new);
    }

    // Cave air on the list is left to Caves whilst that is on.
    private boolean snapshot() {
        Set<Block> listed = new HashSet<>(blocks.resolved());
        if (caves.isOn()) {
            listed.remove(Blocks.CAVE_AIR);
        }
        Map<Block, List<Constraint>> states = new HashMap<>();
        for (Block block : listed) {
            List<Constraint> only = lookFor(block).constraints();
            if (!only.isEmpty()) {
                states.put(block, List.copyOf(only));
            }
        }
        Search next = new Search(Set.copyOf(listed), Map.copyOf(states));
        boolean differs = !next.equals(search);
        if (differs) {
            search = next;
        }
        return differs;
    }

    // A hue derived from the id stays stable between sessions.
    private static int autoColor(Block block) {
        OreKind ore = OreTables.kindOf(block);
        if (ore != null) {
            return ore.colour();
        }
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
        if (caves.isOn()) {
            caveSurface.markChanged(event.getPacket());
        }
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (caves.isOn() != cavesShown) {
            cavesShown = caves.isOn();
            caveSurface.reset();
            searchChanged = true;
        }
        if (searchChanged) {
            searchChanged = false;
            if (snapshot()) {
                resetBlocks();
            }
        }
        HeightBand.Rows rows = band.rows();
        if (!rows.equals(scannedRows)) {
            scannedRows = rows;
            reset();
        }

        Search query = search;
        scanner.update(range.getInt(), (view, out) -> view.forEachMatching(
            Math.max(view.minY(), rows.from()), Math.min(view.maxY(), rows.to()), query::wants,
            (x, y, z, state) -> out.add(new Target(x, y, z, state))));

        // The nearest blocks win when there are more than the limit allows.
        if (drawn.update(scanner.results(), limit.getInt())) {
            regroup(drawn.result());
        }
        if (cavesShown) {
            caveSurface.update(range.getInt(), rows, caveLimit.getInt());
        }
    }

    private static double distanceSqr(Target target, Vec3 eye) {
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
        fullAt.clear();
        fitted.clear();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (Target target : targets) {
            long at = key(target.x(), target.y(), target.z());
            blockAt.put(at, target.block());
            VoxelShape shape = target.state().getShape(mc.level, cursor.set(target.x(), target.y(), target.z()));
            if (shape.isEmpty() || Block.isShapeFullBlock(shape)) {
                fullAt.put(at, target.block());
            } else {
                fitted.put(at, shape.bounds().move(target.x(), target.y(), target.z()));
            }
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

    private static AABB cell(Target target) {
        return new AABB(target.x(), target.y(), target.z(), target.x() + 1, target.y() + 1, target.z() + 1);
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        DrawBatch batch = event.getBatch();
        boolean[] traced = new boolean[groupCenters.size()];
        for (Target target : drawn.result()) {
            BlockLook look = looks.get(target.block());
            boolean own = look != null && look.isOwn();
            BoxStyle style = own ? look.style() : defaultStyle;
            long at = key(target.x(), target.y(), target.z());
            AABB fit = fitted.get(at);
            AABB box = fit != null ? fit : cell(target);
            int hidden = fit != null ? 0 : DrawBatch.sharedSides(fullAt, at, target.block());
            if (own || !automatic.isOn()) {
                style.drawJoined(batch, box, hidden, true);
            } else {
                int color = autoColor(target.block());
                style.drawJoined(batch, box, hidden, color, color, true);
            }

            boolean wantsTracer = own ? look.tracesLines() : defaultTracer.isOn();
            int group = groupOf.get(at);
            if (!wantsTracer || group < 0 || group >= traced.length || traced[group]) {
                continue;
            }
            traced[group] = true;
            batch.tracer(groupCenters.get(group), tracerColor(look, own, target.block()), true);
        }
        if (cavesShown) {
            caveSurface.draw(batch, caveStyle);
        }
    }

    private int tracerColor(BlockLook look, boolean own, Block block) {
        if (own) {
            return look.tracerColor();
        }
        return automatic.isOn() ? autoColor(block) : defaultTracerColor.getColor();
    }

    private static List<String> stateNames(Block block) {
        List<String> names = new ArrayList<>();
        for (Property<?> property : block.getStateDefinition().getProperties()) {
            property.getAllValues().forEach(value -> names.add(value.toString()));
        }
        return names;
    }

    private final class BlockLook {

        private final Block block;
        private final BoolSetting own;
        private final BoxStyle style;
        private final BoolSetting tracer;
        private final ColorSetting tracerColor;
        private final ChoiceListSetting states;

        private BlockLook(Block block) {
            this.block = block;
            String label = sentence(BuiltInRegistries.BLOCK.getKey(block).getPath());
            own = new BoolSetting(label, "Gives these blocks a look of their own instead of the default.", false)
                .visibleWhen(() -> blocks.contains(block));
            style = new BoxStyle(label, BoxStyle.Shape.LINES, 167).under(own);
            tracer = new BoolSetting(label + " tracer",
                "Draws a line to every group of these.", false).under(own);
            tracerColor = new ColorSetting(label + " tracer colour",
                "Colour of those lines.", 167, 1f, 1f, false)
                .under(tracer, () -> own.isOn() && tracer.isOn());
            Collection<String> names = stateNames(block);
            states = new ChoiceListSetting(label + " states",
                "Shows these blocks only in the picked states. A property with nothing picked lets every"
                    + " value through. Click to pick them.", () -> names)
                .onChange(() -> searchChanged = true)
                .visibleWhen(() -> !names.isEmpty() && blocks.contains(block));
        }

        private boolean isOwn() {
            return own.isOn();
        }

        private boolean isKept() {
            return own.isOn() || !states.getValue().isEmpty();
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

        private List<Constraint> constraints() {
            Map<String, Set<String>> picked = new LinkedHashMap<>();
            for (String pick : states.getValue()) {
                int split = pick.indexOf('=');
                if (split > 0) {
                    picked.computeIfAbsent(pick.substring(0, split), name -> new HashSet<>()).add(pick);
                }
            }
            List<Constraint> out = new ArrayList<>();
            for (Map.Entry<String, Set<String>> entry : picked.entrySet()) {
                Property<?> property = block.getStateDefinition().getProperty(entry.getKey());
                if (property != null) {
                    out.add(new Constraint(property, Set.copyOf(entry.getValue())));
                }
            }
            return out;
        }

        private void addTo(List<Setting<?>> out) {
            out.add(own);
            for (Setting<?> setting : style.settings()) {
                out.add(setting);
            }
            out.add(tracer);
            out.add(tracerColor);
            out.add(states);
        }

        private JsonElement toJson() {
            JsonObject out = new JsonObject();
            out.addProperty("own", own.isOn());
            out.add("style", style.toJson());
            out.add("tracer", tracer.toJson());
            out.add("tracerColour", tracerColor.toJson());
            out.add("states", states.toJson());
            return out;
        }

        private void fromJson(JsonElement json) {
            if (!json.isJsonObject()) {
                return;
            }
            JsonObject o = json.getAsJsonObject();
            own.setValue(!o.has("own") || o.get("own").getAsBoolean());
            if (o.has("style")) {
                style.fromJson(o.get("style"));
            }
            if (o.has("tracer")) {
                tracer.fromJson(o.get("tracer"));
            }
            if (o.has("tracerColour")) {
                tracerColor.fromJson(o.get("tracerColour"));
            }
            if (o.has("states")) {
                states.fromJson(o.get("states"));
            }
        }
    }

    private static String sentence(String id) {
        return ChatUtil.capitalised(id.replace('_', ' '));
    }
}
