package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.config.ServerStore;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.util.ChatSender;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.ServerInfo;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// The password goes out once per join and only to the server it was saved for.
// Only a line the server wrote counts as a prompt. Another player's chat never does.
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

    private boolean answered;
    private boolean hinted;

    public AutoLogin() {
        super("AutoLogin", "Answers the login prompt of an offline server with the password saved for it.",
            Category.MISC);
        searchTags("login", "register", "authme", "password");
    }

    @Override
    protected void onEnable() {
        newJoin();
    }

    private void newJoin() {
        answered = false;
        hinted = false;
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

    private void onServerLine(ClientboundSystemChatPacket packet) {
        if (answered || !isEnabled() || ServerInfo.address() == null) {
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
        String password = ServerStore.logins().get(ServerInfo.key());
        if (password == null) {
            hint();
            return;
        }
        answered = true;
        ChatUtil.say(prompt.answer(password));
        ChatUtil.message("§7Answered the login prompt with your saved password.");
    }

    private void hint() {
        if (hinted) {
            return;
        }
        hinted = true;
        ChatUtil.message("§7No password is saved for this server. Type §f"
            + OfflineClient.INSTANCE.getCommandManager().getPrefix() + "autologin set <password>§7 to save one.");
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
