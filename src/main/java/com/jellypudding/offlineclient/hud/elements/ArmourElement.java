package com.jellypudding.offlineclient.hud.elements;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.hud.HudElement;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.util.ColorUtil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

public final class ArmourElement extends HudElement {

    public enum Layout { ACROSS, DOWN }

    public enum Wear { NONE, NUMBER, PERCENT, BAR }

    private static final int SLOT = 16;
    private static final int GAP = 2;
    private static final int BAR_HEIGHT = 2;

    // Hue zero is red and hue one hundred and twenty is green.
    private static final float GREEN_HUE = 120;

    private static final EquipmentSlot[] WORN = {
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    private final EnumSetting<Layout> layout = new EnumSetting<>("Armour layout",
        "Which way the pieces are laid out.", Layout.ACROSS)
        .describe(Layout.ACROSS, "In a row.")
        .describe(Layout.DOWN, "In a column.");
    private final EnumSetting<Wear> wear = new EnumSetting<>("Wear",
        "How the life left in each piece is shown.", Wear.NUMBER)
        .describe(Wear.NONE, "Not at all.")
        .describe(Wear.NUMBER, "The uses left.")
        .describe(Wear.PERCENT, "How much of it is left as a share.")
        .describe(Wear.BAR, "A small bar under the piece.");
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
        for (EquipmentSlot slot : WORN) {
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
            java.util.Collections.reverse(stacks);
        }
        return stacks;
    }

    // The room a label under a piece needs.
    private int labelHeight(Font font) {
        return switch (wear.getValue()) {
            case NONE -> 0;
            case BAR -> BAR_HEIGHT + 1;
            case NUMBER, PERCENT -> font.lineHeight;
        };
    }

    @Override
    public void render(GuiGraphicsExtractor context, Font font) {
        List<ItemStack> stacks = pieces();
        int step = SLOT + GAP + (layout.is(Layout.DOWN) ? labelHeight(font) : 0);
        for (int i = 0; i < stacks.size(); i++) {
            int x = layout.is(Layout.ACROSS) ? i * (SLOT + GAP) : 0;
            int y = layout.is(Layout.ACROSS) ? 0 : i * step;
            ItemStack stack = stacks.get(i);
            context.item(stack, x, y);
            context.itemDecorations(font, stack, x, y);
            drawWear(context, font, stack, x, y);
        }
    }

    private void drawWear(GuiGraphicsExtractor context, Font font, ItemStack stack, int x, int y) {
        if (wear.is(Wear.NONE) || !stack.isDamageableItem()) {
            return;
        }
        int left = stack.getMaxDamage() - stack.getDamageValue();
        float share = Math.clamp((float) left / stack.getMaxDamage(), 0f, 1f);
        int tint = ColorUtil.hsv(share * GREEN_HUE, 0.9f, 1f);
        if (wear.is(Wear.BAR)) {
            context.fill(x, y + SLOT, x + SLOT, y + SLOT + BAR_HEIGHT, 0xC0202020);
            context.fill(x, y + SLOT, x + Math.round(SLOT * share), y + SLOT + BAR_HEIGHT, tint);
            return;
        }
        String label = wear.is(Wear.PERCENT)
            ? Math.round(share * 100) + "%" : String.valueOf(left);
        context.text(font, label, x + (SLOT - font.width(label)) / 2, y + SLOT, tint, true);
    }

    @Override
    public int width(Font font) {
        int count = Math.max(1, pieces().size());
        return layout.is(Layout.ACROSS) ? count * (SLOT + GAP) - GAP : SLOT;
    }

    @Override
    public int height(Font font) {
        int count = Math.max(1, pieces().size());
        int label = labelHeight(font);
        return layout.is(Layout.ACROSS)
            ? SLOT + label : count * (SLOT + GAP + label) - GAP;
    }
}
