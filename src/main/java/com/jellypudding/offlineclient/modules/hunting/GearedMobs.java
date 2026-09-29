package com.jellypudding.offlineclient.modules.hunting;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render2DEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.render.NearFade;
import com.jellypudding.offlineclient.render.WorldToScreen;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.Carried;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.EntityColors;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.GearRule;
import com.jellypudding.offlineclient.util.ItemUtil;
import com.jellypudding.offlineclient.util.Notice;
import com.jellypudding.offlineclient.util.RenderUtil;
import com.jellypudding.offlineclient.util.Sightings;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

// Mobs spawn with at most diamond armour and a few fixed weapons with no names and no
// treasure enchantments. Anything past that was picked up where a player had been.
public final class GearedMobs extends Module {

    // A mob with every piece that gave it away.
    private record Suspect(Mob mob, List<Carried.Piece> pieces) {
    }

    // What every message of this module is about. One quiet time covers them all.
    private static final String KIND = "mob";

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
    private final GearRule enchantments = GearRule.clue();
    private final BoolSetting allays = new BoolSetting("Allays",
        "Flags an allay carrying anything. Only a player can hand an allay its first item.", true);
    private final BoxStyle shape = BoxStyle.shapeOnly(BoxStyle.Shape.BOTH);
    private final BoolSetting tracers = new BoolSetting("Tracers",
        "Also draws a line from you to each one.", false);
    private final BoolSetting labels = new BoolSetting("Labels",
        "Writes what gave each mob away above its head.", true);
    private final NumberSetting scale = new NumberSetting("Scale",
        "Size of the labels.", 1, 0.5, 3, 0.1).min(0.1).under(labels);
    private final Notice notice = new Notice(this, Notice.Where.CHAT);
    private final EntityColors colors = new EntityColors(EntityColors.Mode.SINGLE);
    // A mob walking up to you would hide behind its own box.
    private final NearFade fade = new NearFade(3);

    // Rebuilt once a tick.
    private List<Suspect> found = List.of();
    private final Sightings sightings = new Sightings();

    public GearedMobs() {
        super("GearedMobs", "Highlights mobs carrying gear that only players bring into the world.",
            Category.HUNTING);
        addSettings(items, named, treasure);
        addSettings(enchantments.settings());
        addSettings(allays);
        addSettings(shape.settings());
        addSettings(tracers, labels, scale);
        addSettings(notice.settings());
        addSettings(colors.settings());
        addSettings(fade.setting());
        searchTags("mob gear", "zombie", "netherite", "enchanted", "base");
    }

    // Nothing spawns holding or wearing any of these. Netherite and pickaxes are gathered by
    // name and shulker boxes by their block because tags are not loaded when this runs.
    private static List<Item> playerItems() {
        List<Item> picked = new ArrayList<>(List.of(Items.ELYTRA, Items.TOTEM_OF_UNDYING,
            Items.PLAYER_HEAD, Items.MACE, Items.END_CRYSTAL, Items.ENDER_CHEST, Items.RESPAWN_ANCHOR,
            Items.BEACON, Items.NETHER_STAR, Items.DRAGON_EGG, Items.TURTLE_HELMET, Items.WOLF_ARMOR,
            Items.CONDUIT, Items.RECOVERY_COMPASS, Items.FIREWORK_ROCKET, Items.SHIELD));
        BuiltInRegistries.ITEM.stream()
            .filter(item -> {
                String path = BuiltInRegistries.ITEM.getKey(item).getPath();
                return path.startsWith(NETHERITE) || path.endsWith(PICKAXE) || ItemUtil.isShulkerBox(item);
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
        List<Suspect> suspects = new ArrayList<>();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof Mob mob) || ignored(mob)) {
                continue;
            }
            List<Carried.Piece> pieces = piecesOf(mob);
            if (pieces.isEmpty()) {
                continue;
            }
            suspects.add(new Suspect(mob, pieces));
            if (sightings.firstTime(mob)) {
                notice.tell(KIND, ChatUtil.withArticle(ChatUtil.words(mob.getType())) + " "
                    + Carried.describe(pieces), mob.blockPosition());
            }
        }
        found = suspects;
    }

    // Villagers hold up whatever they trade. Your own pets and mount wear what you gave them.
    private boolean ignored(Mob mob) {
        return mob instanceof AbstractVillager || EntityUtil.isYours(mob);
    }

    private List<Carried.Piece> piecesOf(Mob mob) {
        if (allays.isOn() && mob instanceof Allay allay && allay.hasItemInHand()) {
            return List.of(new Carried.Piece("carrying", ChatUtil.words(allay.getMainHandItem().getItem())));
        }
        return Carried.pieces(mob, this::playerSign);
    }

    // What marks the stack as a player's or null when nothing does.
    private String playerSign(ItemStack stack) {
        String name = ChatUtil.words(stack.getItem());
        if (items.contains(stack.getItem())) {
            return name;
        }
        if (named.isOn() && stack.has(DataComponents.CUSTOM_NAME)) {
            return name + " named " + stack.getHoverName().getString();
        }
        if (treasure.isOn()) {
            for (Object2IntMap.Entry<Holder<Enchantment>> entry : stack.getEnchantments().entrySet()) {
                if (entry.getKey().is(EnchantmentTags.TREASURE)) {
                    return name + " with " + Enchantment.getFullname(entry.getKey(), entry.getIntValue()).getString();
                }
            }
        }
        String picked = enchantments.carried(stack);
        return picked == null ? null : name + " with " + picked;
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!inGame() || found.isEmpty()) {
            return;
        }
        DrawBatch batch = event.getBatch();
        for (Suspect suspect : found) {
            Mob mob = suspect.mob();
            if (mob.isRemoved()) {
                continue;
            }
            AABB box = EntityUtil.lerpedBox(mob, event.getPartialTicks());
            float strength = fade.strengthAt(box.getCenter());
            if (strength <= 0) {
                continue;
            }
            int color = ColorUtil.fade(colors.colorOf(mob), strength);
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
        for (Suspect suspect : found) {
            Mob mob = suspect.mob();
            if (mob.isRemoved()) {
                continue;
            }
            AABB box = EntityUtil.lerpedBox(mob, event.getPartialTicks());
            Vec3 top = new Vec3((box.minX + box.maxX) / 2, box.maxY + LABEL_LIFT, (box.minZ + box.maxZ) / 2);
            Vec3 screen = WorldToScreen.project(top);
            if (screen != null) {
                RenderUtil.label(event.getContext(), mc.font, screen.x, screen.y, scale.getFloat(),
                    List.of(Carried.label(suspect.pieces())), List.of(colors.colorOf(mob)));
            }
        }
    }
}
