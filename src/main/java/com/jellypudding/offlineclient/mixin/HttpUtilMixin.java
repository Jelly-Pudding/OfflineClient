package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.misc.Proxy;
import com.jellypudding.offlineclient.modules.misc.ServerSpoof;
import com.jellypudding.offlineclient.util.Modules;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.util.HttpUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;

// Server packs are the only downloads that come here.
@Mixin(HttpUtil.class)
public abstract class HttpUtilMixin {

    // A saved pack loads without a request. A server that hands two accounts the same pack can tell
    // from the missing download that they share one game.
    @Inject(
        method = "checkExistingFile(Ljava/nio/file/Path;Lcom/google/common/hash/HashFunction;Lcom/google/common/hash/HashCode;)Z",
        at = @At("HEAD"), cancellable = true)
    private static void onCheckExistingFile(CallbackInfoReturnable<Boolean> cir) {
        ServerSpoof spoof = Modules.get(ServerSpoof.class);
        if (spoof != null && spoof.freshPacks()) {
            cir.setReturnValue(false);
        }
    }

    // The pack is fetched from a web host the server picks. That host would see your own address
    // and the server could point the download at your own network. A saved pack never gets this far.
    @WrapOperation(
        method = "downloadFile(Ljava/nio/file/Path;Ljava/net/URL;Ljava/util/Map;"
            + "Lcom/google/common/hash/HashFunction;Lcom/google/common/hash/HashCode;ILjava/net/Proxy;"
            + "Lnet/minecraft/util/HttpUtil$DownloadProgressListener;)Ljava/nio/file/Path;",
        at = @At(value = "INVOKE", target = "Ljava/net/URL;openConnection(Ljava/net/Proxy;)Ljava/net/URLConnection;"))
    private static URLConnection openDownload(URL url, java.net.Proxy launcherProxy,
                                              Operation<URLConnection> original) throws IOException {
        Proxy proxy = Modules.active(Proxy.class);
        java.net.Proxy route = proxy == null ? launcherProxy : proxy.downloadRoute();
        ServerSpoof spoof = Modules.active(ServerSpoof.class);
        URLConnection guarded = spoof == null ? null : spoof.guardedDownload(url, route);
        return guarded == null ? original.call(url, route) : guarded;
    }
}
