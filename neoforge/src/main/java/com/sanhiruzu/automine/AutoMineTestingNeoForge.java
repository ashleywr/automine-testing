package com.sanhiruzu.automine;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * NeoForge entry point.
 *
 * <p>Exists because the pack this is used against (Synesthesia) is NeoForge and does <em>not</em>
 * ship Sinytra Connector - it has Forgified Fabric API, which supplies Fabric API surfaces but does
 * not load {@code fabric.mod.json} mods. A Fabric build of this mod is silently ignored there.
 */
@Mod(value = AutoMineTesting.MOD_ID, dist = Dist.CLIENT)
public final class AutoMineTestingNeoForge {

    public AutoMineTestingNeoForge(IEventBus modBus) {
        // Must precede everything else - see AutoMineTestingFabric for why.
        AutoMinePlatform.setGameDir(FMLPaths.GAMEDIR.get());

        AutoMineTesting.startCommon();

        // Fabric's END_CLIENT_TICK maps to ClientTickEvent.Post on the game bus. The event carries
        // no client reference, unlike the Fabric callback, so it is fetched here.
        NeoForge.EVENT_BUS.addListener(
                (ClientTickEvent.Post event) -> ClientAutomation.onClientTick(Minecraft.getInstance()));
        NeoForge.EVENT_BUS.addListener((RenderFrameEvent.Post event) -> ClientPerformanceProbe.onFrame());

        // ClientCommands is deliberately Fabric-only: it is written against Fabric's
        // ClientCommandManager and FabricClientCommandSource. The HTTP server exposes the same
        // actions, and that is what drives automation, so the NeoForge build does without rather
        // than carrying a second command tree.
    }
}
