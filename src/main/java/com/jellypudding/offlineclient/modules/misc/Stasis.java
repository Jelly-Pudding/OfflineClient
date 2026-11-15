package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.condition.Condition;
import com.jellypudding.offlineclient.condition.Conditions;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.KeyPressEvent;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.event.events.TotemPopEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.path.Trip;
import com.jellypudding.offlineclient.setting.ActionSetting;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.KeybindSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.TextSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatPace;
import com.jellypudding.offlineclient.util.ChatSender;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.FaceMode;
import com.jellypudding.offlineclient.util.SentLine;
import com.jellypudding.offlineclient.util.ServerInfo;
import com.jellypudding.offlineclient.util.ShownChat;
import com.jellypudding.offlineclient.util.SwingMode;
import com.jellypudding.offlineclient.util.TextLines;
import com.jellypudding.offlineclient.util.WorldWatch;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import java.util.ArrayDeque;
import java.util.Deque;

// The two ends of a stasis chamber. A pearl thrown into a chamber waits there until its
// trapdoor is flipped and then brings its thrower home. The player out in the world whispers
// a word to the account at base. That account walks to the chamber the word names and flips
// its trapdoor and puts it back.
public final class Stasis extends Module {

    public enum Role { AWAY, AT_BASE }

    // What the account at base is doing for the call it serves.
    private enum Step { IDLE, WALKING, FLIPPING, HOLDING, RESTORING, RETURNING }

    // One call for a pull and the chamber it is for.
    private record Call(String caller, Chamber chamber) {
    }

    // How many chambers one account can work.
    private static final int SLOTS = 8;

    // How close to the trapdoor the walk ends. A click reaches further than this.
    private static final double WALK_NEAR = 2;

    // Clicks tried before a trapdoor that will not move is given up on.
    private static final int FLIP_TRIES = 3;

    // Calls that wait whilst one is served. Anything past this is turned away.
    private static final int MAX_WAITING = 8;

    private static final int STAY_AWAKE_TICKS = 30 * SharedConstants.TICKS_PER_SECOND;

    // A call the server would have counted as spam is tried again this much later.
    private static final int RETRY_TICKS = SharedConstants.TICKS_PER_SECOND;

    // The rows of one chamber.
    private final class Chamber {

        final int number;
        final TextSetting word;
        final TextSetting trapdoor;
        final ActionSetting here;
        final NumberSetting flipTime;

        Chamber(int number) {
            this.number = number;
            word = new TextSetting("Word " + number,
                "The word that calls for this chamber. A caller whispers it to this account.",
                number == 1 ? "home" : "")
                .under(chambers, () -> number <= chambers.getInt());
            trapdoor = new TextSetting("Trapdoor " + number,
                "Where the trapdoor of this chamber is. Click to type it as x y z.", "")
                .secret().under(word, () -> true);
            here = new ActionSetting("Trapdoor " + number + " here",
                "Takes the trapdoor you are looking at for this chamber.", this::takeAimed)
                .under(word, () -> true);
            flipTime = new NumberSetting("Flip time " + number,
                "How long the trapdoor stays flipped before it is put back the way it was.",
                1, 0.2, 5, 0.1, " seconds")
                .min(0.1)
                .under(word, () -> true);
        }

        void register() {
            addSettings(word, trapdoor, here, flipTime);
        }

        private String takeAimed() {
            BlockHitResult aimed = inGame() ? BlockUtil.aimedBlock() : null;
            if (aimed == null || !(BlockUtil.state(aimed.getBlockPos()).getBlock() instanceof TrapDoorBlock)) {
                return "Look at the trapdoor first";
            }
            trapdoor.setValue(BlockUtil.text(aimed.getBlockPos()));
            OfflineClient.INSTANCE.getConfigManager().saveSoon();
            return "Set to " + trapdoor.getValue();
        }

        String calledBy() {
            return word.getValue().trim();
        }

        // Null whilst no place is typed in.
        BlockPos place() {
            return BlockUtil.parse(trapdoor.getValue());
        }

        // The word has to be the whole message. A word inside a sentence is talk and no call.
        boolean answersTo(String message) {
            return number <= chambers.getInt() && !word.isBlank() && calledBy().equalsIgnoreCase(message);
        }
    }

    private final EnumSetting<Role> role = new EnumSetting<>("Role",
        "Which end of the chamber this account is.", Role.AWAY)
        .describe(Role.AWAY, "You are out in the world and ask the account at base for a pull home.")
        .describe(Role.AT_BASE, "This account waits at base and works the chambers for whoever calls.");

    private final TextSetting puller = new TextSetting("Puller",
        "The player who waits at your chamber. Click to type the name.", "")
        .under(role, Role.AWAY);
    private final TextSetting word = new TextSetting("Word",
        "The word you whisper to ask for a pull. It has to be the word set for your chamber at base.", "home")
        .under(role, Role.AWAY);
    private final KeybindSetting pullKey = new KeybindSetting("Pull key",
        "Asks for a pull the moment it is pressed.", KeybindSetting.UNBOUND)
        .under(role, Role.AWAY);
    private final Conditions callOn = Conditions.forEscape("Call on",
        "What makes it ask for a pull by itself. Click to pick the reasons. Each one you pick lists its own"
            + " rows below and any one of them is enough.");
    private final NumberSetting askAgain = new NumberSetting("Ask again after",
        "How long it waits before it asks again whilst a reason still holds.", 10, 1, 60, 1, " seconds")
        .under(callOn.list(), () -> callOn.size() > 0);

    private final TextLines callers = TextLines.named("Callers", "Caller",
        "How many players may call for a pull. With every name blank any friend may.",
        "A player who may call. Click to type the name.")
        .under(role, Role.AT_BASE);
    private final BoolSetting whispersOnly = new BoolSetting("Whispers only",
        "Only a whisper to this account counts as a call. Off also heeds a caller who says the word and nothing"
            + " else in open chat.", true)
        .under(role, Role.AT_BASE);
    private final BoolSetting reply = new BoolSetting("Reply",
        "Whispers back to the caller once the trapdoor is flipped or why it could not be.", true)
        .under(role, Role.AT_BASE);
    private final BoolSetting goBack = new BoolSetting("Go back",
        "Walks back to where it stood once the trapdoor is put back.", true)
        .under(role, Role.AT_BASE);
    private final BoolSetting stayAwake = new BoolSetting("Stay awake",
        "Swings the arm every half minute between calls. The server counts that as play and does not kick"
            + " the account for idling.", true)
        .under(role, Role.AT_BASE);
    private final EnumSetting<FaceMode> face = FaceMode.setting(FaceMode.SERVER)
        .under(role, Role.AT_BASE);
    private final NumberSetting chambers = new NumberSetting("Chambers",
        "How many chambers this account works. A row opens for each.", 1, 1, SLOTS, 1)
        .max(SLOTS)
        .under(role, Role.AT_BASE);
    private final Chamber[] slots = new Chamber[SLOTS];

    private final TextSetting whisperWith = SentLine.whisperWith();

    private final Trip trip = new Trip();
    private final WorldWatch world = new WorldWatch();
    private final Deque<Call> waiting = new ArrayDeque<>();
    // The call being served. Null between calls and on the walk back.
    private Call serving;
    private Step step = Step.IDLE;
    // Where the account stood before it set out. Null whilst it stands there.
    private BlockPos home;
    private boolean headingHome;
    private boolean wasOpen;
    private int wait;
    private int tries;
    private int awakeIn;
    private int askWait;
    // The role on the last tick. Null as the module goes on.
    private Role roleWas;

    public Stasis() {
        super("Stasis", "Calls for a pull home through a stasis chamber or works the chambers for others.",
            Category.MISC);
        addSettings(role, puller, word, pullKey);
        callOn.list().under(role, Role.AWAY);
        addSettings(callOn.settings());
        addSettings(askAgain);
        addSettings(callers.settings());
        addSettings(whispersOnly, reply, goBack, stayAwake, face, chambers);
        for (int i = 0; i < SLOTS; i++) {
            slots[i] = new Chamber(i + 1);
            slots[i].register();
        }
        addSettings(whisperWith);
        searchTags("stasis chamber", "pearl", "pull", "teleport home", "trapdoor", "second account", "alt");
    }

    // Who you call whilst away and who is being pulled at base.
    @Override
    public String getSuffix() {
        if (role.is(Role.AWAY)) {
            return puller.isBlank() ? null : puller.getValue().trim();
        }
        return serving == null ? null : serving.caller();
    }

    @Override
    protected void onEnable() {
        askWait = 0;
        awakeIn = STAY_AWAKE_TICKS;
        roleWas = null;
        world.accept();
        if (role.is(Role.AWAY)) {
            callOn.tellProblems();
            if (!SentLine.oneWord(puller.getValue().trim())) {
                ChatUtil.error("Stasis cannot ask for a pull until Puller holds the name of the player at your"
                    + " chamber.");
            }
        } else if (noChamberSet()) {
            ChatUtil.error("Stasis has no trapdoor saved. Look at the trapdoor of a chamber and click Trapdoor 1"
                + " here.");
        }
    }

    private boolean noChamberSet() {
        for (Chamber chamber : slots) {
            if (chamber.number <= chambers.getInt() && chamber.place() != null) {
                return false;
            }
        }
        return true;
    }

    @Override
    protected void onDisable() {
        standDown();
    }

    // Drops every call and stops walking.
    private void standDown() {
        trip.stop();
        waiting.clear();
        serving = null;
        home = null;
        headingHome = false;
        step = Step.IDLE;
    }

    @Subscribe
    private void onKeyPress(KeyPressEvent event) {
        if (role.is(Role.AWAY) && inGame() && mc.gui.screen() == null && event.presses(pullKey)) {
            ask("You pressed the pull key.");
        }
    }

    @Subscribe
    private void onTick(TickEvent event) {
        // A role switched over mid walk must not leave the walk keys held.
        if (world.changed() || mc.player.isDeadOrDying() || role.is(Role.AWAY) && step != Step.IDLE) {
            standDown();
        }
        // The reasons start afresh as the away role begins. A hit from before is no news and
        // Time on counts from here.
        if (role.getValue() != roleWas) {
            roleWas = role.getValue();
            callOn.start();
        }
        if (role.is(Role.AWAY)) {
            tickAway();
        } else {
            tickBase();
        }
    }

    // The reasons are read on every tick. One that came and went whilst it waited is not kept.
    private void tickAway() {
        if (askWait > 0) {
            askWait--;
        }
        callOn.tick();
        Condition reason = callOn.firstHolding();
        if (reason != null && askWait == 0 && !mc.player.isDeadOrDying()) {
            ask(reason.reason());
        }
    }

    // A pop is acted on as it is heard of.
    @Subscribe
    private void onTotemPop(TotemPopEvent event) {
        Condition pop = callOn.popped(event);
        if (pop != null && role.is(Role.AWAY) && askWait == 0 && inGame() && !mc.player.isDeadOrDying()) {
            ask(pop.reason());
        }
    }

    // The wait also spaces out the line that says why it cannot ask.
    private void ask(String why) {
        askWait = askAgain.getInt() * SharedConstants.TICKS_PER_SECOND;
        String name = puller.getValue().trim();
        if (!SentLine.oneWord(name) || word.isBlank()) {
            ChatUtil.error("Stasis needs a name in Puller and a word in Word before it can ask.");
            return;
        }
        if (whisper(name, word.getValue().trim(), ChatPace.ESCAPE)) {
            tell("asked §b" + name + "§7 for a pull. " + why);
        } else {
            askWait = RETRY_TICKS;
        }
    }

    // False when nothing went out. A call for help goes at the pace of an escape and may go where a
    // rule of Triggers may not. A name that cannot stand as one word of a command gets nothing.
    private boolean whisper(String to, String text, ChatPace pace) {
        if (whisperWith.isBlank()) {
            ChatUtil.error("Stasis has no command to whisper with. Type one such as /msg into Whisper with.");
            return false;
        }
        String line = SentLine.whisper(whisperWith.getValue(), to, text);
        if (line.isEmpty()) {
            return false;
        }
        if (pace.say(line)) {
            return true;
        }
        ChatPace.tellHeld(getName());
        return false;
    }

    // Fired on the netty thread. The line is read on the game thread where the tab list lives.
    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        Packet<?> packet = event.getPacket();
        if (role.is(Role.AT_BASE) && ChatSender.isChat(packet)) {
            mc.schedule(() -> onLine(packet));
        }
    }

    // A call is the word and nothing else from a player who may call. A whisper is only taken
    // where its sender cannot be forged. Open chat counts where the server sends real player chat.
    private void onLine(Packet<?> packet) {
        if (!isEnabled() || !inGame() || !role.is(Role.AT_BASE)) {
            return;
        }
        ShownChat.Whisper whisper = ShownChat.whisperOf(packet);
        String caller;
        String message;
        if (whisper != null) {
            caller = whisper.from();
            message = whisper.text();
        } else if (!whispersOnly.isOn() && packet instanceof ClientboundPlayerChatPacket) {
            ChatSender.Line line = ChatSender.lineOf(packet);
            if (line == null) {
                return;
            }
            caller = line.name();
            message = line.text().trim();
        } else {
            return;
        }
        if (caller.equalsIgnoreCase(mc.player.getGameProfile().name()) || !mayCall(caller)) {
            return;
        }
        for (Chamber chamber : slots) {
            if (chamber.answersTo(message)) {
                take(new Call(caller, chamber));
                return;
            }
        }
    }

    // With no caller named every friend may call.
    private boolean mayCall(String name) {
        return callers.isEmpty() ? OfflineClient.INSTANCE.getFriendManager().isFriend(name) : callers.holds(name);
    }

    // A chamber already called for is not called for twice.
    private void take(Call call) {
        boolean known = serving != null && serving.chamber() == call.chamber()
            || waiting.stream().anyMatch(other -> other.chamber() == call.chamber());
        if (known || waiting.size() >= MAX_WAITING) {
            return;
        }
        waiting.addLast(call);
        tell("§b" + call.caller() + " §7called for §f" + call.chamber().calledBy() + "§7.");
    }

    private void tickBase() {
        switch (step) {
            case IDLE -> idle();
            case WALKING -> walk();
            case FLIPPING -> flip();
            case HOLDING -> hold();
            case RESTORING -> restore();
            case RETURNING -> goHome();
        }
    }

    // The server only learns of a swing from the punch packet.
    private void idle() {
        if (!waiting.isEmpty()) {
            begin(waiting.removeFirst());
        } else if (stayAwake.isOn() && --awakeIn <= 0) {
            awakeIn = STAY_AWAKE_TICKS;
            SwingMode.BOTH.hit();
        }
    }

    private void begin(Call call) {
        serving = call;
        headingHome = false;
        tries = 0;
        wait = 0;
        BlockPos spot = call.chamber().place();
        if (spot == null) {
            fail("the trapdoor of that chamber is not set");
            return;
        }
        if (home == null) {
            home = mc.player.blockPosition();
        }
        if (BlockUtil.inReach(spot)) {
            step = Step.FLIPPING;
        } else if (trip.start(spot, WALK_NEAR)) {
            step = Step.WALKING;
        } else {
            fail("no search for a way to the chamber could start");
        }
    }

    private void walk() {
        BlockPos spot = serving.chamber().place();
        Trip.State state = trip.tick();
        if (state == Trip.State.WALKING) {
            return;
        }
        if (state == Trip.State.ARRIVED && spot != null && BlockUtil.inReach(spot)) {
            step = Step.FLIPPING;
        } else {
            fail(state == Trip.State.ARRIVED ? "the walk ended out of reach of the trapdoor"
                : "there is no way to the chamber");
        }
    }

    // The trapdoor of the call being served whilst one stands at the saved place and can be
    // worked by hand. Null after saying why not.
    private BlockState trapdoorState() {
        BlockPos spot = serving.chamber().place();
        BlockState state = spot == null ? null : BlockUtil.state(spot);
        if (state == null || !(state.getBlock() instanceof TrapDoorBlock trapdoor)) {
            fail("there is no trapdoor at the saved place");
            return null;
        }
        if (!trapdoor.getType().canOpenByHand()) {
            fail("that trapdoor cannot be flipped by hand");
            return null;
        }
        return state;
    }

    // Clicks the trapdoor until it shows the given way. True once it does. A click is given
    // time to reach the server and come back before the next one.
    private boolean turnTo(BlockState state, boolean open) {
        if (state.getValue(TrapDoorBlock.OPEN) == open) {
            return true;
        }
        if (wait > 0) {
            wait--;
        } else if (tries++ < FLIP_TRIES) {
            BlockUtil.interact(serving.chamber().place(), face.getValue());
            wait = ServerInfo.answerTicks();
        }
        return false;
    }

    private void flip() {
        BlockState state = trapdoorState();
        if (state == null) {
            return;
        }
        if (tries == 0) {
            wasOpen = state.getValue(TrapDoorBlock.OPEN);
        }
        if (turnTo(state, !wasOpen)) {
            int flipTicks = (int) Math.round(serving.chamber().flipTime.getValue() * SharedConstants.TICKS_PER_SECOND);
            wait = Math.max(ServerInfo.answerTicks(), flipTicks);
            step = Step.HOLDING;
        } else if (tries > FLIP_TRIES) {
            fail("the trapdoor did not move");
        }
    }

    // The server takes back a flip it refused. A trapdoor still flipped after the wait went through.
    private void hold() {
        if (wait-- > 0) {
            return;
        }
        BlockState state = trapdoorState();
        if (state == null) {
            return;
        }
        if (state.getValue(TrapDoorBlock.OPEN) == wasOpen) {
            fail("the server would not let the trapdoor be flipped");
            return;
        }
        answer("Pulled.");
        tell("flipped the trapdoor for §b" + serving.caller() + "§7.");
        tries = 0;
        wait = 0;
        step = Step.RESTORING;
    }

    // The pull has happened by this point. A trapdoor that will not go back is only told to
    // this account.
    private void restore() {
        BlockPos spot = serving.chamber().place();
        BlockState state = spot == null ? null : BlockUtil.state(spot);
        boolean gone = state == null || !(state.getBlock() instanceof TrapDoorBlock);
        if (!gone && !turnTo(state, wasOpen) && tries <= FLIP_TRIES) {
            return;
        }
        if (gone || state.getValue(TrapDoorBlock.OPEN) != wasOpen) {
            ChatUtil.error("Stasis could not put the trapdoor for " + serving.chamber().calledBy() + " back.");
        }
        serving = null;
        step = Step.RETURNING;
    }

    // Tells the caller and the chat why the pull did not happen and goes back.
    private void fail(String why) {
        ChatUtil.error("Stasis could not pull " + serving.caller() + " because " + why + ".");
        answer("No pull because " + why + ".");
        serving = null;
        trip.stop();
        step = Step.RETURNING;
    }

    private void answer(String text) {
        if (reply.isOn()) {
            whisper(serving.caller(), text, ChatPace.RULE);
        }
    }

    // The next call is served from wherever the last one ended. The walk back comes after the last.
    private void goHome() {
        if (!waiting.isEmpty()) {
            begin(waiting.removeFirst());
        } else if (headingHome) {
            if (trip.tick() != Trip.State.WALKING) {
                rest();
            }
        } else if (home != null && goBack.isOn() && trip.start(home, 0)) {
            headingHome = true;
        } else {
            rest();
        }
    }

    private void rest() {
        trip.stop();
        home = null;
        headingHome = false;
        awakeIn = STAY_AWAKE_TICKS;
        step = Step.IDLE;
    }
}
