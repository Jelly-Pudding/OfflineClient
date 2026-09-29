package com.jellypudding.offlineclient.util;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SignTextSlot;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// What is written on signs as plain lines. Colour codes and blank lines are left out.
public final class SignWords {

    // The igloo basement sign is the only sign the world writes on. Its front holds two arrows.
    private static final List<String> IGLOO_ARROWS = List.of("<----", "---->");

    private SignWords() {
    }

    // The written lines of one side added to the list.
    public static void addLines(SignText text, List<String> out) {
        for (Component message : text.getMessages(false)) {
            String line = ChatFormatting.stripFormatting(message.getString()).strip();
            if (!line.isEmpty()) {
                out.add(line);
            }
        }
    }

    // The written lines of one side.
    public static List<String> lines(SignBlockEntity sign, SignTextSlot side) {
        List<String> lines = new ArrayList<>();
        addLines(sign.getText(side), lines);
        return lines;
    }

    // The words of a filter in lower case. Spaces separate them.
    public static List<String> words(String filter) {
        List<String> words = new ArrayList<>();
        for (String word : filter.toLowerCase(Locale.ROOT).split("\\s+")) {
            if (!word.isEmpty()) {
                words.add(word);
            }
        }
        return words;
    }

    // True when a word of the filter stands on its own anywhere on the lines.
    public static boolean hasAnyWord(List<String> lines, List<String> words) {
        String text = String.join(" ", lines).toLowerCase(Locale.ROOT);
        for (String word : words) {
            if (ChatUtil.wholeWordIndex(text, word, 0) >= 0) {
                return true;
            }
        }
        return false;
    }

    // True for the writing of the igloo basement sign and nothing else.
    public static boolean iglooArrows(List<String> front, List<String> back) {
        return back.isEmpty() && front.equals(IGLOO_ARROWS);
    }
}
