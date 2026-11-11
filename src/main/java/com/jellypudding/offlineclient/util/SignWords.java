package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
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

    // The most characters one line of a sign edit carries. A longer line drops the connection.
    // The game keeps its own figure private.
    public static final int MOST_LINE_CHARS = 384;

    private SignWords() {
    }

    // True when either side has writing as the game itself judges it.
    public static boolean isWritten(SignBlockEntity sign) {
        boolean filtering = OfflineClient.MC.player.isTextFilteringEnabled();
        return sign.getText(SignTextSlot.FRONT).hasMessage(filtering)
            || sign.getText(SignTextSlot.BACK).hasMessage(filtering);
    }

    // True when a line on either side carries a click action. The server runs it for whoever
    // right clicks the sign and that includes a click that waxes or dyes it.
    public static boolean runsClicks(SignBlockEntity sign) {
        boolean filtering = OfflineClient.MC.player.isTextFilteringEnabled();
        for (SignTextSlot side : SignTextSlot.values()) {
            for (Component line : sign.getText(side).getMessages(filtering)) {
                if (line.getStyle().getClickEvent() != null) {
                    return true;
                }
            }
        }
        return false;
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
