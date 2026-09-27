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
import com.jellypudding.offlineclient.util.EntityColors;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.RenderUtil;
import com.jellypudding.offlineclient.util.Sightings;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

// Mobs spawn with at most diamond armour and a few fixed weapons with no names and no
// treasure enchantments. Anything past that was picked up where a player had been.
public final class GearedMobs extends Module {

    // What gave a mob away. The verb says whether it is held or worn.
    private record Clue(Mob mob, String verb, String what) {
    }

    private static final String NETHERITE = "netherite";
    private static final String PICKAXE = "_pickaxe";

    private static final double LABEL_LIFT = 0.5;

    private final RegistryListSetting<Item> items = new RegistryListSetting<>("Items",
        "Items no mob spawns with. A mob holding or wearing one picked it up where a player had been."
            + " Click to pick them.",
        BuiltInRegistries.ITEM, playerItems());
    private final BoolSetting named = new BoolSetting("Named gear",
        "Flags gear renamed at an anvil. Nothing a mob spawns with has a name.", true);
    private final BoolSetting treasure = new BoolSetting("Treasure enchantments",
        "Flags gear with Mending or another enchantment that spawned gear never gets.", true);
    private final BoolSetting allays = new BoolSetting("Allays",
        "Flags an allay carrying anything. Only a player can hand an allay its first item.", true);
    private final BoxStyle shape = BoxStyle.shapeOnly(BoxStyle.Shape.BOTH);
    private final BoolSetting tracers = new BoolSetting("Tracers",
        "Also draws a line from you to each one.", false);
    private final BoolSetting labels = new BoolSetting("Labels",
        "Writes what gave each mob away above its head.", true);
    private final NumberSetting scale = new NumberSetting("Scale",
        "Size of the labels.", 1, 0.5, 3, 0.1).min(0.1).under(labels);
    private final BoolSetting chat = new BoolSetting("Chat",
        "Posts each mob in chat with where it stands the first time you see it.", true);
    private final EntityColors colors = new EntityColors(EntityColors.Mode.SINGLE);

    // Rebuilt once a tick.
    private List<Clue> found = List.of();
    private final Sightings sightings = new Sightings();

    public GearedMobs() {
        super("GearedMobs", "Highlights mobs carrying gear that only players bring into the world.",
            Category.HUNTING);
        addSettings(items, named, treasure, allays);
        addSettings(shape.settings());
        addSettings(tracers, labels, scale, chat);
        addSettings(colors.settings());
        searchTags("mob gear", "zombie", "netherite", "base");
    }

    // Nothing spawns holding or wearing any of these. Netherite and pickaxes and shulker
    // boxes are gathered by name because tags are not loaded when this runs.
    private static List<Item> playerItems() {
        List<Item> picked = new ArrayList<>(List.of(Items.ELYTRA, Items.TOTEM_OF_UNDYING,
            Items.PLAYER_HEAD, Items.MACE, Items.END_CRYSTAL, Items.ENDER_CHEST, Items.RESPAWN_ANCHOR,
            Items.BEACON, Items.NETHER_STAR, Items.DRAGON_EGG, Items.TURTLE_HELMET, Items.WOLF_ARMOR,
            Items.CONDUIT, Items.RECOVERY_COMPASS, Items.FIREWORK_ROCKET, Items.SHIELD));
        BuiltInRegistries.ITEM.stream()
            .filter(item -> {
                String path = BuiltInRegistries.ITEM.getKey(item).getPath();
                return path.startsWith(NETHERITE) || path.endsWith(PICKAXE)
                    || Block.byItem(item) instanceof ShulkerBoxBlock;
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
        List<Clue> clues = new ArrayList<>();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof Mob mob) || ignored(mob)) {
                continue;
            }
            Clue clue = clueOf(mob);
            if (clue == null) {
                continue;
            }
            clues.add(clue);
            if (chat.isOn() && sightings.firstTime(mob)) {
                ChatUtil.message("§bGearedMobs §f" + mob.getType().getDescription().getString() + " §7at §f"
                    + BlockUtil.text(mob.blockPosition()) + " §7is " + clue.verb() + " §f" + clue.what() + "§7.");
            }
        }
        found = clues;
    }

    // Villagers hold up whatever they trade. Your own pets and mount wear what you gave them.
    private boolean ignored(Mob mob) {
        return mob instanceof AbstractVillager || EntityUtil.isYours(mob);
    }

    private Clue clueOf(Mob mob) {
        if (allays.isOn() && mob instanceof Allay allay && allay.hasItemInHand()) {
            return new Clue(mob, "carrying", allay.getMainHandItem().getHoverName().getString());
        }
        for (EquipmentSlot slot : EquipmentSlot.VALUES) {
            ItemStack stack = mob.getItemBySlot(slot);
            String sign = stack.isEmpty() ? null : playerSign(stack);
            if (sign != null) {
                return new Clue(mob, slot.getType() == EquipmentSlot.Type.HAND ? "holding" : "wearing", sign);
            }
        }
        return null;
    }

    // What marks the stack as a player's or null when nothing does.
    private String playerSign(ItemStack stack) {
        if (items.contains(stack.getItem())) {
            return stack.getItemName().getString();
        }
        if (named.isOn() && stack.has(DataComponents.CUSTOM_NAME)) {
            return stack.getItemName().getString() + " named " + stack.getHoverName().getString();
        }
        if (treasure.isOn()) {
            for (Object2IntMap.Entry<Holder<Enchantment>> entry : stack.getEnchantments().entrySet()) {
                if (entry.getKey().is(EnchantmentTags.TREASURE)) {
                    return stack.getItemName().getString() + " with "
                        + Enchantment.getFullname(entry.getKey(), entry.getIntValue()).getString();
                }
            }
        }
        return null;
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!inGame() || found.isEmpty()) {
            return;
        }
        DrawBatch batch = event.getBatch();
        for (Clue clue : found) {
            Mob mob = clue.mob();
            if (mob.isRemoved()) {
                continue;
            }
            AABB box = EntityUtil.lerpedBox(mob, event.getPartialTicks());
            int color = colors.colorOf(mob);
            shape.draw(batch, box, color, true);
            if (tracers.isOn()) {
                batch.tracer(box.getCenter(), color, true);
            }
        }
    }

    @Subscribe
    private void onRender2D(Render2DEvent event) {
        if (!labels.isOn() || !inGame() || found.isEmpty() || !WorldToScreen.update()) {
            return;
        }
        for (Clue clue : found) {
            Mob mob = clue.mob();
            if (mob.isRemoved()) {
                continue;
            }
            AABB box = EntityUtil.lerpedBox(mob, event.getPartialTicks());
            Vec3 top = new Vec3((box.minX + box.maxX) / 2, box.maxY + LABEL_LIFT, (box.minZ + box.maxZ) / 2);
            Vec3 screen = WorldToScreen.project(top);
            if (screen != null) {
                RenderUtil.label(event.getContext(), mc.font, screen.x, screen.y, scale.getFloat(),
                    List.of(clue.what()), List.of(colors.colorOf(mob)));
            }
        }
    }
}
