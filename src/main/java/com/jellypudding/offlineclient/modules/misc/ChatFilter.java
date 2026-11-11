package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.Setting;
import com.jellypudding.offlineclient.util.BoundedRegex;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.ShownChat;
import com.jellypudding.offlineclient.util.TextLines;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.regex.MatchResult;

// The chat BetterChat keeps from you. A line that matches a pattern is hidden or starred out and
// what other players write can be hidden for everyone or for the muted players alone.
final class ChatFilter {

    private static final Minecraft MC = OfflineClient.MC;

    // What a pattern match is covered with letter for letter.
    private static final char STAR = '*';

    enum Matched { HIDE_LINE, STAR_OUT }

    private final BoolSetting filterRegex = new BoolSetting("Filter regex",
        "Hides or stars out chat that matches one of your patterns.", false);
    private final EnumSetting<Matched> matched = new EnumSetting<>("Matched text",
        "What happens to a line that matches.", Matched.HIDE_LINE)
        .describe(Matched.HIDE_LINE, "The whole line never shows.")
        .describe(Matched.STAR_OUT, "The line shows with stars over the text that matched.")
        .under(filterRegex);
    private final TextLines patterns = new TextLines("Patterns",
        "How many patterns to match against.",
        "A regular expression looked for in every chat line. A plain word works as one and (?i) in front of it"
            + " ignores case. Click to type it.")
        .plain().under(filterRegex);
    private final BoolSetting hidePlayerChat = new BoolSetting("Hide player chat",
        "Hides everything other players write in chat.", false);
    private final BoolSetting keepFriends = new BoolSetting("Keep friends",
        "Still shows what your friends write.", true)
        .under(hidePlayerChat);
    private final BoolSetting keepWhispers = new BoolSetting("Keep whispers",
        "Still shows whispers sent to you.", true)
        .under(hidePlayerChat);
    private final TextLines muted = TextLines.named("Muted players", "Muted player",
        "How many players are muted.", "A player whose chat never shows. Click to type the name.");

    private final BooleanSupplier enabled;
    private final List<BoundedRegex> compiled = new ArrayList<>();
    private String compiledFrom = "";

    ChatFilter(BooleanSupplier enabled) {
        this.enabled = enabled;
    }

    Setting<?>[] settings() {
        List<Setting<?>> rows = new ArrayList<>(List.of(filterRegex, matched));
        rows.addAll(List.of(patterns.settings()));
        rows.addAll(List.of(hidePlayerChat, keepFriends, keepWhispers));
        rows.addAll(List.of(muted.settings()));
        return rows.toArray(Setting<?>[]::new);
    }

    // True when a line must never reach the chat. A pattern hides any line and the rest only
    // ever hide what other players write.
    boolean filters(Component message, GuiMessageSource source) {
        if (!enabled.getAsBoolean()) {
            return false;
        }
        if (filterRegex.isOn() && matched.is(Matched.HIDE_LINE) && matchesPattern(message.getString())) {
            return true;
        }
        if (!hidePlayerChat.isOn() && muted.isEmpty()) {
            return false;
        }
        String writer = ShownChat.writer(message, source);
        if (writer == null || MC.player != null && writer.equalsIgnoreCase(MC.player.getGameProfile().name())) {
            return false;
        }
        if (muted.holds(writer)) {
            return true;
        }
        boolean kept = keepFriends.isOn() && OfflineClient.INSTANCE.getFriendManager().isFriend(writer)
            || keepWhispers.isOn() && ShownChat.whisper(message);
        return hidePlayerChat.isOn() && !kept;
    }

    private boolean matchesPattern(String text) {
        for (BoundedRegex pattern : compiledPatterns()) {
            if (pattern.find(text) != null) {
                return true;
            }
        }
        return false;
    }

    // False when every muted row is taken.
    boolean mute(String name) {
        return muted.add(name);
    }

    boolean unmute(String name) {
        return muted.remove(name);
    }

    List<String> mutedPlayers() {
        return muted.all();
    }

    // True whilst BetterChat is on and hides the chat of that player.
    boolean mutes(String name) {
        return enabled.getAsBoolean() && muted.holds(name);
    }

    // Patterns are rebuilt only after the rows change. A bad one is dropped with a warning.
    private List<BoundedRegex> compiledPatterns() {
        List<String> rows = patterns.all();
        String key = String.join("\n", rows);
        if (key.equals(compiledFrom)) {
            return compiled;
        }
        compiledFrom = key;
        compiled.clear();
        for (String row : rows) {
            BoundedRegex pattern = BoundedRegex.compile(row, ChatUtil::error);
            if (pattern != null) {
                compiled.add(pattern);
            }
        }
        return compiled;
    }

    // The line with stars over every stretch a pattern matched whilst matches are starred out.
    // The styles of the line stay. Otherwise the line comes back as it is.
    Component starOut(Component message) {
        if (!filterRegex.isOn() || !matched.is(Matched.STAR_OUT)) {
            return message;
        }
        String text = message.getString();
        BitSet starred = new BitSet();
        for (BoundedRegex pattern : compiledPatterns()) {
            for (MatchResult match : pattern.all(text)) {
                starred.set(match.start(), match.end());
            }
        }
        if (starred.isEmpty()) {
            return message;
        }
        MutableComponent rebuilt = Component.empty();
        int[] at = {0};
        message.visit((style, part) -> {
            StringBuilder shown = new StringBuilder(part);
            int end = at[0] + part.length();
            for (int i = starred.nextSetBit(at[0]); i >= 0 && i < end; i = starred.nextSetBit(i + 1)) {
                shown.setCharAt(i - at[0], STAR);
            }
            at[0] = end;
            rebuilt.append(Component.literal(shown.toString()).setStyle(style));
            return Optional.empty();
        }, Style.EMPTY);
        return rebuilt;
    }
}
