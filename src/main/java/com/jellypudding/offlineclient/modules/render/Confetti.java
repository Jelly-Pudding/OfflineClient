package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.util.ColorUtil;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.SimpleAnimatedParticle;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.Entity;

// A pop starts an emitter that sprays totem particles around the entity for a second and a half.
// TrackingEmitterMixin marks each of its ticks and ParticleEngineMixin hands every particle here.
public final class Confetti extends Module {

    private final BoolSetting others = new BoolSetting("Others",
        "Turns the totem pops of everyone around you into confetti.", true);
    private final BoolSetting self = new BoolSetting("Self",
        "Turns your own totem pops into confetti.", true);

    // True whilst an emitter chosen for confetti is making its particles.
    private boolean painting;

    public Confetti() {
        super("Confetti", "Turns totem pops into bursts of colourful confetti that only you see.",
            Category.RENDER);
        addSettings(others, self);
        searchTags("totem", "pop", "particles", "party");
    }

    @Override
    protected void onDisable() {
        painting = false;
    }

    public void beginBurst(Entity entity, ParticleOptions particles) {
        painting = particles.getType() == ParticleTypes.TOTEM_OF_UNDYING
            && (entity == mc.player ? self.isOn() : others.isOn());
    }

    public void endBurst() {
        painting = false;
    }

    public void paint(Particle particle) {
        if (painting && particle instanceof SimpleAnimatedParticle piece) {
            piece.setColor(ColorUtil.randomBright());
        }
    }
}
