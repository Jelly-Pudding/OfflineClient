package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundCommandSuggestionsPacket;
import net.minecraft.network.protocol.game.ServerboundCommandSuggestionPacket;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

// The plugins a server gives away. A plugin's commands carry its name before a colon and the
// version command offers every plugin name as you type after it.
public final class ServerPlugins {

    // The server's own namespaces. They name no plugin.
    private static final Set<String> SERVER_OWN = Set.of("minecraft", "bukkit", "spigot", "paper");

    private static final String ASK = "bukkit:version ";

    // The chat screen counts its requests up from nought. A negative id never meets its answers.
    private static final int ASK_ID = -100;

    // The game answers within a tick or two. A server that drops the request gets this long.
    private static final int WAIT_SECONDS = 5;

    private ServerPlugins() {
    }

    // Empty off a server. Game thread only.
    public static List<String> roots() {
        ClientPacketListener connection = OfflineClient.MC.getConnection();
        List<String> names = new ArrayList<>();
        if (connection == null) {
            return names;
        }
        for (CommandNode<?> node : connection.getCommands().getRoot().getChildren()) {
            names.add(node.getName().toLowerCase(Locale.ROOT));
        }
        return names;
    }

    // Game thread only.
    public static Set<String> fromCommands() {
        Set<String> names = new TreeSet<>();
        for (String root : roots()) {
            int colon = root.indexOf(':');
            if (colon > 0 && !SERVER_OWN.contains(root.substring(0, colon))) {
                names.add(root.substring(0, colon));
            }
        }
        return names;
    }

    // Empty when the server keeps the names from you or does not answer in time. Completes on the
    // game thread.
    public static CompletableFuture<List<String>> ask() {
        ClientPacketListener connection = OfflineClient.MC.getConnection();
        if (connection == null) {
            return CompletableFuture.completedFuture(List.of());
        }
        Request request = new Request();
        OfflineClient.INSTANCE.getEventBus().register(request);
        connection.send(new ServerboundCommandSuggestionPacket(ASK_ID, ASK));
        return request.answer.completeOnTimeout(List.of(), WAIT_SECONDS, TimeUnit.SECONDS)
            .whenComplete((names, error) -> OfflineClient.INSTANCE.getEventBus().unregister(request))
            .thenApplyAsync(names -> names, OfflineClient.MC);
    }

    // Listens for the answer to one request. It is registered only whilst it waits.
    private static final class Request {

        private final CompletableFuture<List<String>> answer = new CompletableFuture<>();

        @Subscribe
        private void onPacketReceive(PacketReceiveEvent event) {
            if (event.getPacket() instanceof ClientboundCommandSuggestionsPacket packet && packet.id() == ASK_ID) {
                event.cancel();
                answer.complete(packet.suggestions().stream().map(ClientboundCommandSuggestionsPacket.Entry::text)
                    .toList());
            }
        }
    }
}
