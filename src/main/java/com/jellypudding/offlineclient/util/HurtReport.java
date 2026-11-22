package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.events.EntityDamageEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

// The server sends the hit before the health it took and each line waits a few ticks for the
// health.
public final class HurtReport {

    private record Hit(String words, float before, int due) {
    }

    // The new health reaches the client within a tick or two of the hit.
    private static final int SETTLE_TICKS = 3;

    // False leaves out harm that comes from nowhere such as a fall or fire.
    private final boolean everything;
    private final List<Hit> waiting = new ArrayList<>();
    private int tick;

    public HurtReport(boolean everything) {
        this.everything = everything;
    }

    public void hit(EntityDamageEvent event) {
        LocalPlayer player = OfflineClient.MC.player;
        if (event.getEntity() != player) {
            return;
        }
        String words = words(event.getSource(), player);
        if (words != null) {
            waiting.add(new Hit(words, EntityUtil.totalHealth(player), tick + SETTLE_TICKS));
        }
    }

    public List<String> tick() {
        tick++;
        LocalPlayer player = OfflineClient.MC.player;
        if (waiting.isEmpty() || player == null) {
            return List.of();
        }
        List<String> done = new ArrayList<>();
        Iterator<Hit> it = waiting.iterator();
        while (it.hasNext()) {
            Hit hit = it.next();
            if (tick < hit.due()) {
                continue;
            }
            it.remove();
            float lost = hit.before() - EntityUtil.totalHealth(player);
            done.add(hit.words() + (lost > 0 ? " for §c" + EntityUtil.hearts(lost) : "") + "§7.");
        }
        return done;
    }

    public void clear() {
        waiting.clear();
    }

    private String words(DamageSource source, Player player) {
        Entity attacker = source.getEntity();
        Entity direct = source.getDirectEntity();
        if (attacker == player) {
            return null;
        }
        Vec3 from = attacker != null ? attacker.position() : source.getSourcePosition();
        if (from == null) {
            return everything ? "§b" + ChatUtil.capitalised(source.typeHolder().unwrapKey()
                .map(key -> ChatUtil.words(key.identifier())).orElse("something")) + " §7hurt you" : null;
        }
        String who;
        if (attacker != null) {
            who = name(attacker);
        } else if (direct != null) {
            who = name(direct);
        } else {
            who = source.is(DamageTypeTags.IS_EXPLOSION) ? "An explosion" : "Something";
        }
        String with = attacker != null && direct != null && direct != attacker
            ? " §7with §f" + ChatUtil.withArticle(ChatUtil.words(direct.getType())) : "";
        return "§b" + who + " §7hit you" + with + " §7from §b"
            + Notice.away(player.position().distanceTo(from), player.position(), from) + "§7";
    }

    private static String name(Entity entity) {
        if (entity instanceof Player other) {
            return EntityUtil.displayNameOf(other);
        }
        return ChatUtil.capitalised(ChatUtil.withArticle(ChatUtil.words(entity.getType())));
    }
}
