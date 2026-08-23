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

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

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

    private int dropped;

    public PacketCanceller() {
        super("PacketCanceller", "Drops the packets you name before they are handled.", Category.MISC);
        addSettings(outgoing, incoming, learn);
        searchTags("packet filter", "block packets");
    }

    @Override
    public String getSuffix() {
        return dropped == 0 ? null : dropped + " dropped";
    }

    @Override
    protected void onEnable() {
        dropped = 0;
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
        if (pending.isEmpty()) {
            return;
        }
        // Ids arrive on the netty thread and are printed from here.
        synchronized (pending) {
            for (String id : pending) {
                ChatUtil.message("§7Packet §b" + id);
            }
            pending.clear();
        }
    }

    @Subscribe(priority = 200)
    private void onPacketSend(PacketSendEvent event) {
        note(event.getPacket());
        if (matches(outgoingNames, event.getPacket())) {
            event.cancel();
            dropped++;
        }
    }

    @Subscribe(priority = 200)
    private void onPacketReceive(PacketReceiveEvent event) {
        note(event.getPacket());
        if (matches(incomingNames, event.getPacket())) {
            event.cancel();
            dropped++;
        }
    }

    private void note(Packet<?> packet) {
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
