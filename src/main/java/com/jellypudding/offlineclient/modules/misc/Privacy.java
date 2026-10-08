package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.telemetry.TelemetryEventSender;
import net.minecraft.client.telemetry.TelemetryEventType;

import java.util.concurrent.atomic.AtomicInteger;

// ClientTelemetryManagerMixin hands every telemetry sender the game opens to gate. Each report
// asks at the moment it would go out and switching the module takes effect at once.
public final class Privacy extends Module {

    private final BoolSetting logBlocked = new BoolSetting("Log blocked",
        "Writes each blocked report to the game log.", false);
    private final BoolSetting unsignedChat = new BoolSetting("Unsigned chat",
        "Sends your chat unsigned where the server allows it to keep it out of chat reports."
            + " It starts from your next join.", true);

    private final AtomicInteger blocked = new AtomicInteger();

    public Privacy() {
        super("Privacy", "Stops usage data reaching Mojang and keeps your chat out of reports.", Category.MISC);
        addSettings(logBlocked, unsignedChat);
        searchTags("telemetry", "block telemetry", "tracking", "analytics", "usage data", "mojang",
            "chat reports", "unsigned chat", "signed chat");
    }

    // The game sends its first report before the menu ever opens.
    @Override
    public boolean enabledByDefault() {
        return true;
    }

    // Reports stopped since the game started.
    @Override
    public String getSuffix() {
        return count(blocked.get());
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

    // Read by ClientPacketListenerMixin as the chat keys arrive after a join. True keeps the
    // chat session from starting. A server that enforces signed chat would refuse every line.
    public boolean keepsChatUnsigned(boolean enforced) {
        if (!isEnabled() || !unsignedChat.isOn()) {
            return false;
        }
        if (enforced) {
            ChatUtil.message("§7This server only takes signed chat. Yours stays signed here.");
            return false;
        }
        return true;
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
