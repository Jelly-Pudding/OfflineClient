package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.jellypudding.offlineclient.modules.misc.Timer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.PositionAndRotation;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.GamePacketTypes;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec3;

// A 26.3 server drops anyone whose second position arrives before a tick end packet.
// Every module that moves the player by packet books its positions here. A packet
// without a position is exempt and still lets the next position go further. That
// allowance only renews each server tick. A burst of positions split by tick end
// packets is legal and shares it.
public enum MoveGate {
    INSTANCE;

    // Vanilla counts five movement packets a server tick. Each adds a hundred to the
    // squared distance a position may end from where the tick began.
    private static final int VANILLA_PACKETS = 5;
    private static final double VANILLA_SQUARED = 100;

    // Paper counts twenty after a packet that stays put. Each buys its moved too quickly
    // multiplier in blocks and a vehicle packet twice that.
    private static final int PAPER_PACKETS = 20;
    private static final double PAPER_BLOCKS = 10;
    private static final double VEHICLE_SPEED = 2;

    // Either server lets any packet end ten blocks from where the tick began.
    private static final double FLOOR = 10;

    // A packet from the tick before can land in the same server tick. One is kept in hand.
    private static final int SLACK = 1;
    // A hair is kept in hand of every allowance.
    private static final double MARGIN = 0.1;

    // A pull back this many ticks after a hop only Paper allows is blamed on that hop.
    // Two blamed in a row mean the server allows less than Paper's default.
    private static final int BLAME_TICKS = 40;
    private static final int STRIKES = 2;
    private static final int NEVER = -1;

    // What the rest of a tick still sends once the fillers are out.
    private static final int HEADROOM = 20;

    // Client ticks bunch after a slow frame and run fast under Timer. A burst sent soon
    // after the last position lands in the same server tick and is refused. Steady play
    // at normal speed never runs two ticks closer than half a server tick.
    private static final double SERVER_TICK_NANOS = 50_000_000;

    // The server kicks anyone hanging in the air too long. It counts the ticks whose last
    // movement packet has no block near and drops no further than this.
    private static final double FLOAT_DROP = 0.03125;

    private static final PacketBudget budget = new PacketBudget();
    private static final Lagback.Watcher lagback = Lagback.Watcher.withVehicles();
    private static final JoinWatch join = new JoinWatch();

    private static volatile boolean spent;

    // A packet the gate sends goes out exactly as built and the sender learns whether it left.
    private static Packet<?> own;
    private static PacketSendEvent ownEvent;
    private static boolean delivered;

    // Movement packets the server counts this tick.
    private static int counted;

    // The positions the server was last sent and where it held them as this tick began.
    private static Vec3 lastPlayer;
    private static Vec3 lastVehicle;
    private static Vec3 tickPlayer;
    private static Vec3 tickVehicle;

    private static double paperBlocks = PAPER_BLOCKS;
    private static int tick;
    private static int stretchedAt = NEVER;
    private static int strikes;

    // When the last position left.
    private static long positionAt = System.nanoTime();

    // The client tick in which the gate last dropped a position.
    private static volatile int droppedAt = NEVER;

    // Whether the last packet left the player or the vehicle hanging in the air and for
    // how many ticks the server has counted that.
    private static boolean playerFloating;
    private static boolean vehicleFloating;
    private static int playerFloatingTicks;
    private static int vehicleFloatingTicks;

    // The input the server last took.
    private static Input told = Input.EMPTY;

    public static boolean free() {
        return !spent;
    }

    // Counts client ticks. A sender that must act once a tick compares it.
    public static int tick() {
        return tick;
    }

    // True once a burst can go without sharing the server tick of the last position. It
    // waits a server tick less half a client tick. Faster ticks under Timer wait longer.
    public static boolean settled() {
        double clientTick = SERVER_TICK_NANOS / Math.max(1, Timer.current());
        return System.nanoTime() - positionAt >= SERVER_TICK_NANOS - clientTick / 2;
    }

    // Ticks the server has counted the player or the vehicle they steer hanging in the air.
    public static int floatingTicks(boolean vehicle) {
        return vehicle ? vehicleFloatingTicks : playerFloatingTicks;
    }

    // Packets the budget still has room for over its window.
    public static int spare() {
        return budget.spare();
    }

    // True when the gate dropped a position during this client tick. A look sent on its own
    // after a burst would share its server tick and count against it.
    public static boolean droppedThisTick() {
        return droppedAt == tick;
    }

    // True whilst Latency holds back the positions of the player or the vehicle they steer.
    // Those packets are refused here and a trip sent meanwhile would land late and out of step.
    public static boolean held(boolean vehicle) {
        return Modules.delays(vehicle ? GamePacketTypes.SERVERBOUND_MOVE_VEHICLE
            : GamePacketTypes.SERVERBOUND_MOVE_PLAYER_POS);
    }

    // True whilst the server takes the player for one holding sneak.
    public static boolean crouching() {
        return told.shift();
    }

    // Tells the server the sneak key is up. It goes out exactly as built even past a
    // module that keeps the player sneaking. False when it was held back.
    public static boolean standUp() {
        return sendOwn(new ServerboundPlayerInputPacket(InputUtil.withShift(told, false)));
    }

    // Sends the input the client itself last sent. Modules may rewrite it as usual.
    public static void resumeInput() {
        LocalPlayer player = OfflineClient.MC.player;
        if (player != null) {
            player.connection.send(new ServerboundPlayerInputPacket(player.getLastSentInput()));
        }
    }

    // False when this tick's position is spent or another module held the packet back.
    public static boolean send(double x, double y, double z, boolean onGround) {
        return send(new Vec3(x, y, z), onGround);
    }

    public static boolean send(Vec3 spot, boolean onGround) {
        LocalPlayer player = OfflineClient.MC.player;
        return player != null && sendPosition(spot, new ServerboundMovePlayerPacket.Pos(
            spot.x, spot.y, spot.z, onGround, player.horizontalCollision));
    }

    // The same with the player turned to face that way.
    public static boolean send(Vec3 spot, float yRot, float xRot, boolean onGround) {
        LocalPlayer player = OfflineClient.MC.player;
        return player != null && sendPosition(spot, new ServerboundMovePlayerPacket.PosRot(
            spot, yRot, xRot, onGround, player.horizontalCollision));
    }

    private static boolean sendPosition(Vec3 spot, ServerboundMovePlayerPacket packet) {
        if (spent) {
            return false;
        }
        boolean stretched = beyondVanilla(spot, tickPlayer, vanillaAllowance(counted + 1));
        boolean sent = sendOwn(packet);
        markStretch(sent && stretched);
        return sent;
    }

    // Sends the tick end packet early. The next position may then go straight away and
    // shares what is left of this tick's allowance. False whilst bursts are off.
    public static boolean endTick() {
        if (!bursts() || OfflineClient.MC.player == null) {
            return false;
        }
        if (spent) {
            sendOwn(ServerboundClientTickEndPacket.INSTANCE);
        }
        return !spent;
    }

    // Off whilst AntiPacketKick keeps every teleport to one step a tick.
    public static boolean bursts() {
        return !Modules.spreadsTeleports();
    }

    // Buys the next position a longer reach. The reach methods say how much. A filler
    // that claims ground makes the server charge any fall it holds.
    public static void fillers(int count, boolean onGround) {
        LocalPlayer player = OfflineClient.MC.player;
        if (player == null) {
            return;
        }
        for (int i = Math.min(count, maxFillers()); i > 0; i--) {
            sendOwn(new ServerboundMovePlayerPacket.StatusOnly(onGround, player.horizontalCollision));
        }
    }

    // The most fillers the server still counts ahead of one position this tick. Fewer
    // whilst the client already sends a lot.
    public static int maxFillers() {
        int packets = paperRules() ? PAPER_PACKETS : VANILLA_PACKETS;
        return Math.max(0, Math.min(packets - counted - 1, budget.spare() - HEADROOM));
    }

    // The longest single hop this tick can carry. Past a point the server stops counting
    // fillers and the most fillers are not always the best.
    public static double reach() {
        double best = 0;
        for (int fillers = maxFillers(); fillers >= 0; fillers--) {
            best = Math.max(best, reach(fillers, 1));
        }
        return best;
    }

    // How far a leg of this tick may end from where the tick began after that many
    // fillers. Legs count from one and a tick end packet parts each from the last. On
    // Paper no leg may be longer than this either.
    public static double reach(int fillers, int leg) {
        return playerAllowance(counted + fillers + leg, leg) - MARGIN;
    }

    // The fewest fillers that let one position go that far. Minus one when none do.
    public static int fillersFor(double distance) {
        int most = maxFillers();
        for (int fillers = 0; fillers <= most; fillers++) {
            if (reach(fillers, 1) >= distance) {
                return fillers;
            }
        }
        return -1;
    }

    // Moves the vehicle the player steers. Vehicles have no rule of one position a tick.
    public static boolean sendVehicle(Entity vehicle, Vec3 spot, boolean onGround) {
        if (OfflineClient.MC.player == null) {
            return false;
        }
        boolean stretched = beyondVanilla(spot, tickVehicle, FLOOR);
        boolean sent = sendOwn(new ServerboundMoveVehiclePacket(
            PositionAndRotation.of(spot, vehicle.getYRot(), vehicle.getXRot()), onGround));
        markStretch(sent && stretched);
        return sent;
    }

    // Vehicle packets that stay where the server holds the vehicle. Only Paper counts them.
    public static void vehicleFillers(Entity vehicle, int count) {
        Vec3 held = lastVehicle == null ? vehicle.position() : lastVehicle;
        for (int i = Math.min(count, maxVehicleFillers()); i > 0; i--) {
            sendOwn(new ServerboundMoveVehiclePacket(
                PositionAndRotation.of(held, vehicle.getYRot(), vehicle.getXRot()), true));
        }
    }

    public static int maxVehicleFillers() {
        if (!paperRules()) {
            return 0;
        }
        return Math.max(0, Math.min(PAPER_PACKETS - counted - 1, budget.spare() - HEADROOM));
    }

    // The longest single hop a vehicle can make this tick.
    public static double vehicleReach() {
        double best = 0;
        for (int fillers = maxVehicleFillers(); fillers >= 0; fillers--) {
            best = Math.max(best, vehicleReach(fillers, 1));
        }
        return best;
    }

    // The same for a vehicle leg. Vanilla lets vehicle packets end ten blocks from where
    // the tick began however many there are.
    public static double vehicleReach(int fillers, int leg) {
        double blocks = paperRules()
            ? paperAllowance(counted + fillers + leg, leg, VEHICLE_SPEED, true) : FLOOR;
        return blocks - MARGIN;
    }

    private static double playerAllowance(int count, int leg) {
        if (paperRules()) {
            return paperAllowance(count, leg, 1, false);
        }
        return count + SLACK <= VANILLA_PACKETS ? vanillaAllowance(count) : FLOOR;
    }

    // What vanilla lets a position cover once it has counted that many packets this tick.
    private static double vanillaAllowance(int count) {
        return count <= VANILLA_PACKETS ? Math.sqrt(VANILLA_SQUARED * count) : FLOOR;
    }

    // Paper only counts a packet whilst the count stays within what is left. Every leg
    // before this one moved and used one of those up. A count too high goes as one.
    private static double paperAllowance(int count, int leg, double speed, boolean trailed) {
        int room = PAPER_PACKETS - (leg - 1);
        // The client's own vehicle packet follows the last leg and moved as far.
        int needed = count + SLACK + (trailed ? 2 : 0);
        int counts = needed <= room ? count : 1;
        return Math.max(FLOOR, paperBlocks * speed * counts);
    }

    // Paper and the servers built on it until the session shows otherwise.
    private static boolean paperRules() {
        return paperBlocks > 0 && ServerInfo.runsPaper();
    }

    private static boolean beyondVanilla(Vec3 spot, Vec3 tickStart, double vanilla) {
        return paperRules() && tickStart != null && spot.distanceTo(tickStart) > vanilla;
    }

    private static void markStretch(boolean stretched) {
        if (stretched) {
            stretchedAt = tick;
        }
    }

    // A packet Latency would hold is never sent. Its sender takes it as not sent and nothing
    // stray reaches the server later.
    private static boolean sendOwn(Packet<?> packet) {
        LocalPlayer player = OfflineClient.MC.player;
        if (player == null || Modules.delays(packet.type())) {
            return false;
        }
        own = packet;
        delivered = false;
        try {
            player.connection.send(packet);
        } finally {
            own = null;
            ownEvent = null;
        }
        return delivered;
    }

    @Subscribe(priority = 1000)
    private void onPacketSendFirst(PacketSendEvent event) {
        Packet<?> packet = event.getPacket();
        if (own != null && packet == own) {
            ownEvent = event;
        }
        if (spent && packet instanceof ServerboundMovePlayerPacket move && move.hasPosition()) {
            drop(event);
        }
    }

    // Lowest priority because only the packet that survives every rewrite counts. A
    // rewrite can add a position the early check never saw.
    @Subscribe(priority = -1000)
    private void onPacketSendLast(PacketSendEvent event) {
        boolean mine = event == ownEvent;
        if (mine) {
            event.setPacket(own);
        }
        Packet<?> packet = event.getPacket();
        if (spent && packet instanceof ServerboundMovePlayerPacket move && move.hasPosition()) {
            drop(event);
            return;
        }
        delivered |= mine;
        budget.record();
        track(packet);
    }

    private static void drop(PacketSendEvent event) {
        event.cancel();
        droppedAt = tick;
    }

    private static void track(Packet<?> packet) {
        switch (packet) {
            case ServerboundMovePlayerPacket move -> trackPlayer(move);
            case ServerboundMoveVehiclePacket move -> trackVehicle(move);
            case ServerboundClientTickEndPacket end -> spent = false;
            case ServerboundPlayerInputPacket input -> told = input.input();
            case ServerboundAcceptTeleportationPacket accept -> trackTeleport(accept);
            default -> { }
        }
    }

    // The server puts the player where it sent them and takes the answer as a move from there.
    private static void trackTeleport(ServerboundAcceptTeleportationPacket accept) {
        LocalPlayer player = OfflineClient.MC.player;
        lastPlayer = new Vec3(accept.x(), accept.y(), accept.z());
        playerFloating = player != null && floats(player, lastPlayer, lastPlayer);
    }

    private static void trackPlayer(ServerboundMovePlayerPacket move) {
        LocalPlayer player = OfflineClient.MC.player;
        // The server skips the count for a passenger's own packets.
        if (player != null && !player.isPassenger()) {
            counted++;
        }
        Vec3 from = lastPlayer;
        if (move.hasPosition()) {
            spent = true;
            positionAt = System.nanoTime();
            lastPlayer = new Vec3(move.getX(0), move.getY(0), move.getZ(0));
        }
        if (player != null) {
            Vec3 held = lastPlayer == null ? player.position() : lastPlayer;
            playerFloating = floats(player, from == null ? held : from, held);
        }
    }

    private static void trackVehicle(ServerboundMoveVehiclePacket move) {
        counted++;
        positionAt = System.nanoTime();
        Vec3 to = move.movingTo().position();
        Vec3 from = lastVehicle == null ? to : lastVehicle;
        lastVehicle = to;
        LocalPlayer player = OfflineClient.MC.player;
        vehicleFloating = player != null && floats(player.getRootVehicle(), from, to);
    }

    // True when the server takes the move for one that hangs in the air. It has no block
    // near and drops too little to count as a fall.
    private static boolean floats(Entity mover, Vec3 from, Vec3 to) {
        return from.y - to.y <= FLOAT_DROP && HopPath.floating(mover, to);
    }

    // A paused game sends no tick end packet and would wedge the gate shut.
    @Subscribe(priority = -1000)
    private void onClientTick(ClientTickEvent event) {
        spent = false;
        counted = 0;
        tick++;
        if (join.joined()) {
            forgetSession();
        } else {
            judgeStretch();
        }
        countFloating();
        tickPlayer = lastPlayer;
        tickVehicle = lastVehicle;
    }

    // The server counts once a tick from the last packet it took. A rider counts only for
    // the vehicle they steer and a dead player not at all.
    private static void countFloating() {
        LocalPlayer player = OfflineClient.MC.player;
        boolean riding = player != null && player.isPassenger();
        playerFloating &= player != null && player.isAlive() && !riding;
        vehicleFloating &= riding;
        playerFloatingTicks = playerFloating ? playerFloatingTicks + 1 : 0;
        vehicleFloatingTicks = vehicleFloating ? vehicleFloatingTicks + 1 : 0;
    }

    // A new server may run other rules.
    private static void forgetSession() {
        paperBlocks = PAPER_BLOCKS;
        strikes = 0;
        stretchedAt = NEVER;
        lastPlayer = null;
        lastVehicle = null;
        playerFloating = false;
        vehicleFloating = false;
        told = Input.EMPTY;
        lagback.sync();
    }

    // Paper's default allowance is assumed until the server refuses it twice running.
    private static void judgeStretch() {
        boolean pulled = lagback.happened();
        if (stretchedAt == NEVER) {
            return;
        }
        if (pulled) {
            stretchedAt = NEVER;
            if (++strikes >= STRIKES) {
                strikes = 0;
                paperBlocks = halved(paperBlocks);
            }
        } else if (tick - stretchedAt > BLAME_TICKS) {
            stretchedAt = NEVER;
            strikes = 0;
        }
    }

    // Past a point vanilla's own rules reach as far with fewer packets.
    private static double halved(double blocks) {
        double half = blocks / 2;
        double paperBest = half * (PAPER_PACKETS - SLACK);
        double vanillaBest = Math.sqrt(VANILLA_SQUARED * (VANILLA_PACKETS - SLACK));
        return paperBest > vanillaBest ? half : 0;
    }
}
