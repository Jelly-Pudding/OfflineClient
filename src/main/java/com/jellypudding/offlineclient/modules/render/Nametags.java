package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render2DEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.WorldToScreen;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ChoiceListSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.EntityFilter;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.RenderUtil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.minecart.MinecartTNT;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3x2fStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

// Drawn on the HUD at the projected head position.
public final class Nametags extends Module {

    public enum Durability { NONE, BAR, NUMBER, PERCENT }

    public enum EnchantPosition { ABOVE, ON_TOP }

    public enum DistanceColor { GRADIENT, FLAT }

    private static final int ITEM_SIZE = 16;
    private static final int GOLD = 0xFFE8B923;
    private static final int RED = 0xFFFF4040;
    private static final int TICKS_PER_SECOND = 20;
    private static final int TICKS_PER_MINUTE = TICKS_PER_SECOND * 60;
    private static final int TICKS_PER_HOUR = TICKS_PER_MINUTE * 60;

    // The order the six item slots are drawn in.
    private static final EquipmentSlot[] SLOTS = {
        EquipmentSlot.MAINHAND, EquipmentSlot.HEAD, EquipmentSlot.CHEST,
        EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.OFFHAND
    };

    private static final List<String> DEFAULT_ENCHANTMENTS = List.of(
        "minecraft:protection", "minecraft:blast_protection",
        "minecraft:fire_protection", "minecraft:projectile_protection");

    private record Tag(Entity entity, Vec3 screen, double distance) {
    }

    private record Enchant(String text, int color) {
    }

    private final NumberSetting scale = new NumberSetting("Scale",
        "Size of the tags.", 1, 0.5, 3, 0.1).min(0.1);
    private final NumberSetting range = new NumberSetting("Range",
        "Furthest entity to tag.", 128, 16, 256, 8, " blocks").min(1);
    private final NumberSetting limit = new NumberSetting("Tag limit",
        "How many of the nearest entities get a tag.", 50, 1, 100, 1).min(1);
    private final EntityFilter filter = new EntityFilter("Tag", "tagged", true,
        EntityFilter.Pick.NONE, EntityFilter.Pick.ALL,
        List.of(EntityTypes.ITEM_FRAME, EntityTypes.GLOW_ITEM_FRAME));
    private final BoolSetting self = new BoolSetting("Self",
        "Tag yourself whilst in third person or Freecam.", true);
    private final BoolSetting ignoreFriends = new BoolSetting("Ignore friends",
        "Friends get no tag.", false);
    private final BoolSetting ignoreBots = new BoolSetting("Ignore bots",
        "Players missing from the tab list get no tag. Off labels them as bots.", true);
    private final BoolSetting displayName = new BoolSetting("Display name",
        "Show the server display name with any team prefix instead of the account name.", false);
    private final BoolSetting gameMode = new BoolSetting("Game mode",
        "Put the player's game mode in front of the name.", false);
    private final BoolSetting health = new BoolSetting("Health",
        "Show hearts left including absorption.", true);
    private final BoolSetting ping = new BoolSetting("Ping",
        "Show the player's latency.", true);
    private final BoolSetting pingByLatency = new BoolSetting("Ping by latency",
        "Colour the ping green then amber then red as it climbs.", true).under(ping);
    private final ColorSetting pingColor = new ColorSetting("Ping colour",
        "Colour of the ping text.", 180, 0.88f, 0.67f, false).under(ping, () -> ping.isOn() && !pingByLatency.isOn());
    private final BoolSetting distance = new BoolSetting("Distance",
        "Show how far away the player is.", false);
    private final EnumSetting<DistanceColor> distanceColor = new EnumSetting<>("Distance colour mode",
        "How the distance text is coloured.", DistanceColor.GRADIENT)
        .describe(DistanceColor.GRADIENT, "Red up close through to green far away.")
        .describe(DistanceColor.FLAT, "One colour picked below.")
        .under(distance);
    private final ColorSetting flatDistanceColor = new ColorSetting("Distance colour",
        "Colour of the distance text.", 0, 0f, 0.59f, false)
        .under(distance, () -> distance.isOn() && distanceColor.is(DistanceColor.FLAT));
    private final BoolSetting items = new BoolSetting("Held items",
        "Show what they hold in each hand.", true);
    private final BoolSetting armor = new BoolSetting("Armour",
        "Show their armour next to the held items.", true);
    private final NumberSetting itemSpacing = new NumberSetting("Item spacing",
        "Gap in pixels between the item icons.", 2, 0, 10, 1, " px");
    private final BoolSetting ignoreEmpty = new BoolSetting("Ignore empty slots",
        "Empty slots take no room. Off keeps every icon in a fixed column.", true);
    private final EnumSetting<Durability> durability = new EnumSetting<>("Durability",
        "How the wear of each item is shown.", Durability.BAR)
        .describe(Durability.NONE, "Nothing.")
        .describe(Durability.BAR, "The usual bar under the icon.")
        .describe(Durability.NUMBER, "The points left written over the icon.")
        .describe(Durability.PERCENT, "The share left written over the icon.");
    private final BoolSetting showCount = new BoolSetting("Item count",
        "Show how many are in a dropped stack.", true);
    private final BoolSetting enchantments = new BoolSetting("Enchantments",
        "Write the enchantments of each item by its icon.", false);
    private final ChoiceListSetting shownEnchantments = new ChoiceListSetting("Shown enchantments",
        "Which enchantments are written. Join a world to fill the list.", Nametags::enchantmentIds,
        DEFAULT_ENCHANTMENTS).under(enchantments);
    private final EnumSetting<EnchantPosition> enchantPosition = new EnumSetting<>("Enchantment position",
        "Where the enchantment text sits.", EnchantPosition.ABOVE)
        .describe(EnchantPosition.ABOVE, "Above the row of icons.")
        .describe(EnchantPosition.ON_TOP, "Over each icon.")
        .under(enchantments);
    private final NumberSetting enchantLetters = new NumberSetting("Enchantment letters",
        "How many letters of each enchantment name are kept.", 3, 1, 5, 1).min(1).under(enchantments);
    private final NumberSetting enchantScale = new NumberSetting("Enchantment scale",
        "Size of the enchantment text.", 1, 0.1, 2, 0.1).min(0.1).under(enchantments);
    private final ColorSetting nameColor = new ColorSetting("Name colour",
        "Colour of the name text.", 0, 0f, 1f, false);
    private final ColorSetting gameModeColor = new ColorSetting("Game mode colour",
        "Colour of the game mode letters.", 46, 0.85f, 0.91f, false).under(gameMode);
    private final ColorSetting background = new ColorSetting("Background colour",
        "Colour behind the tag.", 0, 0f, 0f, false);
    private final NumberSetting backgroundOpacity = new NumberSetting("Background opacity",
        "How solid the background is.", 55, 0, 100, 5, "%");

    public Nametags() {
        super("Nametags", "Shows health and ping and gear above players and names above other things.",
            Category.RENDER);
        addSettings(scale, range, limit);
        addSettings(filter.settings());
        addSettings(self, ignoreFriends, ignoreBots, displayName, gameMode, health, ping, pingByLatency,
            pingColor, distance, distanceColor, flatDistanceColor, items, armor, itemSpacing, ignoreEmpty,
            durability, showCount, enchantments, shownEnchantments, enchantPosition, enchantLetters,
            enchantScale, nameColor, gameModeColor, background, backgroundOpacity);
        searchTags("name tags", "player info");
    }

    // Every enchantment the world knows. Empty until a world is loaded.
    private static Collection<String> enchantmentIds() {
        if (mc.level == null) {
            return List.of();
        }
        return mc.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).keySet().stream()
            .map(Object::toString).sorted().collect(Collectors.toList());
    }

    @Subscribe
    private void onRender2D(Render2DEvent event) {
        if (!inGame() || !WorldToScreen.update()) {
            return;
        }
        float partialTicks = event.getPartialTicks();
        Vec3 camera = WorldToScreen.cameraPos();
        List<Tag> tags = new ArrayList<>();

        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!wanted(entity)) {
                continue;
            }
            Vec3 feet = entity.getPosition(partialTicks);
            double dist = camera.distanceTo(feet);
            if (dist > range.getValue()) {
                continue;
            }
            Vec3 screen = WorldToScreen.project(feet.add(0, tagHeight(entity), 0));
            if (screen != null) {
                tags.add(new Tag(entity, screen, dist));
            }
        }

        tags.sort((a, b) -> Double.compare(a.distance(), b.distance()));
        if (tags.size() > limit.getInt()) {
            tags = tags.subList(0, limit.getInt());
        }
        // Far tags draw first so near ones sit on top.
        for (int i = tags.size() - 1; i >= 0; i--) {
            drawTag(event.getContext(), tags.get(i));
        }
    }

    private boolean wanted(Entity entity) {
        if (entity == mc.player) {
            return filter.wantsPlayers() && showSelf();
        }
        if (entity instanceof Player player) {
            if (!filter.matches(player)) {
                return false;
            }
            if (ignoreFriends.isOn() && EntityUtil.isFriend(player)) {
                return false;
            }
            return !ignoreBots.isOn() || tabEntry(player) != null;
        }
        return filter.matches(entity);
    }

    private boolean showSelf() {
        if (!self.isOn()) {
            return false;
        }
        if (Modules.enabled(Freecam.class)) {
            return true;
        }
        return !mc.options.getCameraType().isFirstPerson();
    }

    // Items sit low so their tag hugs them. Everything else gets a little headroom.
    private static double tagHeight(Entity entity) {
        boolean item = entity instanceof ItemEntity || entity instanceof ItemFrame;
        return entity.getEyeHeight() + (item ? 0.2 : 0.5);
    }

    private void drawTag(GuiGraphicsExtractor context, Tag tag) {
        // Shrink with distance down to half size.
        float factor = (float) (scale.getValue() * Math.clamp(1 - tag.distance() / 100.0, 0.5, 1));
        switch (tag.entity()) {
            case Player player -> drawPlayer(context, tag, player, factor);
            case ItemEntity item -> drawItem(context, tag, item.getItem(), factor);
            case ItemFrame frame -> drawItem(context, tag, frame.getItem(), factor);
            case PrimedTnt tnt -> drawText(context, tag, fuseText(tnt.getFuse()), factor);
            case MinecartTNT cart -> drawText(context, tag, fuseText(cart.getFuse()), factor);
            case LivingEntity living -> drawLiving(context, tag, living, factor);
            default -> drawText(context, tag, typeName(tag.entity()), factor);
        }
    }

    private void drawPlayer(GuiGraphicsExtractor context, Tag tag, Player player, float factor) {
        List<String> parts = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();

        if (gameMode.isOn()) {
            parts.add("[" + gameModeText(player) + "] ");
            colors.add(gameModeColor.getColor());
        }
        parts.add(nameText(player));
        colors.add(playerNameColor(player));

        if (health.isOn()) {
            addHealth(parts, colors, player);
        }
        if (ping.isOn()) {
            int latency = latencyOf(player);
            if (latency >= 0) {
                parts.add(" " + latency + "ms");
                colors.add(pingByLatency.isOn() ? ColorUtil.ping(latency) : pingColor.getColor());
            }
        }
        if (distance.isOn() && (player != mc.player || Modules.enabled(Freecam.class))) {
            parts.add(String.format(" %.1fm", tag.distance()));
            colors.add(distanceColor.is(DistanceColor.FLAT)
                ? flatDistanceColor.getColor() : EntityUtil.distanceColor(player));
        }

        label(context, tag, factor, parts, colors);
        drawGear(context, tag, player, factor);
    }

    private void drawLiving(GuiGraphicsExtractor context, Tag tag, LivingEntity living, float factor) {
        List<String> parts = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        parts.add(typeName(living));
        colors.add(nameColor.getColor());
        addHealth(parts, colors, living);
        label(context, tag, factor, parts, colors);
    }

    private void drawItem(GuiGraphicsExtractor context, Tag tag, ItemStack stack, float factor) {
        if (stack.isEmpty()) {
            return;
        }
        List<String> parts = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        parts.add(stack.getHoverName().getString());
        colors.add(nameColor.getColor());
        if (showCount.isOn()) {
            parts.add(" x" + stack.getCount());
            colors.add(GOLD);
        }
        label(context, tag, factor, parts, colors);
    }

    private void drawText(GuiGraphicsExtractor context, Tag tag, String text, float factor) {
        label(context, tag, factor, List.of(text), List.of(nameColor.getColor()));
    }

    private void label(GuiGraphicsExtractor context, Tag tag, float factor,
                       List<String> parts, List<Integer> colors) {
        RenderUtil.label(context, mc.font, tag.screen().x, tag.screen().y, factor, parts, colors,
            ColorUtil.fade(background.getColor(), backgroundOpacity.getFloat() / 100f));
    }

    private static void addHealth(List<String> parts, List<Integer> colors, LivingEntity living) {
        float hp = EntityUtil.totalHealth(living);
        float max = EntityUtil.totalMaxHealth(living);
        parts.add(String.format(" %.0f", hp));
        colors.add(ColorUtil.health(max <= 0 ? 0 : hp / max));
    }

    private String nameText(Player player) {
        if (player != mc.player && displayName.isOn()) {
            return player.getDisplayName().getString();
        }
        return EntityUtil.displayNameOf(player);
    }

    private int playerNameColor(Player player) {
        if (EntityUtil.isFriend(player)) {
            return EntityUtil.FRIEND_COLOR;
        }
        return player == mc.player ? 0xFFB0FFB0 : nameColor.getColor();
    }

    private String gameModeText(Player player) {
        PlayerInfo info = tabEntry(player);
        if (info == null) {
            return "BOT";
        }
        GameType mode = info.getGameMode();
        return switch (mode) {
            case SURVIVAL -> "S";
            case CREATIVE -> "C";
            case ADVENTURE -> "A";
            case SPECTATOR -> "Sp";
        };
    }

    private static String typeName(Entity entity) {
        return entity.getType().getDescription().getString();
    }

    // Seconds with a tenth whilst short and whole minutes or hours beyond.
    private static String fuseText(int ticks) {
        if (ticks > TICKS_PER_HOUR) {
            return ticks / TICKS_PER_HOUR + " h";
        }
        if (ticks > TICKS_PER_MINUTE) {
            return ticks / TICKS_PER_MINUTE + " m";
        }
        return ticks / TICKS_PER_SECOND + "." + (ticks % TICKS_PER_SECOND) / 2 + " s";
    }

    private void drawGear(GuiGraphicsExtractor context, Tag tag, Player player, float factor) {
        if (!items.isOn() && !armor.isOn()) {
            return;
        }
        Font font = mc.font;
        ItemStack[] gear = new ItemStack[SLOTS.length];
        int[] widths = new int[SLOTS.length];
        List<List<Enchant>> enchants = new ArrayList<>();
        int total = 0;
        int deepest = 0;
        boolean any = false;
        for (int i = 0; i < SLOTS.length; i++) {
            gear[i] = slotItem(player, SLOTS[i]);
            any |= !gear[i].isEmpty();
            List<Enchant> list = enchantments.isOn() ? enchantsOf(gear[i]) : List.of();
            enchants.add(list);
            deepest = Math.max(deepest, list.size());
            if (!gear[i].isEmpty() || !ignoreEmpty.isOn()) {
                widths[i] = ITEM_SIZE + itemSpacing.getInt();
            }
            for (Enchant enchant : list) {
                widths[i] = Math.max(widths[i], enchantWidth(font, enchant));
            }
            total += widths[i];
        }
        if (!any) {
            return;
        }

        Matrix3x2fStack pose = context.pose();
        pose.pushMatrix();
        pose.translate((float) tag.screen().x, (float) tag.screen().y);
        pose.scale(factor, factor);
        context.guiRenderState.up();
        // The label background reaches two pixels above the point.
        int y = -ITEM_SIZE - 6;
        int x = -total / 2;
        for (int i = 0; i < SLOTS.length; i++) {
            ItemStack stack = gear[i];
            if (widths[i] == 0) {
                continue;
            }
            int iconX = x + (widths[i] - ITEM_SIZE - itemSpacing.getInt()) / 2;
            if (!stack.isEmpty()) {
                context.item(stack, iconX, y);
                drawDurability(context, font, stack, iconX, y);
            }
            drawEnchants(context, font, enchants.get(i), x, y, widths[i], deepest);
            x += widths[i];
        }
        pose.popMatrix();
    }

    private ItemStack slotItem(Player player, EquipmentSlot slot) {
        boolean hand = slot == EquipmentSlot.MAINHAND || slot == EquipmentSlot.OFFHAND;
        if (hand ? !items.isOn() : !armor.isOn()) {
            return ItemStack.EMPTY;
        }
        return player.getItemBySlot(slot);
    }

    private void drawDurability(GuiGraphicsExtractor context, Font font, ItemStack stack, int x, int y) {
        Durability mode = durability.getValue();
        if (mode == Durability.NONE || !stack.isDamageableItem()) {
            return;
        }
        if (mode == Durability.BAR) {
            context.itemDecorations(font, stack, x, y);
            return;
        }
        int left = stack.getMaxDamage() - stack.getDamageValue();
        String text = mode == Durability.NUMBER
            ? String.valueOf(left) : Math.round(left * 100f / stack.getMaxDamage()) + "%";
        context.guiRenderState.up();
        smallText(context, font, text, x, y, 0.75f, ColorUtil.withAlpha(stack.getBarColor(), 255));
    }

    private void drawEnchants(GuiGraphicsExtractor context, Font font, List<Enchant> list,
                              int x, int y, int width, int deepest) {
        if (list.isEmpty()) {
            return;
        }
        float size = 0.5f * enchantScale.getFloat();
        float lineHeight = font.lineHeight * size;
        float top = enchantPosition.is(EnchantPosition.ABOVE)
            ? y - (list.size() + 1) * lineHeight
            : y + (ITEM_SIZE - list.size() * lineHeight) / 2;
        context.guiRenderState.up();
        for (Enchant enchant : list) {
            float textX = x + (width - font.width(enchant.text()) * size) / 2;
            smallText(context, font, enchant.text(), textX, top, size, enchant.color());
            top += lineHeight;
        }
    }

    private static void smallText(GuiGraphicsExtractor context, Font font, String text,
                                  float x, float y, float size, int color) {
        Matrix3x2fStack pose = context.pose();
        pose.pushMatrix();
        pose.translate(x, y);
        pose.scale(size, size);
        context.text(font, text, 0, 0, color, true);
        pose.popMatrix();
    }

    private int enchantWidth(Font font, Enchant enchant) {
        return Math.round(font.width(enchant.text()) * 0.5f * enchantScale.getFloat());
    }

    // The chosen enchantments of a stack with curses in red.
    private List<Enchant> enchantsOf(ItemStack stack) {
        if (stack.isEmpty()) {
            return List.of();
        }
        ItemEnchantments all = EnchantmentHelper.getEnchantmentsForCrafting(stack);
        List<Enchant> list = new ArrayList<>();
        for (Holder<Enchantment> holder : all.keySet()) {
            String id = holder.unwrapKey().map(key -> key.identifier().toString()).orElse("");
            if (!shownEnchantments.contains(id)) {
                continue;
            }
            String name = holder.value().description().getString();
            String shortName = name.substring(0, Math.min(name.length(), enchantLetters.getInt()));
            int color = holder.is(EnchantmentTags.CURSE) ? RED : 0xFFFFFFFF;
            list.add(new Enchant(shortName + " " + all.getLevel(holder), color));
        }
        return list;
    }

    private PlayerInfo tabEntry(Player player) {
        return mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(player.getUUID());
    }

    // Ping from the tab list or -1 if the player is not on it.
    private int latencyOf(Player player) {
        PlayerInfo info = tabEntry(player);
        return info == null ? -1 : info.getLatency();
    }
}
