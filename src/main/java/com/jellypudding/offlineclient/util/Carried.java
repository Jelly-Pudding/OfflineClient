package com.jellypudding.offlineclient.util;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

// What a living entity holds and wears put into words for chat and labels.
public final class Carried {

    // One piece and how it is carried such as holding Mace.
    public record Piece(String verb, String what) {
    }

    private Carried() {
    }

    // A piece for each slot whose stack the namer puts into words. The namer hands back null
    // for a stack that does not count.
    public static List<Piece> pieces(LivingEntity holder, Function<ItemStack, String> namer) {
        List<Piece> pieces = new ArrayList<>();
        for (EquipmentSlot slot : EquipmentSlot.VALUES) {
            ItemStack stack = holder.getItemBySlot(slot);
            String what = stack.isEmpty() ? null : namer.apply(stack);
            if (what != null) {
                pieces.add(new Piece(slot.getType() == EquipmentSlot.Type.HAND ? "holding" : "wearing", what));
            }
        }
        return pieces;
    }

    // Such as holding Mace and wearing Elytra and Netherite Helmet. The slots run from the hands
    // down to the feet and up to the head and each verb is written once.
    public static String describe(List<Piece> pieces) {
        StringBuilder text = new StringBuilder();
        String verb = null;
        for (Piece piece : pieces) {
            if (piece.verb().equals(verb)) {
                text.append(" and");
            } else {
                text.append(verb == null ? "" : " and ").append(piece.verb());
                verb = piece.verb();
            }
            text.append(' ').append(piece.what());
        }
        return text.toString();
    }

    // The first piece with a count of the rest such as Elytra +2.
    public static String label(List<Piece> pieces) {
        int more = pieces.size() - 1;
        return pieces.getFirst().what() + (more > 0 ? " +" + more : "");
    }
}
