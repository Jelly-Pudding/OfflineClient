package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.ChoiceListSetting;
import com.jellypudding.offlineclient.util.PacketNames;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketType;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

// Both lists offer every packet kind the game knows so login and configuration
// packets can be dropped before a world exists.
public final class PacketCanceller extends Module {

    private final ChoiceListSetting outgoing = new ChoiceListSetting("Outgoing",
        "Packets to drop on the way to the server. Click to pick them.",
        PacketNames::outgoing)
        .onChange(this::resolve);
    private final ChoiceListSetting incoming = new ChoiceListSetting("Incoming",
        "Packets to drop before the game handles them. Click to pick them.",
        PacketNames::incoming)
        .onChange(this::resolve);

    // The picked names resolved to types. Read from the netty thread and replaced whole.
    private volatile Set<PacketType<?>> dropOutgoing = Set.of();
    private volatile Set<PacketType<?>> dropIncoming = Set.of();

    private final AtomicInteger dropped = new AtomicInteger();

    public PacketCanceller() {
        super("PacketCanceller", "Drops the kinds of packet you pick. Hides an action from the server or ignores what it sends.",
            Category.MISC);
        addSettings(outgoing, incoming);
        searchTags("packet filter", "block packets");
    }

    @Override
    public String getSuffix() {
        int count = dropped.get();
        return count == 0 ? null : count + " dropped";
    }

    @Override
    protected void onEnable() {
        dropped.set(0);
        resolve();
    }

    @Override
    protected void onDisable() {
        dropped.set(0);
    }

    private void resolve() {
        dropOutgoing = resolve(outgoing.getValue(), PacketNames::outgoing);
        dropIncoming = resolve(incoming.getValue(), PacketNames::incoming);
    }

    private static Set<PacketType<?>> resolve(Set<String> names,
                                              Function<String, PacketType<?>> lookup) {
        Set<PacketType<?>> types = new HashSet<>();
        for (String name : names) {
            PacketType<?> type = lookup.apply(name);
            if (type != null) {
                types.add(type);
            }
        }
        return Set.copyOf(types);
    }

    @Subscribe(priority = 200)
    private void onPacketSend(PacketSendEvent event) {
        Packet<?> packet = event.getPacket();
        if (dropOutgoing.contains(packet.type())) {
            event.cancel();
            dropped.incrementAndGet();
        }
    }

    @Subscribe(priority = 200)
    private void onPacketReceive(PacketReceiveEvent event) {
        Packet<?> packet = event.getPacket();
        if (dropIncoming.contains(packet.type())) {
            event.cancel();
            dropped.incrementAndGet();
        }
    }
}
