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

public final class SignWords {

    // The igloo basement sign is the only sign the world writes on. Its front holds two arrows.
    private static final List<String> IGLOO_ARROWS = List.of("<----", "---->");

    // The most characters one line of a sign edit carries. A longer line drops the connection.
    // The game keeps its own figure private.
    public static final int MOST_LINE_CHARS = 384;

    private SignWords() {
    }

    public static boolean isWritten(SignBlockEntity sign) {
        boolean filtering = OfflineClient.MC.player.isTextFilteringEnabled();
        return sign.getText(SignTextSlot.FRONT).hasMessage(filtering)
            || sign.getText(SignTextSlot.BACK).hasMessage(filtering);
    }

    // The server runs a click action for whoever right clicks the sign and that includes a click
    // that waxes or dyes it.
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

    public static void addLines(SignText text, List<String> out) {
        for (Component message : text.getMessages(false)) {
            String line = ChatFormatting.stripFormatting(message.getString()).strip();
            if (!line.isEmpty()) {
                out.add(line);
            }
        }
    }

    public static List<String> lines(SignBlockEntity sign, SignTextSlot side) {
        List<String> lines = new ArrayList<>();
        addLines(sign.getText(side), lines);
        return lines;
    }

    public static List<String> words(String filter) {
        List<String> words = new ArrayList<>();
        for (String word : filter.toLowerCase(Locale.ROOT).split("\\s+")) {
            if (!word.isEmpty()) {
                words.add(word);
            }
        }
        return words;
    }

    public static boolean hasAnyWord(List<String> lines, List<String> words) {
        String text = String.join(" ", lines).toLowerCase(Locale.ROOT);
        for (String word : words) {
            if (ChatUtil.wholeWordIndex(text, word, 0) >= 0) {
                return true;
            }
        }
        return false;
    }

    public static boolean iglooArrows(List<String> front, List<String> back) {
        return back.isEmpty() && front.equals(IGLOO_ARROWS);
    }
}
