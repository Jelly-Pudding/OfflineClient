package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.List;

// Only particle types that need no extra data can be spawned from their type alone.
public final class Trail extends Module {

    private final RegistryListSetting<ParticleType<?>> particles = new RegistryListSetting<>("Particles",
        "The particles left at your feet each tick. Kinds that need a block or colour are skipped.",
        BuiltInRegistries.PARTICLE_TYPE,
        List.of(ParticleTypes.DRIPPING_OBSIDIAN_TEAR, ParticleTypes.CAMPFIRE_COSY_SMOKE));
    private final BoolSetting pauseWhenStill = new BoolSetting("Pause when still",
        "Nothing is left whilst you stand in one place.", true);

    public Trail() {
        super("Trail", "Leaves a trail of particles behind you.", Category.RENDER);
        addSettings(particles, pauseWhenStill);
        searchTags("particles", "footsteps");
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            return;
        }
        if (pauseWhenStill.isOn() && mc.player.getX() == mc.player.xo
            && mc.player.getY() == mc.player.yo && mc.player.getZ() == mc.player.zo) {
            return;
        }
        for (ParticleType<?> type : particles.resolved()) {
            if (type instanceof ParticleOptions options) {
                mc.level.addParticle(options, mc.player.getX(), mc.player.getY(), mc.player.getZ(), 0, 0, 0);
            }
        }
    }
}
