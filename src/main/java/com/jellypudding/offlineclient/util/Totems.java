package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.EntityDeathEvent;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.event.events.TotemPopEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;

// The totems you carry and the ones each player has popped since they last died. The pops
// are counted afresh in every new world.
public final class Totems {

    public static final Totems INSTANCE = new Totems();

    // Pops held whilst the game thread is busy. Anything past this is dropped.
    private static final int MAX_PENDING = 4096;

    private static final int MAX_TRACKED = 512;

    // Entity ids from the netty thread. The level must not be read there.
    private final Queue<Integer> waiting = new LinkedBlockingQueue<>(MAX_PENDING);
    // The client removes players without a packet. The map cannot be trusted to empty itself.
    private final Map<UUID, Integer> pops = new BoundedMap<>(MAX_TRACKED);
    private final WorldWatch world = new WorldWatch();

    private Totems() {
    }

    // Every totem you carry. The one in your offhand counts.
    public static int carried() {
        LocalPlayer player = OfflineClient.MC.player;
        if (player == null) {
            return 0;
        }
        ItemStack offhand = player.getOffhandItem();
        return (offhand.is(Items.TOTEM_OF_UNDYING) ? offhand.getCount() : 0)
            + InventoryUtil.count(Items.TOTEM_OF_UNDYING, InventoryUtil.WHOLE_INVENTORY);
    }

    // Totems the player has popped since they last died.
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

    // Counts the pops the netty thread has passed on. Ids from the last world name nothing here.
    // The pops of a player who is dying go into the death.
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
