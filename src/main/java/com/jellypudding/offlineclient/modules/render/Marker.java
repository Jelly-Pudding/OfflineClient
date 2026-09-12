package com.jellypudding.offlineclient.modules.render;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.KeyPressEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.KeybindSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.Setting;
import com.jellypudding.offlineclient.setting.TextSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// Shapes you place yourself for building. Every one is saved with the module.
public final class Marker extends Module {

    public enum Shape { CUBOID, SPHERE }

    public enum World { OVERWORLD, NETHER, END }

    // A sphere slice is one block tall. Its blocks only ever touch sideways.
    private static final Direction[] AROUND = {
        Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    private final TextSetting newName = new TextSetting("New name",
        "Name for the next marker you add.", "Marker");
    private final EnumSetting<Shape> newShape = new EnumSetting<>("New shape",
        "Which shape the next marker takes.", Shape.CUBOID)
        .describe(Shape.CUBOID, "A box between two corners.")
        .describe(Shape.SPHERE, "One flat slice of a ball of blocks.");
    private final KeybindSetting addKey = new KeybindSetting("Add",
        "Press to add a marker where you are looking.", GLFW.GLFW_KEY_BACKSLASH);
    private final KeybindSetting nextLayerKey = new KeybindSetting("Next layer",
        "Press to raise the drawn slice of every sphere marker.", GLFW.GLFW_KEY_RIGHT_BRACKET);
    private final KeybindSetting prevLayerKey = new KeybindSetting("Previous layer",
        "Press to lower the drawn slice of every sphere marker.", GLFW.GLFW_KEY_LEFT_BRACKET);
    private final BoolSetting throughWalls = new BoolSetting("Through walls",
        "Show the markers behind blocks.", true);

    private final List<Entry> entries = new ArrayList<>();

    // One saved entry. The rows are rebuilt from it after the config has loaded.
    private final Setting<Void> store = new Setting<Void>("Markers",
        "Where every marker is kept.", null) {

        @Override
        public JsonElement toJson() {
            JsonArray out = new JsonArray();
            for (Entry entry : entries) {
                if (!entry.isDropped()) {
                    out.add(entry.toJson());
                }
            }
            return out;
        }

        @Override
        public void fromJson(JsonElement json) {
            if (!json.isJsonArray()) {
                return;
            }
            entries.clear();
            for (JsonElement element : json.getAsJsonArray()) {
                Entry entry = Entry.read(element);
                if (entry != null) {
                    entries.add(entry);
                }
            }
        }

        @Override
        public void reset() {
            entries.clear();
        }
    }.visibleWhen(() -> false);

    public Marker() {
        super("Marker", "Draws shapes you place yourself to build against.", Category.RENDER);
        addSettings(newName, newShape, addKey, nextLayerKey, prevLayerKey, throughWalls, store);
        searchTags("build", "shape", "sphere", "cuboid");
    }

    // The rows of every marker follow the list. One can be added at any time.
    @Override
    public List<Setting<?>> getSettings() {
        List<Setting<?>> all = new ArrayList<>(super.getSettings());
        for (Entry entry : entries) {
            if (!entry.isDropped()) {
                entry.addTo(all);
            }
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
        return count(entries.size());
    }

    @Subscribe
    private void onTick(TickEvent event) {
        entries.removeIf(Entry::isDropped);
    }

    @Subscribe
    private void onKeyPress(KeyPressEvent event) {
        if (event.getAction() != GLFW.GLFW_PRESS || mc.gui.screen() != null || !inGame()) {
            return;
        }
        int key = event.getKey();
        if (addKey.isBound() && key == addKey.getValue()) {
            add();
        } else if (nextLayerKey.isBound() && key == nextLayerKey.getValue()) {
            stepLayer(1);
        } else if (prevLayerKey.isBound() && key == prevLayerKey.getValue()) {
            stepLayer(-1);
        }
    }

    // The block you are looking at or the one you stand on.
    private void add() {
        BlockPos pos = mc.player.blockPosition();
        if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            pos = hit.getBlockPos();
        }
        String label = freeName(newName.getValue().trim());
        entries.add(new Entry(label, newShape.getValue(), worldOf(mc.level.dimension()), pos));
        ChatUtil.message("Added marker " + label + ".");
    }

    // Two markers with one name would share their rows.
    private String freeName(String wanted) {
        String base = wanted.isEmpty() ? "Marker" : wanted;
        String label = base;
        int number = 1;
        while (taken(label)) {
            number++;
            label = base + " " + number;
        }
        return label;
    }

    // A marker named after one of the rows above would fight it for the name.
    private boolean taken(String label) {
        for (Setting<?> setting : super.getSettings()) {
            if (setting.getName().equalsIgnoreCase(label)) {
                return true;
            }
        }
        for (Entry entry : entries) {
            if (entry.label().equalsIgnoreCase(label)) {
                return true;
            }
        }
        return false;
    }

    private void stepLayer(int step) {
        for (Entry entry : entries) {
            entry.stepLayer(step);
        }
    }

    private static World worldOf(ResourceKey<Level> key) {
        if (key == Level.NETHER) {
            return World.NETHER;
        }
        return key == Level.END ? World.END : World.OVERWORLD;
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!inGame()) {
            return;
        }
        World here = worldOf(mc.level.dimension());
        boolean through = throughWalls.isOn();
        for (Entry entry : entries) {
            entry.draw(event.getBatch(), here, through);
        }
    }

    // One marker and the rows that shape it.
    private static final class Entry {

        private final String label;
        private final Shape shape;
        private final BoolSetting active;
        private final EnumSetting<World> world;
        private final TextSetting first;
        private final TextSetting second;
        private final NumberSetting radius;
        private final NumberSetting layer;
        private final BoxStyle style;
        private final BoolSetting drop;

        // The ring of the current slice and the numbers it was worked out from.
        private List<BlockPos> ring = List.of();
        private String ringKey = "";
        private final LongOpenHashSet ringKeys = new LongOpenHashSet();

        private Entry(String label, Shape shape, World world, BlockPos pos) {
            this.label = label;
            this.shape = shape;
            boolean sphere = shape == Shape.SPHERE;
            active = new BoolSetting(label, "Draw this marker.", true);
            this.world = new EnumSetting<>(label + " dimension",
                "The world this marker belongs to.", world).under(active);
            first = new TextSetting(label + (sphere ? " centre" : " corner one"),
                "Three numbers for x and y and z.", text(pos)).under(active);
            second = sphere ? null : new TextSetting(label + " corner two",
                "Three numbers for x and y and z.", text(pos)).under(active);
            radius = sphere ? new NumberSetting(label + " radius",
                "How wide the ball is.", 20, 1, 64, 1, " blocks").min(1).max(256).under(active) : null;
            layer = sphere ? new NumberSetting(label + " layer",
                "Which slice of the ball is drawn counting up from the bottom.",
                20, 0, 40, 1).min(0).max(512).under(active) : null;
            style = new BoxStyle(label, BoxStyle.Shape.BOTH, 217).under(active);
            drop = new BoolSetting(label + " remove", "Tick to delete this marker.", false)
                .under(active);
        }

        private String label() {
            return label;
        }

        private boolean isDropped() {
            return drop.isOn();
        }

        private void addTo(List<Setting<?>> out) {
            out.add(active);
            out.add(world);
            out.add(first);
            if (second != null) {
                out.add(second);
            }
            if (radius != null) {
                out.add(radius);
                out.add(layer);
            }
            for (Setting<?> setting : style.settings()) {
                out.add(setting);
            }
            out.add(drop);
        }

        private void stepLayer(int step) {
            if (layer == null || !active.isOn()) {
                return;
            }
            layer.setValue((double) Math.clamp(layer.getInt() + step, 0, 2 * radius.getInt()));
        }

        private void draw(DrawBatch batch, World here, boolean through) {
            if (!active.isOn() || world.getValue() != here) {
                return;
            }
            if (shape == Shape.CUBOID) {
                drawCuboid(batch, through);
            } else {
                drawSphere(batch, through);
            }
        }

        private void drawCuboid(DrawBatch batch, boolean through) {
            BlockPos one = parse(first.getValue());
            BlockPos two = parse(second.getValue());
            if (one == null || two == null) {
                return;
            }
            AABB box = new AABB(Math.min(one.getX(), two.getX()), Math.min(one.getY(), two.getY()),
                Math.min(one.getZ(), two.getZ()), Math.max(one.getX(), two.getX()) + 1,
                Math.max(one.getY(), two.getY()) + 1, Math.max(one.getZ(), two.getZ()) + 1);
            style.draw(batch, box, through);
        }

        private void drawSphere(DrawBatch batch, boolean through) {
            BlockPos centre = parse(first.getValue());
            if (centre == null) {
                return;
            }
            rebuildRing(centre);
            for (BlockPos pos : ring) {
                int hidden = 0;
                for (Direction side : AROUND) {
                    if (ringKeys.contains(BlockPos.offset(pos.asLong(), side))) {
                        hidden |= DrawBatch.sideBit(side);
                    }
                }
                style.drawJoined(batch, new AABB(pos.getX(), pos.getY(), pos.getZ(),
                    pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1), hidden, through);
            }
        }

        // The slice only changes when one of the numbers behind it does.
        private void rebuildRing(BlockPos centre) {
            int r = radius.getInt();
            int slice = Math.min(layer.getInt(), 2 * r);
            String key = centre.asLong() + ":" + r + ":" + slice;
            if (key.equals(ringKey)) {
                return;
            }
            ringKey = key;
            List<BlockPos> found = new ArrayList<>();
            ringKeys.clear();
            int dy = slice - r;
            int flat = (int) Math.ceil(Math.sqrt(Math.max(0, (double) r * r - (double) dy * dy)));
            for (int dx = -flat - 1; dx <= flat + 1; dx++) {
                for (int dz = -flat - 1; dz <= flat + 1; dz++) {
                    double distance = Math.sqrt((double) dx * dx + (double) dy * dy + (double) dz * dz);
                    if (distance < r - 0.5 || distance >= r + 0.5) {
                        continue;
                    }
                    BlockPos pos = centre.offset(dx, dy, dz);
                    found.add(pos);
                    ringKeys.add(pos.asLong());
                }
            }
            ring = found;
        }

        private JsonObject toJson() {
            JsonObject out = new JsonObject();
            out.addProperty("name", label);
            out.addProperty("shape", shape.name());
            out.add("active", active.toJson());
            out.add("world", world.toJson());
            out.add("first", first.toJson());
            if (second != null) {
                out.add("second", second.toJson());
            }
            if (radius != null) {
                out.add("radius", radius.toJson());
                out.add("layer", layer.toJson());
            }
            JsonArray rows = new JsonArray();
            for (Setting<?> setting : style.settings()) {
                rows.add(setting.toJson());
            }
            out.add("style", rows);
            return out;
        }

        private static Entry read(JsonElement json) {
            if (!json.isJsonObject()) {
                return null;
            }
            JsonObject o = json.getAsJsonObject();
            if (!o.has("name") || !o.has("shape")) {
                return null;
            }
            Shape shape;
            try {
                shape = Shape.valueOf(o.get("shape").getAsString());
            } catch (IllegalArgumentException e) {
                return null;
            }
            Entry entry = new Entry(o.get("name").getAsString(), shape,
                World.OVERWORLD, BlockPos.ZERO);
            entry.apply(o);
            return entry;
        }

        private void apply(JsonObject o) {
            if (o.has("active")) {
                active.fromJson(o.get("active"));
            }
            if (o.has("world")) {
                world.fromJson(o.get("world"));
            }
            if (o.has("first")) {
                first.fromJson(o.get("first"));
            }
            if (second != null && o.has("second")) {
                second.fromJson(o.get("second"));
            }
            if (radius != null && o.has("radius")) {
                radius.fromJson(o.get("radius"));
            }
            if (layer != null && o.has("layer")) {
                layer.fromJson(o.get("layer"));
            }
            if (o.has("style") && o.get("style").isJsonArray()) {
                JsonArray rows = o.getAsJsonArray("style");
                Setting<?>[] settings = style.settings();
                for (int i = 0; i < settings.length && i < rows.size(); i++) {
                    settings[i].fromJson(rows.get(i));
                }
            }
        }

        private static String text(BlockPos pos) {
            return pos.getX() + " " + pos.getY() + " " + pos.getZ();
        }

        // Null when the text is not three whole numbers.
        private static BlockPos parse(String text) {
            String[] parts = text.trim().split("\\s+");
            if (parts.length != 3) {
                return null;
            }
            try {
                return new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]));
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }
}
