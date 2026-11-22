package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.Setting;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.telemetry.TelemetryEventSender;
import net.minecraft.client.telemetry.TelemetryEventType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;

import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicInteger;

// ClientTelemetryManagerMixin hands every telemetry sender the game opens to gate. Each report
// asks at the moment it would go out and switching the module takes effect at once.
// ClientPacketListenerMixin asks holdsChatSession before the game starts a chat session. A line
// is only signed once there is one.
public final class Privacy extends Module {

    // What a server answers a line it turns down for want of a signature. The game's words for
    // it advise a reconnect that changes nothing.
    private static final String TURNED_DOWN = "chat.disabled.missingProfileKey";

    private final BoolSetting logBlocked = new BoolSetting("Log blocked",
        "Writes each blocked report to the game log.", false);
    private final BoolSetting unsignedChat = new BoolSetting("Unsigned chat",
        "Sends your chat unsigned where the server allows it to keep it out of chat reports."
            + " Going unsigned starts from your next join.", true);
    private final BoolSetting signIfForced = new BoolSetting("Sign if forced",
        "Signs your chat on a server that refuses unsigned chat. Off keeps it unsigned there and the server"
            + " turns down your chat lines and the game's own message commands such as msg and me whilst"
            + " plugin commands still run.", true)
        .visibleWhen(unsignedChat::isOn);

    private final AtomicInteger blocked = new AtomicInteger();

    // The visit the three fields under it speak of. A server you left is never kept alive.
    private WeakReference<ClientPacketListener> visit = new WeakReference<>(null);
    private boolean forced;
    private boolean unsignedWanted;
    // Read on the network thread.
    private volatile boolean held;

    public Privacy() {
        super("Privacy", "Stops usage data reaching Mojang and keeps your chat out of reports where a server"
            + " takes unsigned chat.", Category.MISC);
        addSettings(logBlocked, unsignedChat, signIfForced);
        searchTags("telemetry", "block telemetry", "tracking", "analytics", "usage data", "mojang",
            "chat reports", "unsigned chat", "signed chat", "chat signing", "secure chat");
    }

    // The game sends its first report before the menu ever opens.
    @Override
    public boolean enabledByDefault() {
        return true;
    }

    @Override
    public String getSuffix() {
        return count(blocked.get());
    }

    @Override
    protected void onEnable() {
        settle();
    }

    @Override
    protected void onDisable() {
        settle();
    }

    // A report is stopped whilst the module is on and also before the module list exists. The
    // game sends one at the end of its own start up.
    public static TelemetryEventSender gate(TelemetryEventSender sender) {
        if (sender == TelemetryEventSender.DISABLED) {
            return sender;
        }
        return (type, properties) -> {
            Privacy privacy = Modules.get(Privacy.class);
            if (privacy == null) {
                return;
            }
            if (privacy.isEnabled()) {
                privacy.stopped(type);
            } else {
                sender.send(type, properties);
            }
        };
    }

    // Asked each time the game is about to start a chat session. It is once after a join and
    // again whenever settle asks the game for one. True holds the session back and every line
    // then goes out unsigned.
    public boolean holdsChatSession(ClientPacketListener connection, boolean enforced) {
        boolean joined = connection != visit.get();
        if (joined) {
            visit = new WeakReference<>(connection);
            forced = enforced;
        }
        unsignedWanted = wantsUnsigned();
        held = unsignedWanted;
        if (!joined && !held) {
            tell("leaves your chat signed from here on.");
        } else if (joined && forced && asksForUnsigned()) {
            ChatUtil.component(held
                ? line("keeps your chat unsigned and this server refuses unsigned chat. It will turn down"
                    + " your chat lines. ").append(signLink())
                : line("leaves your chat signed here because this server refuses unsigned chat. What you say"
                    + " here can be reported. ").append(forcedLink("Stay unsigned", false,
                    "Click to switch Sign if forced off in Privacy. From your next join your chat stays"
                        + " unsigned and this server turns your chat lines down.")));
        }
        return held;
    }

    private boolean asksForUnsigned() {
        return isEnabled() && unsignedChat.isOn();
    }

    private boolean wantsUnsigned() {
        return asksForUnsigned() && !(forced && signIfForced.isOn());
    }

    @Subscribe
    private void onTick(TickEvent event) {
        settle();
    }

    private ClientPacketListener visited() {
        ClientPacketListener connection = mc.getConnection();
        return connection != null && connection == visit.get() ? connection : null;
    }

    // Signing can start at any moment. Going unsigned waits for the next join because a server that
    // has your key refuses every unsigned line.
    private void settle() {
        ClientPacketListener connection = visited();
        if (connection == null) {
            held = false;
            return;
        }
        boolean unsigned = wantsUnsigned();
        if (unsigned == unsignedWanted) {
            return;
        }
        unsignedWanted = unsigned;
        if (held && !unsigned) {
            connection.prepareKeyPair();
        } else if (!held && unsigned) {
            tell("sends your chat unsigned from your next join. Until you leave it stays signed because a"
                + " server that has your key refuses unsigned lines.");
        }
    }

    // Fired on the network thread. Only a line Privacy kept unsigned is answered for here.
    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        if (held && event.getPacket() instanceof ClientboundSystemChatPacket packet && !packet.overlay()
            && packet.content().getContents() instanceof TranslatableContents words
            && TURNED_DOWN.equals(words.getKey())) {
            event.cancel();
            mc.schedule(this::onTurnedDown);
        }
    }

    // A server that turns an unsigned line down refuses unsigned chat whatever it said at the join.
    private void onTurnedDown() {
        if (visited() == null) {
            return;
        }
        forced = true;
        settle();
        String words = "kept that line unsigned and the server turned it down. ";
        if (unsignedWanted) {
            ChatUtil.component(line(words).append(signLink()));
        } else {
            tell(words + "Send it again.");
        }
    }

    private MutableComponent line(String words) {
        return Component.literal(ChatUtil.from(getName(), words));
    }

    private Component signLink() {
        return forcedLink("Sign my chat", true, "Click to switch Sign if forced on in Privacy. Your chat is"
            + " signed on servers that refuse unsigned chat and can be reported there.");
    }

    private Component forcedLink(String shown, boolean on, String hover) {
        return ChatUtil.commandLink("§b[" + shown + "]",
            "set " + Setting.idFor(getName()) + " " + signIfForced.id() + " " + on, hover);
    }

    private void stopped(TelemetryEventType type) {
        // The game drops an optional report itself unless the player opted in to them.
        if (type.isOptIn() && !mc.telemetryOptInExtra()) {
            return;
        }
        blocked.incrementAndGet();
        if (logBlocked.isOn()) {
            OfflineClient.LOG.info("Privacy blocked a {} report", type.id());
        }
    }
}
