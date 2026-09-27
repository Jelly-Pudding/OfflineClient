package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.command.CommandManager;
import com.jellypudding.offlineclient.config.DataFiles;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.ClientAsset;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.entity.player.PlayerSkin;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;

// A server hands out skins in the textures property of each profile in the tab list. An offline
// server only fills it in through a skin plugin and without one everyone wears a default skin.
// A downloaded skin keeps its pixels in memory. A default one is read from the resource packs.
public final class SaveSkinCommand extends Command {

    private static final String FOLDER = "skins";
    private static final String PNG = ".png";
    private static final String CAPE = "_cape";

    public SaveSkinCommand() {
        super("saveskin", "Saves the skin of a player in the tab list to a picture file.",
            "saveskin [player]", "skin");
    }

    @Override
    public void execute(String[] args) {
        Minecraft mc = OfflineClient.MC;
        ClientPacketListener connection = mc.getConnection();
        if (connection == null || mc.player == null) {
            return;
        }
        if (args.length > 1) {
            usage();
            return;
        }
        String wanted = args.length == 0 ? mc.player.getGameProfile().name() : args[0];
        PlayerInfo info = connection.getPlayerInfoIgnoreCase(wanted);
        if (info == null) {
            ChatUtil.error("Nobody called " + wanted + " is in the tab list.");
            return;
        }
        String name = info.getProfile().name();
        // A tab entry answers with the default skin until its download is done and hides unsigned
        // skins. The skin manager hands the skin over once it has arrived.
        mc.getSkinManager().get(info.getProfile()).thenAcceptAsync(skin -> skin.ifPresentOrElse(
            found -> saveAll(name, found),
            () -> ChatUtil.error("The skin of " + name + " could not be downloaded. The log says why.")), mc);
    }

    private static void saveAll(String name, PlayerSkin skin) {
        String fileName = DataFiles.safeName(name);
        Path file = DataFiles.path(FOLDER, fileName + PNG);
        if (!save(skin.body(), file)) {
            ChatUtil.error("The skin of " + name + " could not be saved. The log says why.");
            return;
        }
        ChatUtil.component(Component.literal("§7Saved the skin of §f" + name + " §7to ")
            .append(ChatUtil.fileLink(file)).append("§7."));
        if (skin.body() instanceof ClientAsset.ResourceTexture) {
            ChatUtil.message("§7This server sends no skin for them. That is the default skin the game picked.");
        }
        Path capeFile = DataFiles.path(FOLDER, fileName + CAPE + PNG);
        if (skin.cape() != null && save(skin.cape(), capeFile)) {
            ChatUtil.component(Component.literal("§7Their cape went beside it as ")
                .append(ChatUtil.fileLink(capeFile)).append("§7."));
        }
    }

    // False after logging why the file could not be written.
    private static boolean save(ClientAsset.Texture texture, Path file) {
        if (texture instanceof ClientAsset.DownloadedTexture) {
            AbstractTexture loaded = OfflineClient.MC.getTextureManager().getTexture(texture.texturePath());
            NativeImage pixels = loaded instanceof DynamicTexture dynamic ? dynamic.getPixels() : null;
            if (pixels == null || pixels.isClosed()) {
                OfflineClient.LOG.warn("The texture {} holds no pixels", texture.texturePath());
                return false;
            }
            return DataFiles.writeSafely(file, pixels::writeToFile);
        }
        Optional<Resource> resource = OfflineClient.MC.getResourceManager().getResource(texture.texturePath());
        if (resource.isEmpty()) {
            OfflineClient.LOG.warn("No resource pack holds {}", texture.texturePath());
            return false;
        }
        // A failed write leaves its temporary file behind for the next try to replace.
        return DataFiles.writeSafely(file, temp -> {
            try (InputStream in = resource.get().open()) {
                Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            }
        });
    }

    @Override
    public List<String> complete(String[] tokens, int index, String current) {
        ClientPacketListener connection = OfflineClient.MC.getConnection();
        if (index != 1 || connection == null) {
            return List.of();
        }
        List<String> names = connection.getOnlinePlayers().stream()
            .map(info -> info.getProfile().name())
            .toList();
        return CommandManager.filter(current, names);
    }
}
