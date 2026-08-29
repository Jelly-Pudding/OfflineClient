package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.player.Player;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;

public final class Notifier extends Module {

    private enum Kind {
        ENTER,
        LEAVE,
        TOTEM
    }

    private record Pending(Kind kind, int entityId, UUID uuid) {
    }

    // Ticks after joining during which players already nearby stay quiet.
    private static final int JOIN_GRACE_TICKS = 40;

    // Packets held whilst the game thread is busy. Anything past this is dropped.
    private static final int MAX_PENDING = 4096;

    private static final int MAX_TRACKED = 512;

    private final BoolSetting visualRange = new BoolSetting("Visual range",
        "Say when a player enters or leaves your render distance.", true);
    private final BoolSetting totemPops = new BoolSetting("Totem pops",
        "Say when a player pops a totem and how many so far.", true);
    private final BoolSetting ownTotems = new BoolSetting("Own totems",
        "Also count your own totem pops.", false)
        .under(totemPops);
    private final BoolSetting ignoreFriends = new BoolSetting("Ignore friends",
        "Stay quiet about people on your friend list.", false);
    private final BoolSetting sound = new BoolSetting("Sound",
        "Play a soft ping with every message.", false);

    private final Queue<Pending> queue = new LinkedBlockingQueue<>(MAX_PENDING);
    // Entity id to name for every player the server has sent.
    private final Map<Integer, String> names = bounded(MAX_TRACKED);
    // Totem pops per player since their last death.
    private final Map<UUID, Integer> pops = bounded(MAX_TRACKED);
    private ClientLevel lastLevel;

    /**
     * The client removes some entities without a packet. Neither map can be
     * trusted to empty itself.
     */
    private static <K, V> Map<K, V> bounded(int max) {
        return new LinkedHashMap<K, V>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > max;
            }
        };
    }

    public Notifier() {
        super("Notifier", "Chat messages when players come and go and when totems pop.", Category.MISC);
        addSettings(visualRange, totemPops, ownTotems, ignoreFriends, sound);
        searchTags("visual range", "totem pop", "alert");
    }

    @Override
    protected void onEnable() {
        reset();
    }

    @Override
    protected void onDisable() {
        reset();
    }

    private void reset() {
        queue.clear();
        names.clear();
        pops.clear();
        lastLevel = null;
    }

    // Fired on the netty thread. The level must not be read here.
    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        switch (event.getPacket()) {
            case ClientboundAddEntityPacket packet when packet.getType() == EntityTypes.PLAYER ->
                queue.offer(new Pending(Kind.ENTER, packet.getId(), packet.getUUID()));
            case ClientboundRemoveEntitiesPacket packet -> {
                for (int id : packet.getEntityIds()) {
                    queue.offer(new Pending(Kind.LEAVE, id, null));
                }
            }
            case ClientboundEntityEventPacket packet when packet.getEventId() == EntityEvent.PROTECTED_FROM_DEATH ->
                queue.offer(new Pending(Kind.TOTEM, packet.entityId, null));
            default -> {
            }
        }
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            return;
        }
        if (mc.level != lastLevel) {
            // A new dimension resends every entity. Queued ids belong to the old one.
            queue.clear();
            names.clear();
            pops.clear();
            lastLevel = mc.level;
        }
        Pending pending;
        while ((pending = queue.poll()) != null) {
            switch (pending.kind()) {
                case ENTER -> handleEnter(pending);
                case LEAVE -> handleLeave(pending);
                case TOTEM -> handleTotem(pending);
            }
        }
        if (totemPops.isOn()) {
            checkDeaths();
        }
    }

    private void handleEnter(Pending pending) {
        String name = nameOf(pending);
        if (name == null) {
            return;
        }
        names.put(pending.entityId(), name);
        // Everyone already nearby shows up in one burst right after joining.
        if (mc.player.tickCount < JOIN_GRACE_TICKS) {
            return;
        }
        if (visualRange.isOn() && !skip(name)) {
            say("§b" + name + " §7entered your render distance.");
        }
    }

    private void handleLeave(Pending pending) {
        String name = names.remove(pending.entityId());
        if (name == null) {
            return;
        }
        if (visualRange.isOn() && !skip(name)) {
            say("§b" + name + " §7left your render distance.");
        }
    }

    private void handleTotem(Pending pending) {
        if (!totemPops.isOn() || !(mc.level.getEntity(pending.entityId()) instanceof Player player)) {
            return;
        }
        if (player == mc.player && !ownTotems.isOn()) {
            return;
        }
        String name = player.getGameProfile().name();
        if (player != mc.player && skip(name)) {
            return;
        }
        int count = pops.merge(player.getUUID(), 1, Integer::sum);
        say(label(player) + "popped " + describe(count) + ".");
    }

    private void checkDeaths() {
        if (pops.isEmpty()) {
            return;
        }
        for (Player player : mc.level.players()) {
            Integer count = pops.get(player.getUUID());
            if (count == null || !(player.isDeadOrDying() || player.deathTime > 0)) {
                continue;
            }
            pops.remove(player.getUUID());
            say(label(player) + "died after popping " + describe(count) + ".");
        }
    }

    private String label(Player player) {
        return player == mc.player ? "§7You " : "§b" + player.getGameProfile().name() + " §7";
    }

    private static String describe(int count) {
        return count == 1 ? "§b1 §7totem" : "§b" + count + " §7totems";
    }

    // The tab list usually knows the name before the entity exists.
    private String nameOf(Pending pending) {
        if (mc.getConnection() != null && pending.uuid() != null) {
            PlayerInfo info = mc.getConnection().getPlayerInfo(pending.uuid());
            if (info != null) {
                return info.getProfile().name();
            }
        }
        if (mc.level.getEntity(pending.entityId()) instanceof Player player) {
            return player.getGameProfile().name();
        }
        return null;
    }

    private boolean skip(String name) {
        return ignoreFriends.isOn() && OfflineClient.INSTANCE.getFriendManager().isFriend(name);
    }

    private void say(String message) {
        ChatUtil.message(message);
        if (sound.isOn()) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1f));
        }
    }
}
