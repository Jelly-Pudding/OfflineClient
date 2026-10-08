package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.config.PearlLedger;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.mojang.authlib.GameProfile;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Predicate;

// Who owns each ender pearl in sight. The server names the thrower of a pearl by entity id and
// the client only fills the owner in whilst the thrower is loaded. Every player that comes into
// view teaches its id for the rest of the visit. A pearl that turns up as exactly one player
// joins or goes as exactly one leaves is guessed to be theirs. Packets come in on the network
// thread and are read on the game thread.
public enum PearlOwners {
    INSTANCE;

    // The owner of a pearl with the name they last went by.
    public record Owner(UUID id, String name) {
    }

    // A pearl the server added and the entity id of whoever threw it.
    private static final class Pearl {

        private final UUID uuid;
        private final int ownerId;
        // True whilst the pearl rested in a stasis chamber at the last look.
        private boolean resting;

        private Pearl(UUID uuid, int ownerId) {
            this.uuid = uuid;
            this.ownerId = ownerId;
        }
    }

    // A pearl that came or went and the tick it was read.
    private record Sighting(Pearl pearl, int tick) {
    }

    // Players new to the tab list and the newest entity id read before them.
    private record Listing(List<ClientboundPlayerInfoUpdatePacket.Entry> entries, int newestBefore) {
    }

    // A player who joined the tab list. Their entity id is newer than every id read before.
    private record Join(Owner player, int tick, int newestBefore) {
    }

    private record Leave(Owner player, int tick) {
    }

    // Packets held whilst the game thread is busy. Anything past this is dropped.
    private static final int MAX_PENDING = 4096;
    private static final int MAX_PLAYERS = 1024;
    private static final int MAX_PEARLS = 1024;
    // A join this many ticks either side of a pearl turning up explains it. A leaver's pearls
    // go in the very tick their tab list entry does.
    private static final int WINDOW = 2;
    // A join is judged once every pearl and every other join that bears on it has been read.
    // Both are kept until no join judged later can still need them.
    private static final int JUDGE_AGE = 2 * WINDOW + 1;
    private static final int KEEP_TICKS = 4 * WINDOW + 1;
    // The players and pearls of the world you join pour in first. Nobody counts as joining then.
    private static final int SETTLE_TICKS = 40;
    // The server sends no owner as id nought.
    private static final int NO_OWNER = 0;

    private static final Minecraft MC = OfflineClient.MC;

    // Packets and listings in the order they came.
    private final Queue<Object> waiting = new LinkedBlockingQueue<>(MAX_PENDING);
    // Entity ids hold for a whole visit. A respawn keeps the id.
    private final Map<Integer, UUID> players = new BoundedMap<>(MAX_PLAYERS);
    private final Map<UUID, String> names = new BoundedMap<>(MAX_PLAYERS);
    private final Map<Integer, Pearl> pearls = new BoundedMap<>(MAX_PEARLS);
    // Guesses by owner id and by pearl. A pearl keeps its id whilst its owner is away.
    private final Map<Integer, Owner> guessedIds = new BoundedMap<>(MAX_PLAYERS);
    private final Map<UUID, Owner> guessedPearls = new BoundedMap<>(MAX_PEARLS);
    private final List<Sighting> arrived = new ArrayList<>();
    private final List<Sighting> gone = new ArrayList<>();
    private final List<Join> joins = new ArrayList<>();
    private final List<Leave> leaves = new ArrayList<>();
    private final PearlLedger ledger = new PearlLedger();
    private final WorldWatch world = new WorldWatch();
    // The newest entity id read on this connection. Only the network thread touches it.
    private int newestRead;
    private UUID self;
    private int tick;
    private int joinedAt;

    // Who owns the pearl. Null when nobody can tell. Guesses and remembered owners only count
    // whilst PearlTracker allows them.
    public Owner ownerOf(ThrownEnderpearl pearl) {
        catchUp();
        Pearl seen = pearls.get(pearl.getId());
        return ownerOf(pearl.getUUID(), seen == null ? NO_OWNER : seen.ownerId);
    }

    // The same as a name. Null when nobody can tell.
    public String nameOf(ThrownEnderpearl pearl) {
        Owner owner = ownerOf(pearl);
        return owner == null ? null : owner.name();
    }

    // Every entity the server adds raises the newest id. Your own id starts the count on a join.
    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        Object wanted = switch (event.getPacket()) {
            case ClientboundLoginPacket login -> {
                newestRead = login.playerId();
                yield login;
            }
            case ClientboundAddEntityPacket add -> {
                newestRead = Math.max(newestRead, add.getId());
                yield add.getType() == EntityTypes.PLAYER || add.getType() == EntityTypes.ENDER_PEARL ? add : null;
            }
            case ClientboundPlayerInfoUpdatePacket info ->
                info.newEntries().isEmpty() ? null : new Listing(info.newEntries(), newestRead);
            case ClientboundRemoveEntitiesPacket remove -> remove;
            case ClientboundPlayerInfoRemovePacket left -> left;
            default -> null;
        };
        if (wanted != null) {
            waiting.offer(wanted);
        }
    }

    @Subscribe
    private void onClientTick(ClientTickEvent event) {
        tick++;
        catchUp();
        settle();
        if (MC.level != null) {
            sweep();
        }
    }

    // A frame may draw before the next tick. A lookup then reads the packets first.
    private void catchUp() {
        // A pearl left behind in another world never comes back with the same id.
        if (world.changed()) {
            pearls.clear();
        }
        Object next;
        while ((next = waiting.poll()) != null) {
            switch (next) {
                case ClientboundLoginPacket login -> joined(login.playerId());
                case ClientboundAddEntityPacket add -> added(add);
                case ClientboundRemoveEntitiesPacket remove -> removed(remove.entityIds());
                case Listing listing -> listed(listing);
                case ClientboundPlayerInfoRemovePacket left -> unlisted(left.profileIds());
                default -> {
                }
            }
        }
    }

    // A fresh join to a server. Entity ids start again and nobody is known yet.
    private void joined(int ownId) {
        players.clear();
        names.clear();
        pearls.clear();
        guessedIds.clear();
        guessedPearls.clear();
        arrived.clear();
        gone.clear();
        joins.clear();
        leaves.clear();
        joinedAt = tick;
        ClientPacketListener connection = MC.getConnection();
        GameProfile own = connection == null ? null : connection.getLocalGameProfile();
        self = own == null ? null : own.id();
        if (own != null) {
            players.put(ownId, own.id());
            names.put(own.id(), own.name());
        }
    }

    private void added(ClientboundAddEntityPacket add) {
        if (add.getType() == EntityTypes.PLAYER) {
            players.put(add.getId(), add.getUUID());
            if (MC.level != null && MC.level.getEntity(add.getId()) instanceof Player player) {
                names.put(add.getUUID(), player.getGameProfile().name());
            }
            return;
        }
        Pearl pearl = new Pearl(add.getUUID(), add.getData());
        pearls.put(add.getId(), pearl);
        if (unexplained(pearl)) {
            arrived.add(new Sighting(pearl, tick));
        }
    }

    private void removed(IntList ids) {
        for (int i = 0; i < ids.size(); i++) {
            Pearl pearl = pearls.remove(ids.getInt(i));
            if (pearl != null) {
                gone.add(new Sighting(pearl, tick));
            }
        }
    }

    // The tab list also hands over the names of everyone online as you join.
    private void listed(Listing listing) {
        for (ClientboundPlayerInfoUpdatePacket.Entry entry : listing.entries()) {
            Owner player = new Owner(entry.profileId(), entry.profile().name());
            names.put(player.id(), player.name());
            if (tick - joinedAt > SETTLE_TICKS && !player.id().equals(self)) {
                joins.add(new Join(player, tick, listing.newestBefore()));
            }
        }
    }

    private void unlisted(List<UUID> ids) {
        for (UUID id : ids) {
            if (!id.equals(self)) {
                leaves.add(new Leave(new Owner(id, names.get(id)), tick));
            }
        }
    }

    private void settle() {
        for (Join join : joins) {
            if (tick - join.tick() == JUDGE_AGE) {
                judgeJoin(join);
            }
        }
        arrived.removeIf(sighting -> tick - sighting.tick() > KEEP_TICKS);
        joins.removeIf(join -> tick - join.tick() > KEEP_TICKS);
        judgeLeave(tick - 1);
        gone.removeIf(sighting -> sighting.tick() < tick);
        leaves.removeIf(leave -> leave.tick() < tick);
    }

    // A joiner owns the pearls that turned up within the window of their join from the one
    // owner newer than every id read before they joined. Another join that fits as well leaves
    // them unexplained.
    private void judgeJoin(Join join) {
        int owner = soleOwner(arrived, sighting -> near(join.tick(), sighting)
            && sighting.pearl().ownerId > join.newestBefore());
        if (owner == NO_OWNER || joins.stream().anyMatch(other -> other != join
            && Math.abs(other.tick() - join.tick()) <= 2 * WINDOW && owner > other.newestBefore())) {
            return;
        }
        guessedIds.put(owner, join.player());
        for (Sighting sighting : arrived) {
            if (sighting.pearl().ownerId == owner && near(join.tick(), sighting)) {
                guessedPearls.put(sighting.pearl().uuid, join.player());
            }
        }
    }

    // The server drops a leaver's pearls in the very tick it drops their tab list entry. Those
    // pearls are theirs when exactly one player left in that tick and every pearl gone then
    // that nobody can explain shares one owner.
    private void judgeLeave(int ended) {
        int owner = soleOwner(gone, sighting -> sighting.tick() == ended);
        Leave leaver = only(leaves, leave -> leave.tick() == ended);
        if (owner == NO_OWNER || leaver == null || seen(leaver.player())) {
            return;
        }
        for (Sighting sighting : gone) {
            Pearl pearl = sighting.pearl();
            if (sighting.tick() != ended || pearl.ownerId != owner) {
                continue;
            }
            guessedPearls.put(pearl.uuid, leaver.player());
            if (pearl.resting && Modules.guessesPearlOwners()) {
                remember(pearl.uuid, leaver.player());
            }
        }
    }

    private static boolean near(int listed, Sighting sighting) {
        return Math.abs(listed - sighting.tick()) <= WINDOW;
    }

    // A pearl whose thrower this client has never seen.
    private boolean unexplained(Pearl pearl) {
        return pearl.ownerId != NO_OWNER && !players.containsKey(pearl.ownerId);
    }

    // A leaver this client has seen in view threw only pearls it can name already.
    private boolean seen(Owner player) {
        return players.containsValue(player.id());
    }

    // The owner id shared by every unexplained pearl that passes the test. A player has only the
    // one. No owner when none passes or they differ.
    private int soleOwner(List<Sighting> sightings, Predicate<Sighting> test) {
        int owner = NO_OWNER;
        for (Sighting sighting : sightings) {
            Pearl pearl = sighting.pearl();
            if (!unexplained(pearl) || !test.test(sighting)) {
                continue;
            }
            if (owner != NO_OWNER && owner != pearl.ownerId) {
                return NO_OWNER;
            }
            owner = pearl.ownerId;
        }
        return owner;
    }

    // The one that passes. Null when none does or more than one.
    private static <T> T only(List<T> listings, Predicate<T> test) {
        T found = null;
        for (T listing : listings) {
            if (!test.test(listing)) {
                continue;
            }
            if (found != null) {
                return null;
            }
            found = listing;
        }
        return found;
    }

    // Finds the pearls resting in stasis and keeps their owners for the next visit.
    private void sweep() {
        boolean remembering = Modules.remembersPearlOwners();
        for (Map.Entry<Integer, Pearl> entry : pearls.entrySet()) {
            if (!(MC.level.getEntity(entry.getKey()) instanceof ThrownEnderpearl loaded)) {
                continue;
            }
            Pearl pearl = entry.getValue();
            pearl.resting = ProjectileUtil.inStasis(loaded);
            if (pearl.resting && remembering) {
                Owner owner = ownerOf(pearl.uuid, pearl.ownerId);
                if (owner != null) {
                    remember(pearl.uuid, owner);
                }
            }
        }
    }

    private void remember(UUID pearl, Owner owner) {
        if (Modules.remembersPearlOwners() && owner.name() != null) {
            ledger.put(pearl, owner);
        }
    }

    private Owner ownerOf(UUID pearl, int ownerId) {
        UUID id = players.get(ownerId);
        if (id != null) {
            return named(id, null);
        }
        if (Modules.guessesPearlOwners()) {
            Owner guess = guessedIds.get(ownerId);
            if (guess == null) {
                guess = guessedPearls.get(pearl);
            }
            if (guess != null) {
                return named(guess.id(), guess.name());
            }
        }
        Owner kept = Modules.remembersPearlOwners() ? ledger.get(pearl) : null;
        return kept == null ? null : named(kept.id(), kept.name());
    }

    // The name in the tab list whilst they are online and the last one seen otherwise. Null
    // when no name was ever seen.
    private Owner named(UUID id, String lastName) {
        ClientPacketListener connection = MC.getConnection();
        PlayerInfo info = connection == null ? null : connection.getPlayerInfo(id);
        String name = info != null ? info.getProfile().name() : names.getOrDefault(id, lastName);
        return name == null ? null : new Owner(id, name);
    }
}
