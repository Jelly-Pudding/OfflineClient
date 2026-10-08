package com.jellypudding.offlineclient.util;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.Holder;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import org.joml.Matrix3x2fStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

// Short labels such as Pro 4 for the enchantments on a piece of gear. They are written
// small beside its icon.
public final class EnchantText {

    // One label and the colour it is written in.
    public record Label(String text, int color) {
    }

    // The four kinds of protection on armour.
    public static final List<String> PROTECTIONS = List.of(
        "minecraft:protection", "minecraft:blast_protection",
        "minecraft:fire_protection", "minecraft:projectile_protection");

    private static final int COLOR = 0xFFFFFFFF;
    private static final int CURSE_COLOR = 0xFFFF4040;

    private EnchantText() {
    }

    // The enchantments on the stack whose ids pass the test with curses in red. Each name is
    // cut to that many letters and its level follows.
    public static List<Label> of(ItemStack stack, Predicate<String> shown, int letters) {
        if (stack.isEmpty()) {
            return List.of();
        }
        ItemEnchantments all = EnchantmentHelper.getEnchantmentsForCrafting(stack);
        List<Label> labels = new ArrayList<>();
        for (Holder<Enchantment> holder : all.keySet()) {
            String id = holder.unwrapKey().map(key -> key.identifier().toString()).orElse("");
            if (!shown.test(id)) {
                continue;
            }
            String name = holder.value().description().getString();
            String shortName = name.substring(0, Math.min(name.length(), letters));
            labels.add(new Label(shortName + " " + all.getLevel(holder),
                holder.is(EnchantmentTags.CURSE) ? CURSE_COLOR : COLOR));
        }
        return labels;
    }

    // Labels one under another from the top. Each is centred across the width at the given size.
    public static void draw(GuiGraphicsExtractor context, Font font, List<Label> labels, int x, float top,
                            int width, float size, boolean shadow) {
        Matrix3x2fStack pose = context.pose();
        float y = top;
        for (Label label : labels) {
            pose.pushMatrix();
            pose.translate(x + (width - font.width(label.text()) * size) / 2, y);
            pose.scale(size, size);
            context.text(font, label.text(), 0, 0, label.color(), shadow);
            pose.popMatrix();
            y += font.lineHeight * size;
        }
    }
}
