package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.EntityDamageEvent;
import com.jellypudding.offlineclient.event.events.EntityDeathEvent;
import com.jellypudding.offlineclient.event.events.KillEvent;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

// Your kills and deaths as the server reports them. A player who dies soon after you
// hurt them is your kill. The counts start again when you join another server.
public final class KillTracker {

    public static final KillTracker INSTANCE = new KillTracker();

    private static final Minecraft MC = OfflineClient.MC;

    // The server itself credits a kill to whoever hurt the victim in the last hundred ticks.
    private static final int CREDIT_TICKS = 100;

    // A bed or anchor blast names nobody. One you clicked this recently counts as yours.
    private static final int BLAST_TICKS = 20;

    // A bed blows up from its head half. The half you clicked may be a block away.
    private static final double BLAST_REACH = 1.5;

    // Victims by entity id with the tick you last hurt them.
    private final Map<Integer, Long> hurtAt = new HashMap<>();

    // Beds and anchors you clicked with the tick of the click.
    private final Map<BlockPos, Long> charges = new HashMap<>();

    private final WorldWatch world = new WorldWatch();
    private final ServerWatch server = new ServerWatch();
    private long ticks;
    private int kills;
    private int deaths;
    private int streak;
    private int bestStreak;

    private KillTracker() {
    }

    public int kills() {
        return kills;
    }

    public int deaths() {
        return deaths;
    }

    // Kills since your last death.
    public int streak() {
        return streak;
    }

    public int bestStreak() {
        return bestStreak;
    }

    // Kills for each death. With no deaths yet it is the kills themselves.
    public double ratio() {
        return (double) kills / Math.max(1, deaths);
    }

    @Subscribe
    private void onTick(TickEvent event) {
        ticks++;
        checkServer();
        // Entity ids from another world mean nothing here.
        if (world.changed()) {
            hurtAt.clear();
            charges.clear();
        }
        hurtAt.values().removeIf(at -> ticks - at > CREDIT_TICKS);
        charges.values().removeIf(at -> ticks - at > BLAST_TICKS);
    }

    // The counts belong to one server and start again on any other.
    private void checkServer() {
        if (server.changed()) {
            kills = 0;
            deaths = 0;
            streak = 0;
            bestStreak = 0;
        }
    }

    @Subscribe
    private void onPacketSend(PacketSendEvent event) {
        if (!(event.getPacket() instanceof ServerboundUseItemOnPacket packet)
            || !MC.isSameThread() || MC.level == null) {
            return;
        }
        BlockPos pos = packet.hitResult().getBlockPos();
        Block block = MC.level.getBlockState(pos).getBlock();
        if (block instanceof BedBlock || block instanceof RespawnAnchorBlock) {
            charges.put(pos.immutable(), ticks);
        }
    }

    @Subscribe
    private void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player victim && victim != MC.player && byYou(event.getSource())) {
            hurtAt.put(victim.getId(), ticks);
        }
    }

    private boolean byYou(DamageSource source) {
        if (MC.player != null && source.getEntity() == MC.player) {
            return true;
        }
        Vec3 blast = source.sourcePositionRaw();
        if (blast == null || !source.is(DamageTypes.BAD_RESPAWN_POINT)) {
            return false;
        }
        for (BlockPos clicked : charges.keySet()) {
            if (Vec3.atCenterOf(clicked).distanceTo(blast) <= BLAST_REACH) {
                return true;
            }
        }
        return false;
    }

    @Subscribe
    private void onDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        checkServer();
        if (player == MC.player) {
            deaths++;
            streak = 0;
            return;
        }
        if (hurtAt.remove(player.getId()) == null) {
            return;
        }
        kills++;
        streak++;
        bestStreak = Math.max(bestStreak, streak);
        OfflineClient.INSTANCE.getEventBus().post(new KillEvent(player, streak));
    }
}
