package com.jellypudding.offlineclient.modules.hunting;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render2DEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.render.WorldToScreen;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.RenderUtil;
import com.jellypudding.offlineclient.util.Sightings;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

// Paper empties shulker boxes and bundles in the items it sends for dropped stacks and item
// frames and gear. Only the item itself can be told apart.
public final class Collectibles extends Module {

    // One entity showing a picked item. The chat line tells what it is and where.
    private record Find(Entity entity, String name, String sentence) {
    }

    private static final String MUSIC_DISC = "music_disc_";
    private static final String NETHERITE = "netherite";

    // A dropped item is tiny. Its box is grown to be seen from afar.
    private static final double ITEM_GROW = 0.1;
    private static final double LABEL_LIFT = 0.3;

    private final RegistryListSetting<Item> items = new RegistryListSetting<>("Items",
        "The rare items to look for. Click to pick them.", BuiltInRegistries.ITEM, rareItems());
    private final BoolSetting dropped = new BoolSetting("Dropped",
        "Looks at items lying on the ground.", true);
    private final BoolSetting frames = new BoolSetting("Item frames",
        "Looks at items shown in item frames.", true);
    private final BoolSetting stands = new BoolSetting("Armour stands",
        "Looks at what armour stands hold and wear.", true);
    private final BoolSetting mobs = new BoolSetting("Mobs",
        "Looks at what mobs hold and wear. Your own pets and the mounts you have ridden are left out.", true);
    private final BoxStyle style = new BoxStyle(BoxStyle.Shape.BOTH, 45);
    private final BoolSetting tracers = new BoolSetting("Tracers",
        "Draws a line from you to each find.", false);
    private final BoolSetting labels = new BoolSetting("Labels",
        "Writes the name of each find above it.", true);
    private final NumberSetting scale = new NumberSetting("Scale",
        "Size of the labels.", 1, 0.5, 3, 0.1).min(0.1).under(labels);
    private final BoolSetting chat = new BoolSetting("Chat",
        "Posts each new find in chat with where it is.", true);

    // Rebuilt once a tick.
    private List<Find> found = List.of();
    private final Sightings sightings = new Sightings();

    public Collectibles() {
        super("Collectibles", "Marks the rare items you choose wherever they show in the world.",
            Category.HUNTING);
        addSettings(items, dropped, frames, stands, mobs);
        addSettings(style.settings());
        addSettings(tracers, labels, scale, chat);
        searchTags("rare items", "dragon egg", "heads", "music disc", "item frame", "armour stand");
    }

    // Music discs and netherite are gathered by name because tags are not loaded when this runs.
    private static List<Item> rareItems() {
        List<Item> picked = new ArrayList<>(List.of(Items.DRAGON_EGG, Items.DRAGON_HEAD,
            Items.PLAYER_HEAD, Items.ZOMBIE_HEAD, Items.CREEPER_HEAD, Items.PIGLIN_HEAD,
            Items.SKELETON_SKULL, Items.WITHER_SKELETON_SKULL, Items.ENCHANTED_GOLDEN_APPLE,
            Items.ELYTRA, Items.NETHER_STAR, Items.BEACON, Items.CONDUIT, Items.HEART_OF_THE_SEA,
            Items.HEAVY_CORE, Items.MACE, Items.TRIDENT, Items.SNIFFER_EGG));
        BuiltInRegistries.ITEM.stream()
            .filter(item -> {
                String path = BuiltInRegistries.ITEM.getKey(item).getPath();
                return path.startsWith(MUSIC_DISC) || path.startsWith(NETHERITE);
            })
            .forEach(picked::add);
        return picked;
    }

    @Override
    public String getSuffix() {
        return count(found.size());
    }

    @Override
    protected void onEnable() {
        found = List.of();
        sightings.clear();
    }

    @Override
    protected void onDisable() {
        found = List.of();
        sightings.clear();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            return;
        }
        List<Find> finds = new ArrayList<>();
        for (Entity entity : mc.level.entitiesForRendering()) {
            Find find = findOn(entity);
            if (find == null) {
                continue;
            }
            finds.add(find);
            if (chat.isOn() && sightings.firstTime(entity)) {
                ChatUtil.message("§bCollectibles " + find.sentence() + "§7.");
            }
        }
        found = finds;
    }

    // Null when the entity shows nothing picked or its kind is switched off.
    private Find findOn(Entity entity) {
        return switch (entity) {
            case ItemEntity item -> dropped.isOn() && picked(item.getItem())
                ? shown(item, item.getItem(), " §7lies on the ground at §f") : null;
            case ItemFrame frame -> frames.isOn() && picked(frame.getItem())
                ? shown(frame, frame.getItem(), " §7hangs in an item frame at §f") : null;
            case ArmorStand stand -> stands.isOn() ? carried(stand) : null;
            case Mob mob -> mobs.isOn() && !EntityUtil.isYours(mob) ? carried(mob) : null;
            default -> null;
        };
    }

    private boolean picked(ItemStack stack) {
        return !stack.isEmpty() && items.contains(stack.getItem());
    }

    private static Find shown(Entity entity, ItemStack stack, String where) {
        String name = stack.getHoverName().getString();
        return new Find(entity, name, "§f" + name + where + BlockUtil.text(entity.blockPosition()));
    }

    private Find carried(LivingEntity holder) {
        for (EquipmentSlot slot : EquipmentSlot.VALUES) {
            ItemStack stack = holder.getItemBySlot(slot);
            if (!picked(stack)) {
                continue;
            }
            String name = stack.getHoverName().getString();
            String verb = slot.getType() == EquipmentSlot.Type.HAND ? " §7is holding §f" : " §7is wearing §f";
            return new Find(holder, name, "§f" + holder.getType().getDescription().getString() + " §7at §f"
                + BlockUtil.text(holder.blockPosition()) + verb + name);
        }
        return null;
    }

    private static AABB boxOf(Entity entity, float partialTicks) {
        AABB box = EntityUtil.lerpedBox(entity, partialTicks);
        return entity instanceof ItemEntity ? box.inflate(ITEM_GROW) : box;
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!inGame() || found.isEmpty()) {
            return;
        }
        DrawBatch batch = event.getBatch();
        for (Find find : found) {
            if (find.entity().isRemoved()) {
                continue;
            }
            AABB box = boxOf(find.entity(), event.getPartialTicks());
            style.draw(batch, box, true);
            if (tracers.isOn()) {
                batch.tracer(box.getCenter(), style.lineColor(), true);
            }
        }
    }

    @Subscribe
    private void onRender2D(Render2DEvent event) {
        if (!labels.isOn() || !inGame() || found.isEmpty() || !WorldToScreen.update()) {
            return;
        }
        for (Find find : found) {
            if (find.entity().isRemoved()) {
                continue;
            }
            AABB box = boxOf(find.entity(), event.getPartialTicks());
            Vec3 top = new Vec3((box.minX + box.maxX) / 2, box.maxY + LABEL_LIFT, (box.minZ + box.maxZ) / 2);
            Vec3 screen = WorldToScreen.project(top);
            if (screen != null) {
                RenderUtil.label(event.getContext(), mc.font, screen.x, screen.y, scale.getFloat(),
                    List.of(find.name()), List.of(style.lineColor()));
            }
        }
    }
}
