package com.jellypudding.offlineclient.modules.hunting;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render2DEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.render.FarShapes;
import com.jellypudding.offlineclient.render.WorldToScreen;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.BoundedMap;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.RenderUtil;
import com.jellypudding.offlineclient.util.ServerWatch;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceKey;
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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// A lodestone compass carries the block and dimension of its lodestone and the server sends
// both with the item. Any compass you can see gives its lodestone away. One held in an
// inventory loses its target once the server finds the lodestone gone. A beehive or bee
// nest taken with Silk Touch keeps its bees and the flower each one last fed from. Bees
// keep to flowers near their hive and the item carries no dimension. The Overworld is assumed.
public final class ItemCoords extends Module {

    private enum Kind {
        LODESTONE("Lodestone", "compass"),
        FLOWERS("Flowers", "hive");

        private final String title;
        private final String item;

        Kind(String title, String item) {
            this.title = title;
            this.item = item;
        }
    }

    // Where an item was seen. The label names the place and the chat line tells it in
    // full around the name of the item.
    private record Carrier(String label, String lead, String tail) {

        String phrase(Kind kind) {
            return lead + kind.item + tail;
        }
    }

    // A spot an item points to.
    private record Pointer(GlobalPos spot, Kind kind) {
    }

    private record Find(Pointer pointer, Carrier carrier) {
    }

    private static final Carrier YOURS = new Carrier("your inventory", "a ", " in your inventory");
    private static final Carrier OPENED = new Carrier("opened container", "a ", " in the container you opened");

    // Deep enough for a bundle inside a bundle inside a shulker box.
    private static final int MAX_DEPTH = 4;

    // Far more finds than a session meets. It only bounds the memory.
    private static final int MAX_FINDS = 1024;

    private static final double LABEL_LIFT = 1.5;

    private final BoolSetting hives = new BoolSetting("Bee hives",
        "Also marks the flowers the bees inside a beehive or bee nest item last fed from. They grow near where the hive stood.",
        true);
    private final ColorSetting flowerColor = new ColorSetting("Flower colour",
        "Colour of the flowers bees fed from. Lodestones take the colours below.", 300, false)
        .under(hives);
    private final BoolSetting yours = new BoolSetting("Your items",
        "Also marks what the compasses and hives in your own inventory point to.", true);
    private final BoxStyle style = new BoxStyle(BoxStyle.Shape.BOTH, 180);
    private final BoolSetting tracers = new BoolSetting("Tracers",
        "Draws a line to every find in your dimension.", false);
    private final BoolSetting labels = new BoolSetting("Labels",
        "Writes above each find what it is and where the item was.", true);
    private final NumberSetting scale = new NumberSetting("Scale",
        "Size of the labels.", 1, 0.5, 3, 0.1).min(0.1).under(labels);
    private final BoolSetting chat = new BoolSetting("Chat",
        "Posts each new find in chat and says where its item was.", true);

    // Everything found on the current server. A toggle or another server forgets it.
    private final Map<Pointer, Find> finds = new BoundedMap<>(MAX_FINDS);
    // The spots the item being looked at points to. Reused for every item.
    private final List<Pointer> pointed = new ArrayList<>();
    private final ServerWatch server = new ServerWatch();

    public ItemCoords() {
        super("ItemCoords", "Marks the lodestones compasses point to and the flowers that bees in hive items fed from.",
            Category.HUNTING);
        addSettings(hives, flowerColor, yours);
        addSettings(style.settings());
        addSettings(tracers, labels, scale, chat);
        searchTags("lodestone", "lodestone finder", "compass", "base", "coords", "bee", "beehive", "bee nest", "flower");
    }

    @Override
    public String getSuffix() {
        return count(finds.size());
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
        finds.clear();
        server.forget();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            return;
        }
        if (server.changed()) {
            // Two servers share their dimension names.
            finds.clear();
        }
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity != mc.player) {
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
        String type = entity.getType().getDescription().getString();
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
            tracker.target().ifPresent(spot -> pointed.add(new Pointer(spot, Kind.LODESTONE)));
        }
        Bees bees = hives.isOn() ? item.get(DataComponents.BEES) : null;
        if (bees != null) {
            for (BeehiveBlockEntity.Occupant bee : bees.bees()) {
                bee.entityData().copyTagWithoutId().read(Bee.TAG_FLOWER_POS, BlockPos.CODEC).ifPresent(flower ->
                    pointed.add(new Pointer(GlobalPos.of(Level.OVERWORLD, flower), Kind.FLOWERS)));
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

    private void note(Carrier carrier) {
        for (Pointer pointer : pointed) {
            if (finds.containsKey(pointer)) {
                continue;
            }
            finds.put(pointer, new Find(pointer, carrier));
            if (chat.isOn()) {
                ResourceKey<Level> dimension = pointer.spot().dimension();
                String away = dimension.equals(mc.level.dimension()) ? "" : " in " + dimensionName(dimension);
                String what = pointer.kind() == Kind.LODESTONE ? "a lodestone" : "flowers bees fed from";
                ChatUtil.message("§bItemCoords §7found " + what + " at §f" + BlockUtil.text(pointer.spot().pos())
                    + "§7" + away + " from " + carrier.phrase(pointer.kind()) + "§7.");
            }
        }
    }

    private static String dimensionName(ResourceKey<Level> dimension) {
        if (dimension.equals(Level.OVERWORLD)) {
            return "the Overworld";
        }
        if (dimension.equals(Level.NETHER)) {
            return "the Nether";
        }
        return dimension.equals(Level.END) ? "the End" : dimension.identifier().getPath();
    }

    private boolean here(Find find) {
        return find.pointer().spot().dimension().equals(mc.level.dimension());
    }

    private int colorOf(Find find) {
        return find.pointer().kind() == Kind.FLOWERS ? flowerColor.getColor() : style.lineColor();
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!inGame() || finds.isEmpty()) {
            return;
        }
        DrawBatch batch = event.getBatch();
        for (Find find : finds.values()) {
            if (!here(find)) {
                continue;
            }
            BlockPos pos = find.pointer().spot().pos();
            AABB box = FarShapes.pullIn(DrawBatch.blockBox(pos));
            if (find.pointer().kind() == Kind.FLOWERS) {
                style.draw(batch, box, flowerColor.getColor(), true);
            } else {
                style.draw(batch, box, true);
            }
            if (tracers.isOn()) {
                batch.tracer(FarShapes.pullIn(Vec3.atCenterOf(pos)), colorOf(find), true);
            }
        }
    }

    @Subscribe
    private void onRender2D(Render2DEvent event) {
        if (!labels.isOn() || !inGame() || finds.isEmpty() || !WorldToScreen.update()) {
            return;
        }
        Vec3 camera = WorldToScreen.cameraPos();
        for (Find find : finds.values()) {
            if (!here(find)) {
                continue;
            }
            Vec3 spot = Vec3.atCenterOf(find.pointer().spot().pos()).add(0, LABEL_LIFT, 0);
            Vec3 screen = WorldToScreen.project(spot);
            if (screen == null) {
                continue;
            }
            String distance = String.format(Locale.ROOT, " %dm", Math.round(camera.distanceTo(spot)));
            RenderUtil.label(event.getContext(), mc.font, screen.x, screen.y, scale.getFloat(),
                List.of(find.pointer().kind().title, " via " + find.carrier().label(), distance),
                List.of(colorOf(find), RenderUtil.MUTED_TEXT, RenderUtil.MUTED_TEXT));
        }
    }
}
