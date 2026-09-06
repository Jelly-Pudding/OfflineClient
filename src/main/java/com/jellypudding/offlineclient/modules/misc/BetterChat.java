package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ChatSendEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.TextSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.TextLines;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.world.entity.player.PlayerSkin;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

// ChatComponentMixin asks this about every line that comes in and about the history size.
public final class BetterChat extends Module {

    private static final int VANILLA_HISTORY = 100;
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter CLOCK_SECONDS = DateTimeFormatter.ofPattern("HH:mm:ss");

    private static final String PLAIN = "abcdefghijklmnopqrstuvwxyz";
    private static final String SMALL = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴩqʀꜱᴛᴜᴠᴡxyᴢ";

    // How much wider the chat gets so a head fits on the left.
    private static final int HEAD_ROOM = 10;

    // A number of three digits or more then one of two or more read as coordinates.
    private static final Pattern COORDINATES = Pattern.compile("-?\\d{3,}[\\s,;/]+-?\\d{2,}");

    // A run of blank lines is what a server sends to push chat off the screen.
    private static final Pattern BLANK_RUN = Pattern.compile("\\n(\\n|\\s)+\\n");

    // Our own clock stamp on the front of a stored line.
    private static final Pattern STAMP = Pattern.compile("^\\[\\d{2}:\\d{2}(?::\\d{2})?] ");

    // The usual server layout for a chat line.
    private static final Pattern SENDER = Pattern.compile("^<([^<>]{1,32})>");

    private final BoolSetting timestamps = new BoolSetting("Timestamps",
        "Puts the time in front of every line.", true);
    private final BoolSetting showSeconds = new BoolSetting("Show seconds",
        "Timestamps carry seconds as well as hours and minutes.", false)
        .under(timestamps);
    private final BoolSetting playerHeads = new BoolSetting("Player heads",
        "Draws the face of the sender beside their message.", true);
    private final BoolSetting longerHistory = new BoolSetting("Longer history",
        "Keeps more lines than the hundred vanilla scrolls back through.", true);
    private final NumberSetting historySize = new NumberSetting("History size",
        "Lines kept for scrolling back.", 1000, 100, 5000, 100, " lines")
        .under(longerHistory);
    private final BoolSetting keepHistory = new BoolSetting("Keep history",
        "Chat survives a disconnect and the debug clear key.", true);
    private final BoolSetting infiniteBox = new BoolSetting("Infinite chat box",
        "Lifts the limit on how much you can type into the chat box.", true);
    private final BoolSetting guardCoordinates = new BoolSetting("Guard coordinates",
        "Holds back a message that looks like coordinates until you send it a second time.",
        true);
    private final BoolSetting antiClear = new BoolSetting("Anti clear",
        "Stops a server wiping your chat with a run of blank lines.", true);
    private final BoolSetting filterRegex = new BoolSetting("Filter regex",
        "Drops any line that matches one of your patterns.", false);
    private final TextLines patterns = new TextLines("Patterns",
        "How many patterns to match against.").plain().under(filterRegex);

    private final BoolSetting annoy = new BoolSetting("Annoy",
        "Sends your messages in aLtErNaTiNg case.", false);
    private final BoolSetting fancy = new BoolSetting("Fancy chat",
        "Sends your messages in small caps.", false);

    private final BoolSetting prefix = new BoolSetting("Prefix",
        "Puts something in front of every message you send.", false);
    private final BoolSetting prefixRandom = new BoolSetting("Random prefix",
        "Uses a random three digit number instead of the text.", false)
        .under(prefix);
    private final TextSetting prefixText = new TextSetting("Prefix text",
        "The text put in front. Click to type it.", "> ")
        .under(prefix, () -> prefix.isOn() && !prefixRandom.isOn());
    private final BoolSetting prefixSmall = new BoolSetting("Small caps prefix",
        "Writes the prefix in small caps.", false)
        .under(prefix, () -> prefix.isOn() && !prefixRandom.isOn());

    private final BoolSetting suffix = new BoolSetting("Suffix",
        "Puts something on the end of every message you send.", false);
    private final BoolSetting suffixRandom = new BoolSetting("Random suffix",
        "Uses a random three digit number instead of the text.", false)
        .under(suffix);
    private final TextSetting suffixText = new TextSetting("Suffix text",
        "The text put on the end. Click to type it.", " | offline")
        .under(suffix, () -> suffix.isOn() && !suffixRandom.isOn());
    private final BoolSetting suffixSmall = new BoolSetting("Small caps suffix",
        "Writes the suffix in small caps.", true)
        .under(suffix, () -> suffix.isOn() && !suffixRandom.isOn());

    private final List<Pattern> compiled = new ArrayList<>();
    private String compiledFrom = "";
    private String heldBack;

    // The chat line being drawn. Set by the line consumer just before the text goes out.
    private GuiMessage.Line drawing;
    private boolean startOfEntry;
    private boolean previousEnded = true;
    private int lastIndex = -1;

    public BetterChat() {
        super("BetterChat", "Small improvements to the chat box.", Category.MISC);
        addSettings(timestamps, showSeconds, playerHeads, longerHistory, historySize,
            keepHistory, infiniteBox, guardCoordinates, antiClear, filterRegex);
        addSettings(patterns.settings());
        addSettings(annoy, fancy, prefix, prefixRandom, prefixText, prefixSmall,
            suffix, suffixRandom, suffixText, suffixSmall);
        searchTags("timestamp", "chat history", "coords", "player heads", "small caps");
    }

    // True when a line matches one of the patterns and must never reach the chat.
    public boolean filters(Component message) {
        if (!isEnabled() || !filterRegex.isOn()) {
            return false;
        }
        String text = message.getString();
        for (Pattern pattern : compiledPatterns()) {
            if (pattern.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    // Patterns are rebuilt only after the rows change. A bad one is dropped with a warning.
    private List<Pattern> compiledPatterns() {
        List<String> rows = patterns.all();
        String key = String.join("\n", rows);
        if (key.equals(compiledFrom)) {
            return compiled;
        }
        compiledFrom = key;
        compiled.clear();
        for (String row : rows) {
            try {
                compiled.add(Pattern.compile(row));
            } catch (PatternSyntaxException error) {
                ChatUtil.error("That is not a pattern: " + row);
            }
        }
        return compiled;
    }

    public Component decorate(Component message) {
        if (!isEnabled()) {
            return message;
        }
        if (antiClear.isOn() && BLANK_RUN.matcher(message.getString()).find()) {
            message = collapse(message);
        }
        if (!timestamps.isOn()) {
            return message;
        }
        String now = LocalTime.now().format(showSeconds.isOn() ? CLOCK_SECONDS : CLOCK);
        MutableComponent stamp = Component.literal("[" + now + "] ")
            .withStyle(ChatFormatting.DARK_GRAY);
        return stamp.append(message);
    }

    // Rebuilds the line with every run of blank lines cut down to one break.
    private static Component collapse(Component message) {
        MutableComponent rebuilt = Component.empty();
        message.visit((style, text) -> {
            Matcher matcher = BLANK_RUN.matcher(text);
            rebuilt.append(Component.literal(matcher.find() ? matcher.replaceAll("\n\n") : text)
                .setStyle(style));
            return Optional.empty();
        }, Style.EMPTY);
        return rebuilt;
    }

    public int historyLimit(int vanilla) {
        if (!isEnabled() || !longerHistory.isOn()) {
            return vanilla;
        }
        return Math.max(VANILLA_HISTORY, historySize.getInt());
    }

    public boolean keepsHistory() {
        return isEnabled() && keepHistory.isOn();
    }

    public boolean liftsBoxLimit() {
        return isEnabled() && infiniteBox.isOn();
    }

    // Player heads.

    private boolean drawsHeads() {
        return isEnabled() && playerHeads.isOn();
    }

    public int headRoom(int width) {
        return drawsHeads() ? width + HEAD_ROOM : width;
    }

    // The consumer walks the drawn lines from the top down and starts over each frame.
    public void beginLine(GuiMessage.Line line, int index) {
        if (!drawsHeads()) {
            return;
        }
        startOfEntry = index >= lastIndex || previousEnded;
        lastIndex = index;
        previousEnded = line.endOfEntry();
        drawing = line;
    }

    public void endLine() {
        drawing = null;
    }

    public void drawHead(GuiGraphicsExtractor graphics, int y, int colour) {
        if (!drawsHeads() || drawing == null || !startOfEntry) {
            return;
        }
        PlayerSkin skin = senderSkin(drawing);
        if (skin != null) {
            PlayerFaceExtractor.extractRenderState(graphics, skin, 0, y, 8, colour);
        }
    }

    // The sender is read from the usual <name> opening of a chat line.
    private static PlayerSkin senderSkin(GuiMessage.Line line) {
        if (mc.getConnection() == null) {
            return null;
        }
        String text = STAMP.matcher(line.parent().content().getString()).replaceFirst("").trim();
        Matcher matcher = SENDER.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        PlayerInfo info = mc.getConnection().getPlayerInfoIgnoreCase(matcher.group(1));
        return info == null ? null : info.getSkin();
    }

    // Sending.

    // The text to send in place of what was typed. Commands are left alone.
    public String rewrite(String message) {
        if (!isEnabled() || message.startsWith("/")) {
            return message;
        }
        String body = message;
        if (annoy.isOn()) {
            body = alternate(body);
        }
        if (fancy.isOn()) {
            body = smallCaps(body);
        }
        return affix(prefix, prefixRandom, prefixText, prefixSmall) + body
            + affix(suffix, suffixRandom, suffixText, suffixSmall);
    }

    private static String affix(BoolSetting on, BoolSetting random, TextSetting text,
                                BoolSetting small) {
        if (!on.isOn()) {
            return "";
        }
        if (random.isOn()) {
            return String.format("(%03d) ", ThreadLocalRandom.current().nextInt(1000));
        }
        return small.isOn() ? smallCaps(text.getValue()) : text.getValue();
    }

    private static String alternate(String message) {
        StringBuilder out = new StringBuilder(message.length());
        boolean upper = true;
        for (int point : message.codePoints().toArray()) {
            out.appendCodePoint(upper ? Character.toUpperCase(point) : Character.toLowerCase(point));
            upper = !upper;
        }
        return out.toString();
    }

    private static String smallCaps(String message) {
        StringBuilder out = new StringBuilder(message.length());
        for (char letter : message.toCharArray()) {
            int at = PLAIN.indexOf(Character.toLowerCase(letter));
            out.append(at == -1 ? letter : SMALL.charAt(at));
        }
        return out.toString();
    }

    @Subscribe
    private void onChatSend(ChatSendEvent event) {
        if (!guardCoordinates.isOn()) {
            return;
        }
        String message = event.getMessage();
        if (message.startsWith("/") || !COORDINATES.matcher(message).find()) {
            return;
        }
        // The same text sent twice goes through. That is the second look asked for.
        if (message.equals(heldBack)) {
            heldBack = null;
            return;
        }
        heldBack = message;
        event.cancel();
        ChatUtil.message("That looks like coordinates. Send it again if you mean it.");
    }

    @Override
    protected void onDisable() {
        heldBack = null;
        drawing = null;
    }
}
