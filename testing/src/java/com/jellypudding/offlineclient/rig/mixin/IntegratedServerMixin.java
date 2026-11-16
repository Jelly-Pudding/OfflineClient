package com.jellypudding.offlineclient.rig.mixin;

import net.minecraft.client.server.IntegratedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// The game stops its own server by handing it a job and waiting until the job is done. The test
// library lets the game and the server run in turns. A server that has had its turn never takes
// the job and the game waits for good. The job only sends away the guests of a world opened to
// LAN and the server sends everybody away again as it stops.
@Mixin(IntegratedServer.class)
public abstract class IntegratedServerMixin {

    @Redirect(method = "halt", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/server/IntegratedServer;executeBlocking(Ljava/lang/Runnable;)V"))
    private void rigNeverWaitsForServer(IntegratedServer server, Runnable job) {
        if (server.isSameThread()) {
            job.run();
        } else {
            server.execute(job);
        }
    }
}
