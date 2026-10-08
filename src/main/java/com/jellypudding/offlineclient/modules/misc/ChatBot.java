package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.config.ServerStore;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.ActionSetting;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.TextSetting;
import com.jellypudding.offlineclient.util.ChatModel;
import com.jellypudding.offlineclient.util.ChatSender;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.Web;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.util.StringUtil;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;

// A line holding one of your own rule replies is never answered and nothing goes out during
// the cooldown. Two clients running the same rules stop after a single exchange. A model
// writes one reply at a time and the cooldown starts once that reply has gone out.
public final class ChatBot extends Module {

    public enum Replies { RULES, MODEL }

    // How many reply rules can be set up one by one.
    private static final int SLOTS = 16;

    // Swapped for the name of the player being answered.
    private static final String PLAYER = "{player}";

    // One line a second never trips the spam kick of a vanilla server.
    private static final double MIN_COOLDOWN = 1;

    // How much of the recent chat the model reads before it answers.
    private static final int CONTEXT_LINES = 10;
    private static final long CONTEXT_MS = TimeUnit.MINUTES.toMillis(5);

    // A name tag such as <Steve> that a model copied from the chat it read.
    private static final Pattern NAME_TAG = Pattern.compile("^<[^<>]*>\\s*");

    // The made up player whose line the test answers.
    private static final String TEST_PLAYER = "Alex";

    private static final double PERCENT = 100;

    private static final String NO_ADDRESS = "§bChatBot §cneeds a model address that starts with http or https.";

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
                .under(count, () -> slot < count.getInt());
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

    // A line heard and when.
    private record Heard(ChatModel.Said said, long at) {
    }

    // A written reply held back until a player typing it would have finished.
    private record Due(String text, long at) {
    }

    private final EnumSetting<Replies> replies = new EnumSetting<>("Replies",
        "Where the replies come from.", Replies.RULES)
        .describe(Replies.RULES, "The phrases and answers in the rows below.")
        .describe(Replies.MODEL, "A chat model at the address below writes each reply.");
    private final NumberSetting ruleCount = new NumberSetting("Rules",
        "How many phrases get a reply. A row opens for each.", 1, 0, SLOTS, 1).min(0).max(SLOTS)
        .under(replies, Replies.RULES);
    private final Slot[] slots = new Slot[SLOTS];
    private final TextSetting modelAddress = new TextSetting("Model address",
        "The web address of the chat model. A model running on your own computer needs no key.", "")
        .under(replies, Replies.MODEL);
    private final TextSetting modelName = new TextSetting("Model name",
        "Which model the service runs. Click to type it.", "")
        .under(replies, Replies.MODEL);
    private final TextSetting persona = new TextSetting("Persona",
        "Who the bot pretends to be when it writes. Click to type it.", "a friendly player")
        .under(replies, Replies.MODEL);
    private final BoolSetting onlyNamed = new BoolSetting("Only when named",
        "Only answers lines that mention your name.", true)
        .under(replies, Replies.MODEL);
    // Nought would be the same as Only when named.
    private final NumberSetting joinIn = new NumberSetting("Join in",
        "Chance of answering a line that does not mention you.", 10, 1, 100, 1, "%").min(1).max(PERCENT)
        .unless(onlyNamed);
    private final NumberSetting typingSpeed = new NumberSetting("Typing speed",
        "Words a minute the replies are paced at.", 60, 10, 200, 5, " wpm").min(1)
        .under(replies, Replies.MODEL);
    private final ActionSetting testModel = new ActionSetting("Test model",
        "Asks the model to answer a made up line and shows the reply only to you.", this::test)
        .under(replies, Replies.MODEL);
    private final NumberSetting cooldown = new NumberSetting("Cooldown",
        "Seconds after a reply before the bot answers anyone again. Lines in between get no reply.",
        10, MIN_COOLDOWN, 60, 1, "s").min(MIN_COOLDOWN);

    // The newest lines heard. The model reads them before it answers.
    private final Deque<Heard> heard = new ArrayDeque<>();
    private long quietUntil;
    // Set whilst the model writes. Nothing else is asked until its answer is back.
    private boolean asking;
    private Due due;
    // Counts up whenever replies on their way are dropped. An answer from an older round is
    // thrown away.
    private int round;
    // The last problem told in chat. The same one again stays quiet until a reply gets through.
    private String warned;

    public ChatBot() {
        super("ChatBot", "Answers other players in chat by rules you set or with a chat model.", Category.MISC);
        addSettings(replies, ruleCount);
        for (int i = 0; i < SLOTS; i++) {
            slots[i] = new Slot(i + 1, ruleCount);
            addSettings(slots[i].phrase, slots[i].reply);
        }
        addSettings(modelAddress, modelName, persona, onlyNamed, joinIn, typingSpeed, testModel, cooldown);
        searchTags("auto reply", "responder", "answer", "ai", "chat model");
    }

    @Override
    protected void onEnable() {
        quietUntil = 0;
        warned = null;
        dropReplies();
        if (replies.is(Replies.MODEL) && modelHost() == null) {
            warn(NO_ADDRESS);
        }
    }

    @Override
    protected void onDisable() {
        dropReplies();
    }

    // The host a saved key belongs to. Null whilst the model address names none.
    public String modelHost() {
        return ChatModel.host(modelAddress.getValue());
    }

    // Forgets the lines heard and any reply on its way.
    private void dropReplies() {
        round++;
        asking = false;
        due = null;
        heard.clear();
    }

    // Fired on the netty thread. The line is read on the game thread where the tab list lives.
    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        Packet<?> packet = event.getPacket();
        if (packet instanceof ClientboundLoginPacket) {
            // A reply meant for one server never goes out on the next.
            mc.schedule(this::dropReplies);
        } else if (ChatSender.isChat(packet)) {
            mc.schedule(() -> onLine(ChatSender.lineOf(packet)));
        }
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (due == null || System.currentTimeMillis() < due.at()) {
            return;
        }
        String text = due.text();
        due = null;
        if (replies.is(Replies.MODEL)) {
            say(text);
        }
    }

    private void onLine(ChatSender.Line line) {
        if (line == null || !isEnabled() || mc.player == null) {
            return;
        }
        long now = System.currentTimeMillis();
        heard.addLast(new Heard(new ChatModel.Said(line.name(), line.text()), now));
        if (heard.size() > CONTEXT_LINES) {
            heard.removeFirst();
        }
        if (line.isOwn() || now < quietUntil) {
            return;
        }
        if (replies.is(Replies.RULES)) {
            answerByRule(line);
        } else {
            answerByModel(line, now);
        }
    }

    private void answerByRule(ChatSender.Line line) {
        String self = selfName();
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
        if (answer != null) {
            String reply = StringUtil.filterText(answer.replyTo(line.name()));
            say(reply.startsWith("/") ? reply : StringUtil.trimChatMessage(reply));
        }
    }

    // A line that names you always gets an answer and any other line only by the Join in chance.
    private void answerByModel(ChatSender.Line line, long now) {
        if (asking || due != null) {
            return;
        }
        String self = selfName();
        if (!ChatSender.holds(line.text(), self)
            && (onlyNamed.isOn() || ThreadLocalRandom.current().nextDouble(PERCENT) >= joinIn.getValue())) {
            return;
        }
        heard.removeIf(old -> now - old.at() > CONTEXT_MS);
        int asked = round;
        asking = ask(self, heard.stream().map(Heard::said).toList(), reply -> {
            if (current(asked)) {
                warned = null;
                String text = chatLine(reply, self);
                due = text.isEmpty() ? null : new Due(text, now + typingMillis(text));
            }
        }, problem -> {
            if (current(asked)) {
                warn(problem);
            }
        });
        if (!asking) {
            warn(NO_ADDRESS);
        }
    }

    // True for the answer to the request still waited on whilst Model is chosen. Either way
    // the wait is over.
    private boolean current(int asked) {
        if (asked != round) {
            return false;
        }
        asking = false;
        return replies.is(Replies.MODEL);
    }

    // Shows what the model makes of a made up line without saying anything.
    private String test() {
        if (!inGame()) {
            return "Join a world first";
        }
        String self = selfName();
        boolean asked = ask(self, List.of(new ChatModel.Said(TEST_PLAYER, "hey " + self + " what are you up to")),
            reply -> {
                String text = chatLine(reply, self);
                ChatUtil.message(text.isEmpty() ? "§bChatBot §7would stay quiet." : "§bChatBot §7would say §f" + text);
            }, ChatUtil::message);
        return asked ? "Asking the model" : "Type a web address first";
    }

    // Asks the model on a worker thread. The reply or a chat line saying why there is none
    // comes back on the game thread. False without asking whilst the address names no host.
    private boolean ask(String self, List<ChatModel.Said> chat, Consumer<String> reply, Consumer<String> problem) {
        String address = modelAddress.getValue();
        String host = ChatModel.host(address);
        if (host == null) {
            return false;
        }
        String key = ServerStore.modelKeys().get(host);
        String model = modelName.getValue();
        String instructions = instructions(self);
        Web.later("chat model", () -> {
            ChatModel.Answer answer = ChatModel.ask(address, model, key, instructions, chat);
            mc.execute(() -> {
                if (answer.problem() == null) {
                    reply.accept(answer.reply());
                } else {
                    problem.accept("§bChatBot §cgot no reply from " + host + ". " + answer.problem()
                        + (answer.keyRefused() && key == null ? keyHint() : ""));
                }
            });
        });
        return true;
    }

    // A service that turns away a missing key gets told how to save one.
    private static String keyHint() {
        return " Type " + OfflineClient.INSTANCE.getCommandManager().getPrefix() + "chatbot key <key> to save one.";
    }

    // What the model is told before it reads the chat.
    private String instructions(String self) {
        String who = persona.getValue().strip().replaceAll("[.!?]+$", "");
        return "You are " + self + " chatting with other players on a Minecraft server."
            + (who.isEmpty() ? "" : " Stay in character as " + who + ".")
            + " Answer the newest line with one short chat line of your own. Write only the words you would type.";
    }

    // The reply as one line of chat. A name tag or your own name in front and wrapping quotes
    // come off. Another player could talk the model into a command and a reply never runs as one.
    private static String chatLine(String reply, String self) {
        String line = StringUtil.filterText(NAME_TAG.matcher(reply).replaceFirst("")).strip();
        if (line.regionMatches(true, 0, self + ":", 0, self.length() + 1)) {
            line = line.substring(self.length() + 1).strip();
        }
        if (line.length() > 1 && line.startsWith("\"") && line.endsWith("\"")) {
            line = line.substring(1, line.length() - 1).strip();
        }
        while (line.startsWith("/")) {
            line = line.substring(1).strip();
        }
        return StringUtil.trimChatMessage(line);
    }

    // How long a player typing at the set speed takes over the reply.
    private long typingMillis(String text) {
        int words = text.split("\\s+").length;
        return Math.round(words * TimeUnit.MINUTES.toMillis(1) / typingSpeed.getValue());
    }

    // A problem is told once. The same one again stays quiet until a reply gets through. Chat
    // shows nothing outside a world and a problem only counts as told once it was seen.
    private void warn(String problem) {
        if (mc.player != null && !problem.equals(warned)) {
            warned = problem;
            ChatUtil.message(problem);
        }
    }

    // Every reply goes out here. The bot answers nobody again until the cooldown has passed.
    private void say(String text) {
        quietUntil = System.currentTimeMillis() + Math.round(cooldown.getValue() * TimeUnit.SECONDS.toMillis(1));
        ChatUtil.say(text);
    }

    private static String selfName() {
        return mc.player.getGameProfile().name();
    }
}
