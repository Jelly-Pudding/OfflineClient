package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.util.PacketNotice;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundMoveMinecartPacket;
import net.minecraft.network.protocol.game.ClientboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundProjectilePowerPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.util.StringUtil;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.PositionPath;
import net.minecraft.world.entity.PositionStep;
import net.minecraft.world.entity.vehicle.minecart.NewMinecartBehavior;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

// Drops or trims server packets carrying values nothing in the game produces. It runs
// before the game or any other module handles them. Packets that fail to read at all
// are AntiPacketKick's job.
public final class AntiCrash extends Module {

    // Ten times what the server lets a player cover in one tick. Nothing in the game moves faster.
    private static final double MAX_SPEED = 100;

    // Room past the edge of the world for anything knocked off it.
    private static final double EDGE_MARGIN = 1000;
    private static final double MAX_HORIZONTAL = Level.MAX_LEVEL_SIZE + EDGE_MARGIN;
    private static final double MAX_VERTICAL = Level.MAX_ENTITY_SPAWN_Y + EDGE_MARGIN;

    // The explosion tracker spawns no more block particles than this in a tick. A bigger
    // count only serves to overflow the sum it takes over every blast of the tick.
    private static final int MAX_BLAST_PARTICLES = 512;

    // The particle engine keeps at most this many of one kind. More in one tick is wasted work.
    private static final int PARTICLE_BUDGET = 16384;

    // The farthest view distance a server can set.
    private static final int MAX_CHUNK_RADIUS = ChunkMap.MAX_VIEW_DISTANCE;

    // The longest sign line the client can send back.
    private static final int MAX_SIGN_LINE = 384;

    private static final String VELOCITY = "Velocity";
    private static final String TOO_FAST = "Dropped a push faster than anything in the game.";

    private final BoolSetting explosions = new BoolSetting("Explosions",
        "Drops explosions outside the world and knockback faster than anything in the game. "
            + "Caps the block particles a blast asks for.", true);
    private final BoolSetting particles = new BoolSetting("Particles",
        "Spawns no more particles a tick than the game can keep. One packet can ask for billions.", true);
    private final BoolSetting velocity = new BoolSetting("Velocity",
        "Drops a push faster than anything in the game. The game freezes working out such a move.", true);
    private final BoolSetting positions = new BoolSetting("Positions",
        "Drops anything the server moves past the edge of the world. The game freezes when the camera goes there.",
        true);
    private final BoolSetting chunkRadius = new BoolSetting("Chunk radius",
        "Caps the chunk radius the server sets at thirty two. A bigger one runs the game out of memory.", true);
    private final BoolSetting signs = new BoolSetting("Sign lines",
        "Trims sign lines to what the server takes when you close a sign. A longer line drops your connection.",
        true);
    private final BoolSetting notify = new BoolSetting("Notify",
        "Says in chat when a guard throws something away.", true);

    // Particles asked for since the last client tick. Filled on the netty thread.
    private final AtomicInteger particlesThisTick = new AtomicInteger();

    public AntiCrash() {
        super("AntiCrash", "Stops packets that would crash or freeze the game.", Category.MISC);
        addSettings(explosions, particles, velocity, positions, chunkRadius, signs, notify);
        searchTags("crash", "freeze", "lag", "exploit");
    }

    // Nothing legitimate trips a guard. It is safe to have on everywhere.
    @Override
    public boolean enabledByDefault() {
        return true;
    }

    @Subscribe
    private void onClientTick(ClientTickEvent event) {
        particlesThisTick.set(0);
    }

    // Fired on the netty thread. Runs before every other module sees the packet.
    @Subscribe(priority = 500)
    private void onPacketReceive(PacketReceiveEvent event) {
        Packet<?> packet = event.getPacket();
        Packet<?> safe = guard(packet);
        if (safe == null) {
            event.cancel();
        } else if (safe != packet) {
            event.setPacket(safe);
        }
    }

    // Hands back the packet itself when it is fine. A trimmed copy replaces it and null drops it.
    private Packet<?> guard(Packet<?> packet) {
        return switch (packet) {
            case ClientboundExplodePacket blast when explosions.isOn() -> explosion(blast);
            case ClientboundLevelParticlesPacket burst when particles.isOn() -> particles(burst);
            case ClientboundSetEntityMotionPacket motion when velocity.isOn() && !sane(motion.movement()) ->
                drop(VELOCITY, TOO_FAST);
            case ClientboundProjectilePowerPacket power when velocity.isOn()
                && !(Math.abs(power.getAccelerationPower()) <= MAX_SPEED) -> drop(VELOCITY, TOO_FAST);
            case ClientboundAddEntityPacket spawn -> spawn(spawn);
            case ClientboundPlayerPositionPacket teleport -> teleport(teleport);
            case ClientboundTeleportEntityPacket teleport -> teleport(teleport);
            case ClientboundEntityPositionSyncPacket sync when positions.isOn() && !inWorld(sync.position()) ->
                outOfWorld();
            case ClientboundMoveVehiclePacket vehicle when positions.isOn()
                && !inWorld(vehicle.movingTo().position()) -> outOfWorld();
            case ClientboundMoveMinecartPacket minecart -> minecart(minecart);
            case ClientboundSetChunkCacheRadiusPacket radius when chunkRadius.isOn()
                && radius.getRadius() > MAX_CHUNK_RADIUS -> {
                capped(radius.getRadius());
                yield new ClientboundSetChunkCacheRadiusPacket(MAX_CHUNK_RADIUS);
            }
            case ClientboundLoginPacket login when chunkRadius.isOn() && login.chunkRadius() > MAX_CHUNK_RADIUS -> {
                capped(login.chunkRadius());
                yield new ClientboundLoginPacket(login.playerId(), login.hardcore(), login.levels(),
                    login.maxPlayers(), MAX_CHUNK_RADIUS, login.simulationDistance(), login.reducedDebugInfo(),
                    login.showDeathScreen(), login.doLimitedCrafting(), login.commonPlayerSpawnInfo(),
                    login.onlineMode(), login.enforcesSecureChat());
            }
            default -> packet;
        };
    }

    // Two blasts in one tick with huge block counts overflow the weight sum and crash
    // the level tick. The radius only spreads particles and is left alone.
    private Packet<?> explosion(ClientboundExplodePacket blast) {
        if (!inWorld(blast.center())) {
            return drop("Explosions", "Dropped an explosion outside the world.");
        }
        Optional<Vec3> knockback = blast.playerKnockback().filter(AntiCrash::sane);
        int blocks = Math.min(blast.blockCount(), MAX_BLAST_PARTICLES);
        if (knockback.equals(blast.playerKnockback()) && blocks == blast.blockCount()) {
            return blast;
        }
        report("Explosions", "Trimmed an explosion that would have crashed or frozen the game.");
        return new ClientboundExplodePacket(blast.center(), blast.radius(), blocks, knockback,
            blast.explosionParticle(), blast.explosionSound(), blast.blockParticles(), blast.playSound());
    }

    // A count of nought still spawns one particle. The tally stops at the budget and
    // never overflows however much a packet asks for.
    private Packet<?> particles(ClientboundLevelParticlesPacket burst) {
        int wanted = Math.max(1, burst.count());
        int used = particlesThisTick.getAndUpdate(
            sum -> Math.min(PARTICLE_BUDGET, sum + Math.min(wanted, PARTICLE_BUDGET)));
        int left = PARTICLE_BUDGET - used;
        if (wanted <= left) {
            return burst;
        }
        if (left <= 0) {
            return drop("Particles", "Held back a flood of particles from the server.");
        }
        report("Particles", "Held back a flood of particles from the server.");
        return new ClientboundLevelParticlesPacket(burst.particle(), burst.overrideLimiter(), burst.alwaysShow(),
            burst.x(), burst.y(), burst.z(), burst.xDist(), burst.yDist(), burst.zDist(),
            burst.xMaxSpeed(), burst.yMaxSpeed(), burst.zMaxSpeed(), left, burst.randomizationType());
    }

    private Packet<?> spawn(ClientboundAddEntityPacket spawn) {
        if (positions.isOn() && !inWorld(new Vec3(spawn.getX(), spawn.getY(), spawn.getZ()))) {
            return outOfWorld();
        }
        if (!velocity.isOn() || sane(spawn.getMovement())) {
            return spawn;
        }
        report(VELOCITY, TOO_FAST);
        return new ClientboundAddEntityPacket(spawn.getId(), spawn.getUUID(), spawn.getX(), spawn.getY(),
            spawn.getZ(), spawn.getXRot(), spawn.getYRot(), spawn.getType(), spawn.getData(), Vec3.ZERO,
            spawn.getYHeadRot());
    }

    // A dropped teleport is never confirmed. The server resends it and you stay put.
    private Packet<?> teleport(ClientboundPlayerPositionPacket teleport) {
        PositionMoveRotation change = teleport.change();
        if (positions.isOn() && !inWorld(change.position())) {
            return outOfWorld();
        }
        if (!velocity.isOn() || sane(change.deltaMovement())) {
            return teleport;
        }
        report(VELOCITY, TOO_FAST);
        return new ClientboundPlayerPositionPacket(teleport.id(), stilled(change), teleport.relatives());
    }

    private Packet<?> teleport(ClientboundTeleportEntityPacket teleport) {
        PositionMoveRotation change = teleport.change();
        if (positions.isOn() && !inWorld(change.position())) {
            return outOfWorld();
        }
        if (!velocity.isOn() || sane(change.deltaMovement())) {
            return teleport;
        }
        report(VELOCITY, TOO_FAST);
        return new ClientboundTeleportEntityPacket(teleport.id(), stilled(change), teleport.relatives(),
            teleport.onGround());
    }

    private Packet<?> minecart(ClientboundMoveMinecartPacket minecart) {
        for (NewMinecartBehavior.MinecartStep step : minecart.lerpSteps()) {
            if (positions.isOn() && !inWorld(step.position())) {
                return outOfWorld();
            }
            if (velocity.isOn() && !sane(step.movement())) {
                return drop(VELOCITY, TOO_FAST);
            }
        }
        return minecart;
    }

    private static PositionMoveRotation stilled(PositionMoveRotation change) {
        return new PositionMoveRotation(change.position(), Vec3.ZERO, change.yRot(), change.xRot());
    }

    // Every point an entity passes on its way counts.
    private static boolean inWorld(PositionPath path) {
        if (!inWorld(path.endPosition())) {
            return false;
        }
        if (path instanceof PositionPath.Stepped stepped) {
            for (PositionStep step : stepped.steps()) {
                if (!inWorld(step.position())) {
                    return false;
                }
            }
        }
        return true;
    }

    // A value that is not a number fails every comparison and counts as outside.
    private static boolean inWorld(Vec3 pos) {
        return Math.abs(pos.x) <= MAX_HORIZONTAL && Math.abs(pos.z) <= MAX_HORIZONTAL
            && Math.abs(pos.y) <= MAX_VERTICAL;
    }

    private static boolean sane(Vec3 motion) {
        return motion.lengthSqr() <= MAX_SPEED * MAX_SPEED;
    }

    @Subscribe
    private void onPacketSend(PacketSendEvent event) {
        if (!signs.isOn() || !(event.getPacket() instanceof ServerboundSignUpdatePacket sign)
            || sign.lines().stream().allMatch(line -> line.length() <= MAX_SIGN_LINE)) {
            return;
        }
        List<String> lines = sign.lines().stream()
            .map(line -> StringUtil.truncateStringIfNecessary(line, MAX_SIGN_LINE, false))
            .toList();
        event.setPacket(new ServerboundSignUpdatePacket(sign.pos(), lines, sign.slot()));
        report("Sign lines", "Trimmed a sign line longer than the server takes.");
    }

    private Packet<?> outOfWorld() {
        return drop("Positions", "Dropped a move past the edge of the world.");
    }

    private void capped(int radius) {
        report("Chunk radius", "The server asked for a chunk radius of " + radius + ". It was capped at "
            + MAX_CHUNK_RADIUS + ".");
    }

    private Packet<?> drop(String guard, String message) {
        report(guard, message);
        return null;
    }

    private void report(String guard, String message) {
        if (notify.isOn()) {
            PacketNotice.report(guard, message);
        }
    }
}
