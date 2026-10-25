package com.sanhiruzu.automine;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;

public final class AutoMineTestingFabric implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // Must precede everything else: ClientAutomation resolves its automation paths from a
        // static field initialised on first class load, so the game directory has to be in place
        // before anything can touch that class.
        AutoMinePlatform.setGameDir(FabricLoader.getInstance().getGameDir());

        AutoMineTesting.startCommon();
        ClientTickEvents.END_CLIENT_TICK.register(ClientAutomation::onClientTick);
        ClientCommands.register();
    }
}
