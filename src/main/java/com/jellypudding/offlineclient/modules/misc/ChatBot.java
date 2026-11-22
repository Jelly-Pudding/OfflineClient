package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.config.ServerStore;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.friend.FriendManager;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.ActionSetting;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ChoiceListSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.TextSetting;
import com.jellypudding.offlineclient.util.ChatGames;
import com.jellypudding.offlineclient.util.ChatModel;
import com.jellypudding.offlineclient.util.ChatPace;
import com.jellypudding.offlineclient.util.ChatSender;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.LinePattern;
import com.jellypudding.offlineclient.util.ListFile;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.SentLine;
import com.jellypudding.offlineclient.util.ShownChat;
import com.jellypudding.offlineclient.util.TabChanges;
import com.jellypudding.offlineclient.util.TextLines;
import com.jellypudding.offlineclient.util.Web;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.util.StringUtil;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Pattern;

// A line a player wrote never counts as a line of the server. A line holding one of your own
// replies is never answered and two clients running the same rules stop after a single exchange. A
// model writes one reply at a time and its cooldown starts once that reply has gone out.
public final class ChatBot extends Module {

    public enum Hears { PLAYER_SAYS, SERVER_SAYS, REPEAT_A_PLAYER, PLAYER_JOINS, PLAYER_LEAVES }

    public enum AcceptFrom { FRIENDS, ALL_BUT_ENEMIES }

    private static final int SLOTS = 16;

    // One line a second never trips the spam kick of a vanilla server.
    private static final double MIN_COOLDOWN = 1;

    private static final double WAIT_SPREAD = 0.5;

    private static final int MAX_WAITING = 16;

    private static final int CONTEXT_LINES = 10;
    private static final long CONTEXT_MS = TimeUnit.MINUTES.toMillis(5);

    private static final int MAX_QUESTIONS = 2000;

    // A name tag such as <Steve> that a model copied from the chat it read.
    private static final Pattern NAME_TAG = Pattern.compile("^<[^<>]*>\\s*");

    private static final String TEST_PLAYER = "Alex";

    private static final double PERCENT = 100;

    private static final String NO_ADDRESS = ChatUtil.from("ChatBot",
        "§cneeds a model address that starts with http or https.");

    // How the common teleport plugins word a request.
    private static final String[] REQUEST_WORDINGS = {
        "{player} has requested to teleport to you", "{player} has requested that you teleport to them",
        "{player} wants to teleport to you", "{player} wants you to teleport to them",
        "{player} has sent you a teleport request", "{player} has sent a teleport request to you",
        "{player} sent you a tpa request"
    };

    private static final Map<String, ChatGames.Kind> GAMES = games();

    private final class Rule {

        final int slot;
        final EnumSetting<Hears> hears;
        final TextSetting phrase;
        final TextSetting wording;
        final TextSetting player;
        final BoolSetting regex;
        final BoolSetting friendsOnly;
        final TextSetting reply;

        private LinePattern pattern;
        private String patternFrom;
        private boolean wordingNames;
        private String wordingFrom;
        long restsUntil;

        Rule(int number) {
            slot = number - 1;
            String tail = " " + number;
            hears = new EnumSetting<>("Rule" + tail, "What sets this rule off.", Hears.PLAYER_SAYS)
                .describe(Hears.PLAYER_SAYS, "Another player writes a line that holds the phrase.")
                .describe(Hears.SERVER_SAYS,
                    "The server writes a line that starts with the wording. A line a player wrote never counts.")
                .describe(Hears.REPEAT_A_PLAYER, "The player you name writes in open chat and the rule says the same.")
                .describe(Hears.PLAYER_JOINS, "A player joins the server.")
                .describe(Hears.PLAYER_LEAVES, "A player leaves the server.")
                .under(ruleCount, () -> slot < ruleCount.getInt());
            phrase = new TextSetting("Phrase" + tail,
                "Words in another player's line that get a reply. Click to type them.", "")
                .under(hears, Hears.PLAYER_SAYS);
            wording = new TextSetting("Wording" + tail,
                "How the server starts the line with {player} where a name stands. Click to type it.", "")
                .under(hears, Hears.SERVER_SAYS);
            player = new TextSetting("Player" + tail,
                "The player whose lines are said again. Click to type the name.", "")
                .under(hears, Hears.REPEAT_A_PLAYER);
            regex = new BoolSetting("Regex" + tail,
                "Reads the phrase or the wording as a regular expression. The first group in brackets of a"
                    + " wording is the name.", false)
                .under(hears, Hears.PLAYER_SAYS, Hears.SERVER_SAYS);
            friendsOnly = new BoolSetting("Friends only" + tail,
                "Only acts for a player on your friend list.", false)
                .under(hears, this::asksFriends);
            reply = new TextSetting("Reply" + tail,
                "What you say with {player} for the name. Start it with a slash to run a command."
                    + TextLines.LIVE_WORDS, "")
                .under(hears, () -> !hears.is(Hears.REPEAT_A_PLAYER));
        }

        void register() {
            addSettings(hears, phrase, wording, player, regex, friendsOnly, reply);
        }

        boolean inUse() {
            return slot < ruleCount.getInt() && switch (hears.getValue()) {
                case PLAYER_SAYS -> !phrase.isBlank() && !reply.isBlank();
                case SERVER_SAYS -> !wording.isBlank() && !reply.isBlank();
                case REPEAT_A_PLAYER -> !player.isBlank();
                case PLAYER_JOINS, PLAYER_LEAVES -> !reply.isBlank();
            };
        }

        private LinePattern pattern() {
            boolean server = hears.is(Hears.SERVER_SAYS);
            if (!server && !hears.is(Hears.PLAYER_SAYS)) {
                return null;
            }
            String typed = server ? wording.getValue() : phrase.getValue();
            String from = hears.getValue() + "\n" + regex.isOn() + "\n" + typed;
            if (!from.equals(patternFrom)) {
                patternFrom = from;
                Consumer<String> problems = problem -> ChatUtil.error("ChatBot rule " + (slot + 1) + " " + problem);
                pattern = server ? LinePattern.forServer(typed, regex.isOn(), problems)
                    : LinePattern.forPlayers(typed, regex.isOn(), problems);
            }
            return pattern;
        }

        private boolean wordingNamesPlayer() {
            String from = regex.isOn() + "\n" + wording.getValue();
            if (!from.equals(wordingFrom)) {
                wordingFrom = from;
                wordingNames = LinePattern.forServer(wording.getValue(), regex.isOn(), problem -> {
                }).namesPlayer();
            }
            return wordingNames;
        }

        private boolean needsName() {
            return reply.getValue().contains(LinePattern.PLAYER);
        }

        private boolean asksFriends() {
            return switch (hears.getValue()) {
                case REPEAT_A_PLAYER -> false;
                case SERVER_SAYS -> wordingNamesPlayer();
                default -> true;
            };
        }

        // A friend has to be certain to have written the line. Another player can make a line
        // of their own read as the line of a friend.
        private boolean mayAnswer(String name, boolean certain) {
            return !friendsOnly.isOn() || !asksFriends() || certain && name != null && isFriend(name);
        }

        String answerTo(Spoken spoken) {
            if (!inUse()) {
                return null;
            }
            ChatSender.Line line = spoken.line();
            if (hears.is(Hears.REPEAT_A_PLAYER)) {
                if (spoken.whisper() || !spoken.certain() || !line.name().equalsIgnoreCase(player.getValue().strip())) {
                    return null;
                }
                // What the bot said itself and heard back is never said again. Two bots that
                // repeat each other stop after one round.
                String said = SentLine.chat(line.text());
                return said.equalsIgnoreCase(lastSaid) ? "" : said;
            }
            LinePattern listens = pattern();
            if (listens == null || listens.match(line.text()) == null || !mayAnswer(line.name(), spoken.certain())) {
                return null;
            }
            return SentLine.of(reply.getValue(), "player", line.name());
        }

        // A name at the very start of a line of the server is one no other player put there.
        String answerToServer(String text, String self) {
            LinePattern listens = hears.is(Hears.SERVER_SAYS) && inUse() ? pattern() : null;
            LinePattern.Hit hit = listens == null ? null : listens.match(text);
            if (hit == null || self.equalsIgnoreCase(hit.name()) || !mayAnswer(hit.name(), true)
                || hit.name() == null && needsName()) {
                return null;
            }
            return hit.name() == null ? SentLine.of(reply.getValue())
                : SentLine.of(reply.getValue(), "player", hit.name());
        }

        String answerToTab(TabChanges.Change change) {
            Hears wanted = change.joined() ? Hears.PLAYER_JOINS : Hears.PLAYER_LEAVES;
            if (!inUse() || !hears.is(wanted) || !mayAnswer(change.name(), true)) {
                return null;
            }
            return SentLine.of(reply.getValue(), "player", change.name());
        }

        // A rule that rests still takes the line and nothing else answers it.
        void answer(String line, long now) {
            if (now < restsUntil || line.isEmpty()) {
                return;
            }
            long at = now + answerWait();
            if (queue(new Due(line, at, this::inUse, false, null))) {
                restsUntil = at + cooldownMillis();
            }
        }

        boolean repliesWith(String text, String self) {
            if (!inUse() || hears.is(Hears.REPEAT_A_PLAYER)) {
                return false;
            }
            String yours = SentLine.of(reply.getValue(), "player", self);
            return !yours.isBlank() && ChatSender.holds(text, yours);
        }

        void tellProblems() {
            if (slot >= ruleCount.getInt() || hears.is(Hears.REPEAT_A_PLAYER)) {
                return;
            }
            String rule = "ChatBot rule " + (slot + 1);
            boolean listens = switch (hears.getValue()) {
                case PLAYER_SAYS -> !phrase.isBlank();
                case SERVER_SAYS -> !wording.isBlank();
                default -> true;
            };
            if (listens && reply.isBlank()) {
                ChatUtil.error(rule + " has no reply yet.");
            } else if (!listens && !reply.isBlank()) {
                ChatUtil.error(rule + " has a reply but nothing to listen for yet.");
            }
            LinePattern listening = inUse() ? pattern() : null;
            if (listening != null && hears.is(Hears.SERVER_SAYS) && !listening.namesPlayer() && needsName()) {
                ChatUtil.error(rule + " says {player} but its wording names nobody. Put {player} where the name"
                    + " stands.");
            }
        }
    }

    // Certain when no other player can have made it read as theirs.
    private record Spoken(ChatSender.Line line, boolean whisper, boolean certain) {
    }

    private record Recent(ChatModel.Said said, long at) {
    }

    // Wanted is asked again just before the line goes out. Told is shown to you once it has gone
    // and may be null.
    private record Due(String line, long at, BooleanSupplier wanted, boolean fromModel, String told) {
    }

    private final NumberSetting ruleCount = new NumberSetting("Rules",
        "How many rules the bot follows. A row opens for each.", 1, 0, SLOTS, 1).max(SLOTS);
    private final Rule[] rules = new Rule[SLOTS];
    private final BoolSetting chatModel = new BoolSetting("Chat model",
        "A chat model answers what other players write whenever no rule does.", false);
    private final TextSetting modelAddress = new TextSetting("Model address",
        "The web address of the chat model. A model running on your own computer needs no key.", "")
        .under(chatModel);
    private final TextSetting modelName = new TextSetting("Model name",
        "Which model the service runs. Click to type it.", "")
        .under(chatModel);
    private final TextSetting persona = new TextSetting("Persona",
        "Who the bot pretends to be when it writes. Click to type it.", "a friendly player")
        .under(chatModel);
    private final BoolSetting onlyNamed = new BoolSetting("Only when named",
        "Only answers lines that mention your name.", true)
        .under(chatModel);
    // Nought would be the same as Only when named.
    private final NumberSetting joinIn = new NumberSetting("Join in",
        "Chance of answering a line that does not mention you.", 10, 1, 100, 1, "%").max(PERCENT)
        .unless(onlyNamed);
    private final ActionSetting testModel = new ActionSetting("Test model",
        "Asks the model to answer a made up line and shows the reply only to you.", this::test)
        .under(chatModel);
    private final BoolSetting teleports = new BoolSetting("Accept teleports",
        "Accepts a teleport request when the server tells you of one.", false);
    private final TextLines requests = TextLines.named("Requests", "Request",
        "How many wordings of a request are looked for.",
        "A teleport request as the server starts the line with {player} where the name stands. Click to type it.",
        REQUEST_WORDINGS)
        .under(teleports);
    private final TextSetting acceptWith = new TextSetting("Accept with",
        "The command that accepts a request with the name of the player who asked on the end or where you put"
            + " {player}. Click to type it.", "/tpaccept {player}")
        .under(teleports);
    private final EnumSetting<AcceptFrom> acceptFrom = new EnumSetting<>("Accept from",
        "Whose teleport requests are accepted.", AcceptFrom.FRIENDS)
        .describe(AcceptFrom.FRIENDS, "Only a player on your friend list.")
        .describe(AcceptFrom.ALL_BUT_ENEMIES, "Anyone who is not on your enemy list.")
        .under(teleports);
    private final BoolSetting games = new BoolSetting("Chat games",
        "Answers the chat games a server runs such as working out a sum or unscrambling a word.", false);
    private final ChoiceListSetting gameKinds = new ChoiceListSetting("Games",
        "The chat games the bot answers. Click to pick them.", GAMES::keySet, startingGames())
        .under(games);
    private final TextSetting wordFile = new TextSetting("Word file",
        "A text file in the offlineclient folder with a word on each line. Unscrambling and missing letters look"
            + " in it and at the name of every item and mob.", "words.txt")
        .under(games, () -> games.isOn() && (picked(ChatGames.Kind.UNSCRAMBLE) || picked(ChatGames.Kind.FILL_IN)));
    private final TextSetting triviaFile = new TextSetting("Trivia file",
        "A text file in the offlineclient folder with a question and its answer on each line split by an"
            + " equals sign.", "trivia.txt")
        .under(games, () -> games.isOn() && picked(ChatGames.Kind.TRIVIA));
    private final NumberSetting typingSpeed = new NumberSetting("Typing speed",
        "Words a minute the answers of the model and of the chat games are typed at.", 60, 10, 200, 5, " wpm")
        .min(1)
        .visibleWhen(() -> chatModel.isOn() || games.isOn());
    private final NumberSetting answerAfter = new NumberSetting("Answer after",
        "Seconds before a rule or a game or an accept goes out. Each wait runs up to half as long again at"
            + " random.", 0, 0, 10, 0.5, "s");
    private final NumberSetting cooldown = new NumberSetting("Cooldown",
        "Seconds a rule or the chat games or the model rest after an answer. Lines in between get no answer"
            + " from them.", 10, MIN_COOLDOWN, 60, 1, "s");

    private final TabChanges tab = new TabChanges();
    // Kept in the order the lines go out.
    private final List<Due> due = new ArrayList<>();
    private final Deque<Recent> heard = new ArrayDeque<>();
    private final ListFile wordList = new ListFile();
    private final ListFile triviaList = new ListFile();
    private ChatGames.Words words;
    private List<String> wordsFrom;
    private Map<String, String> trivia = Map.of();
    private List<String> triviaFrom;
    private boolean asking;
    private long modelRestsUntil;
    private long gamesRestUntil;
    // Counts up whenever replies on their way are dropped. An answer from an older round is
    // thrown away.
    private int round;
    private String warned;
    private String lastSaid = "";

    public ChatBot() {
        super("ChatBot", "Answers players and the server in chat by rules you set or with a chat model.",
            Category.MISC);
        addSettings(ruleCount);
        for (int i = 0; i < SLOTS; i++) {
            rules[i] = new Rule(i + 1);
            rules[i].register();
        }
        addSettings(chatModel, modelAddress, modelName, persona, onlyNamed, joinIn, testModel, teleports);
        addSettings(requests.settings());
        addSettings(acceptWith, acceptFrom, games, gameKinds, wordFile, triviaFile, typingSpeed, answerAfter,
            cooldown);
        searchTags("auto reply", "responder", "answer", "ai", "chat model", "auto accept", "tpaccept", "tpa",
            "welcome", "greeter", "annoy", "echo", "repeat", "chat game", "unscramble", "trivia");
    }

    @Override
    protected void onEnable() {
        warned = null;
        lastSaid = "";
        modelRestsUntil = 0;
        gamesRestUntil = 0;
        dropReplies();
        tab.sync();
        for (Rule rule : rules) {
            rule.restsUntil = 0;
            rule.tellProblems();
        }
        if (chatModel.isOn() && modelHost() == null) {
            warn(NO_ADDRESS);
        }
        if (teleports.isOn()) {
            List<String> wordings = requests.all();
            if (wordings.isEmpty() || acceptWith.isBlank()) {
                ChatUtil.error("ChatBot accepts no teleport whilst it has no request wording or Accept with is"
                    + " empty.");
            }
            wordings.stream().filter(wording -> !namesPlayer(wording)).forEach(wording -> ChatUtil.error(
                "ChatBot passes over the request wording " + wording + " because it holds no {player}."));
        }
    }

    @Override
    protected void onDisable() {
        dropReplies();
    }

    public String modelHost() {
        return ChatModel.host(modelAddress.getValue());
    }

    private void dropReplies() {
        round++;
        asking = false;
        due.clear();
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
            mc.schedule(() -> onChat(packet));
        }
        List<TabChanges.Change> changes = tab.read(packet);
        if (!changes.isEmpty()) {
            mc.schedule(() -> changes.forEach(this::onTabChange));
        }
    }

    @Subscribe
    private void onTick(TickEvent event) {
        long now = System.currentTimeMillis();
        while (!due.isEmpty() && due.getFirst().at() <= now) {
            Due next = due.getFirst();
            if (!next.wanted().getAsBoolean()) {
                due.removeFirst();
                continue;
            }
            if (!ChatPace.RULE.say(next.line())) {
                ChatPace.tellHeld(getName());
                return;
            }
            due.removeFirst();
            lastSaid = next.line();
            if (next.fromModel()) {
                modelRestsUntil = now + cooldownMillis();
            }
            if (next.told() != null) {
                ChatUtil.message(next.told());
            }
        }
    }

    private void onChat(Packet<?> packet) {
        if (!isEnabled() || !inGame()) {
            return;
        }
        Spoken spoken = playerLine(packet);
        if (spoken != null) {
            onPlayerLine(spoken);
            return;
        }
        String text = ShownChat.serverText(packet);
        if (text != null) {
            onServerLine(text);
        }
    }

    private Spoken playerLine(Packet<?> packet) {
        ChatSender.Line line = ChatSender.lineOf(packet);
        if (line != null) {
            return new Spoken(line, ShownChat.whisper(packet),
                line.name().equalsIgnoreCase(ChatSender.certainWriter(packet)));
        }
        ShownChat.Whisper whisper = ShownChat.whisperOf(packet);
        if (whisper == null) {
            return null;
        }
        ClientPacketListener connection = mc.getConnection();
        PlayerInfo info = connection == null ? null : connection.getPlayerInfo(whisper.from());
        return new Spoken(new ChatSender.Line(info == null ? null : info.getProfile().id(), whisper.from(),
            whisper.text()), true, true);
    }

    private void onPlayerLine(Spoken spoken) {
        ChatSender.Line line = spoken.line();
        if (Modules.mutes(line.name())) {
            return;
        }
        long now = System.currentTimeMillis();
        heard.addLast(new Recent(new ChatModel.Said(line.name(), line.text()), now));
        if (heard.size() > CONTEXT_LINES) {
            heard.removeFirst();
        }
        String self = selfName();
        if (line.isOwn() || line.name().equalsIgnoreCase(self) || line.text().equalsIgnoreCase(lastSaid)) {
            return;
        }
        // Another bot saying one of our replies back would start an endless exchange.
        for (Rule rule : rules) {
            if (rule.repliesWith(line.text(), self)) {
                return;
            }
        }
        for (Rule rule : rules) {
            String answer = rule.answerTo(spoken);
            if (answer != null) {
                rule.answer(answer, now);
                return;
            }
        }
        if (chatModel.isOn()) {
            answerByModel(line, now);
        }
    }

    private void onServerLine(String text) {
        long now = System.currentTimeMillis();
        String self = selfName();
        for (Rule rule : rules) {
            String answer = rule.answerToServer(text, self);
            if (answer != null) {
                rule.answer(answer, now);
                return;
            }
        }
        if (teleports.isOn() && acceptRequest(text, self, now)) {
            return;
        }
        if (games.isOn()) {
            answerGame(text, now);
        }
    }

    private void onTabChange(TabChanges.Change change) {
        if (!isEnabled() || !inGame() || change.name().equalsIgnoreCase(selfName())) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Rule rule : rules) {
            String answer = rule.answerToTab(change);
            if (answer != null) {
                rule.answer(answer, now);
                return;
            }
        }
    }

    // The accept always names the player who asked. A bare accept takes whichever request came last
    // and that may not be theirs. A name the line gives may be no player at all such as a star that
    // a server reads as everyone.
    private boolean acceptRequest(String text, String self, long now) {
        for (String wording : requests.all()) {
            LinePattern.Hit hit = namesPlayer(wording)
                ? LinePattern.forServer(wording, false, ChatUtil::error).match(text) : null;
            if (hit == null) {
                continue;
            }
            if (self.equalsIgnoreCase(hit.name())) {
                return true;
            }
            String name = ChatSender.listedName(hit.name());
            String shown = EntityUtil.displayName(name == null ? hit.name() : name);
            String refusal = refusal(name);
            if (refusal != null) {
                tell("left the teleport request of §b" + shown + "§7 alone. " + refusal);
                return true;
            }
            queue(new Due(SentLine.of(SentLine.naming(acceptWith.getValue()), "player", name), now + answerWait(),
                teleports::isOn, false,
                ChatUtil.from(getName(), "accepted the teleport request of §b" + shown + "§7.")));
            return true;
        }
        return false;
    }

    private String refusal(String listed) {
        if (listed == null) {
            return "Nobody by that name is on the tab list.";
        }
        if (acceptFrom.is(AcceptFrom.FRIENDS)) {
            return isFriend(listed) ? null : "They are not your friend.";
        }
        return friends().isEnemy(listed) ? "They are your enemy." : null;
    }

    private void answerGame(String text, long now) {
        Set<ChatGames.Kind> kinds = EnumSet.noneOf(ChatGames.Kind.class);
        GAMES.forEach((name, kind) -> {
            if (gameKinds.contains(name)) {
                kinds.add(kind);
            }
        });
        if (kinds.isEmpty() || now < gamesRestUntil) {
            return;
        }
        String answer = ChatGames.solve(text, kinds, this::gameWords,
            kinds.contains(ChatGames.Kind.TRIVIA) ? trivia() : Map.of());
        if (answer == null || ChatSender.namesAnyone(text)) {
            return;
        }
        String line = SentLine.chat(answer);
        long at = now + answerWait() + typingMillis(line);
        if (queue(new Due(line, at, games::isOn, false, null))) {
            gamesRestUntil = at + cooldownMillis();
        }
    }

    private boolean picked(ChatGames.Kind kind) {
        for (Map.Entry<String, ChatGames.Kind> game : GAMES.entrySet()) {
            if (game.getValue() == kind && gameKinds.contains(game.getKey())) {
                return true;
            }
        }
        return false;
    }

    private ChatGames.Words gameWords() {
        List<String> lines = wordList.lines(wordFile.getValue(), ChatUtil::error);
        if (lines != wordsFrom) {
            wordsFrom = lines;
            Set<String> all = new LinkedHashSet<>(lines);
            all.addAll(GameNames.ALL);
            words = new ChatGames.Words(all);
        }
        return words;
    }

    private Map<String, String> trivia() {
        List<String> lines = triviaList.lines(triviaFile.getValue(), ChatUtil::error);
        if (lines != triviaFrom) {
            triviaFrom = lines;
            Map<String, String> read = new LinkedHashMap<>();
            for (String line : lines) {
                int split = line.lastIndexOf('=');
                String question = split == -1 ? "" : line.substring(0, split).strip().toLowerCase(Locale.ROOT);
                String answer = split == -1 ? "" : line.substring(split + 1).strip();
                if (!question.isEmpty() && !answer.isEmpty() && read.size() < MAX_QUESTIONS) {
                    read.putIfAbsent(question, answer);
                }
            }
            trivia = read;
        }
        return trivia;
    }

    private boolean queue(Due next) {
        if (next.line().isBlank() || due.size() >= MAX_WAITING
            || due.stream().anyMatch(waiting -> waiting.line().equals(next.line()))) {
            return false;
        }
        int at = 0;
        while (at < due.size() && due.get(at).at() <= next.at()) {
            at++;
        }
        due.add(at, next);
        return true;
    }

    private long answerWait() {
        double spread = 1 + ThreadLocalRandom.current().nextDouble(WAIT_SPREAD);
        return Math.round(answerAfter.getValue() * spread * TimeUnit.SECONDS.toMillis(1));
    }

    private long cooldownMillis() {
        return Math.round(cooldown.getValue() * TimeUnit.SECONDS.toMillis(1));
    }

    private static boolean namesPlayer(String wording) {
        return wording.contains(LinePattern.PLAYER);
    }

    private static FriendManager friends() {
        return OfflineClient.INSTANCE.getFriendManager();
    }

    private static boolean isFriend(String name) {
        return friends().isFriend(name);
    }

    private void answerByModel(ChatSender.Line line, long now) {
        if (asking || now < modelRestsUntil || due.stream().anyMatch(Due::fromModel)) {
            return;
        }
        String self = selfName();
        if (!ChatSender.holds(line.text(), self)
            && (onlyNamed.isOn() || ThreadLocalRandom.current().nextDouble(PERCENT) >= joinIn.getValue())) {
            return;
        }
        heard.removeIf(old -> now - old.at() > CONTEXT_MS);
        int asked = round;
        asking = ask(self, heard.stream().map(Recent::said).toList(), reply -> {
            if (current(asked)) {
                warned = null;
                String text = chatLine(reply, self);
                queue(new Due(text, now + typingMillis(text), chatModel::isOn, true, null));
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

    private boolean current(int asked) {
        if (asked != round) {
            return false;
        }
        asking = false;
        return isEnabled() && chatModel.isOn();
    }

    private String test() {
        if (!inGame()) {
            return "Join a world first";
        }
        String self = selfName();
        boolean asked = ask(self, List.of(new ChatModel.Said(TEST_PLAYER, "hey " + self + " what are you up to")),
            reply -> {
                String text = chatLine(reply, self);
                tell(text.isEmpty() ? "would stay quiet." : "would say §f" + text);
            }, ChatUtil::message);
        return asked ? "Asking the model" : "Type a web address first";
    }

    // Asks on a worker thread and answers on the game thread.
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
                    problem.accept(ChatUtil.from(getName(), "§cgot no reply from " + host + ". " + answer.problem()
                        + (answer.keyRefused() && key == null ? keyHint() : "")));
                }
            });
        });
        return true;
    }

    private static String keyHint() {
        return " Type " + OfflineClient.INSTANCE.getCommandManager().getPrefix() + "chatbot key <key> to save one.";
    }

    private String instructions(String self) {
        String who = persona.getValue().strip().replaceAll("[.!?]+$", "");
        return "You are " + self + " chatting with other players on a Minecraft server."
            + (who.isEmpty() ? "" : " Stay in character as " + who + ".")
            + " Answer the newest line with one short chat line of your own. Write only the words you would type.";
    }

    // Another player could talk the model into a command and a reply never runs as one.
    private static String chatLine(String reply, String self) {
        String line = StringUtil.filterText(NAME_TAG.matcher(reply).replaceFirst("")).strip();
        if (line.regionMatches(true, 0, self + ":", 0, self.length() + 1)) {
            line = line.substring(self.length() + 1).strip();
        }
        if (line.length() > 1 && line.startsWith("\"") && line.endsWith("\"")) {
            line = line.substring(1, line.length() - 1).strip();
        }
        return SentLine.chat(line);
    }

    private long typingMillis(String text) {
        int words = text.split("\\s+").length;
        return Math.round(words * TimeUnit.MINUTES.toMillis(1) / typingSpeed.getValue());
    }

    // Chat shows nothing outside a world and a problem only counts as told once it was seen.
    private void warn(String problem) {
        if (mc.player != null && !problem.equals(warned)) {
            warned = problem;
            ChatUtil.message(problem);
        }
    }

    private static String selfName() {
        return mc.player.getGameProfile().name();
    }

    private static Map<String, ChatGames.Kind> games() {
        Map<String, ChatGames.Kind> games = new LinkedHashMap<>();
        games.put("Type a word", ChatGames.Kind.TYPE);
        games.put("Reverse a word", ChatGames.Kind.REVERSE);
        games.put("Sums", ChatGames.Kind.SUM);
        games.put("Missing letters", ChatGames.Kind.FILL_IN);
        games.put("Unscramble", ChatGames.Kind.UNSCRAMBLE);
        games.put("Trivia", ChatGames.Kind.TRIVIA);
        return games;
    }

    private static List<String> startingGames() {
        Set<ChatGames.Kind> left = EnumSet.of(ChatGames.Kind.TYPE, ChatGames.Kind.REVERSE, ChatGames.Kind.TRIVIA);
        return GAMES.entrySet().stream().filter(game -> !left.contains(game.getValue()))
            .map(Map.Entry::getKey).toList();
    }

    // Read once the first time a game needs a word.
    private static final class GameNames {

        static final List<String> ALL = read();

        private static List<String> read() {
            Set<String> names = new LinkedHashSet<>();
            addAll(names, BuiltInRegistries.ITEM.keySet());
            addAll(names, BuiltInRegistries.ENTITY_TYPE.keySet());
            addAll(names, BuiltInRegistries.MOB_EFFECT.keySet());
            return List.copyOf(names);
        }

        private static void addAll(Set<String> names, Set<Identifier> ids) {
            for (Identifier id : ids) {
                String whole = ChatUtil.words(id);
                names.add(whole);
                names.addAll(List.of(whole.split(" ")));
            }
        }
    }
}
