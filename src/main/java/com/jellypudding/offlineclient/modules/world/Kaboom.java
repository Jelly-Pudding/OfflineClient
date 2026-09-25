package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockMiner;
import com.jellypudding.offlineclient.util.BlockUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

// The server keeps only one break at a time. The same block is sent over and over
// to force it through. That is a lot of packets and a strict server will kick you.
public final class Kaboom extends Module {

    // How far the blast reaches. Past this the server refuses the packets anyway.
    private static final double RADIUS = 6;

    private final NumberSetting power = new NumberSetting("Power",
        "How many times each block is hit.", 128, 32, 512, 32).min(1);
    private final NumberSetting rate = new NumberSetting("Packets a tick",
        "How many packets go out each tick. Fewer gives the server time to keep up.", 600, 100, 4000, 100).min(1);
    private final BoolSetting sound = new BoolSetting("Sound",
        "Play an explosion for yourself.", true);
    private final BoolSetting particles = new BoolSetting("Particles",
        "Show an explosion for yourself.", true);

    private final List<BlockPos> targets = new ArrayList<>();
    private int roundsLeft;
    private int next;

    public Kaboom() {
        super("Kaboom", "Blows a hole around you in one go.", Category.WORLD);
        addSettings(power, rate, sound, particles);
        searchTags("explode", "boom", "instant mine");
    }

    @Override
    public boolean savesEnabledState() {
        return false;
    }

    @Override
    public String getSuffix() {
        return count(roundsLeft, "left");
    }

    @Override
    protected void onEnable() {
        targets.clear();
        next = 0;
        roundsLeft = 0;
        if (!inGame()) {
            setEnabled(false);
            return;
        }
        if (!mc.player.onGround() && !mc.player.isCreative()) {
            disable("Stand on something first.");
            return;
        }
        gather();
        if (targets.isEmpty()) {
            disable("There is nothing to break here.");
            return;
        }
        show();
        roundsLeft = power.getInt();
    }

    @Override
    protected void onDisable() {
        targets.clear();
        roundsLeft = 0;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            setEnabled(false);
            return;
        }
        int budget = rate.getInt();
        while (budget > 0 && roundsLeft > 0) {
            BlockMiner.breakInstantly(targets.get(next));
            budget--;
            if (++next >= targets.size()) {
                next = 0;
                roundsLeft--;
            }
        }
        if (roundsLeft == 0) {
            setEnabled(false);
        }
    }

    // Furthest first. The walls go before the floor and you do not fall out of reach.
    private void gather() {
        for (BlockPos pos : BlockUtil.positionsWithin(RADIUS).reversed()) {
            if (!BlockUtil.state(pos).isAir()) {
                targets.add(pos);
            }
        }
    }

    private void show() {
        Vec3 middle = mc.player.position();
        if (sound.isOn()) {
            mc.level.playLocalSound(middle.x, middle.y, middle.z,
                SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 4f, 1f, false);
        }
        if (particles.isOn()) {
            mc.level.addParticle(ParticleTypes.EXPLOSION_EMITTER,
                middle.x, middle.y, middle.z, 1, 0, 0);
        }
    }
}
