package com.jellypudding.offlineclient.hud.elements;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.gui.GuiTheme;
import com.jellypudding.offlineclient.hud.HudElement;
import com.jellypudding.offlineclient.hud.RowCache;
import com.jellypudding.offlineclient.hud.TextRow;
import com.jellypudding.offlineclient.render.BodyPicture;
import com.jellypudding.offlineclient.render.EntityPreview;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ChoiceListSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.EnchantText;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.ItemUtil;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.SeenEffects;
import com.jellypudding.offlineclient.util.ServerInfo;
import com.jellypudding.offlineclient.util.Totems;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.core.Holder;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

// Whoever you are fighting with their gear and how hurt they are. The target of any aura
// that is on comes first and then whoever is under the crosshair.
public final class CombatElement extends HudElement {

    private static final int BAR_WIDTH = 80;
    private static final int BAR_HEIGHT = 4;
    private static final int GAP = 2;
    private static final int SLOT = 16;

    // Their main hand then head to feet then the other hand.
    private static final List<EquipmentSlot> GEAR = List.of(EquipmentSlot.MAINHAND, EquipmentSlot.HEAD,
        EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.OFFHAND);

    // Enchantments are written at half size under each piece. Four letters tell Protection
    // from Projectile Protection.
    private static final float ENCHANT_SIZE = 0.5f;
    private static final int ENCHANT_LETTERS = 4;

    // The bar keeps room after a player's health for the absorption an enchanted golden apple gives.
    private static final float ABSORPTION_ROOM = 16;
    private static final int ABSORPTION_COLOR = 0xFFFFCC33;
    private static final int POPS_COLOR = 0xFFFFC040;

    // The body stands in a box this size beside the writing and fills most of its height.
    private static final int MODEL_WIDTH = 30;
    private static final int MODEL_HEIGHT = 48;
    private static final float MODEL_FILL = 0.85f;

    // Anything in hand that wins a fight or blows one up.
    private static final Predicate<ItemStack> THREAT = stack -> stack.is(ItemTags.SWORDS)
        || stack.is(ItemTags.AXES) || stack.is(ItemTags.SPEARS) || stack.is(Items.MACE)
        || stack.is(Items.TRIDENT) || stack.is(Items.END_CRYSTAL) || stack.is(Items.RESPAWN_ANCHOR)
        || stack.is(ItemTags.BEDS);

    // What the element shows of one target. The gear is empty whilst it is hidden or they
    // carry nothing. The effects are empty whilst they are hidden or none are seen.
    private record Shown(LivingEntity target, List<TextRow> lines, List<Holder<MobEffect>> effects,
                         List<ItemStack> gear, List<List<EnchantText.Label>> enchants) {
    }

    private final NumberSetting range = new NumberSetting("Combat range",
        "How far away someone still counts as a target.", 16, 4, 64, 1, " blocks").min(1);
    private final BoolSetting nearest = new BoolSetting("Fall back to nearest",
        "Show the nearest enemy whilst nothing else is picked.", true);
    private final BoolSetting distance = new BoolSetting("Show distance",
        "Write how far away they are.", true);
    private final BoolSetting ping = new BoolSetting("Combat ping",
        "Writes their ping coloured from green to red.", true);
    private final BoolSetting status = new BoolSetting("Combat status",
        "Says whether they are a friend or wear no armour or hold a weapon.", true);
    private final BoolSetting pops = new BoolSetting("Combat pops",
        "Writes how many totems they have popped since they last died.", true);
    private final BoolSetting effects = new BoolSetting("Combat effects",
        "Shows icons for the potion effects the target is seen to carry.", true);
    private final BoolSetting gear = new BoolSetting("Combat gear",
        "Shows their armour and what they hold with its wear.", true);
    private final BoolSetting enchantments = new BoolSetting("Combat enchantments",
        "Writes the enchantments on each piece of their gear.", true).under(gear);
    private final ChoiceListSetting shownEnchantments = new ChoiceListSetting("Combat shown enchantments",
        "Which enchantments are written. Join a world to fill the list.", ItemUtil::enchantmentIds,
        EnchantText.PROTECTIONS).under(enchantments);
    private final BoolSetting bar = new BoolSetting("Health bar",
        "Draws a bar of their health with any absorption in gold after it.", true);
    private final BoolSetting model = new BoolSetting("Combat model",
        "Draws their body beside the name.", true);
    // Each gathering reads every piece of their gear and its enchantments.
    private final RowCache<Shown> built = new RowCache<>(this::build);

    public CombatElement() {
        super("Combat", "Who you are fighting with their gear and how hurt they are.", false, 50, 62);
        add(range, nearest, distance, ping, status, pops, effects, gear, enchantments, shownEnchantments, bar, model);
    }

    @Override
    public boolean visible() {
        return isActive() && shown() != null;
    }

    // You stand in as the target whilst the editor is open and nobody else is picked.
    private Shown shown() {
        List<Shown> found = built.get();
        if (!found.isEmpty()) {
            return found.getFirst();
        }
        LocalPlayer player = OfflineClient.MC.player;
        return editing() && player != null ? shownOf(player) : null;
    }

    private List<Shown> build() {
        LivingEntity target = target();
        return target == null ? List.of() : List.of(shownOf(target));
    }

    private LivingEntity target() {
        LocalPlayer player = OfflineClient.MC.player;
        if (player == null) {
            return null;
        }
        LivingEntity picked = Modules.target();
        if (picked != null) {
            return picked;
        }
        Entity looked = OfflineClient.MC.crosshairPickEntity;
        if (looked instanceof LivingEntity living && living != player) {
            return living;
        }
        return nearest.isOn() ? EntityUtil.nearestEnemy(range.getValue()) : null;
    }

    private Shown shownOf(LivingEntity target) {
        Font font = OfflineClient.MC.font;
        List<TextRow> lines = new ArrayList<>(2);
        lines.add(TextRow.of(font, label(target), GuiTheme.HUD_TEXT));
        if (target instanceof Player player) {
            TextRow details = details(font, player);
            if (!details.parts().isEmpty()) {
                lines.add(details);
            }
        }
        List<ItemStack> worn = new ArrayList<>(GEAR.size());
        List<List<EnchantText.Label>> enchants = new ArrayList<>(GEAR.size());
        if (gear.isOn() && GEAR.stream().anyMatch(slot -> !target.getItemBySlot(slot).isEmpty())) {
            for (EquipmentSlot slot : GEAR) {
                ItemStack stack = target.getItemBySlot(slot);
                worn.add(stack);
                enchants.add(enchantments.isOn()
                    ? EnchantText.of(stack, shownEnchantments::contains, ENCHANT_LETTERS) : List.of());
            }
        }
        return new Shown(target, lines, effects.isOn() ? SeenEffects.of(target) : List.of(), worn, enchants);
    }

    private String label(LivingEntity target) {
        String name = EntityUtil.displayNameOf(target);
        String health = String.format(Locale.ROOT, " %.1f", EntityUtil.totalHealth(target));
        if (!distance.isOn()) {
            return name + health;
        }
        return name + health + String.format(Locale.ROOT, " at %.1fm",
            OfflineClient.MC.player.distanceTo(target));
    }

    // Friend or Naked or Threat then the ping and the pops each in its own colour.
    private TextRow details(Font font, Player player) {
        List<String> words = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        if (status.isOn()) {
            if (EntityUtil.isFriend(player)) {
                say(words, colors, "Friend", EntityUtil.FRIEND_COLOR);
            } else if (ItemUtil.ARMOR_SLOTS.stream().allMatch(slot -> player.getItemBySlot(slot).isEmpty())) {
                say(words, colors, "Naked", GuiTheme.GREEN);
            } else if (THREAT.test(player.getMainHandItem()) || THREAT.test(player.getOffhandItem())) {
                say(words, colors, "Threat", GuiTheme.RED);
            }
        }
        int latency = ping.isOn() ? ServerInfo.latency(player.getUUID()) : -1;
        if (latency >= 0) {
            say(words, colors, latency + "ms", ColorUtil.ping(latency));
        }
        int popped = pops.isOn() ? Totems.INSTANCE.popped(player) : 0;
        if (popped > 0) {
            say(words, colors, popped + (popped == 1 ? " pop" : " pops"), POPS_COLOR);
        }
        return TextRow.of(font, words, colors);
    }

    // Every word after the first keeps a space from the one before.
    private static void say(List<String> words, List<Integer> colors, String word, int color) {
        words.add(words.isEmpty() ? word : " " + word);
        colors.add(color);
    }

    @Override
    public void render(GuiGraphicsExtractor context, Font font) {
        Shown shown = shown();
        if (shown == null) {
            return;
        }
        if (model.isOn()) {
            drawModel(context, shown.target());
        }
        int left = left();
        int y = 0;
        for (TextRow line : shown.lines()) {
            line.draw(context, font, left, y);
            y += TextRow.LINE;
        }
        int iconTop = effectsTop(font, shown);
        for (int i = 0; i < shown.effects().size(); i++) {
            context.blitSprite(RenderPipelines.GUI_TEXTURED, Hud.getMobEffectSprite(shown.effects().get(i)),
                left + i * (font.lineHeight + GAP), iconTop, font.lineHeight, font.lineHeight);
        }
        int top = gearTop(font, shown);
        for (int i = 0; i < shown.gear().size(); i++) {
            ItemStack stack = shown.gear().get(i);
            int x = left + i * (SLOT + GAP);
            context.item(stack, x, top);
            context.itemDecorations(font, stack, x, top);
            EnchantText.draw(context, font, shown.enchants().get(i), x, top + SLOT, SLOT, ENCHANT_SIZE,
                textShadow());
        }
        if (bar.isOn()) {
            drawBar(context, shown.target(), left, barTop(font, shown), columnWidth(font, shown));
        }
    }

    // The box is given in screen pixels because this draw ignores the pose. Any body from a
    // bat to a ghast is fitted to it at its standing size. Sneaking or gliding never resizes it.
    private void drawModel(GuiGraphicsExtractor context, LivingEntity target) {
        LivingEntityRenderState state = EntityPreview.flatState(target,
            OfflineClient.MC.getDeltaTracker().getGameTimeDeltaPartialTick(true));
        EntityDimensions standing = target.getDimensions(Pose.STANDING);
        if (state == null || standing.width() <= 0 || standing.height() <= 0) {
            return;
        }
        state.bodyRot = EntityPreview.FACING_YOU;
        int left = boxLeft();
        int top = boxTop();
        int right = left + (int) Math.round(MODEL_WIDTH * scaleX());
        int bottom = top + boxHeight();
        float fit = Math.min((bottom - top) / standing.height(), (right - left) / standing.width());
        EntityPreview.draw(context, BodyPicture.CombatModel::new, state, fit * MODEL_FILL,
            new Vector3f(0, state.boundingBoxHeight / 2, 0), left, top, right, bottom);
    }

    // Their health then their absorption in gold. The whole bar stands for their most health and
    // their absorption. A player's keeps the absorption room even whilst they have none.
    private static void drawBar(GuiGraphicsExtractor context, LivingEntity target, int x, int y, int width) {
        float max = target.getMaxHealth();
        float health = Math.max(0, Math.min(target.getHealth(), max));
        float absorption = target.getAbsorptionAmount();
        float room = target instanceof Player ? Math.max(ABSORPTION_ROOM, absorption) : absorption;
        float whole = max + room;
        int healthEnd = x + Math.round(width * health / whole);
        int absorptionEnd = healthEnd + Math.round(width * absorption / whole);
        context.fill(x, y, x + width, y + BAR_HEIGHT, GuiTheme.BAR_TRACK);
        context.fill(x, y, healthEnd, y + BAR_HEIGHT, ColorUtil.redToGreen(max <= 0 ? 0 : health / max));
        context.fill(healthEnd, y, Math.min(x + width, absorptionEnd), y + BAR_HEIGHT, ABSORPTION_COLOR);
    }

    // Where the writing and gear start. The body stands left of them.
    private int left() {
        return model.isOn() ? MODEL_WIDTH + GAP : 0;
    }

    // As wide as the widest line or the effects or the gear or the bar.
    private int columnWidth(Font font, Shown shown) {
        int widest = TextRow.widest(shown.lines());
        if (!shown.effects().isEmpty()) {
            widest = Math.max(widest, shown.effects().size() * (font.lineHeight + GAP) - GAP);
        }
        if (!shown.gear().isEmpty()) {
            widest = Math.max(widest, GEAR.size() * (SLOT + GAP) - GAP);
        }
        return bar.isOn() ? Math.max(widest, BAR_WIDTH) : widest;
    }

    // The effect icons stand as tall as a line in one row under the writing.
    private static int effectsTop(Font font, Shown shown) {
        return TextRow.height(font, shown.lines().size()) + GAP;
    }

    // The bottom of the effect icons or of the writing when no effect shows.
    private static int textBottom(Font font, Shown shown) {
        return shown.effects().isEmpty() ? TextRow.height(font, shown.lines().size())
            : effectsTop(font, shown) + font.lineHeight;
    }

    private static int gearTop(Font font, Shown shown) {
        return textBottom(font, shown) + GAP;
    }

    // How far below the gear the enchantments of the most enchanted piece reach.
    private static int enchantDepth(Font font, Shown shown) {
        int deepest = 0;
        for (List<EnchantText.Label> labels : shown.enchants()) {
            deepest = Math.max(deepest, labels.size());
        }
        return (int) Math.ceil(deepest * font.lineHeight * ENCHANT_SIZE);
    }

    // The bottom of the gear and its enchantments or of what stands above it when no gear shows.
    private static int contentBottom(Font font, Shown shown) {
        return shown.gear().isEmpty() ? textBottom(font, shown)
            : gearTop(font, shown) + SLOT + enchantDepth(font, shown);
    }

    private static int barTop(Font font, Shown shown) {
        return contentBottom(font, shown) + GAP;
    }

    @Override
    public int width(Font font) {
        Shown shown = shown();
        return shown == null ? 1 : left() + columnWidth(font, shown);
    }

    @Override
    public int height(Font font) {
        Shown shown = shown();
        if (shown == null) {
            return font.lineHeight;
        }
        int column = bar.isOn() ? barTop(font, shown) + BAR_HEIGHT : contentBottom(font, shown);
        return model.isOn() ? Math.max(column, MODEL_HEIGHT) : column;
    }
}
