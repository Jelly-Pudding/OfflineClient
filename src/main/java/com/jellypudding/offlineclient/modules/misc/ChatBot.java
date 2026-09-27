package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.TextSetting;
import com.jellypudding.offlineclient.util.ChatSender;
import com.jellypudding.offlineclient.util.ChatUtil;
import net.minecraft.network.protocol.Packet;
import net.minecraft.util.StringUtil;

import java.util.concurrent.TimeUnit;

// A line holding one of your own replies is never answered and nothing goes out during
// the cooldown. Two clients running the same rules stop after a single exchange.
public final class ChatBot extends Module {

    // How many reply rules can be set up one by one.
    private static final int SLOTS = 16;

    // Swapped for the name of the player being answered.
    private static final String PLAYER = "{player}";

    // One line a second never trips the spam kick of a vanilla server.
    private static final double MIN_COOLDOWN = 1;

    private record Rule(String phrase, String reply) {

        // The reply as it goes out to one player.
        String replyTo(String name) {
            return reply.replace(PLAYER, name);
        }
    }

    // The settings for one rule. The phrase row folds the reply away.
    private static final class Slot {

        final TextSetting phrase;
        final TextSetting reply;

        Slot(int number, NumberSetting count) {
            int slot = number - 1;
            phrase = new TextSetting("Phrase " + number,
                "Words in another player's line that get a reply. Click to type them.", "")
                .visibleWhen(() -> slot < count.getInt());
            reply = new TextSetting("Reply " + number,
                "What you say back with {player} for their name. Start it with a slash to run a command.", "")
                .under(phrase, () -> slot < count.getInt());
        }

        // Null whilst either box is blank.
        Rule rule() {
            if (phrase.isBlank() || reply.isBlank()) {
                return null;
            }
            return new Rule(phrase.getValue().trim(), reply.getValue().trim());
        }
    }

    private final NumberSetting ruleCount = new NumberSetting("Rules",
        "How many phrases get a reply. A row opens for each.", 1, 0, SLOTS, 1).min(0).max(SLOTS);
    private final Slot[] slots = new Slot[SLOTS];
    private final NumberSetting cooldown = new NumberSetting("Cooldown",
        "Seconds after a reply before the bot answers anyone again. Lines in between get no reply.",
        10, MIN_COOLDOWN, 60, 1, "s").min(MIN_COOLDOWN);

    private long quietUntil;

    public ChatBot() {
        super("ChatBot", "Replies to other players when their chat holds a phrase you pick.", Category.MISC);
        addSettings(ruleCount);
        for (int i = 0; i < SLOTS; i++) {
            slots[i] = new Slot(i + 1, ruleCount);
            addSettings(slots[i].phrase, slots[i].reply);
        }
        addSettings(cooldown);
        searchTags("auto reply", "responder", "answer");
    }

    @Override
    protected void onEnable() {
        quietUntil = 0;
    }

    // Fired on the netty thread. The line is read on the game thread where the tab list lives.
    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        Packet<?> packet = event.getPacket();
        if (ChatSender.isChat(packet)) {
            mc.schedule(() -> onLine(ChatSender.lineOf(packet)));
        }
    }

    private void onLine(ChatSender.Line line) {
        if (line == null || line.isOwn() || !isEnabled() || mc.player == null
            || System.currentTimeMillis() < quietUntil) {
            return;
        }
        String self = mc.player.getGameProfile().name();
        Rule answer = null;
        for (int i = 0; i < ruleCount.getInt(); i++) {
            Rule rule = slots[i].rule();
            if (rule == null) {
                continue;
            }
            // Another bot saying one of our replies back would start an endless exchange.
            if (ChatSender.holds(line.text(), rule.replyTo(self))) {
                return;
            }
            if (answer == null && ChatSender.holds(line.text(), rule.phrase())) {
                answer = rule;
            }
        }
        if (answer == null) {
            return;
        }
        quietUntil = System.currentTimeMillis() + Math.round(cooldown.getValue() * TimeUnit.SECONDS.toMillis(1));
        String reply = StringUtil.filterText(answer.replyTo(line.name()));
        ChatUtil.say(reply.startsWith("/") ? reply : StringUtil.trimChatMessage(reply));
    }
}
