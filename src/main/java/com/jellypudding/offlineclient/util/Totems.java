package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.EntityDeathEvent;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.event.events.TotemPopEvent;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;

import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;

// The pops are counted afresh in every new world.
public final class Totems {

    public static final Totems INSTANCE = new Totems();

    private static final int MAX_PENDING = 4096;

    private static final int MAX_TRACKED = 512;

    // Entity ids from the netty thread. The level must not be read there.
    private final Queue<Integer> waiting = new LinkedBlockingQueue<>(MAX_PENDING);
    // The client removes players without a packet. The map cannot be trusted to empty itself.
    private final Map<UUID, Integer> pops = new BoundedMap<>(MAX_TRACKED);
    private final WorldWatch world = new WorldWatch();

    private Totems() {
    }

    public static int carried() {
        if (OfflineClient.MC.player == null) {
            return 0;
        }
        return InventoryUtil.countWithOffhand(Items.TOTEM_OF_UNDYING, InventoryUtil.WHOLE_INVENTORY);
    }

    // The game looks for death protection in both hands and a totem is only the usual one.
    public static boolean inHand(LivingEntity entity) {
        for (InteractionHand hand : InteractionHand.values()) {
            if (entity.getItemInHand(hand).has(DataComponents.DEATH_PROTECTION)) {
                return true;
            }
        }
        return false;
    }

    public int popped(Player player) {
        return pops.getOrDefault(player.getUUID(), 0);
    }

    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacket() instanceof ClientboundEntityEventPacket packet
            && packet.getEventId() == EntityEvent.PROTECTED_FROM_DEATH) {
            waiting.offer(packet.entityId);
        }
    }

    @Subscribe
    private void onTick(TickEvent event) {
        catchUp(null);
    }

    // A pop that came just before the death is counted into it and not posted on its own.
    @Subscribe
    private void onDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        catchUp(player);
        Integer count = pops.remove(player.getUUID());
        if (count != null) {
            post(new TotemPopEvent(player, count, true));
        }
    }

    // Ids from the last world name nothing here. The pops of a player who is dying go into the
    // death.
    private void catchUp(Player dying) {
        if (world.changed()) {
            waiting.clear();
            pops.clear();
        }
        Integer id;
        while ((id = waiting.poll()) != null) {
            if (OfflineClient.MC.level.getEntity(id) instanceof Player player) {
                int count = pops.merge(player.getUUID(), 1, Integer::sum);
                if (player != dying) {
                    post(new TotemPopEvent(player, count, false));
                }
            }
        }
    }

    private static void post(TotemPopEvent event) {
        OfflineClient.INSTANCE.getEventBus().post(event);
    }
}
