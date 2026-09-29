package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.util.BoundedMap;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Action;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// The one place that decides what a bot is. Combat modules skip a bot through Modules.isBot
// and ESP and Nametags and Tracers ask Modules.hidesBot. Every tell comes from the server.
// A player cannot fake one to slip past a combat module.
public final class AntiBot extends Module {

    // The render modules that can leave bots out.
    public enum View {
        ESP,
        NAMETAGS,
        TRACERS
    }

    // UUID versions a real account can have. An offline server makes 3 from the name and
    // Mojang hands out 4. Bedrock players who come in through Geyser carry 0.
    private static final Set<Integer> ACCOUNT_VERSIONS = Set.of(0, 3, 4);

    // A player who joined just before a ping update can show nothing in that one.
    private static final int SILENT_UPDATES = 2;

    private static final int MAX_TRACKED = 512;

    private final BoolSetting notInTab = new BoolSetting("Not in tab list",
        "A player the tab list has no entry for counts as a bot.", true);
    private final BoolSetting unlisted = new BoolSetting("Unlisted",
        "A player the server keeps off the tab list counts as a bot. Turn it off on a server that hides every player.",
        true);
    private final BoolSetting oddUuid = new BoolSetting("Odd UUID",
        "A player whose UUID no real account can have counts as a bot.", true);
    private final BoolSetting noPing = new BoolSetting("No ping",
        "A player whose ping stays at zero through two ping updates counts as a bot. Servers update pings about every half minute.",
        false);
    private final BoolSetting hideEsp = new BoolSetting("Hide from ESP",
        "Leaves bots out of ESP.", true);
    private final BoolSetting hideNametags = new BoolSetting("Hide from Nametags",
        "Gives bots no name tag. Off marks their tag with BOT.", true);
    private final BoolSetting hideTracers = new BoolSetting("Hide from Tracers",
        "Draws no tracer to a bot.", true);

    // Ping updates in a row that showed a player at zero. Filled on the netty thread.
    private final Map<UUID, Integer> silent = Collections.synchronizedMap(new BoundedMap<>(MAX_TRACKED));

    public AntiBot() {
        super("AntiBot", "Spots fake players the server spawns and keeps every combat module off them.",
            Category.COMBAT);
        addSettings(notInTab, unlisted, oddUuid, noPing, hideEsp, hideNametags, hideTracers);
        searchTags("bot", "npc", "fake player", "anticheat");
    }

    @Override
    protected void onDisable() {
        silent.clear();
    }

    // Our own FakePlayer bodies have no tab entry either. They stay fair game for practice.
    public boolean isBot(Entity entity) {
        if (!(entity instanceof Player player) || player == mc.player || Modules.isLocalBody(player)) {
            return false;
        }
        ClientPacketListener connection = mc.getConnection();
        if (connection == null) {
            return false;
        }
        UUID id = player.getUUID();
        if (oddUuid.isOn() && !ACCOUNT_VERSIONS.contains(id.version())) {
            return true;
        }
        PlayerInfo info = connection.getPlayerInfo(id);
        if (info == null) {
            return notInTab.isOn();
        }
        if (unlisted.isOn() && !connection.getListedOnlinePlayers().contains(info)) {
            return true;
        }
        return noPing.isOn() && silent.getOrDefault(id, 0) >= SILENT_UPDATES;
    }

    public boolean hides(View view, Entity entity) {
        BoolSetting hide = switch (view) {
            case ESP -> hideEsp;
            case NAMETAGS -> hideNametags;
            case TRACERS -> hideTracers;
        };
        return hide.isOn() && isBot(entity);
    }

    // Fired on the netty thread.
    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        switch (event.getPacket()) {
            case ClientboundLoginPacket _ -> silent.clear();
            case ClientboundPlayerInfoUpdatePacket packet when isPingUpdate(packet) -> countSilence(packet);
            case ClientboundPlayerInfoRemovePacket packet -> packet.profileIds().forEach(silent::remove);
            default -> {
            }
        }
    }

    // The server shares every ping in one update now and then. The packet that adds a
    // player carries a first ping as well and is left out.
    private static boolean isPingUpdate(ClientboundPlayerInfoUpdatePacket packet) {
        return packet.actions().contains(Action.UPDATE_LATENCY) && !packet.actions().contains(Action.ADD_PLAYER);
    }

    private void countSilence(ClientboundPlayerInfoUpdatePacket packet) {
        for (ClientboundPlayerInfoUpdatePacket.Entry entry : packet.entries()) {
            if (entry.latency() == 0) {
                silent.merge(entry.profileId(), 1, Integer::sum);
            } else {
                silent.remove(entry.profileId());
            }
        }
    }
}
