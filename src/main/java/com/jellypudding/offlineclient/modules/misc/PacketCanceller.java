package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.TextSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

// Names are matched against the packet id such as swing or minecraft:swing.
public final class PacketCanceller extends Module {

    private final TextSetting outgoing = new TextSetting("Outgoing",
        "Packets to drop on the way out. Separate names with spaces.", "");
    private final TextSetting incoming = new TextSetting("Incoming",
        "Packets to drop on the way in. Separate names with spaces.", "");
    private final BoolSetting learn = new BoolSetting("Learn names",
        "Print each packet id to chat the first time it goes past.", false);

    // Read from the netty thread and replaced whole.
    private volatile Set<String> outgoingNames = Set.of();
    private volatile Set<String> incomingNames = Set.of();
    private String outgoingText = "";
    private String incomingText = "";

    // Only touched whilst Learn names is on.
    private final Set<String> seen = new HashSet<>();
    private final Set<String> pending = new LinkedHashSet<>();

    private final AtomicInteger dropped = new AtomicInteger();

    public PacketCanceller() {
        super("PacketCanceller", "Drops the packets you name before they are handled.", Category.MISC);
        addSettings(outgoing, incoming, learn);
        searchTags("packet filter", "block packets");
    }

    @Override
    public String getSuffix() {
        int count = dropped.get();
        return count == 0 ? null : count + " dropped";
    }

    @Override
    protected void onEnable() {
        reset();
    }

    @Override
    protected void onDisable() {
        reset();
    }

    private void reset() {
        dropped.set(0);
        synchronized (pending) {
            seen.clear();
            pending.clear();
        }
        outgoingText = null;
        incomingText = null;
    }

    @Subscribe
    private void onClientTick(ClientTickEvent event) {
        if (!outgoing.getValue().equals(outgoingText)) {
            outgoingText = outgoing.getValue();
            outgoingNames = parse(outgoingText);
        }
        if (!incoming.getValue().equals(incomingText)) {
            incomingText = incoming.getValue();
            incomingNames = parse(incomingText);
        }
        // Ids arrive on the netty thread and are printed from here.
        List<String> ready;
        synchronized (pending) {
            if (pending.isEmpty()) {
                return;
            }
            ready = new ArrayList<>(pending);
            pending.clear();
        }
        for (String id : ready) {
            ChatUtil.message("§7Packet §b" + id);
        }
    }

    @Subscribe(priority = 200)
    private void onPacketSend(PacketSendEvent event) {
        remember(event.getPacket());
        if (matches(outgoingNames, event.getPacket())) {
            event.cancel();
            dropped.incrementAndGet();
        }
    }

    @Subscribe(priority = 200)
    private void onPacketReceive(PacketReceiveEvent event) {
        remember(event.getPacket());
        if (matches(incomingNames, event.getPacket())) {
            event.cancel();
            dropped.incrementAndGet();
        }
    }

    private void remember(Packet<?> packet) {
        if (!learn.isOn()) {
            return;
        }
        String id = packet.type().id().toString();
        synchronized (pending) {
            if (seen.add(id)) {
                pending.add(id);
            }
        }
    }

    private static boolean matches(Set<String> names, Packet<?> packet) {
        if (names.isEmpty()) {
            return false;
        }
        Identifier id = packet.type().id();
        return names.contains(id.getPath())
            || names.contains(id.toString())
            || names.contains(packet.getClass().getSimpleName().toLowerCase(Locale.ROOT));
    }

    private static Set<String> parse(String text) {
        Set<String> names = new HashSet<>();
        for (String part : text.toLowerCase(Locale.ROOT).split("[\\s,]+")) {
            if (!part.isBlank()) {
                names.add(part);
            }
        }
        return Set.copyOf(names);
    }
}
