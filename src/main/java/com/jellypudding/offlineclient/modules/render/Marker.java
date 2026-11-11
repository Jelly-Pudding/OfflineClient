package com.jellypudding.offlineclient.modules.render;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.KeyPressEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.gui.BlockPick;
import com.jellypudding.offlineclient.module.AreaPlacer;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.render.JoinedCells;
import com.jellypudding.offlineclient.setting.ActionSetting;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.KeybindSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.Setting;
import com.jellypudding.offlineclient.setting.TextSetting;
import com.jellypudding.offlineclient.util.BlockRing;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.Dimension;
import com.jellypudding.offlineclient.util.Hands;
import com.jellypudding.offlineclient.util.ServerInfo;
import com.jellypudding.offlineclient.util.TextCut;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

// Shapes you place yourself for building. Every one is saved with the module.
public final class Marker extends Module {

    public enum Shape { CUBOID, SPHERE, RING, CYLINDER }

    private static final String USUAL_NAME = "Marker";
    // A name heads every row of its marker and a description is a hover text. Both are cut to fit.
    private static final int LONGEST_NAME = 32;
    private static final int LONGEST_NOTE = 160;
    // With no draw range a ring is drawn this far out. The game cuts off anything further from the
    // camera even at the largest render distance and cloud range.
    private static final int IN_SIGHT = 2048;
    // A ring is worked out again once you have moved this far.
    private static final int WINDOW_STEP = 16;
    private static final int TARGET_COLOR = 0xFF60D060;

    private final TextSetting newName = new TextSetting("New name",
        "Name for the next marker you add.", USUAL_NAME);
    private final EnumSetting<Shape> newShape = new EnumSetting<>("New shape",
        "Which shape the next marker takes.", Shape.CUBOID)
        .describe(Shape.CUBOID, "A box between two corners.")
        .describe(Shape.SPHERE, "One flat slice of a ball of blocks.")
        .describe(Shape.RING, "A flat circle of blocks one block tall.")
        .describe(Shape.CYLINDER, "A ring repeated up a number of layers.");
    private final KeybindSetting addKey = new KeybindSetting("Add",
        "Adds a marker where you are looking.", InputConstants.KEY_BACKSLASH);
    private final BoolSetting throughWalls = new BoolSetting("Through walls",
        "Shows the markers behind blocks.", true);
    private final NumberSetting drawRange = new NumberSetting("Draw range",
        "Parts of a sphere or ring or cylinder further from you than this are left out. Nought draws all of it"
            + " that is in sight.", 0, 0, 512, 16, " blocks").max(IN_SIGHT);
    // Never saved. A game that starts with it on would place blocks before you had looked.
    private final BoolSetting build = new BoolSetting("Build",
        "Places blocks from your hotbar on every marker that is drawn as you come within reach of it. A cuboid"
            + " gets its outer faces. It is off when the game starts and stops when you join another server.",
        false) {

        @Override
        public boolean saves() {
            return false;
        }
    };

    private final List<Entry> entries = new ArrayList<>();
    private final Builder builder = new Builder();
    // The server Build was switched on for. Null whilst it is off.
    private String buildingOn;

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
    }.internal();

    public Marker() {
        super("Marker", "Draws shapes you place yourself to build against.", Category.RENDER);
        addSettings(newName, newShape, addKey, throughWalls, drawRange, build);
        for (Setting<?> row : builder.rows()) {
            addSettings(row.under(build));
        }
        addSettings(store);
        searchTags("build", "shape", "sphere", "cuboid", "ring", "circle", "cylinder");
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
    public String getSuffix() {
        return count(entries.size());
    }

    @Override
    protected void onDisable() {
        builder.setEnabled(false);
    }

    @Subscribe
    private void onTick(TickEvent event) {
        entries.removeIf(Entry::isDropped);
        if (!build.isOn()) {
            buildingOn = null;
        } else if (buildingOn == null) {
            buildingOn = ServerInfo.key();
        } else if (!buildingOn.equals(ServerInfo.key())) {
            // The markers hold no server and the same spots somewhere else are not the build.
            build.setValue(false);
            buildingOn = null;
            tell("stopped building because this is another server.");
        }
        builder.setEnabled(build.isOn());
    }

    @Subscribe
    private void onKeyPress(KeyPressEvent event) {
        if (mc.gui.screen() != null || !inGame()) {
            return;
        }
        if (event.presses(addKey)) {
            add();
            return;
        }
        for (Entry entry : entries) {
            entry.onKey(event);
        }
    }

    // The block you are looking at or the one you stand on.
    private void add() {
        BlockPos pos = mc.player.blockPosition();
        BlockHitResult hit = BlockUtil.aimedBlock();
        if (hit != null) {
            pos = hit.getBlockPos();
        }
        Shape shape = newShape.getValue();
        String label = freeName(newName.getValue().trim(), shape);
        entries.add(new Entry(label, shape, dimensionHere(), pos));
        ChatUtil.message("Added marker " + label + ".");
    }

    // Two markers with one name would share their rows.
    private String freeName(String wanted, Shape shape) {
        String base = wanted.isEmpty() ? USUAL_NAME : TextCut.cut(wanted, LONGEST_NAME);
        String label = base;
        int number = 1;
        while (taken(label, shape)) {
            number++;
            label = base + " " + number;
        }
        return label;
    }

    // A marker with a row named like one already listed would fight it for the name. A marker
    // called Wall centre meets the centre row of a sphere called Wall that way.
    private boolean taken(String label, Shape shape) {
        Set<String> listed = new HashSet<>();
        for (Setting<?> setting : getSettings()) {
            listed.add(setting.id());
        }
        List<Setting<?>> rows = new ArrayList<>();
        new Entry(label, shape, Dimension.OVERWORLD, BlockPos.ZERO).addTo(rows);
        for (Setting<?> row : rows) {
            if (listed.contains(row.id())) {
                return true;
            }
        }
        return false;
    }

    // A dimension a server added counts as the overworld.
    private static Dimension dimensionHere() {
        Dimension known = Dimension.here();
        return known == null ? Dimension.OVERWORLD : known;
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!inGame()) {
            return;
        }
        Dimension here = dimensionHere();
        boolean through = throughWalls.isOn();
        int range = drawRange.getInt();
        for (Entry entry : entries) {
            entry.draw(event.getBatch(), here, through, range);
        }
    }

    // The part that places. It is a placer like Filler that takes its spots from the markers on
    // show and is switched by the Build row. It is never listed as a module of its own.
    private final class Builder extends AreaPlacer {

        // True once the player has been told the hotbar holds nothing to build with.
        private boolean toldEmpty;

        private Builder() {
            super(USUAL_NAME, "Places the blocks of the markers.",
                "The blocks the markers are built from. Click to pick them.",
                List.of(Blocks.COBBLESTONE, Blocks.STONE, Blocks.DIRT, Blocks.NETHERRACK, Blocks.OBSIDIAN),
                "Outline the spots waiting for a block.", 0, TARGET_COLOR);
        }

        private Setting<?>[] rows() {
            return new Setting<?>[] {range, wallsRange, blocks, perRound, delay, rotate, render};
        }

        // The lowest spots come first and each layer has the one below it to stand on.
        @Override
        protected void collect() {
            if (Hands.taken()) {
                return;
            }
            Dimension here = dimensionHere();
            List<Predicate<BlockPos>> shapes = new ArrayList<>();
            for (Entry entry : entries) {
                Predicate<BlockPos> shape = entry.blocks(here);
                if (shape != null) {
                    shapes.add(shape);
                }
            }
            if (shapes.isEmpty()) {
                return;
            }
            for (BlockPos pos : BlockUtil.positionsWithin(range.getValue())) {
                if (shapes.stream().anyMatch(shape -> shape.test(pos)) && BlockUtil.blockFits(pos) && inReach(pos)) {
                    targets.add(pos);
                }
            }
            Vec3 eye = mc.player.getEyePosition();
            targets.sort(Comparator.<BlockPos>comparingInt(BlockPos::getY)
                .thenComparingDouble(pos -> eye.distanceToSqr(Vec3.atCenterOf(pos))));
            tellEmpty(!targets.isEmpty() && BlockUtil.findBlockSlot(this::allowed) == -1);
        }

        // Said once each time the blocks run out with spots still waiting.
        private void tellEmpty(boolean empty) {
            if (empty && !toldEmpty) {
                ChatUtil.error("Marker has none of its build blocks in the hotbar.");
            }
            toldEmpty = empty;
        }

        @Override
        protected boolean placeOn(BlockPos pos) {
            return BlockUtil.placeAny(pos, rotate.isOn(), true);
        }
    }

    // One marker and the rows that shape it.
    private static final class Entry {

        // A typed spot with a row that moves it to where you stand and one that waits for a click.
        private final class Spot {

            private final TextSetting text;
            private final ActionSetting here;
            private final ActionSetting pick;

            private Spot(String what, BlockPos pos) {
                text = new TextSetting(label + " " + what, "Three numbers for x and y and z.",
                    BlockUtil.text(pos)).under(active);
                here = new ActionSetting(label + " " + what + " here",
                    "Moves the " + what + " to where you stand.", this::moveHere)
                    .under(text, () -> true);
                pick = new ActionSetting(label + " " + what + " pick",
                    "Closes the menu until you click a block and moves the " + what + " onto it.",
                    () -> BlockPick.start("the " + what + " of " + label, this::moveTo))
                    .under(text, () -> true);
            }

            private String moveHere() {
                return mc.player == null ? "You are not in a world" : moveTo(mc.player.blockPosition());
            }

            // The marker belongs to the dimension its newest spot was taken in.
            private String moveTo(BlockPos pos) {
                text.setValue(BlockUtil.text(pos));
                world.setValue(dimensionHere());
                OfflineClient.INSTANCE.getConfigManager().saveSoon();
                return "Moved to " + BlockUtil.text(pos);
            }

            // Null whilst the text is not three whole numbers.
            private BlockPos read() {
                return BlockUtil.parse(text.getValue());
            }

            private void addTo(List<Setting<?>> out) {
                out.add(text);
                out.add(here);
                out.add(pick);
            }
        }

        // The numbers a ring was worked out from. Another ring is due once any of them moves.
        private record RingKey(long centre, int radius, long dy, int anchorX, int anchorZ, int reach) {
        }

        private final String label;
        private final Shape shape;
        private final TextSetting note;
        private final BoolSetting active;
        private final EnumSetting<Dimension> world;
        private final Spot first;
        // Only a cuboid has a second corner. Only the round shapes have a radius.
        private final Spot second;
        private final NumberSetting radius;
        // Only a sphere is cut into layers and only a cylinder has a height.
        private final NumberSetting layer;
        private final KeybindSetting nextLayer;
        private final KeybindSetting prevLayer;
        private final NumberSetting height;
        private final BoxStyle style;
        private final BoolSetting drop;

        // The blocks of the ring at its lowest layer and what they were worked out from.
        private List<BlockPos> ring = List.of();
        private RingKey ringKey;
        private final JoinedCells<BlockPos> joined = new JoinedCells<>(BlockPos::asLong);

        private Entry(String label, Shape shape, Dimension world, BlockPos pos) {
            this.label = label;
            this.shape = shape;
            boolean round = shape != Shape.CUBOID;
            boolean sphere = shape == Shape.SPHERE;
            note = new TextSetting(label + " description",
                "A few words about this marker. They show when you point at its first row.", "");
            // The first row of a marker tells what the marker is for once it has been given a description.
            active = new BoolSetting(label, "Draws this marker.", true) {

                @Override
                public String getDescription() {
                    return note.isBlank() ? super.getDescription() : TextCut.cut(note.getValue(), LONGEST_NOTE);
                }
            };
            note.under(active);
            this.world = new EnumSetting<>(label + " dimension",
                "The world this marker belongs to.", world).under(active);
            first = new Spot(round ? "centre" : "corner one", pos);
            second = round ? null : new Spot("corner two", pos);
            radius = round ? new NumberSetting(label + " radius",
                sphere ? "How wide the ball is." : "How far the ring lies from its centre.", 20, 1, 64, 1, " blocks")
                .max(Level.MAX_LEVEL_SIZE).under(active) : null;
            layer = sphere ? new NumberSetting(label + " layer",
                "Which slice of the ball is drawn counting up from the bottom.", 20, 0, 40, 1)
                .under(active) : null;
            nextLayer = sphere ? new KeybindSetting(label + " next layer",
                "Raises the drawn slice of this sphere.", InputConstants.KEY_RBRACKET)
                .under(layer, () -> true) : null;
            prevLayer = sphere ? new KeybindSetting(label + " previous layer",
                "Lowers the drawn slice of this sphere.", InputConstants.KEY_LBRACKET)
                .under(layer, () -> true) : null;
            height = shape == Shape.CYLINDER ? new NumberSetting(label + " height",
                "How many layers the cylinder has counting up from its centre.", 4, 1, 64, 1, " blocks")
                .max(DimensionType.Y_SIZE).under(active) : null;
            style = new BoxStyle(label, BoxStyle.Shape.BOTH, 217).under(active);
            drop = new BoolSetting(label + " remove", "Deletes this marker once ticked.", false)
                .under(active);
            // Every row is part of the marker you placed. A reset of the module leaves them alone.
            List<Setting<?>> rows = new ArrayList<>();
            addTo(rows);
            rows.forEach(Setting::noDefault);
        }

        private boolean isDropped() {
            return drop.isOn();
        }

        private void addTo(List<Setting<?>> out) {
            out.add(active);
            out.add(world);
            out.add(note);
            first.addTo(out);
            if (second != null) {
                second.addTo(out);
            }
            if (radius != null) {
                out.add(radius);
            }
            if (layer != null) {
                out.add(layer);
                out.add(nextLayer);
                out.add(prevLayer);
            }
            if (height != null) {
                out.add(height);
            }
            for (Setting<?> setting : style.settings()) {
                out.add(setting);
            }
            out.add(drop);
        }

        private void onKey(KeyPressEvent event) {
            if (layer == null || !active.isOn()) {
                return;
            }
            if (event.presses(nextLayer)) {
                stepLayer(1);
            } else if (event.presses(prevLayer)) {
                stepLayer(-1);
            }
        }

        private void stepLayer(int step) {
            layer.setValue((double) Math.clamp(layer.getInt() + (long) step, 0, 2 * radius.getInt()));
        }

        // How far above or below the middle of the ball the drawn slice lies. A ring and a
        // cylinder are the cut through the middle.
        private long sliceOffset() {
            int r = radius.getInt();
            return layer == null ? 0 : Math.min(layer.getInt(), 2 * r) - r;
        }

        // How many blocks tall the shape stands on its lowest layer.
        private int layers() {
            return height == null ? 1 : height.getInt();
        }

        // The test for a block of the marker as it is drawn. A cuboid is its outer faces. Null
        // whilst the marker is off or belongs to another dimension or a spot cannot be read.
        private Predicate<BlockPos> blocks(Dimension here) {
            BlockPos one = active.isOn() && world.getValue() == here ? first.read() : null;
            if (one == null) {
                return null;
            }
            if (shape == Shape.CUBOID) {
                BlockPos two = second.read();
                if (two == null) {
                    return null;
                }
                BoundingBox box = BoundingBox.fromCorners(one, two);
                return pos -> box.isInside(pos) && onFace(box, pos);
            }
            int r = radius.getInt();
            long dy = sliceOffset();
            long bottom = one.getY() + dy;
            long top = bottom + layers();
            return pos -> pos.getY() >= bottom && pos.getY() < top
                && BlockRing.onBall((long) pos.getX() - one.getX(), dy, (long) pos.getZ() - one.getZ(), r);
        }

        private static boolean onFace(BoundingBox box, BlockPos pos) {
            return pos.getX() == box.minX() || pos.getX() == box.maxX() || pos.getY() == box.minY()
                || pos.getY() == box.maxY() || pos.getZ() == box.minZ() || pos.getZ() == box.maxZ();
        }

        private void draw(DrawBatch batch, Dimension here, boolean through, int range) {
            if (!active.isOn() || world.getValue() != here) {
                return;
            }
            if (shape == Shape.CUBOID) {
                drawCuboid(batch, through);
            } else {
                drawRing(batch, through, range);
            }
        }

        private void drawCuboid(DrawBatch batch, boolean through) {
            BlockPos one = first.read();
            BlockPos two = second.read();
            if (one == null || two == null) {
                return;
            }
            style.draw(batch, AABB.encapsulatingFullBlocks(one, two), through);
        }

        // Each block of the ring stands as tall as the shape. The blocks only ever touch sideways.
        private void drawRing(DrawBatch batch, boolean through, int range) {
            BlockPos centre = first.read();
            if (centre == null) {
                return;
            }
            rebuildRing(centre, range == 0 ? IN_SIGHT : range);
            joined.update(ring);
            Vec3 eye = mc.player.getEyePosition();
            double limit = (double) range * range;
            int tall = layers();
            for (BlockPos pos : ring) {
                if (range > 0 && eye.distanceToSqr(pos.getX() + 0.5, eye.y, pos.getZ() + 0.5) > limit) {
                    continue;
                }
                style.drawJoined(batch, new AABB(pos.getX(), pos.getY(), pos.getZ(),
                    pos.getX() + 1, pos.getY() + tall, pos.getZ() + 1), joined.hiddenSides(pos), through);
            }
        }

        // Only the stretch of the ring within reach of where you stand is worked out. A ring of
        // any size then costs the same. It is worked out again once a number behind it changes
        // or you have moved a good way.
        private void rebuildRing(BlockPos centre, int reach) {
            BlockPos feet = mc.player.blockPosition();
            int anchorX = Math.floorDiv(feet.getX(), WINDOW_STEP) * WINDOW_STEP;
            int anchorZ = Math.floorDiv(feet.getZ(), WINDOW_STEP) * WINDOW_STEP;
            long dy = sliceOffset();
            RingKey key = new RingKey(centre.asLong(), radius.getInt(), dy, anchorX, anchorZ, reach);
            if (key.equals(ringKey)) {
                return;
            }
            ringKey = key;
            int span = reach + WINDOW_STEP;
            int y = (int) (centre.getY() + dy);
            List<BlockPos> found = new ArrayList<>();
            BlockRing.slice(key.radius(), dy, offset(anchorX - span, centre.getX()),
                offset(anchorX + span, centre.getX()), offset(anchorZ - span, centre.getZ()),
                offset(anchorZ + span, centre.getZ()),
                (dx, dz) -> found.add(new BlockPos(centre.getX() + dx, y, centre.getZ() + dz)));
            ring = found;
        }

        // How far a coordinate lies from the middle. A typed centre can be any number at all.
        private static int offset(long coordinate, int middle) {
            return Math.clamp(coordinate - middle, Integer.MIN_VALUE, Integer.MAX_VALUE);
        }

        private JsonObject toJson() {
            JsonObject out = new JsonObject();
            out.addProperty("name", label);
            out.addProperty("shape", shape.name());
            out.add("active", active.toJson());
            out.add("world", world.toJson());
            out.add("note", note.toJson());
            out.add("first", first.text.toJson());
            if (second != null) {
                out.add("second", second.text.toJson());
            }
            if (radius != null) {
                out.add("radius", radius.toJson());
            }
            if (layer != null) {
                out.add("layer", layer.toJson());
                out.add("nextLayer", nextLayer.toJson());
                out.add("prevLayer", prevLayer.toJson());
            }
            if (height != null) {
                out.add("height", height.toJson());
            }
            out.add("style", style.toJson());
            return out;
        }

        private static Entry read(JsonElement json) {
            if (!json.isJsonObject()) {
                return null;
            }
            JsonObject o = json.getAsJsonObject();
            JsonElement named = o.get("name");
            JsonElement shaped = o.get("shape");
            if (named == null || shaped == null || !named.isJsonPrimitive() || !shaped.isJsonPrimitive()) {
                return null;
            }
            Shape shape;
            try {
                shape = Shape.valueOf(shaped.getAsString());
            } catch (IllegalArgumentException e) {
                return null;
            }
            String name = TextCut.cut(named.getAsString().trim(), LONGEST_NAME);
            Entry entry = new Entry(name.isEmpty() ? USUAL_NAME : name, shape, Dimension.OVERWORLD, BlockPos.ZERO);
            entry.apply(o);
            return entry;
        }

        private void apply(JsonObject o) {
            load(o, "active", active);
            load(o, "world", world);
            load(o, "note", note);
            note.setValue(TextCut.cut(note.getValue(), LONGEST_NOTE));
            load(o, "first", first.text);
            if (second != null) {
                load(o, "second", second.text);
            }
            if (radius != null) {
                load(o, "radius", radius);
            }
            if (layer != null) {
                load(o, "layer", layer);
                load(o, "nextLayer", nextLayer);
                load(o, "prevLayer", prevLayer);
            }
            if (height != null) {
                load(o, "height", height);
            }
            if (o.has("style")) {
                style.fromJson(o.get("style"));
            }
        }

        private static void load(JsonObject o, String key, Setting<?> setting) {
            if (o.has(key)) {
                setting.fromJson(o.get(key));
            }
        }
    }
}
