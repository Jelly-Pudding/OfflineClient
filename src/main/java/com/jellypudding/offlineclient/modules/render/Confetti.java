package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.SlainEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.AlertSound;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.SimpleAnimatedParticle;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.List;

// A pop starts an emitter that sprays totem particles around the entity for a second and a half.
// TrackingEmitterMixin marks each of its ticks and ParticleEngineMixin hands every particle here.
public final class Confetti extends Module {

    public enum KillMark { LIGHTNING, PARTICLES }

    private static final double BURST_SPEED = 0.25;

    private final BoolSetting others = new BoolSetting("Others",
        "Turns the totem pops of everyone around you into confetti.", true);
    private final BoolSetting self = new BoolSetting("Self",
        "Turns your own totem pops into confetti.", true);
    private final BoolSetting kills = new BoolSetting("Kills",
        "Marks the spot where something you killed dies with an effect only you see.", false);
    private final EnumSetting<KillMark> mark = new EnumSetting<>("Kill effect",
        "What shows where the body falls.", KillMark.LIGHTNING)
        .describe(KillMark.LIGHTNING, "A lightning bolt with its thunder.")
        .describe(KillMark.PARTICLES, "A burst of the particles you pick. Totem particles come out as confetti.")
        .under(kills);
    private final RegistryListSetting<ParticleType<?>> particles = new RegistryListSetting<>("Kill particles",
        "The particles of the burst. Kinds that need a block or colour are skipped.",
        BuiltInRegistries.PARTICLE_TYPE, List.of(ParticleTypes.TOTEM_OF_UNDYING))
        .under(mark, KillMark.PARTICLES);
    private final NumberSetting count = new NumberSetting("Particle count",
        "How many of each picked particle the burst throws.", 40, 5, 200, 5).min(1)
        .under(mark, KillMark.PARTICLES);
    private final BoolSetting mobs = new BoolSetting("Mobs too",
        "Marks the mobs you kill as well as the players.", true)
        .under(kills);
    private final AlertSound alarm = new AlertSound("Kill", "Plays a sound when something you killed dies.",
        SoundEvents.EXPERIENCE_ORB_PICKUP).under(kills);

    private boolean painting;

    public Confetti() {
        super("Confetti", "Turns totem pops into colourful confetti and marks your kills on your screen only.",
            Category.RENDER);
        addSettings(others, self, kills, mark, particles, count, mobs);
        addSettings(alarm.settings());
        searchTags("totem", "pop", "particles", "party", "kill effect", "lightning");
    }

    @Override
    protected void onDisable() {
        painting = false;
        alarm.stop();
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

    @Subscribe
    private void onSlain(SlainEvent event) {
        Entity victim = event.getVictim();
        if (!kills.isOn() || !inGame() || !(victim instanceof Player || mobs.isOn())) {
            return;
        }
        alarm.ring();
        Vec3 spot = victim.position();
        if (mark.is(KillMark.LIGHTNING)) {
            strike(spot);
        } else {
            burst(spot.add(0, victim.getBbHeight() / 2, 0));
        }
    }

    // A bolt that exists on this client alone. Its own tick plays the thunder and takes it away.
    private void strike(Vec3 spot) {
        LightningBolt bolt = new LightningBolt(EntityTypes.LIGHTNING_BOLT, mc.level);
        bolt.setId(EntityUtil.nextLocalId());
        bolt.setVisualOnly(true);
        bolt.snapTo(spot);
        mc.level.addEntity(bolt);
    }

    private void burst(Vec3 centre) {
        RandomSource random = mc.level.getRandom();
        try {
            for (ParticleType<?> type : particles.resolved()) {
                if (!(type instanceof ParticleOptions options)) {
                    continue;
                }
                painting = type == ParticleTypes.TOTEM_OF_UNDYING;
                for (int i = 0; i < count.getInt(); i++) {
                    mc.level.addAlwaysVisibleParticle(options, centre.x, centre.y, centre.z,
                        random.nextGaussian() * BURST_SPEED, random.nextGaussian() * BURST_SPEED,
                        random.nextGaussian() * BURST_SPEED);
                }
            }
        } finally {
            painting = false;
        }
    }
}
