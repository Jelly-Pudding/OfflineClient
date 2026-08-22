package com.jellypudding.offlineclient;

import net.fabricmc.api.ClientModInitializer;

public class OfflineClientMod implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        OfflineClient.INSTANCE.init();
    }
}
