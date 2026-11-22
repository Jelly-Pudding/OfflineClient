package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.Setting;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;

// The two rows that send what a module takes out of a menu onto the ground rather than into
// your inventory. Each owner keeps its own.
public final class ThrowOut {

    private final BoolSetting on;
    private final BoolSetting backwards;

    public ThrowOut(String description) {
        on = new BoolSetting("Throw out", description, false);
        backwards = new BoolSetting("Throw backwards", "Throws the pile behind you.", false).under(on);
    }

    public Setting<?>[] settings() {
        return new Setting<?>[] {on, backwards};
    }

    public boolean isOn() {
        return on.isOn();
    }

    // Button one throws the whole stack of the slot onto the ground. A result slot that fills
    // again is thrown until it stays empty. The look has to reach the server first.
    public void from(AbstractContainerMenu menu, int slot) {
        LocalPlayer player = OfflineClient.MC.player;
        if (backwards.isOn()) {
            FaceMode.SPAM.faceExact(player.getYRot() + 180f, player.getXRot(), RotationPriority.IDLE);
        }
        MenuClicks.click(menu, slot, 1, ContainerInput.THROW);
    }
}
