package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.config.WaypointStore;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ChatSendEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.ActionSetting;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.TextSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatPace;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.SentLine;
import com.jellypudding.offlineclient.util.ServerInfo;
import com.jellypudding.offlineclient.util.Tally;
import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.StringUtil;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Every line is sealed with AES in GCM mode. A run of text that fails the tag check is left as it came.
// Sealed lines are written in lower case letters and digits. Caps and markdown filters leave those alone.
public final class SecretChat extends Module {

    private static final String CIPHER = "AES/GCM/NoPadding";
    private static final String STRETCH = "PBKDF2WithHmacSHA256";

    // Every client has to stretch a key the same way. The salt only needs to match between them.
    private static final byte[] SALT = "offlineclient secret chat".getBytes(StandardCharsets.UTF_8);
    private static final int STRETCH_ROUNDS = 100_000;
    private static final int KEY_BITS = 256;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int TAG_BYTES = TAG_BITS / Byte.SIZE;

    // Leads the sealed bytes. A zero byte at the front would vanish when written as a number.
    private static final byte LEAD = 1;
    private static final int HEADER_BYTES = 1 + NONCE_BYTES;
    private static final int RADIX = 36;

    // Text bytes one line carries. The longest line it can make still fits in one chat packet.
    private static final int LINE_BYTES = lineBytes();

    // A server kicks a player who sends ten chat lines at once. A secret stays well short of that.
    private static final int MAX_LINES = 4;

    // A run shorter than the shortest sealed line of a single byte is an ordinary word.
    private static final Pattern SEALED = Pattern.compile(
        "[a-z0-9]{" + lowest(1).toString(RADIX).length() + ",}");

    private static final SecureRandom RANDOM = new SecureRandom();

    // Starts the text of a ping that asks for a spot back. Chat never carries a control
    // character and nothing a player types can pass for a ping.
    private static final char ASKS = '\u0001';
    // Starts a spot sent back. Nobody answers it.
    private static final char ANSWERS = '\u0002';
    // A dimension id with a namespace and a path. A ping naming anything else is read as text.
    private static final Pattern DIMENSION = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    // A server kicks a player who whispers too fast. One a second stays far below that.
    private static final int WHISPER_GAP_TICKS = SharedConstants.TICKS_PER_SECOND;

    private final TextSetting sharedKey = new TextSetting("Key",
        "The secret you share with your friends. Anyone who has it can read every secret line. Click to type it.",
        "").secret();
    private final TextSetting prefix = new TextSetting("Prefix",
        "Messages starting with this go out encrypted without it. When it is empty every message goes out encrypted.",
        "#");
    private final ActionSetting pingFriends = new ActionSetting("Ping friends",
        "Whispers your spot in code to every friend online.", () -> ping(null));
    private final NumberSetting pingEvery = new NumberSetting("Ping every",
        "Seconds between pings sent on their own. Nought sends none.", 0, 0, 300, 10, " seconds");
    private final BoolSetting answerPings = new BoolSetting("Answer pings",
        "Whispers your own spot back to a friend who pings you.", false);
    private final BoolSetting markPings = new BoolSetting("Mark pings",
        "Keeps a waypoint named after each friend where they last pinged from.", true);
    private final TextSetting whisperWith = SentLine.whisperWith();

    private String stretchedFrom;
    private SecretKey stretched;

    // The players waiting for a whisper of your spot. True asks them for theirs back.
    private final Map<String, Boolean> outgoing = new LinkedHashMap<>();
    private int whisperGap;
    private int sinceAutoPing;

    public SecretChat() {
        super("SecretChat", "Chats in public in a code that anyone with your key can read.",
            Category.MISC);
        addSettings(sharedKey, prefix, pingFriends, pingEvery, answerPings, markPings, whisperWith);
        searchTags("encrypt", "encryption", "private chat", "cipher", "ping", "share location");
    }

    @Override
    protected void onEnable() {
        sinceAutoPing = 0;
    }

    @Override
    protected void onDisable() {
        outgoing.clear();
        whisperGap = 0;
    }

    // Runs after commands and the coordinate guard. A line either of them took is left alone.
    @Subscribe(priority = -10)
    private void onChatSend(ChatSendEvent event) {
        String message = event.getMessage();
        if (message.startsWith("/") || !message.startsWith(prefix.getValue())) {
            return;
        }
        String text = message.substring(prefix.getValue().length()).trim();
        if (text.isEmpty()) {
            return;
        }
        event.cancel();
        SecretKey sealing = key();
        if (sealing == null) {
            ChatUtil.error("Type a key into SecretChat first. Nothing was sent.");
            return;
        }
        List<String> pieces = pieces(text);
        if (pieces.size() > MAX_LINES) {
            ChatUtil.error("That secret needs " + pieces.size() + " lines and the most is " + MAX_LINES
                + ". Nothing was sent.");
            return;
        }
        for (String piece : pieces) {
            ChatPace.PLAYER.say(seal(sealing, piece));
        }
    }

    public Component reveal(Component message) {
        if (!isEnabled() || !SEALED.matcher(message.getString()).find()) {
            return message;
        }
        SecretKey opening = key();
        if (opening == null) {
            return message;
        }
        MutableComponent rebuilt = Component.empty();
        boolean[] opened = new boolean[1];
        message.visit((style, part) -> {
            Matcher matcher = SEALED.matcher(part);
            int from = 0;
            while (matcher.find()) {
                String text = open(opening, matcher.group());
                if (text == null) {
                    continue;
                }
                rebuilt.append(Component.literal(part.substring(from, matcher.start())).setStyle(style));
                rebuilt.append(Component.literal("[Secret] ").withStyle(ChatFormatting.DARK_PURPLE));
                rebuilt.append(shown(text, style.withColor(ChatFormatting.LIGHT_PURPLE)));
                from = matcher.end();
                opened[0] = true;
            }
            rebuilt.append(Component.literal(part.substring(from)).setStyle(style));
            return Optional.empty();
        }, Style.EMPTY);
        return opened[0] ? rebuilt : message;
    }

    public boolean isPing(String text) {
        SecretKey opening = isEnabled() ? key() : null;
        if (opening == null) {
            return false;
        }
        Matcher matcher = SEALED.matcher(text);
        while (matcher.find()) {
            String opened = open(opening, matcher.group());
            if (opened != null && startsPing(opened)) {
                return true;
            }
        }
        return false;
    }

    private static boolean startsPing(String text) {
        return !text.isEmpty() && (text.charAt(0) == ASKS || text.charAt(0) == ANSWERS);
    }

    private Component shown(String text, Style style) {
        Spot spot = Spot.read(text);
        if (spot == null) {
            return Component.literal(StringUtil.filterText(text)).setStyle(style);
        }
        heard(spot);
        Component at = ChatUtil.walkLink(spot.pos(), spot.dimension());
        return Component.literal(spot.name() + " is at ").setStyle(style).append(at)
            .append(Component.literal(" in the " + ServerInfo.dimensionName(spot.dimension()) + ".").setStyle(style));
    }

    // Your own whispers come back to you as well and do nothing.
    private void heard(Spot spot) {
        if (mc.player == null || spot.name().equalsIgnoreCase(mc.player.getGameProfile().name())
            || !OfflineClient.INSTANCE.getFriendManager().isFriend(spot.name())) {
            return;
        }
        if (markPings.isOn()) {
            WaypointStore.get().mark(spot.name(), spot.pos(), spot.dimension(), WaypointStore.Colour.AUTO);
        }
        if (answerPings.isOn() && spot.asks()) {
            queue(spot.name(), false);
        }
    }

    // A null name pings every friend online. Hands back a few words about it.
    public String ping(String target) {
        if (!inGame()) {
            return "Join a world first";
        }
        if (!isEnabled()) {
            return "Switch SecretChat on first";
        }
        if (key() == null) {
            return "Type a key first";
        }
        if (target != null) {
            if (mc.getConnection().getPlayerInfoIgnoreCase(target) == null) {
                return target + " is not online";
            }
            queue(target, true);
            return "Pinging " + target;
        }
        List<String> friends = onlineFriends();
        friends.forEach(name -> queue(name, true));
        return friends.isEmpty() ? "No friends online" : "Pinging " + Tally.counted(friends.size(), "friend");
    }

    public List<String> onlineFriends() {
        List<String> online = new ArrayList<>();
        if (mc.getConnection() == null) {
            return online;
        }
        for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
            String name = info.getProfile().name();
            if (OfflineClient.INSTANCE.getFriendManager().isFriend(name)) {
                online.add(name);
            }
        }
        return online;
    }

    // A ping that asks wins over a plain answer already waiting for the same player.
    private void queue(String name, boolean asks) {
        outgoing.merge(name.toLowerCase(Locale.ROOT), asks, Boolean::logicalOr);
    }

    @Subscribe
    private void onTick(TickEvent event) {
        int every = pingEvery.getInt() * SharedConstants.TICKS_PER_SECOND;
        if (every > 0 && ++sinceAutoPing >= every) {
            sinceAutoPing = 0;
            onlineFriends().forEach(name -> queue(name, true));
        }
        if (whisperGap > 0) {
            whisperGap--;
        } else if (!outgoing.isEmpty()) {
            whisperNext();
        }
    }

    private void whisperNext() {
        SecretKey sealing = key();
        if (sealing == null) {
            outgoing.clear();
            return;
        }
        if (!ChatPace.RULE.fits(0, 1)) {
            return;
        }
        Iterator<Map.Entry<String, Boolean>> waiting = outgoing.entrySet().iterator();
        while (waiting.hasNext()) {
            Map.Entry<String, Boolean> next = waiting.next();
            String name = next.getKey();
            boolean asks = next.getValue();
            waiting.remove();
            PlayerInfo info = mc.getConnection().getPlayerInfoIgnoreCase(name);
            if (info == null || !SentLine.oneWord(info.getProfile().name())) {
                continue;
            }
            String spot = Spot.yours(asks).text();
            // Only a dimension with a very long name makes a spot too big for one chat line.
            if (spot.getBytes(StandardCharsets.UTF_8).length > LINE_BYTES) {
                ChatUtil.error("The name of this dimension is too long for a ping.");
                outgoing.clear();
                return;
            }
            // SentLine.whisper would cut the line to the chat limit and a long spot would lose its tail.
            ChatPace.RULE.say(StringUtil.filterText(SentLine.asCommand(whisperWith.getValue())) + " "
                + info.getProfile().name() + " " + seal(sealing, spot));
            whisperGap = WHISPER_GAP_TICKS;
            return;
        }
    }

    // Stretching is slow and runs once for each key typed.
    private SecretKey key() {
        String typed = sharedKey.getValue();
        if (typed.isBlank()) {
            return null;
        }
        if (!typed.equals(stretchedFrom)) {
            stretched = stretch(typed);
            stretchedFrom = typed;
        }
        return stretched;
    }

    private static SecretKey stretch(String typed) {
        try {
            PBEKeySpec spec = new PBEKeySpec(typed.toCharArray(), SALT, STRETCH_ROUNDS, KEY_BITS);
            byte[] raw = SecretKeyFactory.getInstance(STRETCH).generateSecret(spec).getEncoded();
            return new SecretKeySpec(raw, "AES");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("This Java runtime cannot stretch a key", e);
        }
    }

    // A sealed line is one number made of the lead then the nonce then the text and tag.
    // The lead keeps it at least this and below twice this.
    private static BigInteger lowest(int textBytes) {
        return BigInteger.ONE.shiftLeft(Byte.SIZE * (NONCE_BYTES + textBytes + TAG_BYTES));
    }

    private static int lineBytes() {
        int bytes = 1;
        while (lowest(bytes + 1).shiftLeft(1).subtract(BigInteger.ONE).toString(RADIX).length()
            <= SharedConstants.MAX_CHAT_LENGTH) {
            bytes++;
        }
        return bytes;
    }

    private static List<String> pieces(String text) {
        List<String> pieces = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = start;
            int bytes = 0;
            int space = -1;
            while (end < text.length()) {
                int point = text.codePointAt(end);
                int size = utf8Bytes(point);
                if (bytes + size > LINE_BYTES) {
                    break;
                }
                if (point == ' ') {
                    space = end;
                }
                bytes += size;
                end += Character.charCount(point);
            }
            if (end < text.length() && space > start) {
                end = space;
            }
            pieces.add(text.substring(start, end));
            start = end;
            while (start < text.length() && text.charAt(start) == ' ') {
                start++;
            }
        }
        return pieces;
    }

    private static int utf8Bytes(int point) {
        if (point < 0x80) {
            return 1;
        }
        if (point < 0x800) {
            return 2;
        }
        return point < 0x10000 ? 3 : 4;
    }

    private static String seal(SecretKey sealing, String text) {
        byte[] packed;
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, sealing, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] sealed = cipher.doFinal(text.getBytes(StandardCharsets.UTF_8));
            packed = new byte[HEADER_BYTES + sealed.length];
            packed[0] = LEAD;
            System.arraycopy(nonce, 0, packed, 1, NONCE_BYTES);
            System.arraycopy(sealed, 0, packed, HEADER_BYTES, sealed.length);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("This Java runtime cannot encrypt with AES", e);
        }
        return new BigInteger(packed).toString(RADIX);
    }

    private static String open(SecretKey opening, String run) {
        byte[] packed = new BigInteger(run, RADIX).toByteArray();
        if (packed.length <= HEADER_BYTES + TAG_BYTES || packed[0] != LEAD) {
            return null;
        }
        try {
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, opening, new GCMParameterSpec(TAG_BITS, packed, 1, NONCE_BYTES));
            byte[] text = cipher.doFinal(packed, HEADER_BYTES, packed.length - HEADER_BYTES);
            return new String(text, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            return null;
        }
    }

    private record Spot(String name, String dimension, BlockPos pos, boolean asks) {

        static Spot yours(boolean asks) {
            return new Spot(mc.player.getGameProfile().name(), ServerInfo.dimension(), mc.player.blockPosition(), asks);
        }

        static Spot read(String text) {
            if (!startsPing(text)) {
                return null;
            }
            String[] parts = StringUtil.filterText(text.substring(1)).split(" ", 3);
            if (parts.length != 3 || !DIMENSION.matcher(parts[1]).matches()) {
                return null;
            }
            BlockPos pos = BlockUtil.parse(parts[2]);
            return pos == null ? null : new Spot(parts[0], parts[1], pos, text.charAt(0) == ASKS);
        }

        String text() {
            return (asks ? ASKS : ANSWERS) + name + " " + dimension + " " + BlockUtil.text(pos);
        }
    }
}
