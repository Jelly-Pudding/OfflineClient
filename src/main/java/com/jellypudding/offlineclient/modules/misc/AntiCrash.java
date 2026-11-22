package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.PacketNotice;
import com.jellypudding.offlineclient.util.PacketUtil;
import com.jellypudding.offlineclient.util.SignWords;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundMoveMinecartPacket;
import net.minecraft.network.protocol.game.ClientboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundProjectilePowerPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.util.StringUtil;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.PositionPath;
import net.minecraft.world.entity.PositionStep;
import net.minecraft.world.entity.vehicle.minecart.NewMinecartBehavior;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

// Packets that fail to read at all are AntiPacketKick's job. Firework bursts are counted where the
// game sets them off.
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
    // No blast a command can make touches this many blocks. A plain server counts the air a blast
    // went through and a wither far from any block already sends thousands.
    private static final int MAX_REAL_BLAST = 1 << 20;

    // The particle engine keeps at most this many of one kind. More in one tick is wasted work.
    private static final int PARTICLE_BUDGET = 16384;

    private static final int MAX_CHUNK_RADIUS = ChunkMap.MAX_VIEW_DISTANCE;

    // Far wider than any screen and room for the many parts a pack font builds a boss bar from.
    private static final int LONGEST_NAME = 1000;

    // More lines than the chat keeps. Nothing a player could read comes faster.
    private static final int CHAT_BUDGET = 200;

    // A rocket holds no more stars than fit a crafting grid beside its paper and gunpowder. The
    // game lets a server send one with hundreds and plays them all.
    private static final int MOST_STARS = 7;

    // Eight full rockets a tick. A rocket with no star still makes a puff and counts as one.
    private static final int STAR_BUDGET = 8 * MOST_STARS;

    private static final String VELOCITY = "Velocity";
    private static final String NAMES = "Long names";
    private static final String FIREWORKS = "Fireworks";
    private static final String CHAT_FLOOD = "Chat flood";
    private static final String FLOOD_DROPPED = "Dropped a flood of chat lines from the server.";
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
    private final BoolSetting names = new BoolSetting(NAMES,
        "Cuts the name of an entity or boss bar or item to a thousand characters. The game draws a name of any"
            + " length every frame.", true);
    private final BoolSetting fireworks = new BoolSetting(FIREWORKS,
        "Cuts a rocket to the seven stars one can be crafted with and lets no more than fifty six stars burst in a"
            + " tick. A storm of them freezes the game.", true);
    private final BoolSetting chatFlood = new BoolSetting(CHAT_FLOOD,
        "Drops server lines past two hundred a second. Chat from players always shows.", true);
    private final NumberSetting entityCap = new NumberSetting("Entity cap",
        "New entities are dropped once the world around you holds this many. Players always appear. Zero sets"
            + " no cap.", 0, 0, 10000, 100);
    private final BoolSetting demo = new BoolSetting("Demo screen",
        "Drops the demo welcome screen and the demo hints a server can send.", true);
    private final BoolSetting credits = new BoolSetting("End credits",
        "Skips the end credits and asks for the respawn they end with straight away.", false);
    private final BoolSetting guardian = new BoolSetting("Guardian face",
        "Drops the elder guardian face and its sound. The mining fatigue it comes with still lands.", false);
    private final BoolSetting notify = new BoolSetting("Notify",
        "Says in chat when a guard throws something away.", true);

    // Filled on the netty thread.
    private final AtomicInteger particlesThisTick = new AtomicInteger();
    // The world only counts an entity once the game thread has taken its packet. One let in just
    // before a tick ended is in neither the world nor the fresh tally and the older tally still
    // holds it.
    private final AtomicInteger spawnsThisTick = new AtomicInteger();
    private final AtomicInteger spawnsLastTick = new AtomicInteger();
    private final AtomicInteger chatThisSecond = new AtomicInteger();
    private int secondTicks;
    // Bursts happen on the game thread.
    private int starsThisTick;

    public AntiCrash() {
        super("AntiCrash", "Stops packets that would crash or freeze the game.", Category.MISC);
        addSettings(explosions, particles, velocity, positions, chunkRadius, signs, names, fireworks, chatFlood,
            entityCap, demo, credits, guardian, notify);
        searchTags("crash", "freeze", "lag", "exploit", "demo", "credits", "elder guardian", "boss bar",
            "entity limit", "chat spam", "firework");
    }

    // Nothing legitimate trips a guard. It is safe to have on everywhere.
    @Override
    public boolean enabledByDefault() {
        return true;
    }

    @Subscribe
    private void onClientTick(ClientTickEvent event) {
        particlesThisTick.set(0);
        spawnsLastTick.set(spawnsThisTick.getAndSet(0));
        starsThisTick = 0;
        if (++secondTicks >= SharedConstants.TICKS_PER_SECOND) {
            secondTicks = 0;
            chatThisSecond.set(0);
        }
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
            case ClientboundAddEntityPacket spawn when overCap(spawn) ->
                drop("Entity cap", "Dropped new entities past the cap you set.");
            case ClientboundAddEntityPacket spawn -> spawn(spawn);
            case ClientboundSetEntityDataPacket data when names.isOn() || fireworks.isOn() -> entityData(data);
            case ClientboundBossEventPacket bar when names.isOn() -> bossBar(bar);
            case ClientboundSystemChatPacket line when chatFlood.isOn() && !line.overlay() && floods() ->
                drop(CHAT_FLOOD, FLOOD_DROPPED);
            case ClientboundDisguisedChatPacket _ when chatFlood.isOn() && floods() ->
                drop(CHAT_FLOOD, FLOOD_DROPPED);
            case ClientboundGameEventPacket event -> gameEvent(event);
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
        boolean wildPush = !knockback.equals(blast.playerKnockback());
        int blocks = Math.min(blast.blockCount(), MAX_BLAST_PARTICLES);
        if (!wildPush && blocks == blast.blockCount()) {
            return blast;
        }
        // The count of a real blast is brought down without a word.
        if (wildPush || blast.blockCount() > MAX_REAL_BLAST) {
            report("Explosions", "Trimmed an explosion that would have crashed or frozen the game.");
        }
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

    // The world is read from the netty thread. Its count may be a tick behind and the tallies of
    // this tick and the one before make up for that. Counting one twice for a tick only holds
    // the next few back a moment.
    private boolean overCap(ClientboundAddEntityPacket spawn) {
        int cap = entityCap.getInt();
        ClientLevel level = mc.level;
        return cap > 0 && level != null && spawn.getType() != EntityTypes.PLAYER
            && level.getEntityCount() + spawnsLastTick.get() + spawnsThisTick.getAndIncrement() >= cap;
    }

    private boolean floods() {
        return chatThisSecond.getAndIncrement() >= CHAT_BUDGET;
    }

    // A demo of the game runs on a world of its own and shows these by itself. Closing the credits
    // asks the server for a respawn and skipping them has to ask too.
    private Packet<?> gameEvent(ClientboundGameEventPacket event) {
        ClientboundGameEventPacket.Type kind = event.getEvent();
        if (kind == ClientboundGameEventPacket.DEMO_EVENT && demo.isOn() && !mc.isDemo()) {
            return drop("Demo screen", "Dropped a demo screen the server sent.");
        }
        if (kind == ClientboundGameEventPacket.GUARDIAN_ELDER_EFFECT && guardian.isOn()) {
            return null;
        }
        if (kind == ClientboundGameEventPacket.WIN_GAME && credits.isOn()) {
            PacketUtil.send(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
            return null;
        }
        return event;
    }

    // An entity carries its name and a rocket its stars as data values.
    private Packet<?> entityData(ClientboundSetEntityDataPacket data) {
        List<SynchedEntityData.DataValue<?>> values = data.packedItems();
        List<SynchedEntityData.DataValue<?>> safe = null;
        for (int i = 0; i < values.size(); i++) {
            SynchedEntityData.DataValue<?> value = values.get(i);
            SynchedEntityData.DataValue<?> fixed = safeValue(value);
            if (fixed != value) {
                safe = safe == null ? new ArrayList<>(values) : safe;
                safe.set(i, fixed);
            }
        }
        return safe == null ? data : new ClientboundSetEntityDataPacket(data.id(), safe);
    }

    private SynchedEntityData.DataValue<?> safeValue(SynchedEntityData.DataValue<?> value) {
        if (names.isOn() && value.value() instanceof Optional<?> held && held.isPresent()
            && held.get() instanceof Component name && tooLong(name)) {
            report(NAMES, "Cut an entity name too long to draw.");
            return new SynchedEntityData.DataValue<>(value.id(), EntityDataSerializers.OPTIONAL_COMPONENT,
                Optional.of(cut(name)));
        }
        if (fireworks.isOn() && value.value() instanceof ItemStack rocket) {
            Fireworks stars = rocket.get(DataComponents.FIREWORKS);
            if (stars != null && stars.explosions().size() > MOST_STARS) {
                ItemStack trimmed = rocket.copy();
                trimmed.set(DataComponents.FIREWORKS, new Fireworks(stars.flightDuration(),
                    List.copyOf(stars.explosions().subList(0, MOST_STARS))));
                report(FIREWORKS, "Cut a rocket with more stars than one can be crafted with.");
                return new SynchedEntityData.DataValue<>(value.id(), EntityDataSerializers.ITEM_STACK, trimmed);
            }
        }
        return value;
    }

    // The packet only shows what it holds to a handler. A bar that is added or renamed with a long
    // name is built again with the name cut.
    private Packet<?> bossBar(ClientboundBossEventPacket bar) {
        ClientboundBossEventPacket[] safe = {bar};
        bar.dispatch(new ClientboundBossEventPacket.Handler() {
            @Override
            public void add(UUID id, Component name, float progress, BossEvent.BossBarColor colour,
                            BossEvent.BossBarOverlay overlay, boolean darkenScreen, boolean playMusic,
                            boolean createFog) {
                if (tooLong(name)) {
                    BossEvent cutBar = new BossEvent(id, cut(name), colour, overlay) {
                    };
                    cutBar.setProgress(progress);
                    cutBar.setDarkenScreen(darkenScreen).setPlayBossMusic(playMusic).setCreateWorldFog(createFog);
                    safe[0] = ClientboundBossEventPacket.createAddPacket(cutBar);
                }
            }

            @Override
            public void updateName(UUID id, Component name) {
                if (tooLong(name)) {
                    safe[0] = ClientboundBossEventPacket.createUpdateNamePacket(new BossEvent(id, cut(name),
                        BossEvent.BossBarColor.WHITE, BossEvent.BossBarOverlay.PROGRESS) {
                    });
                }
            }
        });
        if (safe[0] != bar) {
            report(NAMES, "Cut a boss bar name too long to draw.");
        }
        return safe[0];
    }

    // Asked by ClientLevelMixin as a rocket bursts.
    public boolean letsBurst(List<FireworkExplosion> stars) {
        if (!fireworks.isOn()) {
            return true;
        }
        int cost = Math.max(1, stars.size());
        if (starsThisTick + cost <= STAR_BUDGET) {
            starsThisTick += cost;
            return true;
        }
        report(FIREWORKS, "Dropped a storm of fireworks.");
        return false;
    }

    // Read by ItemStackMixin. The anvil reads the whole name and never this one.
    public Component shortName(ItemStack stack, Component name) {
        return names.isOn() && !stack.getComponentsPatch().isEmpty() && tooLong(name) ? cut(name) : name;
    }

    // Reading stops one character past the limit however long the name is.
    private static boolean tooLong(Component name) {
        return name.getString(LONGEST_NAME + 1).length() > LONGEST_NAME;
    }

    private static Component cut(Component name) {
        return ChatUtil.head(name, LONGEST_NAME);
    }

    private Packet<?> spawn(ClientboundAddEntityPacket spawn) {
        return move(spawn, new Vec3(spawn.getX(), spawn.getY(), spawn.getZ()), spawn.getMovement(),
            () -> new ClientboundAddEntityPacket(spawn.getId(), spawn.getUUID(), spawn.getX(), spawn.getY(),
                spawn.getZ(), spawn.getXRot(), spawn.getYRot(), spawn.getType(), spawn.getData(), Vec3.ZERO,
                spawn.getYHeadRot()));
    }

    // A dropped teleport is never confirmed. The server resends it and you stay put.
    private Packet<?> teleport(ClientboundPlayerPositionPacket teleport) {
        PositionMoveRotation change = teleport.change();
        return move(teleport, change.position(), change.deltaMovement(),
            () -> new ClientboundPlayerPositionPacket(teleport.id(), stilled(change), teleport.relatives()));
    }

    private Packet<?> teleport(ClientboundTeleportEntityPacket teleport) {
        PositionMoveRotation change = teleport.change();
        return move(teleport, change.position(), change.deltaMovement(),
            () -> new ClientboundTeleportEntityPacket(teleport.id(), stilled(change), teleport.relatives(),
                teleport.onGround()));
    }

    private Packet<?> move(Packet<?> packet, Vec3 position, Vec3 push, Supplier<Packet<?>> stilled) {
        if (positions.isOn() && !inWorld(position)) {
            return outOfWorld();
        }
        if (!velocity.isOn() || sane(push)) {
            return packet;
        }
        report(VELOCITY, TOO_FAST);
        return stilled.get();
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
            || sign.lines().stream().allMatch(line -> line.length() <= SignWords.MOST_LINE_CHARS)) {
            return;
        }
        List<String> lines = sign.lines().stream()
            .map(line -> StringUtil.truncateStringIfNecessary(line, SignWords.MOST_LINE_CHARS, false))
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
