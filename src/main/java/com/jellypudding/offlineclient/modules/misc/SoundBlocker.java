package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Silences the sounds you pick. This sits on the sound engine rather than the
 * network so weather and other sounds the client makes itself are caught too.
 */
public final class SoundBlocker extends Module {

    private final RegistryListSetting<SoundEvent> sounds = new RegistryListSetting<>("Sounds",
        "The sounds to mute. Click to pick them.", BuiltInRegistries.SOUND_EVENT,
        List.of(SoundEvents.GENERIC_EXPLODE.value(), SoundEvents.RESPAWN_ANCHOR_DEPLETE.value()));
    private final BoolSetting blockAll = new BoolSetting("Block all",
        "Mute everything instead of the chosen sounds.", false);
    private final BoolSetting keepUi = new BoolSetting("Keep menus",
        "Lets button clicks and menu sounds through.", true)
        .visibleWhen(blockAll::isOn);
    private final BoolSetting keepMusic = new BoolSetting("Keep music",
        "Lets the background music through.", true)
        .visibleWhen(blockAll::isOn);

    private final AtomicInteger muted = new AtomicInteger();

    public SoundBlocker() {
        super("SoundBlocker", "Mutes the sounds you pick.", Category.MISC);
        addSettings(sounds, blockAll, keepUi, keepMusic);
        searchTags("mute", "no sound", "silence", "crystal spam", "no rain");
    }

    @Override
    public String getSuffix() {
        int count = muted.get();
        return count == 0 ? null : count + " muted";
    }

    @Override
    protected void onEnable() {
        muted.set(0);
    }

    // Called from the sound engine before every sound starts.
    public boolean shouldMute(SoundInstance instance) {
        if (!isEnabled()) {
            return false;
        }
        if (blockAll.isOn()) {
            SoundSource source = instance.getSource();
            if (keepUi.isOn() && source == SoundSource.UI) {
                return false;
            }
            if (keepMusic.isOn() && source == SoundSource.MUSIC) {
                return false;
            }
        } else if (!sounds.getValue().contains(instance.getIdentifier())) {
            return false;
        }
        muted.incrementAndGet();
        return true;
    }
}
