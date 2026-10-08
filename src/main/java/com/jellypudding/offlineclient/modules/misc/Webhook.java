package com.jellypudding.offlineclient.modules.misc;

import com.google.gson.JsonObject;
import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.event.events.EntityDamageEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.ActionSetting;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.TextSetting;
import com.jellypudding.offlineclient.util.ChatSender;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.HurtReport;
import com.jellypudding.offlineclient.util.PlayerAlarm;
import com.jellypudding.offlineclient.util.ServerInfo;
import com.jellypudding.offlineclient.util.Web;
import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;

// Lines wait on the game thread and leave together at most once every two seconds. The
// services that take webhooks turn away posts that come faster.
public final class Webhook extends Module {

    // What a line is about. Each kind has a switch of its own.
    public enum Alert { FIND, ALARM, MENTION, DAMAGE, HUNGER, STOPPED, DISCONNECT }

    private static final int GAP_TICKS = 2 * SharedConstants.TICKS_PER_SECOND;

    // The longest post the services take.
    private static final int MOST_CHARS = 2000;

    // How long you stand on one spot before you count as stopped.
    private static final int STILL_TICKS = 30 * SharedConstants.TICKS_PER_SECOND;

    // Shuffling about less than this from where you stopped is still standing.
    private static final double STILL_REACH = 0.5;

    private final TextSetting address = new TextSetting("Address",
        "The webhook address the alerts go to. Click to type it.", "");
    private final BoolSetting finds = new BoolSetting("Finds",
        "Sends each find the hunting modules announce.", true);
    private final BoolSetting alarms = new BoolSetting("Alarms",
        "Sends a line whenever one of the Notifier alarms rings.", true);
    private final BoolSetting mentions = new BoolSetting("Mentions",
        "Sends the chat lines ChatAlerts picks out.", true);
    private final BoolSetting damage = new BoolSetting("Damage",
        "Sends a line whenever you take damage.", false);
    private final BoolSetting hunger = new BoolSetting("Hunger",
        "Sends a line when your food runs out.", false);
    private final BoolSetting stopped = new BoolSetting("Stopped",
        "Sends a line when you have not moved for half a minute.", false);
    private final BoolSetting disconnects = new BoolSetting("Disconnects",
        "Sends a line when the server drops you.", true);
    private final ActionSetting sendTest = new ActionSetting("Send test",
        "Sends a test line to the address.", this::test);

    private final Map<Alert, BoolSetting> switches = Map.of(Alert.FIND, finds, Alert.ALARM, alarms,
        Alert.MENTION, mentions, Alert.DAMAGE, damage, Alert.HUNGER, hunger, Alert.STOPPED, stopped,
        Alert.DISCONNECT, disconnects);

    private final Deque<String> waiting = new ArrayDeque<>();
    private final PlayerAlarm.Watcher rings = new PlayerAlarm.Watcher();
    private final HurtReport hurts = new HurtReport(true);
    // Set whilst a post is on its way. The answer comes back on another thread.
    private volatile boolean posting;
    // Set whilst you play in a world. A disconnected screen without it is a join that failed.
    private boolean playing;
    private int gap;
    private Vec3 stillAt;
    private int stillTicks;
    private boolean hungry;

    public Webhook() {
        super("Webhook", "Sends the alerts you pick to a webhook address.", Category.MISC);
        addSettings(address, finds, alarms, mentions, damage, hunger, stopped, disconnects, sendTest);
        searchTags("discord", "phone", "alerts", "afk", "notify");
    }

    @Override
    protected void onEnable() {
        rings.sync();
        waiting.clear();
        hurts.clear();
        stillAt = null;
        stillTicks = 0;
        hungry = false;
        playing = false;
    }

    // Queues a line about one kind of alert whilst its switch is on. Colour codes are taken out.
    public void send(Alert alert, String text) {
        if (switches.get(alert).isOn() && !address.isBlank()) {
            waiting.addLast(ChatFormatting.stripFormatting(text));
        }
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

    @Subscribe
    private void onDamage(EntityDamageEvent event) {
        if (damage.isOn()) {
            hurts.hit(event);
        }
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
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

    // Told once for each stop. Moving off the spot starts the count again.
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
        if (waiting.isEmpty() || posting) {
            return;
        }
        gap = GAP_TICKS;
        post(joined(), false);
    }

    // As many waiting lines as fit one post. The rest wait for the next.
    private String joined() {
        StringBuilder text = new StringBuilder();
        while (!waiting.isEmpty()) {
            String line = waiting.peekFirst();
            if (!text.isEmpty() && text.length() + 1 + line.length() > MOST_CHARS) {
                break;
            }
            waiting.removeFirst();
            text.append(text.isEmpty() ? "" : "\n").append(line);
        }
        return text.length() > MOST_CHARS ? text.substring(0, MOST_CHARS) : text.toString();
    }

    private String test() {
        if (address.isBlank()) {
            return "Type an address first";
        }
        post("A test line from " + OfflineClient.NAME + ".", true);
        return "Sending a test line";
    }

    // The answer to a test is said in chat. Any other post that fails goes in the game log.
    private void post(String text, boolean test) {
        JsonObject body = new JsonObject();
        body.addProperty("content", text);
        String to = address.getValue();
        posting = true;
        Web.later("webhook", () -> {
            Web.Page answer = Web.postJson(to, body.toString());
            posting = false;
            if (test) {
                mc.execute(() -> ChatUtil.message(answer.ok() ? "§bWebhook §7took the test line."
                    : "§bWebhook §cgot no good answer from that address."));
            } else if (!answer.ok()) {
                OfflineClient.LOG.warn("The webhook answered {}", answer.status());
            }
        });
    }
}
