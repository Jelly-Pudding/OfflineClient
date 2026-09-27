package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ChatSendEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.TextSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
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
import java.util.List;
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

    private final TextSetting sharedKey = new TextSetting("Key",
        "The secret you share with your friends. Anyone who has it can read every secret line. Click to type it.",
        "");
    private final TextSetting prefix = new TextSetting("Prefix",
        "Messages starting with this go out encrypted without it. When it is empty every message goes out encrypted.",
        "#");

    private String stretchedFrom;
    private SecretKey stretched;

    public SecretChat() {
        super("SecretChat", "Chats in public in a code that anyone with your key can read.",
            Category.MISC);
        addSettings(sharedKey, prefix);
        searchTags("encrypt", "encryption", "private chat", "cipher");
    }

    // Runs after commands and the coordinate guard. A line either of them took is left alone.
    @Subscribe(priority = -10)
    private void onChatSend(ChatSendEvent event) {
        String message = event.getMessage();
        if (event.isCancelled() || message.startsWith("/") || !message.startsWith(prefix.getValue())) {
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
            ChatUtil.say(seal(sealing, piece));
        }
    }

    // The line with every run sealed under the key swapped for its text. Anything else is untouched.
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
                rebuilt.append(Component.literal(text).setStyle(style.withColor(ChatFormatting.LIGHT_PURPLE)));
                from = matcher.end();
                opened[0] = true;
            }
            rebuilt.append(Component.literal(part.substring(from)).setStyle(style));
            return Optional.empty();
        }, Style.EMPTY);
        return opened[0] ? rebuilt : message;
    }

    // Null whilst the key is blank. Stretching is slow and runs once for each key typed.
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

    // The text cut into pieces that each fit one line. A cut falls on a space where there is one.
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

    // Null unless the run was sealed under this key. Colour codes in the text are dropped.
    private static String open(SecretKey opening, String run) {
        byte[] packed = new BigInteger(run, RADIX).toByteArray();
        if (packed.length <= HEADER_BYTES + TAG_BYTES || packed[0] != LEAD) {
            return null;
        }
        try {
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, opening, new GCMParameterSpec(TAG_BITS, packed, 1, NONCE_BYTES));
            byte[] text = cipher.doFinal(packed, HEADER_BYTES, packed.length - HEADER_BYTES);
            return StringUtil.filterText(new String(text, StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            return null;
        }
    }
}
