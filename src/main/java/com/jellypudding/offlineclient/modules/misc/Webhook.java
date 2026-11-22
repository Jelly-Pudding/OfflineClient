package com.jellypudding.offlineclient.modules.misc;

import com.google.gson.JsonObject;
import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.event.events.EntityDamageEvent;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.ActionSetting;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.TextSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatSender;
import com.jellypudding.offlineclient.util.HurtReport;
import com.jellypudding.offlineclient.util.PlayerAlarm;
import com.jellypudding.offlineclient.util.ServerInfo;
import com.jellypudding.offlineclient.util.TextLines;
import com.jellypudding.offlineclient.util.Web;
import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;

// Lines wait on the game thread and leave together at most once every two seconds. The
// services that take webhooks turn away posts that come faster. A post turned away for coming
// too soon goes again once the wait the service asked for is over.
public final class Webhook extends Module {

    public enum Alert { FIND, ALARM, MENTION, CHAT, DAMAGE, DEATH, HUNGER, STOPPED, DISCONNECT }

    private static final int GAP_TICKS = 2 * SharedConstants.TICKS_PER_SECOND;

    // A service asking for a longer wait is asked again after a minute.
    private static final int LONGEST_WAIT_TICKS = SharedConstants.TICKS_PER_MINUTE;

    // The longest post the services take.
    private static final int MOST_CHARS = 2000;

    private static final int MOST_WAITING = 500;

    private static final int STILL_TICKS = 30 * SharedConstants.TICKS_PER_SECOND;

    private static final double STILL_REACH = 0.5;

    private final TextSetting address = new TextSetting("Address",
        "The webhook address the alerts go to. Click to type it.", "").secret();
    private final BoolSetting finds = new BoolSetting("Finds",
        "Sends each find the hunting modules announce.", true);
    private final BoolSetting alarms = new BoolSetting("Alarms",
        "Sends a line whenever one of the Notifier alarms rings.", true);
    private final BoolSetting mentions = new BoolSetting("Mentions",
        "Sends the chat lines ChatAlerts picks out.", true);
    private final BoolSetting allChat = new BoolSetting("All chat",
        "Sends every chat line the server shows you.", false);
    private final TextLines skip = new TextLines("Skip",
        "How many words keep a chat line back.",
        "A chat line holding this text is not sent. Click to type it.").plain().under(allChat);
    private final BoolSetting damage = new BoolSetting("Damage",
        "Sends a line whenever you take damage.", false);
    private final BoolSetting deaths = new BoolSetting("Deaths",
        "Sends where you died and what killed you.", false);
    private final BoolSetting hunger = new BoolSetting("Hunger",
        "Sends a line when your food runs out.", false);
    private final BoolSetting stopped = new BoolSetting("Stopped",
        "Sends a line when you have not moved for half a minute.", false);
    private final BoolSetting disconnects = new BoolSetting("Disconnects",
        "Sends a line when the server drops you.", true);
    private final ActionSetting sendTest = new ActionSetting("Send test",
        "Sends a test line to the address.", this::test);

    private final Map<Alert, BoolSetting> switches = Map.of(Alert.FIND, finds, Alert.ALARM, alarms,
        Alert.MENTION, mentions, Alert.CHAT, allChat, Alert.DAMAGE, damage, Alert.DEATH, deaths,
        Alert.HUNGER, hunger, Alert.STOPPED, stopped, Alert.DISCONNECT, disconnects);

    // Alerts leave ahead of chat lines. A flood of chat never holds an alarm back.
    private final Deque<String> alertLines = new ArrayDeque<>();
    private final Deque<String> chatLines = new ArrayDeque<>();
    private final PlayerAlarm.Watcher rings = new PlayerAlarm.Watcher();
    private final HurtReport hurts = new HurtReport(true);
    // Posts on their way. Waiting lines leave only once every answer is back.
    private int posting;
    // A post the service turned away for coming too soon. It goes again before anything else.
    private String refused;
    // Set whilst you play in a world. A disconnected screen without it is a join that failed.
    private boolean playing;
    private int gap;
    private Vec3 stillAt;
    private int stillTicks;
    private boolean hungry;

    public Webhook() {
        super("Webhook", "Sends the alerts you pick to a webhook address.", Category.MISC);
        addSettings(address, finds, alarms, mentions, allChat);
        addSettings(skip.settings());
        addSettings(damage, deaths, hunger, stopped, disconnects, sendTest);
        searchTags("discord", "phone", "alerts", "afk", "notify", "chat log", "death");
    }

    @Override
    protected void onEnable() {
        rings.sync();
        alertLines.clear();
        chatLines.clear();
        refused = null;
        hurts.clear();
        stillAt = null;
        stillTicks = 0;
        hungry = false;
        playing = false;
    }

    public void send(Alert alert, String text) {
        if (!switches.get(alert).isOn() || address.isBlank()) {
            return;
        }
        Deque<String> lines = alert == Alert.CHAT ? chatLines : alertLines;
        if (lines.size() >= MOST_WAITING) {
            lines.removeFirst();
        }
        String plain = ChatFormatting.stripFormatting(text);
        lines.addLast(plain.length() > MOST_CHARS ? plain.substring(0, MOST_CHARS) : plain);
    }

    // Called as the disconnected screen opens. The game leaves the world and opens the screen in one
    // call and the tick that clears the flag comes after.
    public void dropped(Component reason) {
        if (!playing) {
            return;
        }
        playing = false;
        String server = ServerInfo.address();
        send(Alert.DISCONNECT, "The server" + (server == null ? "" : " " + server) + " dropped you. "
            + ChatSender.plain(reason).replace('\n', ' ').trim());
    }

    // Fired on the netty thread. The lines wait on the game thread with the rest.
    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        Packet<?> packet = event.getPacket();
        if (packet instanceof ClientboundPlayerCombatKillPacket death && deaths.isOn()) {
            mc.schedule(() -> died(death));
        }
        Component line = allChat.isOn() ? shownLine(packet) : null;
        if (line != null) {
            mc.schedule(() -> chat(line));
        }
    }

    private static Component shownLine(Packet<?> packet) {
        return switch (packet) {
            case ClientboundSystemChatPacket system when !system.overlay() -> system.content();
            case ClientboundPlayerChatPacket chat -> chat.chatType().decorate(
                chat.unsignedContent().orElseGet(() -> Component.literal(chat.body().content())));
            case ClientboundDisguisedChatPacket disguised -> disguised.chatType().decorate(disguised.message());
            default -> null;
        };
    }

    private void chat(Component line) {
        String text = line.getString(MOST_CHARS);
        String lower = text.toLowerCase(Locale.ROOT);
        for (String word : skip.all()) {
            if (lower.contains(word.trim().toLowerCase(Locale.ROOT))) {
                return;
            }
        }
        send(Alert.CHAT, text);
    }

    private void died(ClientboundPlayerCombatKillPacket death) {
        if (!deaths.isOn() || mc.player == null || death.playerId() != mc.player.getId()) {
            return;
        }
        String cause = death.message().getString(MOST_CHARS).replace('\n', ' ').trim();
        send(Alert.DEATH, "You died at " + BlockUtil.text(mc.player.blockPosition()) + " in the "
            + ServerInfo.dimensionName(ServerInfo.dimension()) + "." + (cause.isEmpty() ? "" : " " + cause));
    }

    @Subscribe
    private void onDamage(EntityDamageEvent event) {
        if (damage.isOn()) {
            hurts.hit(event);
        }
    }

    @Subscribe
    private void onTick(TickEvent event) {
        // A world Linger holds has dropped already and nothing in it changes.
        if (Linger.holding()) {
            return;
        }
        playing = true;
        for (PlayerAlarm.Ring ring : rings.fresh()) {
            send(Alert.ALARM, "The " + ring.alarm().toLowerCase(Locale.ROOT) + " alarm rang for " + ring.player() + ".");
        }
        for (String line : hurts.tick()) {
            send(Alert.DAMAGE, line);
        }
        boolean empty = mc.player.getFoodData().getFoodLevel() == 0;
        if (empty && !hungry) {
            send(Alert.HUNGER, "Your food has run out.");
        }
        hungry = empty;
        watchStill();
    }

    private void watchStill() {
        Vec3 here = mc.player.position();
        if (stillAt == null || stillAt.distanceTo(here) > STILL_REACH) {
            stillAt = here;
            stillTicks = 0;
            return;
        }
        if (++stillTicks == STILL_TICKS) {
            send(Alert.STOPPED, "You have not moved for half a minute.");
        }
    }

    // Runs in menus too. A disconnect is posted from the disconnected screen.
    @Subscribe
    private void onClientTick(ClientTickEvent event) {
        if (mc.level == null) {
            playing = false;
        }
        if (gap > 0) {
            gap--;
            return;
        }
        if (posting > 0) {
            return;
        }
        String text = refused == null ? joined() : refused;
        refused = null;
        if (text.isEmpty()) {
            return;
        }
        gap = GAP_TICKS;
        post(text, false);
    }

    private String joined() {
        StringBuilder text = new StringBuilder();
        take(alertLines, text);
        if (alertLines.isEmpty()) {
            take(chatLines, text);
        }
        return text.toString();
    }

    // No line is longer than a post.
    private static void take(Deque<String> lines, StringBuilder text) {
        while (!lines.isEmpty()) {
            String line = lines.peekFirst();
            if (!text.isEmpty() && text.length() + 1 + line.length() > MOST_CHARS) {
                return;
            }
            lines.removeFirst();
            text.append(text.isEmpty() ? "" : "\n").append(line);
        }
    }

    private String test() {
        if (address.isBlank()) {
            return "Type an address first";
        }
        post("A test line from " + OfflineClient.NAME + ".", true);
        return "Sending a test line";
    }

    private void post(String text, boolean test) {
        JsonObject body = new JsonObject();
        body.addProperty("content", text);
        String to = address.getValue();
        posting++;
        Web.later("webhook", () -> {
            Web.Page answer = Web.postJson(to, body.toString());
            mc.execute(() -> answered(text, test, answer));
        });
    }

    private void answered(String text, boolean test, Web.Page answer) {
        posting--;
        if (test) {
            tell(answer.ok() ? "took the test line."
                : "§cgot no good answer from that address.");
        } else if (answer.tooSoon()) {
            refused = text;
            long asked = Math.ceilDiv(answer.retryAfter().toMillis(), SharedConstants.MILLIS_PER_TICK);
            gap = Math.max(gap, Math.clamp(asked, GAP_TICKS, LONGEST_WAIT_TICKS));
        } else if (!answer.ok()) {
            OfflineClient.LOG.warn("The webhook answered {}", answer.status());
        }
    }
}
