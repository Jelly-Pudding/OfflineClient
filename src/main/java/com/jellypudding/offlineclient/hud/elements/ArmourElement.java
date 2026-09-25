package com.jellypudding.offlineclient.hud.elements;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.hud.HudElement;
import com.jellypudding.offlineclient.hud.HudLayout;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.ItemUtil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ArmourElement extends HudElement {

    public enum Wear { NONE, NUMBER, PERCENT }

    private static final int SLOT = 16;
    private static final int GAP = 2;
    // Labels side by side need more air than bare items do.
    private static final int LABEL_GAP = 4;
    private static final String FULL = "100%";

    private final EnumSetting<HudLayout> layout = HudLayout.setting("Armour layout",
        "Which way the pieces are laid out.");
    private final EnumSetting<Wear> wear = new EnumSetting<>("Wear",
        "Writes the life left in each piece under it.", Wear.NUMBER)
        .describe(Wear.NONE, "No text. The game still draws its wear bar.")
        .describe(Wear.NUMBER, "The uses left.")
        .describe(Wear.PERCENT, "How much of it is left as a share.");
    private final BoolSetting offhand = new BoolSetting("Armour offhand",
        "Show what you hold in your other hand too.", true);
    private final BoolSetting hideEmpty = new BoolSetting("Hide empty armour",
        "Leave out the pieces you are not wearing.", false);
    private final BoolSetting flip = new BoolSetting("Flip armour order",
        "Boots first instead of the helmet.", false);

    public ArmourElement() {
        super("Armour", "What you wear and how worn it is.", false, 50, 88);
        add(layout, wear, offhand, hideEmpty, flip);
    }

    @Override
    public boolean visible() {
        return isActive() && !pieces().isEmpty();
    }

    private List<ItemStack> pieces() {
        Player player = OfflineClient.MC.player;
        List<ItemStack> stacks = new ArrayList<>(5);
        if (player == null) {
            return stacks;
        }
        for (EquipmentSlot slot : ItemUtil.ARMOR_SLOTS) {
            ItemStack stack = player.getItemBySlot(slot);
            if (!hideEmpty.isOn() || !stack.isEmpty()) {
                stacks.add(stack);
            }
        }
        if (offhand.isOn()) {
            ItemStack stack = player.getOffhandItem();
            if (!hideEmpty.isOn() || !stack.isEmpty()) {
                stacks.add(stack);
            }
        }
        if (flip.isOn()) {
            Collections.reverse(stacks);
        }
        return stacks;
    }

    private boolean labelled(ItemStack stack) {
        return !wear.is(Wear.NONE) && stack.isDamageableItem();
    }

    private int labelHeight(Font font) {
        return wear.is(Wear.NONE) ? 0 : font.lineHeight;
    }

    // Room for the item or the longest label it could ever carry. A piece that
    // wears down keeps its place.
    private int column(Font font, List<ItemStack> stacks) {
        int widest = SLOT;
        for (ItemStack stack : stacks) {
            if (labelled(stack)) {
                String longest = wear.is(Wear.PERCENT) ? FULL : String.valueOf(stack.getMaxDamage());
                widest = Math.max(widest, font.width(longest));
            }
        }
        return widest;
    }

    private int gap() {
        return wear.is(Wear.NONE) ? GAP : LABEL_GAP;
    }

    @Override
    public void render(GuiGraphicsExtractor context, Font font) {
        List<ItemStack> stacks = pieces();
        int column = column(font, stacks);
        boolean across = layout.is(HudLayout.ACROSS);
        for (int i = 0; i < stacks.size(); i++) {
            int left = across ? i * (column + gap()) : 0;
            int top = across ? 0 : i * (SLOT + labelHeight(font) + GAP);
            int itemX = left + (column - SLOT) / 2;
            ItemStack stack = stacks.get(i);
            context.item(stack, itemX, top);
            context.itemDecorations(font, stack, itemX, top);
            if (labelled(stack)) {
                double share = ItemUtil.durabilityPercent(stack);
                String label = wear.is(Wear.PERCENT) ? Math.round(share) + "%"
                    : String.valueOf(stack.getMaxDamage() - stack.getDamageValue());
                context.text(font, label, left + (column - font.width(label)) / 2, top + SLOT,
                    ColorUtil.redToGreen((float) share / 100f), true);
            }
        }
    }

    @Override
    public int width(Font font) {
        List<ItemStack> stacks = pieces();
        int column = column(font, stacks);
        int count = Math.max(1, stacks.size());
        return layout.is(HudLayout.ACROSS) ? count * (column + gap()) - gap() : column;
    }

    @Override
    public int height(Font font) {
        int count = Math.max(1, pieces().size());
        int label = labelHeight(font);
        return layout.is(HudLayout.ACROSS)
            ? SLOT + label : count * (SLOT + label + GAP) - GAP;
    }
}
