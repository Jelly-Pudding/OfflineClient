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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Every packet kind the game knows in either direction. Built once from the
// protocol templates. A list can offer them before one has ever been seen.
public final class PacketNames {

    private static final Map<String, PacketType<?>> OUTGOING = new LinkedHashMap<>();
    private static final Map<String, PacketType<?>> INCOMING = new LinkedHashMap<>();

    static {
        collect(INCOMING, StatusProtocols.CLIENTBOUND_TEMPLATE, LoginProtocols.CLIENTBOUND_TEMPLATE,
            ConfigurationProtocols.CLIENTBOUND_TEMPLATE, GameProtocols.CLIENTBOUND_TEMPLATE);
        collect(OUTGOING, HandshakeProtocols.SERVERBOUND_TEMPLATE,
            StatusProtocols.SERVERBOUND_TEMPLATE, LoginProtocols.SERVERBOUND_TEMPLATE,
            ConfigurationProtocols.SERVERBOUND_TEMPLATE, GameProtocols.SERVERBOUND_TEMPLATE);
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

    public static PacketType<?> outgoing(String name) {
        return OUTGOING.get(name);
    }

    public static PacketType<?> incoming(String name) {
        return INCOMING.get(name);
    }

    private static List<String> sorted(Collection<String> names) {
        List<String> all = new ArrayList<>(names);
        all.sort(null);
        return all;
    }
}
