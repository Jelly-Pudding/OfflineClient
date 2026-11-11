package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.config.NameStore;
import com.jellypudding.offlineclient.config.ServerStore;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.util.ChatPace;
import com.jellypudding.offlineclient.util.ChatSender;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.ServerInfo;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// The password goes out once per join and only to the server and name it was saved for.
// Only a line the server wrote counts as a prompt. Another player's chat never does. A password
// typed by hand in answer to a prompt is offered once the server stops asking and is only kept
// once the player says to. It is written nowhere but the file of saved passwords.
public final class AutoLogin extends Module {

    // What a prompt asks for. The password follows the command once or twice.
    private record Prompt(String command, int copies) {

        String answer(String password) {
            return command + (" " + password).repeat(copies);
        }
    }

    // A login command followed by a placeholder such as /login <password>.
    private static final Pattern PROMPT = Pattern.compile("/(login|register)\\s+[<\\[]");
    private static final Pattern PLACEHOLDER = Pattern.compile("[<\\[][^<>\\[\\]]+[>\\]]");
    private static final String LOGIN = "login";
    private static final String AT = "@";

    // The words login and register plugins answer to.
    private static final Set<String> LOGIN_WORDS = Set.of("login", "l", "log", "register", "reg");
    private static final String REGISTER_START = "reg";

    // A login command typed this long after the last prompt answers nothing. Servers turn away a
    // player who has not logged in well before then.
    private static final int PROMPT_TICKS = SharedConstants.TICKS_PER_MINUTE;

    // A server that refuses a password asks again within a few seconds.
    private static final int QUIET_TICKS = 10 * SharedConstants.TICKS_PER_SECOND;

    private boolean answered;
    private boolean hinted;
    // Ticks left in which a login command answers the last prompt. The first one sent closes it.
    private int promptLeft;
    // The last prompt whilst the saved password has yet to go out in answer to it.
    private Prompt unanswered;
    // A password typed by hand on this join and the key it would be saved under. It waits here
    // until the player saves it and is forgotten when the join or the module ends.
    private String typed;
    private String typedFor;
    // Ticks before the typed password is offered. Zero once it has been.
    private int offerIn;

    public AutoLogin() {
        super("AutoLogin", "Answers the login prompt of an offline server with its saved password and offers to"
            + " save one you type.", Category.MISC);
        searchTags("login", "register", "authme", "password", "save password");
    }

    @Override
    protected void onEnable() {
        newJoin();
    }

    // Where the password for this server and the name you joined it with is saved. Your account
    // name keeps the server alone as its key.
    public static String loginKey() {
        String joined = NameStore.ownName();
        String server = ServerInfo.key();
        return joined.equalsIgnoreCase(NameStore.accountName()) ? server
            : joined.toLowerCase(Locale.ROOT) + AT + server;
    }

    private void newJoin() {
        answered = false;
        hinted = false;
        promptLeft = 0;
        unanswered = null;
        forgetTyped();
    }

    @Override
    protected void onDisable() {
        forgetTyped();
    }

    private void forgetTyped() {
        typed = null;
        typedFor = null;
        offerIn = 0;
    }

    // Fired on the netty thread. The game thread takes the work in the order it arrived.
    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        switch (event.getPacket()) {
            case ClientboundLoginPacket _ -> mc.schedule(this::newJoin);
            case ClientboundSystemChatPacket packet when !packet.overlay() ->
                mc.schedule(() -> onServerLine(packet));
            default -> {
            }
        }
    }

    // A prompt after a login command means the password sent was refused.
    private void onServerLine(ClientboundSystemChatPacket packet) {
        if (!isEnabled() || ServerInfo.address() == null) {
            return;
        }
        ChatSender.Line line = ChatSender.lineOf(packet);
        if (line != null && !line.isOwn()) {
            return;
        }
        Prompt prompt = promptIn(ChatSender.plain(packet.content()));
        if (prompt == null) {
            return;
        }
        promptLeft = PROMPT_TICKS;
        if (offerIn > 0) {
            forgetTyped();
        }
        if (answered) {
            return;
        }
        String key = loginKey();
        if (ServerStore.logins().get(key) == null) {
            hint(key);
            return;
        }
        unanswered = prompt;
        answer();
    }

    // The answer goes at the pace of an escape because a server turns away a player who is slow to
    // log in. One held back is tried again each tick whilst the prompt is open.
    private void answer() {
        String password = ServerStore.logins().get(loginKey());
        if (promptLeft == 0 || password == null) {
            unanswered = null;
            return;
        }
        if (!ChatPace.ESCAPE.say(unanswered.answer(password))) {
            return;
        }
        unanswered = null;
        answered = true;
        ChatUtil.message("§7Answered the login prompt with your saved password.");
    }

    private void hint(String key) {
        if (hinted) {
            return;
        }
        hinted = true;
        ChatUtil.message("§7No password is saved for §b" + key + "§7. Log in yourself and AutoLogin offers to"
            + " keep the password once the server takes it or type §f"
            + OfflineClient.INSTANCE.getCommandManager().getPrefix() + "autologin set <password>§7.");
    }

    // A command sent after a prompt may be its answer.
    @Subscribe
    private void onPacketSend(PacketSendEvent event) {
        String command = switch (event.getPacket()) {
            case ServerboundChatCommandPacket packet -> packet.command();
            case ServerboundChatCommandSignedPacket packet -> packet.command();
            default -> null;
        };
        if (command != null) {
            mc.execute(() -> learn(command));
        }
    }

    // Only the first login command after a prompt answers it. The saved password sent by this
    // module counts as that answer too. A password that differs from the saved one waits to be
    // offered.
    private void learn(String command) {
        String password = typedPassword(command);
        if (password == null || promptLeft == 0 || !isEnabled() || Modules.dormant()
            || ServerInfo.address() == null) {
            return;
        }
        promptLeft = 0;
        forgetTyped();
        String key = loginKey();
        if (!password.equals(ServerStore.logins().get(key))) {
            typed = password;
            typedFor = key;
            offerIn = QUIET_TICKS;
        }
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (promptLeft > 0) {
            promptLeft--;
        }
        if (unanswered != null) {
            answer();
        }
        if (offerIn > 0 && --offerIn == 0) {
            offer();
        }
    }

    // The server has not asked again and still has you. The line never names the password.
    private void offer() {
        ClientPacketListener game = mc.getConnection();
        if (game == null || !game.getConnection().isConnected() || Modules.dormant()) {
            forgetTyped();
            return;
        }
        String offer = ServerStore.logins().get(typedFor) == null
            ? "§7Keep the password you typed for §b" + typedFor + "§7? "
            : "§7Keep the password you typed for §b" + typedFor + "§7 in place of the saved one? ";
        ChatUtil.component(Component.literal(offer).append(ChatUtil.commandLink("§b[Save]", "autologin save",
            "Click to save it for AutoLogin.")));
    }

    // The password of a login or register command. A register that names two different ones has
    // none because the server refuses it.
    private static String typedPassword(String command) {
        String[] words = command.trim().split("\\s+");
        String first = words[0].toLowerCase(Locale.ROOT);
        if (words.length < 2 || !LOGIN_WORDS.contains(first)
            || first.startsWith(REGISTER_START) && words.length > 2 && !words[1].equals(words[2])) {
            return null;
        }
        return words[1];
    }

    // Saves the password the server took on this join. Hands back what it was saved under. Null
    // when no such password has been offered.
    public String saveTyped() {
        if (typed == null || offerIn > 0) {
            return null;
        }
        String key = typedFor;
        ServerStore.logins().set(key, typed);
        forgetTyped();
        return key;
    }

    // A login wins when the line offers both. Null for a line that is no prompt.
    private static Prompt promptIn(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        Matcher matcher = PROMPT.matcher(lower);
        int registerAt = -1;
        while (matcher.find()) {
            if (matcher.group(1).equals(LOGIN)) {
                return new Prompt("/login", 1);
            }
            if (registerAt == -1) {
                registerAt = matcher.start();
            }
        }
        if (registerAt == -1) {
            return null;
        }
        // Most plugins want the password twice. A prompt showing one placeholder wants it once.
        Matcher slots = PLACEHOLDER.matcher(lower.substring(registerAt));
        int count = 0;
        while (slots.find()) {
            count++;
        }
        return new Prompt("/register", count == 1 ? 1 : 2);
    }
}
