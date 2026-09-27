package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.KeyPressEvent;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.jellypudding.offlineclient.event.events.RightClickEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.ExclusivityGroup;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.KeybindSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

// Lets a dead player walk and look about until they respawn. GuiMixin keeps the death
// screen shut and LivingEntityMixin frees the body. The server throws away whatever a
// dead player does.
public final class Ghost extends Module {

    private final KeybindSetting respawnKey = new KeybindSetting("Respawn key",
        "Press to respawn whilst you are a ghost.", InputConstants.KEY_R);
    private final BoolSetting deathButton = new BoolSetting("Death screen button",
        "Adds a button to the death screen that turns this on when you die with it off.", true);

    // The body the hint went to and the one a respawn was asked for. A respawn brings a new one.
    private LocalPlayer greeted;
    private LocalPlayer respawning;

    public Ghost() {
        super("Ghost", "Lets you walk and look around after you die whilst the server still sees you dead.",
            Category.PLAYER);
        addSettings(respawnKey, deathButton);
        searchTags("death", "dead", "respawn", "death screen");
    }

    @Override
    public ExclusivityGroup getExclusivityGroup() {
        return ExclusivityGroup.RESPAWN;
    }

    // Read by DeathScreenMixin whilst the module is off.
    public boolean showsButton() {
        return deathButton.isOn();
    }

    // True whilst you are dead and walking about. Read by the mixins that free the body.
    public boolean walking() {
        LocalPlayer player = mc.player;
        return isEnabled() && player != null && player.isDeadOrDying();
    }

    @Override
    protected void onEnable() {
        if (mc.gui.screen() instanceof DeathScreen) {
            mc.gui.setScreen(null);
        }
    }

    // The death screen comes back with its own respawn button.
    @Override
    protected void onDisable() {
        if (mc.player != null && mc.player.isDeadOrDying() && mc.gui.screen() == null) {
            mc.gui.setScreen(null);
        }
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!walking() || greeted == mc.player) {
            return;
        }
        greeted = mc.player;
        ChatUtil.message(respawnKey.isBound()
            ? "§7You are a ghost. Press §b" + respawnKey.getKeyName() + " §7to respawn."
            : "§7You are a ghost. Turn Ghost off to see the death screen.");
    }

    @Subscribe
    private void onKeyPress(KeyPressEvent event) {
        if (event.getAction() != InputConstants.PRESS || !respawnKey.isBound()
            || event.getKey() != respawnKey.getValue() || mc.gui.screen() != null) {
            return;
        }
        // One request is enough. The server answers it with a new body.
        if (walking() && respawning != mc.player) {
            respawning = mc.player;
            mc.player.respawn();
        }
    }

    // A dead player's clicks never reach the world. Blocks would only seem to change.
    // MinecraftMixin holds back the attack key.
    @Subscribe
    private void onRightClick(RightClickEvent event) {
        if (walking()) {
            event.cancel();
        }
    }

    // The server throws away every move a dead player sends.
    @Subscribe
    private void onPacketSend(PacketSendEvent event) {
        if (event.getPacket() instanceof ServerboundMovePlayerPacket && walking()) {
            event.cancel();
        }
    }
}
