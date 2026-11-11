package com.jellypudding.offlineclient.modules.hunting;

import com.jellypudding.offlineclient.config.FindLog;
import com.jellypudding.offlineclient.config.FindSource;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render2DEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.gui.FindsScreen;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.render.FarShapes;
import com.jellypudding.offlineclient.render.FindLines;
import com.jellypudding.offlineclient.render.WorldToScreen;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.BoundedMap;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.Leak;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.Notice;
import com.jellypudding.offlineclient.util.RenderUtil;
import com.jellypudding.offlineclient.util.ServerWatch;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.bee.Bee;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.component.Bees;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.LodestoneTracker;
import net.minecraft.world.item.component.MapDecorations;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import net.minecraft.world.level.saveddata.maps.MapDecorationType;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// A lodestone compass carries the block and dimension of its lodestone and the server sends
// both with the item. Any compass you can see gives its lodestone away. One held in an
// inventory loses its target once the server finds the lodestone gone. A beehive or bee
// nest taken with Silk Touch keeps its bees and the flower each one last fed from. Bees
// keep to flowers near their hive and the item carries no dimension. The Overworld is assumed.
// An explorer or treasure map carries the marker of where it leads. Only the overworld makes
// such maps and the marker has no height.
public final class ItemCoords extends Module implements FindSource, Leak.Source {

    private enum Kind {
        LODESTONE("compass"),
        FLOWERS("hive"),
        // A map find is named after where it leads such as woodland mansion.
        MAP("map");

        private final String item;

        Kind(String item) {
            this.item = item;
        }

        // The kind of a find from what the finds call it. Every other name is where a map leads.
        private static Kind named(String name) {
            return name.equals(LODESTONE_NAME) ? LODESTONE : name.equals(FLOWERS_NAME) ? FLOWERS : MAP;
        }
    }

    // Where an item was seen. The label names the place and the chat line tells it in
    // full around the name of the item.
    private record Carrier(String label, String lead, String tail) {

        String phrase(Kind kind) {
            return lead + kind.item + tail;
        }
    }

    // A spot an item points to and what the finds call it.
    private record Pointer(GlobalPos spot, Kind kind, String name) {

        String dimension() {
            return spot.dimension().identifier().toString();
        }

        // Such as Lodestone or Woodland mansion.
        String title() {
            return ChatUtil.capitalised(name);
        }

        // Such as a lodestone or a woodland mansion.
        String news() {
            return switch (kind) {
                case LODESTONE -> "a lodestone";
                case FLOWERS -> "flowers bees fed from";
                case MAP -> ChatUtil.withArticle(name);
            };
        }
    }

    // A find told about since the module was switched on and when.
    private record Told(Carrier carrier, long time) {
    }

    private static final String LODESTONE_NAME = "lodestone";
    private static final String FLOWERS_NAME = "flowers";
    // The red cross of a treasure map. Every other marker is named after its picture.
    private static final String TREASURE = "buried treasure";

    // A map marker has no height. The structures maps lead to sit around the sea.
    private static final int MAP_HEIGHT = 63;
    // A map the game makes leads to one place. A server can send more markers and only this many
    // are read.
    private static final int MAX_MARKERS = 8;

    private static final Carrier YOURS = new Carrier("your inventory", "a ", " in your inventory");
    private static final Carrier OPENED = new Carrier("opened container", "a ", " in the container you opened");

    // Deep enough for a bundle inside a bundle inside a shulker box.
    private static final int MAX_DEPTH = 4;

    // Far more finds than a session meets. It only bounds the memory.
    private static final int MAX_TOLD = 1024;

    // The newest finds the leaks list shows. Older ones stay in the finds.
    private static final int MAX_LEAKS = 64;

    private static final double LABEL_LIFT = 1.5;

    private final BoolSetting hives = new BoolSetting("Bee hives",
        "Also marks the flowers the bees inside a beehive or bee nest item last fed from. They grow near where the hive stood.",
        true);
    private final ColorSetting flowerColor = new ColorSetting("Flower colour",
        "Colour of the flowers bees fed from. Lodestones take the colours below.", 300, false)
        .under(hives);
    private final BoolSetting maps = new BoolSetting("Maps",
        "Also marks where explorer and treasure and trial and village maps lead.", true);
    private final ColorSetting mapColor = new ColorSetting("Map colour",
        "Colour of the places maps lead to. Lodestones take the colours below.", 40, false)
        .under(maps);
    private final BoolSetting yours = new BoolSetting("Your items",
        "Also marks what the compasses and hives and maps in your own inventory point to.", true);
    private final BoxStyle style = new BoxStyle(BoxStyle.Shape.BOTH, 180);
    private final BoolSetting labels = new BoolSetting("Labels",
        "Writes above each find what it is and where the item was.", true);
    private final NumberSetting scale = new NumberSetting("Scale",
        "Size of the labels.", 1, 0.5, 3, 0.1).min(0.1).under(labels);
    private final BoolSetting waypoints = new BoolSetting("Waypoints",
        "Saves a waypoint at each lodestone and flower and map target the first time it is found.", false);
    private final FindLog finds = new FindLog(this);
    private final Notice notice = new Notice(this, Notice.Where.CHAT);
    private final FindLines marks = new FindLines(finds, false);

    // What was told since the module was switched on. A toggle tells it all again.
    private final Map<Pointer, Told> told = new BoundedMap<>(MAX_TOLD);
    // The newest of them as the leaks list shows them. Rebuilt every tick.
    private final List<Leak> leaks = new ArrayList<>();
    // The spots the item being looked at points to. Reused for every item.
    private final List<Pointer> pointed = new ArrayList<>();
    private final ServerWatch server = new ServerWatch();

    public ItemCoords() {
        super("ItemCoords", "Marks where compasses and maps lead and the flowers that bees in hive items fed from.",
            Category.HUNTING);
        addSettings(hives, flowerColor, maps, mapColor, yours);
        addSettings(style.settings());
        addSettings(labels, scale);
        addSettings(notice.settings());
        addSettings(marks.settings());
        addSettings(waypoints);
        addSettings(FindsScreen.settingsFor(finds));
        searchTags("lodestone", "lodestone finder", "compass", "base", "coords", "bee", "beehive", "bee nest",
            "flower", "map", "explorer map", "treasure map", "buried treasure", "woodland mansion");
    }

    @Override
    public FindLog findLog() {
        return finds;
    }

    @Override
    public List<Leak> leaks() {
        return Collections.unmodifiableList(leaks);
    }

    @Override
    public String getSuffix() {
        return count(finds.count());
    }

    @Override
    protected void onEnable() {
        forget();
    }

    @Override
    protected void onDisable() {
        forget();
    }

    private void forget() {
        told.clear();
        leaks.clear();
        server.forget();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (server.changed()) {
            told.clear();
        }
        // A copy Blink or FakePlayer puts down carries your own gear.
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity != mc.player && !Modules.isLocalBody(entity)) {
                look(entity);
            }
        }
        if (yours.isOn()) {
            Inventory inventory = mc.player.getInventory();
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                if (points(inventory.getItem(i))) {
                    note(YOURS);
                }
            }
        }
        lookInOpenedContainer();
        leaks.clear();
        told.entrySet().stream().skip(Math.max(0, told.size() - MAX_LEAKS))
            .forEach(entry -> leaks.add(leakOf(entry.getKey(), entry.getValue())));
    }

    private void lookInOpenedContainer() {
        AbstractContainerMenu menu = mc.player.containerMenu;
        if (menu == mc.player.inventoryMenu) {
            return;
        }
        for (Slot slot : menu.slots) {
            if (slot.container != mc.player.getInventory() && points(slot.getItem())) {
                note(OPENED);
            }
        }
    }

    private void look(Entity entity) {
        switch (entity) {
            case ItemFrame frame -> {
                if (points(frame.getItem())) {
                    note(carrierOf(frame));
                }
            }
            case ItemEntity item -> {
                if (points(item.getItem())) {
                    note(new Carrier("dropped item", "the ", " on the ground at §f"
                        + BlockUtil.text(item.blockPosition())));
                }
            }
            case LivingEntity living -> {
                for (EquipmentSlot slot : EquipmentSlot.VALUES) {
                    if (points(living.getItemBySlot(slot))) {
                        note(carrierOf(living));
                    }
                }
            }
            default -> {
            }
        }
    }

    private static Carrier carrierOf(Entity entity) {
        if (entity instanceof Player player) {
            String name = EntityUtil.displayNameOf(player);
            return new Carrier(name, "the ", " §f" + name + " §7carries");
        }
        String type = ChatUtil.words(entity.getType());
        if (entity instanceof ItemFrame) {
            return new Carrier(type, "the ", " in the §f" + type + " §7at §f" + BlockUtil.text(entity.blockPosition()));
        }
        return new Carrier(type, "the ", " the §f" + type + " §7carries");
    }

    // Fills the pointed list and says whether it found anything.
    private boolean points(ItemStack stack) {
        pointed.clear();
        if (!stack.isEmpty()) {
            targets(stack, 0);
        }
        return !pointed.isEmpty();
    }

    // Every spot the item points to and those of anything packed inside it.
    private void targets(ItemInstance item, int depth) {
        LodestoneTracker tracker = item.get(DataComponents.LODESTONE_TRACKER);
        if (tracker != null) {
            tracker.target().ifPresent(spot -> pointed.add(new Pointer(spot, Kind.LODESTONE, LODESTONE_NAME)));
        }
        Bees bees = hives.isOn() ? item.get(DataComponents.BEES) : null;
        if (bees != null) {
            for (BeehiveBlockEntity.Occupant bee : bees.bees()) {
                bee.entityData().copyTagWithoutId().read(Bee.TAG_FLOWER_POS, BlockPos.CODEC).ifPresent(flower ->
                    pointed.add(new Pointer(GlobalPos.of(Level.OVERWORLD, flower), Kind.FLOWERS, FLOWERS_NAME)));
            }
        }
        MapDecorations markers = maps.isOn() ? item.get(DataComponents.MAP_DECORATIONS) : null;
        if (markers != null) {
            for (MapDecorations.Entry marker : markers.decorations().values().stream().limit(MAX_MARKERS).toList()) {
                BlockPos spot = BlockPos.containing(marker.x(), MAP_HEIGHT, marker.z());
                pointed.add(new Pointer(GlobalPos.of(Level.OVERWORLD, spot), Kind.MAP, destination(marker.type())));
            }
        }
        if (depth >= MAX_DEPTH) {
            return;
        }
        ItemContainerContents contents = item.get(DataComponents.CONTAINER);
        if (contents != null) {
            for (ItemStackTemplate inner : contents.nonEmptyItems()) {
                targets(inner, depth + 1);
            }
        }
        BundleContents bundle = item.get(DataComponents.BUNDLE_CONTENTS);
        if (bundle != null) {
            for (ItemStackTemplate inner : bundle.items()) {
                targets(inner, depth + 1);
            }
        }
    }

    // Where a map marker leads in words such as woodland mansion.
    private static String destination(Holder<MapDecorationType> type) {
        return type.value().equals(MapDecorationTypes.RED_X.value()) ? TREASURE : ChatUtil.words(type.value().assetId());
    }

    // Every spot goes in the finds. Each is told once since the module was switched on and
    // a spot new to the finds takes a waypoint.
    private void note(Carrier carrier) {
        long now = System.currentTimeMillis();
        for (Pointer pointer : pointed) {
            if (told.containsKey(pointer)) {
                continue;
            }
            BlockPos pos = pointer.spot().pos();
            String dimension = pointer.dimension();
            boolean fresh = finds.add(dimension, pos, pointer.name(), carrier.label());
            Told seen = new Told(carrier, now);
            told.put(pointer, seen);
            notice.tell(pointer.kind().item, pointer.news() + " §7from " + carrier.phrase(pointer.kind()), pos,
                dimension);
            if (fresh && waypoints.isOn()) {
                leakOf(pointer, seen).saveFresh(pointer.title().replace(" ", ""), dimension);
            }
        }
    }

    private Leak leakOf(Pointer pointer, Told seen) {
        return new Leak(pointer.title(), "via " + seen.carrier().label(), pointer.spot().pos(),
            pointer.kind() != Kind.MAP, pointer.dimension(), seen.time(), colorOf(pointer.kind()));
    }

    private int colorOf(Kind kind) {
        return switch (kind) {
            case LODESTONE -> style.lineColor();
            case FLOWERS -> flowerColor.getColor();
            case MAP -> mapColor.getColor();
        };
    }

    private int colorOf(FindLog.Find find) {
        return colorOf(Kind.named(find.kind()));
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!inGame() || finds.count() == 0) {
            return;
        }
        DrawBatch batch = event.getBatch();
        for (FindLog.Find find : finds.here()) {
            if (!marks.inDrawRange(find)) {
                continue;
            }
            Kind kind = Kind.named(find.kind());
            if (kind == Kind.LODESTONE) {
                style.draw(batch, FarShapes.pullIn(DrawBatch.blockBox(find.pos())), true);
            } else {
                style.draw(batch, FarShapes.pullIn(DrawBatch.blockBox(find.pos())), colorOf(kind), true);
            }
        }
        marks.draw(batch, this::colorOf);
    }

    @Subscribe
    private void onRender2D(Render2DEvent event) {
        if (!labels.isOn() || !inGame() || finds.count() == 0 || !WorldToScreen.update()) {
            return;
        }
        Vec3 camera = WorldToScreen.cameraPos();
        for (FindLog.Find find : finds.here()) {
            if (!marks.inDrawRange(find)) {
                continue;
            }
            Vec3 spot = Vec3.atCenterOf(find.pos()).add(0, LABEL_LIFT, 0);
            Vec3 screen = WorldToScreen.project(spot);
            if (screen == null) {
                continue;
            }
            // A map target has no true height and is measured across the ground.
            double away = Kind.named(find.kind()) == Kind.MAP ? Math.hypot(spot.x - camera.x, spot.z - camera.z)
                : camera.distanceTo(spot);
            String distance = String.format(Locale.ROOT, " %dm", Math.round(away));
            RenderUtil.label(event.getContext(), mc.font, screen.x, screen.y, scale.getFloat(),
                List.of(ChatUtil.capitalised(find.kind()), " via " + find.detail(), distance),
                List.of(colorOf(find), RenderUtil.MUTED_TEXT, RenderUtil.MUTED_TEXT));
        }
    }
}
