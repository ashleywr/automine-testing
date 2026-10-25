package com.sanhiruzu.automine;

import java.nio.file.Path;

/**
 * The only thing the shared code needs from the mod loader: where the game directory is.
 *
 * <p>Fabric answers this with {@code FabricLoader.getInstance().getGameDir()} and NeoForge with
 * {@code FMLPaths.GAMEDIR.get()}. Rather than abstract over both with a service interface, the
 * loader entry point pushes the answer in before anything else runs. There is exactly one value
 * and one writer, so a holder is honest about the shape of the problem.
 */
public final class AutoMinePlatform {

    private static volatile Path gameDir;

    private AutoMinePlatform() {
    }

    public static void setGameDir(Path dir) {
        gameDir = dir;
    }

    /**
     * @throws IllegalStateException if called before the loader entry point ran. That is a wiring
     *     bug, not a runtime condition, and failing loudly beats silently resolving automation
     *     paths against the process working directory.
     */
    public static Path gameDir() {
        Path dir = gameDir;
        if (dir == null) {
            throw new IllegalStateException(
                    "AutoMinePlatform.gameDir() called before the loader entry point set it");
        }
        return dir;
    }
}
