package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerStatusPinger;
import net.minecraft.server.network.EventLoopGroupHolder;
import net.minecraft.util.Util;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

// The ask goes over a connection of its own and the answer comes back on the game thread.
public final class StatusPing {

    private static final int WAIT_TICKS = 10 * SharedConstants.TICKS_PER_SECOND;

    private StatusPing() {
    }

    // The callback gets null when the server did not answer.
    public static void ask(String address, Consumer<ServerData> answer) {
        ServerData asked = new ServerData("", address, ServerData.Type.OTHER);
        ServerStatusPinger pinger = new ServerStatusPinger();
        CompletableFuture<ServerData> pong = new CompletableFuture<>();
        OfflineClient.INSTANCE.getEventBus().register(new Wait(pinger, pong, answer));
        boolean nativeTransport = OfflineClient.MC.options.useNativeTransport();
        // The lookup and the connect block. The server list runs them off the game thread as well.
        Util.nonCriticalIoPool().execute(() -> {
            try {
                pinger.pingServer(asked, () -> {
                }, () -> pong.complete(asked), EventLoopGroupHolder.remote(nativeTransport));
            } catch (Exception e) {
                pong.complete(null);
            }
        });
    }

    private static final class Wait {

        private final ServerStatusPinger pinger;
        private final CompletableFuture<ServerData> pong;
        private final Consumer<ServerData> answer;
        private int ticksLeft = WAIT_TICKS;

        private Wait(ServerStatusPinger pinger, CompletableFuture<ServerData> pong, Consumer<ServerData> answer) {
            this.pinger = pinger;
            this.pong = pong;
            this.answer = answer;
        }

        @Subscribe
        private void onClientTick(ClientTickEvent event) {
            pinger.tick();
            if (!pong.isDone() && --ticksLeft > 0) {
                return;
            }
            OfflineClient.INSTANCE.getEventBus().unregister(this);
            pinger.removeAll();
            answer.accept(pong.getNow(null));
        }
    }
}
