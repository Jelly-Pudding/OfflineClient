package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.stats.Stat;
import net.minecraft.stats.StatType;
import net.minecraft.stats.Stats;
import net.minecraft.stats.StatsCounter;

import java.util.function.Predicate;

// The player's own statistics kept fresh whilst something shows them. The server answers
// with what changed since its last answer and the game writes each answer into the counter
// the player carries. Every join brings a new counter and the first answer on it holds
// every statistic. A copy of that answer marks the start of the visit.
public final class PlayerStats {

    public static final PlayerStats INSTANCE = new PlayerStats();

    // Once nothing has shown the statistics for this long the asking stops.
    private static final int FORGET_TICKS = SharedConstants.TICKS_PER_SECOND;

    private static final long NEVER = Long.MIN_VALUE;

    private StatsCounter counter;
    // The counter as the first answer of the visit left it. Null until that answer.
    private Object2IntMap<Stat<?>> visitStart;
    private long ticks;
    private long wantedAt = NEVER;
    private long askedAt = NEVER;
    private long answeredAt = NEVER;
    private int refreshTicks;

    private PlayerStats() {
    }

    // Called whilst something shows the statistics. The server is asked this often.
    public void want(double refreshSeconds) {
        wantedAt = ticks;
        refreshTicks = (int) Math.round(refreshSeconds * SharedConstants.TICKS_PER_SECOND);
    }

    // True once the server has answered on this visit.
    public boolean ready() {
        return visitStart != null && OfflineClient.MC.player != null
            && OfflineClient.MC.player.getStats() == counter;
    }

    // Client ticks since the last answer. The time statistics grow by one each tick in between.
    public long ticksSinceAnswer() {
        return answeredAt == NEVER ? 0 : ticks - answeredAt;
    }

    // A statistic of the custom kind such as play time. Over the visit it is only what
    // changed since the visit began.
    public long custom(Identifier id, boolean visitOnly) {
        Stat<Identifier> stat = Stats.CUSTOM.get(id);
        return valueOf(stat, OfflineClient.MC.player.getStats().getValue(stat), visitOnly);
    }

    // Every statistic of one kind the counter holds whose subject passes the test added up.
    // The counter only ever changes on the game thread and this runs there too.
    @SuppressWarnings("unchecked")
    public <T> long sum(StatType<T> type, Predicate<T> counted, boolean visitOnly) {
        long total = 0;
        for (Object2IntMap.Entry<Stat<?>> entry : OfflineClient.MC.player.getStats().stats.object2IntEntrySet()) {
            Stat<?> stat = entry.getKey();
            if (stat.getType() == type && counted.test((T) stat.getValue())) {
                total += valueOf(stat, entry.getIntValue(), visitOnly);
            }
        }
        return total;
    }

    private long valueOf(Stat<?> stat, int now, boolean visitOnly) {
        return visitOnly && visitStart != null ? now - visitStart.getInt(stat) : now;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        ticks++;
        follow();
        boolean wanted = wantedAt != NEVER && ticks - wantedAt <= FORGET_TICKS;
        boolean due = askedAt == NEVER || ticks - askedAt >= refreshTicks;
        ClientPacketListener connection = OfflineClient.MC.getConnection();
        if (wanted && due && connection != null) {
            connection.send(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.REQUEST_STATS));
            askedAt = ticks;
        }
    }

    // The game has written an answer into the player's counter.
    public void onAnswer() {
        follow();
        answeredAt = ticks;
        if (visitStart == null) {
            visitStart = new Object2IntOpenHashMap<>(counter.stats);
        }
    }

    // A counter the player has not carried before means a new visit.
    private void follow() {
        StatsCounter now = OfflineClient.MC.player.getStats();
        if (now != counter) {
            counter = now;
            visitStart = null;
            askedAt = NEVER;
            answeredAt = NEVER;
        }
    }
}
