package com.sanhiruzu.automine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loader-independent core. Everything here compiles against vanilla Minecraft only.
 *
 * <p>The class keeps its original name because {@code AutoMineTesting.LOGGER} is referenced
 * throughout the shared code; the loader entry points are separate classes per source set
 * ({@code AutoMineTestingFabric}, {@code AutoMineTestingNeoForge}) and call {@link #startCommon()}.
 */
public final class AutoMineTesting {

    public static final String MOD_ID = "automine_testing";
    public static final Logger LOGGER = LoggerFactory.getLogger("AutoMineTesting");

    private AutoMineTesting() {
    }

    /**
     * Start the parts that are the same on every loader. The per-tick hook and any client commands
     * are registered by the entry point, because those are loader APIs.
     */
    public static void startCommon() {
        AutoMineHttpServer.start();
    }
}
