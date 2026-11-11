package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ChatSendEvent;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.ShownChat;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.PlayerSkin;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// ChatComponentMixin asks this about every line that comes in and about the history size. What you
// send and the chat it hides and the commands a click runs are parts of their own beside it.
public final class BetterChat extends Module {

    private static final int VANILLA_HISTORY = 100;
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter CLOCK_SECONDS = DateTimeFormatter.ofPattern("HH:mm:ss");

    // How much wider the chat gets to fit a head on the left.
    private static final int HEAD_ROOM = 10;
    private static final int HEAD_SIZE = 8;

    // Sender lookups remembered per message. Cleared once it outgrows any history.
    private static final int HEAD_CACHE = 8192;

    // A run of blank lines is what a server sends to push chat off the screen.
    private static final Pattern BLANK_RUN = Pattern.compile("\\n(\\n|\\s)+\\n");

    // Our own clock stamp on the front of a stored line. Some servers add the same.
    private static final Pattern STAMP = Pattern.compile("^\\[\\d{2}:\\d{2}(?::\\d{2})?] ");

    private static final int RGB = 0xFFFFFF;

    // The bar beside a line Mojang can act on. Amber stands apart from the grey of the game's own marks.
    private static final GuiMessageTag REPORTABLE = new GuiMessageTag(0xE0A030, GuiMessageTag.Icon.CHAT_MODIFIED,
        Component.literal("This line is signed and a report of it can reach Mojang."), null);

    private final BoolSetting timestamps = new BoolSetting("Timestamps",
        "Puts the time in front of every line.", true);
    private final BoolSetting showSeconds = new BoolSetting("Show seconds",
        "Timestamps carry seconds as well as hours and minutes.", false)
        .under(timestamps);
    private final BoolSetting playerHeads = new BoolSetting("Player heads",
        "Draws the face of the sender in front of their message.", true);
    private final BoolSetting scrollbar = new BoolSetting("Scrollbar",
        "A bar beside the open chat you can drag or click to scroll.", true);
    private final NumberSetting lift = new NumberSetting("Chat lift",
        "How far the chat sits above the bottom of the screen. Vanilla uses forty.",
        22, 0, 80, 1, " px");
    private final NumberSetting indent = new NumberSetting("Chat indent",
        "How far the chat sits in from the left edge.", 4, 0, 80, 1, " px");
    private final BoolSetting longerHistory = new BoolSetting("Longer history",
        "Keeps more lines than the hundred vanilla scrolls back through.", true);
    private final NumberSetting historySize = new NumberSetting("History size",
        "Lines kept for scrolling back.", 1000, 100, 5000, 100, " lines")
        .under(longerHistory);
    private final BoolSetting keepHistory = new BoolSetting("Keep history",
        "Chat survives a disconnect and the debug clear key.", true);
    private final BoolSetting markReportable = new BoolSetting("Mark reportable",
        "Marks chat lines that carry a signature Mojang can act on.", false);
    private final BoolSetting infiniteBox = new BoolSetting("Infinite chat box",
        "Lifts the limit on how much you can type into the chat box.", true);
    private final BoolSetting colourCommands = new BoolSetting("Colour commands",
        "Colours what you type once it starts with the command prefix.", true);
    private final ColorSetting commandColour = new ColorSetting("Command colour",
        "Colour of a command whilst you type it.", 180, false)
        .under(colourCommands);
    private final BoolSetting antiClear = new BoolSetting("Anti clear",
        "Some servers send a wall of blank lines to push your chat off the screen. This cuts it out.",
        true);

    private final ChatRewrite rewriter = new ChatRewrite(this::isEnabled);
    private final ChatFilter filter = new ChatFilter(this::isEnabled);
    private final ClickGuard clicks = new ClickGuard(this::isEnabled);

    // The skin found for each message. A miss is remembered as well and never searched twice.
    private final Map<GuiMessage, Optional<PlayerSkin>> heads = new IdentityHashMap<>();

    // The chat line being drawn. Set by the line consumer just before the text goes out.
    private GuiMessage.Line drawing;
    private boolean topOfEntry;

    public static String withoutStamp(String line) {
        return STAMP.matcher(line).replaceFirst("");
    }

    public BetterChat() {
        super("BetterChat", "Small improvements to the chat box.", Category.MISC);
        addSettings(timestamps, showSeconds, playerHeads, scrollbar, lift, indent,
            longerHistory, historySize, keepHistory, markReportable, infiniteBox,
            colourCommands, commandColour, rewriter.guardRow(), antiClear);
        addSettings(filter.settings());
        addSettings(clicks.settings());
        addSettings(rewriter.rewriteRows());
        searchTags("timestamp", "chat history", "coords", "player heads", "small caps",
            "scrollbar", "mute", "ignore", "hide chat", "no player chat", "chat reports", "censor",
            "word filter", "abbreviation", "emote", "replace", "reply", "click event");
    }

    public boolean filters(Component message, GuiMessageSource source) {
        return filter.filters(message, source);
    }

    public boolean blocksClick(String command) {
        return clicks.blocksClick(command);
    }

    public boolean mute(String name) {
        return filter.mute(name);
    }

    public boolean unmute(String name) {
        return filter.unmute(name);
    }

    public List<String> mutedPlayers() {
        return filter.mutedPlayers();
    }

    public boolean mutes(String name) {
        return filter.mutes(name);
    }

    public String rewrite(String message) {
        return rewriter.rewrite(message);
    }

    // The tag a player line goes up with. A signed line the game trusts has none and Mark
    // reportable gives it one.
    public GuiMessageTag tagFor(MessageSignature signature, GuiMessageTag tag) {
        return isEnabled() && markReportable.isOn() && signature != null && tag == null ? REPORTABLE : tag;
    }

    // The shown part of the chat box in the command colour once the whole box starts with the
    // command prefix. Null leaves it to the game.
    public FormattedCharSequence commandText(String typed, String shown) {
        if (!isEnabled() || !colourCommands.isOn()
            || !typed.startsWith(OfflineClient.INSTANCE.getCommandManager().getPrefix())) {
            return null;
        }
        return FormattedCharSequence.forward(shown, Style.EMPTY.withColor(commandColour.getColor() & RGB));
    }

    public Component decorate(Component message) {
        if (!isEnabled()) {
            return message;
        }
        if (antiClear.isOn() && BLANK_RUN.matcher(message.getString()).find()) {
            message = collapse(message);
        }
        message = filter.starOut(message);
        message = clicks.withClickCommands(message);
        if (!timestamps.isOn()) {
            return message;
        }
        String now = LocalTime.now().format(showSeconds.isOn() ? CLOCK_SECONDS : CLOCK);
        MutableComponent stamp = Component.literal("[" + now + "] ")
            .withStyle(ChatFormatting.DARK_GRAY);
        // Both hang off a blank root or the message would inherit the grey of the stamp.
        return Component.empty().append(stamp).append(message);
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

    public boolean showsScrollbar() {
        return isEnabled() && scrollbar.isOn();
    }

    // Vanilla keeps forty pixels clear for the hotbar and the health row.
    public int chatLift(int vanilla) {
        return isEnabled() ? lift.getInt() : vanilla;
    }

    public float chatIndent(float vanilla) {
        return isEnabled() ? indent.getFloat() : vanilla;
    }

    private boolean drawsHeads() {
        return isEnabled() && playerHeads.isOn();
    }

    // The chat background grows to fit a head beside a full width line.
    public int headRoom(int width) {
        return drawsHeads() ? width + HEAD_ROOM : width;
    }

    // Only a line with a head beside it moves across. The rest stay flush with the edge.
    public int textStart(int x) {
        return drawing != null && headOf(drawing.parent()) != null ? x + HEAD_ROOM : x;
    }

    // The consumer walks the lines from the bottom up. The top line of a message is
    // the one below a line that ends the message above it.
    public void beginLine(GuiMessage.Line line, int index) {
        if (!drawsHeads()) {
            return;
        }
        drawing = line;
        ChatComponent chat = mc.gui.hud.getChat();
        List<GuiMessage.Line> lines = chat.trimmedMessages;
        int above = index + chat.chatScrollbarPos + 1;
        topOfEntry = above >= lines.size() || lines.get(above).endOfEntry();
    }

    public void endLine() {
        drawing = null;
    }

    public void drawHead(GuiGraphicsExtractor graphics, int y, int colour) {
        if (drawing == null || !topOfEntry) {
            return;
        }
        PlayerSkin skin = headOf(drawing.parent());
        if (skin != null) {
            PlayerFaceExtractor.extractRenderState(graphics, skin, 0, y, HEAD_SIZE, colour);
        }
    }

    private PlayerSkin headOf(GuiMessage message) {
        if (!drawsHeads()) {
            return null;
        }
        if (heads.size() > HEAD_CACHE) {
            heads.clear();
        }
        return heads.computeIfAbsent(message, m -> Optional.ofNullable(senderSkin(m))).orElse(null);
    }

    // The face of the player who wrote the line. A line the server or the client wrote has none.
    private static PlayerSkin senderSkin(GuiMessage message) {
        if (mc.getConnection() == null) {
            return null;
        }
        String writer = ShownChat.writer(message.content(), message.source());
        PlayerInfo info = writer == null ? null : mc.getConnection().getPlayerInfoIgnoreCase(writer);
        return info == null ? null : info.getSkin();
    }

    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        rewriter.onPacketReceive(event.getPacket());
    }

    @Subscribe
    private void onChatSend(ChatSendEvent event) {
        rewriter.onChatSend(event);
    }

    @Override
    protected void onDisable() {
        rewriter.forget();
        drawing = null;
        heads.clear();
    }
}
