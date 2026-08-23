package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Drops the incoming effect packets for the effects you pick. A blocked effect
 * keeps running on the server copy of you.
 */
public final class NoStatusEffects extends Module {

    private final RegistryListSetting<MobEffect> effects = new RegistryListSetting<>("Effects",
        "The effects to block. Click to pick them.", BuiltInRegistries.MOB_EFFECT,
        List.of(MobEffects.LEVITATION.value(), MobEffects.JUMP_BOOST.value(),
            MobEffects.SLOW_FALLING.value(), MobEffects.DOLPHINS_GRACE.value()));
    private final BoolSetting blockAll = new BoolSetting("Block all",
        "Block every effect instead of the chosen ones.", false);
    private final BoolSetting selfOnly = new BoolSetting("You only",
        "Leave the effects on other entities alone.", true);
    private final BoolSetting clearActive = new BoolSetting("Clear active",
        "Strip a blocked effect that is already running on you.", true);

    private final AtomicInteger blocked = new AtomicInteger();

    // Read on the network thread and written on the main thread.
    private volatile int localId = -1;

    public NoStatusEffects() {
        super("NoStatusEffects", "Stops the effects you pick from reaching you.", Category.PLAYER);
        addSettings(effects, blockAll, selfOnly, clearActive);
        searchTags("no levitation", "anti effect", "potion block");
    }

    @Override
    public String getSuffix() {
        int count = blocked.get();
        return count == 0 ? null : count + " blocked";
    }

    @Override
    protected void onEnable() {
        blocked.set(0);
        localId = mc.player == null ? -1 : mc.player.getId();
    }

    @Override
    protected void onDisable() {
        localId = -1;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            return;
        }
        localId = mc.player.getId();
        if (!clearActive.isOn()) {
            return;
        }
        List<Holder<MobEffect>> stale = null;
        for (MobEffectInstance instance : mc.player.getActiveEffects()) {
            if (isBlocked(instance.getEffect())) {
                if (stale == null) {
                    stale = new ArrayList<>();
                }
                stale.add(instance.getEffect());
            }
        }
        if (stale == null) {
            return;
        }
        // Removing inside the loop above would break the iterator.
        for (Holder<MobEffect> effect : stale) {
            mc.player.removeEffect(effect);
        }
    }

    @Subscribe(priority = 200)
    private void onPacketReceive(PacketReceiveEvent event) {
        if (!(event.getPacket() instanceof ClientboundUpdateMobEffectPacket packet)) {
            return;
        }
        if (selfOnly.isOn() && packet.getEntityId() != localId) {
            return;
        }
        if (isBlocked(packet.getEffect())) {
            event.cancel();
            blocked.incrementAndGet();
        }
    }

    // Called from the network thread as well as the main thread.
    private boolean isBlocked(Holder<MobEffect> effect) {
        if (blockAll.isOn()) {
            return true;
        }
        return effect.isBound() && effects.contains(effect.value());
    }
}
