package com.jellypudding.offlineclient.util;

import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.protocol.handshake.HandshakeProtocols;
import net.minecraft.network.protocol.login.LoginProtocols;
import net.minecraft.network.protocol.status.StatusProtocols;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Every packet kind the game knows in either direction. Built once from the
// protocol templates. A list can offer them before one has ever been seen.
public final class PacketNames {

    private static final Map<String, PacketType<?>> OUTGOING = new LinkedHashMap<>();
    private static final Map<String, PacketType<?>> INCOMING = new LinkedHashMap<>();

    // The kinds that travel once a world is joined.
    private static final Map<String, PacketType<?>> PLAY_OUTGOING = new LinkedHashMap<>();
    private static final Map<String, PacketType<?>> PLAY_INCOMING = new LinkedHashMap<>();

    static {
        collect(INCOMING, StatusProtocols.CLIENTBOUND_TEMPLATE, LoginProtocols.CLIENTBOUND_TEMPLATE,
            ConfigurationProtocols.CLIENTBOUND_TEMPLATE, GameProtocols.CLIENTBOUND_TEMPLATE);
        collect(OUTGOING, HandshakeProtocols.SERVERBOUND_TEMPLATE,
            StatusProtocols.SERVERBOUND_TEMPLATE, LoginProtocols.SERVERBOUND_TEMPLATE,
            ConfigurationProtocols.SERVERBOUND_TEMPLATE, GameProtocols.SERVERBOUND_TEMPLATE);
        collect(PLAY_INCOMING, GameProtocols.CLIENTBOUND_TEMPLATE);
        collect(PLAY_OUTGOING, GameProtocols.SERVERBOUND_TEMPLATE);
    }

    private PacketNames() {
    }

    private static void collect(Map<String, PacketType<?>> into,
                                ProtocolInfo.DetailsProvider... providers) {
        for (ProtocolInfo.DetailsProvider provider : providers) {
            provider.details().listPackets((type, id) -> into.put(type.id().toString(), type));
        }
    }

    public static Collection<String> outgoing() {
        return sorted(OUTGOING.keySet());
    }

    public static Collection<String> incoming() {
        return sorted(INCOMING.keySet());
    }

    public static Collection<String> playOutgoing() {
        return sorted(PLAY_OUTGOING.keySet());
    }

    public static Collection<String> playIncoming() {
        return sorted(PLAY_INCOMING.keySet());
    }

    // The kinds a list of picked names stands for. A name the game does not know is skipped.
    public static Set<PacketType<?>> outgoingTypes(Collection<String> names) {
        return resolve(names, OUTGOING);
    }

    public static Set<PacketType<?>> incomingTypes(Collection<String> names) {
        return resolve(names, INCOMING);
    }

    private static Set<PacketType<?>> resolve(Collection<String> names, Map<String, PacketType<?>> known) {
        Set<PacketType<?>> types = new HashSet<>();
        for (String name : names) {
            PacketType<?> type = known.get(name);
            if (type != null) {
                types.add(type);
            }
        }
        return Set.copyOf(types);
    }

    private static List<String> sorted(Collection<String> names) {
        List<String> all = new ArrayList<>(names);
        all.sort(null);
        return all;
    }
}
