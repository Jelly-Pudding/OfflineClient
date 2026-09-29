package com.jellypudding.offlineclient.modules.misc;

import com.google.common.collect.MapMaker;
import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.ChoiceListSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.MoveGate;
import com.jellypudding.offlineclient.util.PacketNames;
import com.jellypudding.offlineclient.util.WorldWatch;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.GamePacketTypes;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;

// Holds the packets you pick for a while before they go on. Kinds that must keep their order
// travel as a group. A held position waits for MoveGate like every other position.
public final class Latency extends Module {

    public enum SwitchOff { SEND, DROP }

    // Releases after MoveGate has opened the new tick for a position.
    private static final int AFTER_MOVE_GATE = -2000;

    // Positions reach the server one to a tick. A held one let go out of turn would share a tick
    // with a newer one. The answer to a teleport waits behind the older positions.
    private static final Set<PacketType<?>> MOVEMENT = Set.of(
        GamePacketTypes.SERVERBOUND_MOVE_PLAYER_POS, GamePacketTypes.SERVERBOUND_MOVE_PLAYER_POS_ROT,
        GamePacketTypes.SERVERBOUND_MOVE_PLAYER_ROT, GamePacketTypes.SERVERBOUND_MOVE_PLAYER_STATUS_ONLY,
        GamePacketTypes.SERVERBOUND_ACCEPT_TELEPORTATION);

    // A click that lands after its container closed is ignored and the screen shows items that
    // never moved. A hit or a use that overtakes a hotbar change or a sneak uses the wrong one.
    private static final Set<PacketType<?>> HANDS = Set.of(
        GamePacketTypes.SERVERBOUND_CONTAINER_CLICK, GamePacketTypes.SERVERBOUND_CONTAINER_CLOSE,
        GamePacketTypes.SERVERBOUND_CONTAINER_BUTTON_CLICK,
        GamePacketTypes.SERVERBOUND_CONTAINER_SLOT_STATE_CHANGED, GamePacketTypes.SERVERBOUND_PLACE_RECIPE,
        GamePacketTypes.SERVERBOUND_SET_CARRIED_ITEM, GamePacketTypes.SERVERBOUND_SET_CREATIVE_MODE_SLOT,
        GamePacketTypes.SERVERBOUND_USE_ITEM, GamePacketTypes.SERVERBOUND_USE_ITEM_ON,
        GamePacketTypes.SERVERBOUND_PLAYER_ACTION, GamePacketTypes.SERVERBOUND_INTERACT,
        GamePacketTypes.SERVERBOUND_ATTACK, GamePacketTypes.SERVERBOUND_PLAYER_INPUT,
        GamePacketTypes.SERVERBOUND_PLAYER_COMMAND,
        GamePacketTypes.SERVERBOUND_PICK_ITEM_FROM_BLOCK, GamePacketTypes.SERVERBOUND_PICK_ITEM_FROM_ENTITY,
        GamePacketTypes.SERVERBOUND_SELECT_TRADE, GamePacketTypes.SERVERBOUND_RENAME_ITEM,
        GamePacketTypes.SERVERBOUND_BUNDLE_ITEM_SELECTED);

    // The server checks every message against the ones it saw before and drops a player whose
    // chat arrives out of order.
    private static final Set<PacketType<?>> CHAT = Set.of(
        GamePacketTypes.SERVERBOUND_CHAT, GamePacketTypes.SERVERBOUND_CHAT_ACK,
        GamePacketTypes.SERVERBOUND_CHAT_COMMAND, GamePacketTypes.SERVERBOUND_CHAT_COMMAND_SIGNED,
        GamePacketTypes.SERVERBOUND_CHAT_SESSION_UPDATE);

    // An older copy of a slot handled after a newer one would overwrite it. Slots and trades sent
    // for a menu are thrown away unless the menu opened first.
    private static final Set<PacketType<?>> INVENTORY = Set.of(
        GamePacketTypes.CLIENTBOUND_OPEN_SCREEN, GamePacketTypes.CLIENTBOUND_CONTAINER_CLOSE,
        GamePacketTypes.CLIENTBOUND_CONTAINER_SET_CONTENT, GamePacketTypes.CLIENTBOUND_CONTAINER_SET_SLOT,
        GamePacketTypes.CLIENTBOUND_CONTAINER_SET_DATA, GamePacketTypes.CLIENTBOUND_SET_CURSOR_ITEM,
        GamePacketTypes.CLIENTBOUND_SET_PLAYER_INVENTORY, GamePacketTypes.CLIENTBOUND_SET_HELD_SLOT,
        GamePacketTypes.CLIENTBOUND_MERCHANT_OFFERS, GamePacketTypes.CLIENTBOUND_MOUNT_SCREEN_OPEN);

    private static final List<Set<PacketType<?>>> OUTGOING_GROUPS = List.of(MOVEMENT, HANDS, CHAT);
    private static final List<Set<PacketType<?>>> INCOMING_GROUPS = List.of(INVENTORY);

    // The tick end packet keeps positions one to a server tick. The others change the protocol
    // under a held packet or come before there is a world to handle them in.
    private static final Set<PacketType<?>> NEVER_OUTGOING = Set.of(
        GamePacketTypes.SERVERBOUND_CLIENT_TICK_END, GamePacketTypes.SERVERBOUND_CONFIGURATION_ACKNOWLEDGED);
    private static final Set<PacketType<?>> NEVER_INCOMING = Set.of(
        GamePacketTypes.CLIENTBOUND_START_CONFIGURATION, GamePacketTypes.CLIENTBOUND_LOGIN);

    private final ChoiceListSetting outgoing = new ChoiceListSetting("Outgoing",
        "Packets held on their way to the server. Click to pick them. A movement or item or chat"
            + " packet holds the rest of its group with it.",
        () -> offer(PacketNames.playOutgoing(), NEVER_OUTGOING))
        .onChange(this::resolve);
    private final NumberSetting outgoingDelay = new NumberSetting("Outgoing delay",
        "Ticks each packet to the server is held.", 10, 1, 100, 1, " ticks").min(1);
    private final ChoiceListSetting incoming = new ChoiceListSetting("Incoming",
        "Packets from the server held before the game handles them. Click to pick them. An"
            + " inventory packet holds the rest of its group with it.",
        () -> offer(PacketNames.playIncoming(), NEVER_INCOMING))
        .onChange(this::resolve);
    private final NumberSetting incomingDelay = new NumberSetting("Incoming delay",
        "Ticks each packet from the server is held.", 10, 1, 100, 1, " ticks").min(1);
    private final NumberSetting jitter = new NumberSetting("Jitter",
        "Holds each packet up to this many ticks longer at random. The order never changes.",
        0, 0, 20, 1, " ticks").min(0);
    private final EnumSetting<SwitchOff> switchOff = new EnumSetting<>("On switch off",
        "What happens to the packets still held when Latency is switched off.", SwitchOff.SEND)
        .describe(SwitchOff.SEND, "Sends every held packet in order straight away.")
        .describe(SwitchOff.DROP, "Throws every held packet away. Chat still goes out in order.");

    // The world counts up with every respawn or join the server sends. A packet held in an
    // older world is dropped once the client has moved on.
    private record Held(Packet<?> packet, int due, int world) {
    }

    // Filled from the network thread and emptied on the game thread.
    private final ConcurrentLinkedQueue<Held> outbound = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Held> inbound = new ConcurrentLinkedQueue<>();

    // The picked names grown by their groups. Read from the network thread and replaced whole.
    private volatile Set<PacketType<?>> holdOutgoing = Set.of();
    private volatile Set<PacketType<?>> holdIncoming = Set.of();

    private volatile int tick;
    private volatile int world;
    // Set once switched off with SEND. Everything still held is due and new packets of the held
    // kinds queue behind it.
    private volatile boolean flushing;
    // The packet being let go. Our own handlers wave it through.
    private volatile Packet<?> releasing;
    // The event the released packet left in. Another module may take it back there.
    private PacketSendEvent leaving;

    // Packets let go that another module took back as they left. That module sends each one
    // again later and it is waved through once. Weak keys compare by identity and a record
    // packet such as a second hit on the same target is never mistaken for one.
    private final Set<Packet<?>> retaken = Collections.newSetFromMap(new MapMaker().weakKeys().makeMap());

    private final WorldWatch worldWatch = new WorldWatch();

    // Registered for good. Held packets keep leaving after the module is switched off and new
    // ones may not overtake them.
    private final Object releaser = new Object() {
        @Subscribe(priority = Subscribe.FIRST)
        private void onPacketSend(PacketSendEvent event) {
            if (holding()) {
                holdOutgoing(event);
            }
        }

        @Subscribe(priority = Subscribe.FIRST)
        private void onPacketReceive(PacketReceiveEvent event) {
            if (holding()) {
                holdIncoming(event);
            }
        }

        @Subscribe(priority = AFTER_MOVE_GATE)
        private void onClientTick(ClientTickEvent event) {
            tick++;
            release();
        }
    };

    public Latency() {
        super("Latency", "Holds the packets you pick for a while before they go on.", Category.MISC);
        addSettings(outgoing, outgoingDelay, incoming, incomingDelay, jitter, switchOff);
        searchTags("packet delay", "fake lag", "ping", "lag", "delay");
        watch(releaser);
    }

    @Override
    public String getSuffix() {
        return count(outbound.size() + inbound.size(), "held");
    }

    @Override
    protected void onEnable() {
        resolve();
        flushing = false;
    }

    // What is still held leaves at once. Nothing sent after the switch may overtake it.
    @Override
    protected void onDisable() {
        if (switchOff.is(SwitchOff.DROP)) {
            dropAllButChat();
            return;
        }
        flushing = true;
        release();
    }

    // True whilst packets of this kind on their way out are held. Reached from any thread.
    public boolean delays(PacketType<?> type) {
        return holding() && holdOutgoing.contains(type);
    }

    private boolean holding() {
        return isEnabled() || flushing;
    }

    private static Collection<String> offer(Collection<String> names, Set<PacketType<?>> never) {
        Set<String> left = new HashSet<>();
        for (PacketType<?> type : never) {
            left.add(type.id().toString());
        }
        return names.stream().filter(name -> !left.contains(name)).toList();
    }

    private void resolve() {
        holdOutgoing = withGroups(PacketNames.outgoingTypes(outgoing.getValue()), OUTGOING_GROUPS, NEVER_OUTGOING);
        holdIncoming = withGroups(PacketNames.incomingTypes(incoming.getValue()), INCOMING_GROUPS, NEVER_INCOMING);
    }

    // Adds the rest of every group a picked kind belongs to.
    private static Set<PacketType<?>> withGroups(Set<PacketType<?>> picked, List<Set<PacketType<?>>> groups,
                                                 Set<PacketType<?>> never) {
        Set<PacketType<?>> held = new HashSet<>(picked);
        for (Set<PacketType<?>> group : groups) {
            if (!Collections.disjoint(group, picked)) {
                held.addAll(group);
            }
        }
        held.removeAll(never);
        return Set.copyOf(held);
    }

    private void holdOutgoing(PacketSendEvent event) {
        Packet<?> packet = event.getPacket();
        if (packet == releasing) {
            leaving = event;
            return;
        }
        if (retaken.remove(packet) || !holdOutgoing.contains(packet.type()) || !playing()) {
            return;
        }
        if (packet instanceof ServerboundAcceptTeleportationPacket) {
            // The server ignores every position from its teleport until this answer. Held ones
            // would only reach it in that gap and one arriving a second late makes the server
            // send a fresh teleport whose number this answer does not match.
            outbound.removeIf(held -> MOVEMENT.contains(held.packet().type()));
        }
        event.cancel();
        outbound.add(new Held(packet, dueIn(outgoingDelay), world));
    }

    private void holdIncoming(PacketReceiveEvent event) {
        Packet<?> packet = event.getPacket();
        if (packet == releasing) {
            return;
        }
        if (packet instanceof ClientboundRespawnPacket || packet instanceof ClientboundLoginPacket) {
            world++;
        }
        if (!holdIncoming.contains(packet.type()) || !playing()) {
            return;
        }
        event.cancel();
        inbound.add(new Held(packet, dueIn(incomingDelay), world));
    }

    private int dueIn(NumberSetting delay) {
        int extra = jitter.getInt() > 0 ? ThreadLocalRandom.current().nextInt(jitter.getInt() + 1) : 0;
        return tick + delay.getInt() + extra;
    }

    // Only the game protocol is held. A server switch runs configuration on the same kinds.
    private static boolean playing() {
        ClientPacketListener listener = OfflineClient.MC.getConnection();
        return listener != null && listener.getConnection().getPacketListener() == listener;
    }

    // A lost connection makes every held packet meaningless. A new world only makes what the
    // server sent before it meaningless. Positions from before it are dropped once its
    // teleport is answered.
    private void release() {
        if (!playing()) {
            forget();
            return;
        }
        if (worldWatch.changed()) {
            int now = world;
            inbound.removeIf(held -> held.world() < now);
        }
        releaseOutgoing();
        releaseIncoming();
        if (outbound.isEmpty() && inbound.isEmpty()) {
            flushing = false;
        }
    }

    // A second position in a tick needs a tick end packet before it. Without bursts it waits
    // for the next tick and everything behind it waits too.
    private void releaseOutgoing() {
        Held next;
        while ((next = outbound.peek()) != null && due(next)) {
            if (carriesPosition(next.packet()) && !MoveGate.free() && !MoveGate.endTick()) {
                return;
            }
            outbound.poll();
            send(next.packet());
        }
    }

    // A module that takes the packet back as it leaves sends the same packet again later.
    private void send(Packet<?> packet) {
        releasing = packet;
        leaving = null;
        try {
            OfflineClient.MC.getConnection().send(packet);
        } finally {
            releasing = null;
        }
        if (leaving != null && leaving.isCancelled()) {
            retaken.add(packet);
        }
        leaving = null;
    }

    // Every module sees the packet again as it takes effect and may still drop it. A packet that
    // moves you to a new world takes the ones behind it along.
    @SuppressWarnings("unchecked")
    private void releaseIncoming() {
        Held next;
        while ((next = inbound.peek()) != null && due(next)) {
            inbound.poll();
            PacketReceiveEvent event = new PacketReceiveEvent(next.packet());
            releasing = next.packet();
            try {
                OfflineClient.INSTANCE.getEventBus().post(event);
            } finally {
                releasing = null;
            }
            if (!event.isCancelled()) {
                ((Packet<ClientGamePacketListener>) event.getPacket()).handle(OfflineClient.MC.getConnection());
            }
            if (!playing()) {
                forget();
                return;
            }
        }
    }

    private boolean due(Held held) {
        return flushing || held.due() <= tick;
    }

    private static boolean carriesPosition(Packet<?> packet) {
        return packet instanceof ServerboundMovePlayerPacket move && move.hasPosition();
    }

    // A lost message would break the chain the server checks every later one against.
    private void dropAllButChat() {
        inbound.clear();
        if (playing()) {
            Held next;
            while ((next = outbound.poll()) != null) {
                if (CHAT.contains(next.packet().type())) {
                    OfflineClient.MC.getConnection().send(next.packet());
                }
            }
        }
        forget();
    }

    private void forget() {
        outbound.clear();
        inbound.clear();
        flushing = false;
    }
}
