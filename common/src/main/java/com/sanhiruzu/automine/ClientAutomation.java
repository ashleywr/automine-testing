package com.sanhiruzu.automine;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.DataPackConfig;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public final class ClientAutomation {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path GAME_DIRECTORY = AutoMinePlatform.gameDir();
    private static final Path AUTOMATION_DIR = GAME_DIRECTORY.resolve("automine_testing");
    private static final Path COMMAND_FILE = AUTOMATION_DIR.resolve("command.json");
    private static final Path STATUS_FILE = AUTOMATION_DIR.resolve("status.json");
    private static final Path READY_FILE = AUTOMATION_DIR.resolve("ready.json");
    private static final Path DEFAULT_AMI_SEARCH_OUTPUT = AUTOMATION_DIR.resolve("ami_search_results.json");
    private static final int DEFAULT_SCREENSHOT_DELAY_TICKS = 3;
    private static final int DEFAULT_AMI_SEARCH_LIMIT = 100;
    private static final int DEFAULT_AMI_REINDEX_TIMEOUT_TICKS = 20 * 60;
    // RoadWeaver deliberately limits an explicit manual sample to 1,024 coarse tiles.
    // In the Mod Test window, 15 wheel steps keeps the continent-sized view below that cap.
    private static final int ROADWEAVER_MAX_SAMPLE_ZOOM_OUT_STEPS = 15;
    private static final boolean AUTO_OPEN_WORLD =
            Boolean.parseBoolean(System.getProperty("automine.autoOpenWorld", "false"));
    private static final boolean AUTO_CREATE_WORLD =
            Boolean.parseBoolean(System.getProperty("automine.createWorldIfMissing", "false"));
    private static final String AUTO_WORLD_NAME = System.getProperty("automine.worldName", "New World");
    private static final int AUTO_WORLD_RETRY_TICKS = 20 * 10;
    private static final int AUTO_WORLD_MAX_ATTEMPTS = 2;
    private static final String ROADWEAVER_MAP_SCREEN = "net.shiroha233.roadweaver.client.map.RoadMapScreen";

    private static PendingScreenshot pendingScreenshot;
    private static PendingRoadMapCapture pendingRoadMapCapture;
    private static PendingRoadMapResampleCapture pendingRoadMapResampleCapture;
    private static PendingAmiSearch pendingAmiSearch;
    private static int pendingClientStopTicks = -1;
    private static int pendingUseTicks;
    private static boolean previousUseDown;
    private static int statusTicks;
    private static String currentCommandId = "";
    private static String lastCommandId = "";
    private static int lastCommandStatusTicks;
    private static int autoWorldRetryTicks;
    private static int autoWorldAttempts;
    private static volatile String automationPhase = AUTO_OPEN_WORLD ? "starting" : "manual";
    private static volatile String automationMessage = "";
    private static volatile String lastStatusJson = "{}";
    private static volatile boolean automationActionFailed;
    private static volatile long worldLoadStartedAtMs = -1L;
    private static volatile long worldLoadFinishedAtMs = -1L;

    private ClientAutomation() {
    }

    public static String seedgenRegistrySnapshotJson() {
        return GSON.toJson(callOnClientThread(Minecraft.getInstance(), SeedgenWorldgenProbe::snapshot));
    }

    public static void onClientTick(Minecraft minecraft) {
        ensureAutomationDir();
        statusTicks++;
        if (pendingUseTicks > 0 && (--pendingUseTicks == 0 || minecraft.level == null
                || minecraft.player == null || minecraft.screen != null)) {
            pendingUseTicks = 0;
            minecraft.options.keyUse.setDown(previousUseDown);
        }
        dismissAccessibilityOnboarding(minecraft);
        processAutoWorldOpen(minecraft);
        if (statusTicks >= 20 && !hasPendingAutomationAction()) {
            statusTicks = 0;
            writeStatus("ready", null, null);
        }

        processPendingScreenshot(minecraft);
        processPendingRoadMapCapture(minecraft);
        processPendingRoadMapResampleCapture(minecraft);
        processPendingAmiSearch();
        processCommandFile(minecraft);
        processPendingClientStop(minecraft);
    }

    public static boolean openInventory() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            writeStatus("error", "Cannot open inventory before a world is loaded.", null);
            return false;
        }

        runOnClientThread(minecraft, () -> minecraft.setScreen(new InventoryScreen(minecraft.player)));
        writeStatus("ok", "Inventory screen opened.", null);
        return true;
    }

    public static void closeScreen() {
        Minecraft minecraft = Minecraft.getInstance();
        runOnClientThread(minecraft, () -> minecraft.setScreen(null));
        writeStatus("ok", "Screen closed.", null);
    }

    public static boolean isInWorld() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.level != null && minecraft.player != null;
    }

    public static boolean isWorldReadyForInteraction() {
        Minecraft minecraft = Minecraft.getInstance();
        return isInWorld() && !(minecraft.screen instanceof ReceivingLevelScreen);
    }

    public static boolean isReadyForWorldCreation() {
        Minecraft minecraft = Minecraft.getInstance();
        return !isInWorld() && minecraft.screen instanceof TitleScreen;
    }

    public static boolean requestClientStop() {
        if (pendingClientStopTicks >= 0) {
            return true;
        }
        pendingClientStopTicks = 5;
        writeStatus("stopping_client", "Closing the benchmark client after pending output is flushed.", null);
        return true;
    }

    public static boolean closeWorld() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!isInWorld()) {
            writeStatus("error", "Cannot close a world when no world is loaded.", null);
            return false;
        }

        automationPhase = "closing_world";
        automationMessage = "Closing the current world.";
        writeStatus("closing_world", automationMessage, null);
        // Match PauseScreen's disconnect sequence. Closing only the client
        // connection leaves an integrated server running, so Minecraft waits
        // forever for a shutdown that was never requested.
        minecraft.level.disconnect();
        minecraft.disconnect(new TitleScreen());
        automationPhase = "manual";
        automationMessage = "Returned to the title screen.";
        writeStatus("ok", automationMessage, null);
        return true;
    }

    public static void closeWorldAsync() {
        Minecraft minecraft = Minecraft.getInstance();
        automationPhase = "closing_world";
        automationMessage = "Closing the current world.";
        writeStatus("closing_world", automationMessage, null);
        minecraft.execute(() -> {
            try {
                closeWorld();
            } catch (RuntimeException | LinkageError e) {
                automationPhase = "error";
                automationMessage = "Failed to close world: " + rootMessage(e);
                writeStatus("error", automationMessage, null);
            }
        });
    }

    public static boolean openWorld(String worldName) {
        Minecraft minecraft = Minecraft.getInstance();
        String cleanWorldName = worldName == null ? "" : worldName.trim();
        if (cleanWorldName.isEmpty()) {
            writeStatus("error", "World name must not be blank.", null);
            return false;
        }
        if (isInWorld()) {
            automationPhase = "in_world";
            automationMessage = "World loaded.";
            writeStatus("ok", "Already in a world.", null);
            return true;
        }

        beginWorldLoad("opening_world", "Opening world: " + cleanWorldName);
        writeStatus("pending_world", "Opening world: " + cleanWorldName, null);
        minecraft.createWorldOpenFlows().openWorld(cleanWorldName, () ->
                minecraft.setScreen(new TitleScreen()));
        return true;
    }

    public static void openWorldAsync(String worldName) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            try {
                openWorld(worldName);
            } catch (RuntimeException | LinkageError e) {
                automationPhase = "error";
                automationMessage = "Failed to open world: " + rootMessage(e);
                writeStatus("error", automationMessage, null);
            }
        });
    }

    public static boolean createWorld(String worldName) {
        return createWorld(worldName, null);
    }

    public static void createWorldAsync(String worldName, Long seed, List<String> dataPacks) {
        createWorldAsync(worldName, seed, dataPacks, "");
    }

    public static void createWorldAsync(String worldName, Long seed, List<String> dataPacks, String worldPreset) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            try {
                createWorld(worldName, seed, dataPacks, worldPreset);
            } catch (RuntimeException | LinkageError e) {
                automationPhase = "error";
                automationMessage = "Failed to create world: " + rootMessage(e);
                writeStatus("error", automationMessage, null);
            }
        });
    }

    public static boolean createWorld(String worldName, Long seed) {
        return createWorld(worldName, seed, List.of());
    }

    public static boolean createWorld(String worldName, Long seed, List<String> dataPacks) {
        return createWorld(worldName, seed, dataPacks, "");
    }

    public static boolean createWorld(String worldName, Long seed, List<String> dataPacks, String worldPreset) {
        Minecraft minecraft = Minecraft.getInstance();
        String cleanWorldName = worldName == null ? "" : worldName.trim();
        if (cleanWorldName.isEmpty()) {
            writeStatus("error", "World name must not be blank.", null);
            return false;
        }
        if (isInWorld()) {
            automationPhase = "in_world";
            automationMessage = "World loaded.";
            writeStatus("ok", "Already in a world.", null);
            return true;
        }
        if (!isReadyForWorldCreation()) {
            automationPhase = "error";
            automationMessage = "Cannot create a world before the title screen is ready.";
            writeStatus("error", automationMessage, null);
            return false;
        }

        beginWorldLoad("creating_world", "Creating world: " + cleanWorldName);
        writeStatus("pending_world", "Creating world: " + cleanWorldName, null);
        LevelSettings settings = new LevelSettings(
                cleanWorldName,
                GameType.CREATIVE,
                false,
                Difficulty.PEACEFUL,
                true,
                new GameRules(),
                worldDataConfiguration(dataPacks)
        );
        minecraft.createWorldOpenFlows().createFreshLevel(
                cleanWorldName,
                settings,
                seed == null ? WorldOptions.defaultWithRandomSeed() : new WorldOptions(seed, true, false),
                registryAccess -> worldDimensions(registryAccess, worldPreset),
                new TitleScreen()
        );
        return true;
    }

    private static WorldDimensions worldDimensions(net.minecraft.core.RegistryAccess registryAccess, String requestedPreset) {
        if (requestedPreset == null || requestedPreset.isBlank()) {
            return WorldPresets.createNormalWorldDimensions(registryAccess);
        }
        ResourceLocation id = ResourceLocation.tryParse(requestedPreset.trim());
        if (id == null) {
            throw new IllegalArgumentException("Invalid world preset id: " + requestedPreset);
        }
        ResourceKey<WorldPreset> key = ResourceKey.create(Registries.WORLD_PRESET, id);
        return registryAccess.registryOrThrow(Registries.WORLD_PRESET).getHolder(key)
                .orElseThrow(() -> new IllegalArgumentException("World preset is not available: " + id))
                .value().createWorldDimensions();
    }

    private static WorldDataConfiguration worldDataConfiguration(List<String> requestedDataPacks) {
        if (requestedDataPacks == null || requestedDataPacks.isEmpty()) {
            return WorldDataConfiguration.DEFAULT;
        }

        LinkedHashSet<String> enabled = new LinkedHashSet<>(WorldDataConfiguration.DEFAULT.dataPacks().getEnabled());
        for (String requestedDataPack : requestedDataPacks) {
            if (requestedDataPack != null && !requestedDataPack.isBlank()) {
                enabled.add(requestedDataPack.trim());
            }
        }
        List<String> disabled = WorldDataConfiguration.DEFAULT.dataPacks().getDisabled().stream()
                .filter(dataPack -> !enabled.contains(dataPack))
                .toList();
        return new WorldDataConfiguration(
                new DataPackConfig(List.copyOf(enabled), disabled),
                WorldDataConfiguration.DEFAULT.enabledFeatures()
        );
    }

    public static boolean openRoadWeaverMapAndScreenshot(String requestedName, int zoomOutSteps, int delayTicks) {
        return openRoadWeaverMap(requestedName, zoomOutSteps, delayTicks, false);
    }

    /**
     * Safe to call from AutoMine's HTTP worker. Biome-source queries are pure
     * generator sampling (the same category of off-thread work RoadWeaver does),
     * and deliberately do not touch chunks, entities, or client UI state.
     */
    public static JsonObject captureBiomeContiguityAudit(String requestedOutput,
                                                          int centerX,
                                                          int centerZ,
                                                          int radiusBlocks,
                                                          int sampleStepBlocks) {
        Minecraft minecraft = Minecraft.getInstance();
        MinecraftServer server = minecraft.getSingleplayerServer();
        if (server == null) {
            throw new IllegalStateException("Cannot audit biomes before a singleplayer world is loaded.");
        }
        try {
            int step = Math.max(4, sampleStepBlocks);
            int radius = Math.max(step, radiusBlocks);
            // Keep a malformed HTTP request from stalling the client with an
            // unbounded flood fill. A 64-block step across this cap is 263,169 samples.
            if ((long) radius * 2L / step + 1L > 513L) {
                throw new IllegalArgumentException("Biome audit grid is limited to 513 samples per side.");
            }
            JsonObject report = BiomeContiguityAudit.audit(
                    minecraft.getSingleplayerServer().overworld(), centerX, centerZ, radius, step);
            Path output = biomeAuditPath(requestedOutput);
            Files.writeString(output, GSON.toJson(report));
            JsonObject data = new JsonObject();
            data.addProperty("biomeAudit", output.toAbsolutePath().toString());
            data.addProperty("biomeAuditSampleCount", report.get("sampleCount").getAsInt());
            data.addProperty("biomeAuditBiomeTypes", report.get("biomeTypes").getAsInt());
            data.addProperty("biomeAuditConnectedZones", report.get("connectedZones").getAsInt());
            data.addProperty("biomeAuditBoundaryEdges", report.get("differentBiomeBoundaryEdges").getAsInt());
            return data;
        } catch (IOException | RuntimeException | LinkageError e) {
            throw new IllegalStateException("Failed to audit biome contiguity: " + rootMessage(e), e);
        }
    }

    /**
     * Captures generator-column relief statistics without loading terrain
     * chunks. This makes terrain quality a benchmarked signal rather than a
     * subjective inference from RoadWeaver's coloured overview tiles.
     */
    public static JsonObject captureTerrainReliefAudit(String requestedOutput,
                                                        int centerX,
                                                        int centerZ,
                                                        int radiusBlocks,
                                                        int sampleStepBlocks) {
        Minecraft minecraft = Minecraft.getInstance();
        MinecraftServer server = minecraft.getSingleplayerServer();
        if (server == null) {
            throw new IllegalStateException("Cannot audit terrain before a singleplayer world is loaded.");
        }
        try {
            Path output = terrainAuditPath(requestedOutput);
            String outputName = output.getFileName().toString();
            Path waterPreview = output.resolveSibling(outputName.substring(0, outputName.length() - ".json".length()) + "-water.png");
            Path reliefPreview = output.resolveSibling(outputName.substring(0, outputName.length() - ".json".length()) + "-relief.png");
            Path seafloorPreview = output.resolveSibling(outputName.substring(0, outputName.length() - ".json".length()) + "-seafloor.png");
            JsonObject report = TerrainReliefAudit.audit(
                    server.overworld(), centerX, centerZ, radiusBlocks, sampleStepBlocks, waterPreview, reliefPreview,
                    seafloorPreview);
            Files.writeString(output, GSON.toJson(report));
            JsonObject data = new JsonObject();
            data.addProperty("terrainAudit", output.toAbsolutePath().toString());
            data.addProperty("terrainAuditSampleCount", report.get("sampleCount").getAsInt());
            data.addProperty("terrainAuditLandHeightRange",
                    report.getAsJsonObject("landSurface").has("range")
                            ? report.getAsJsonObject("landSurface").get("range").getAsInt()
                            : 0);
            data.addProperty("terrainWaterPreview", waterPreview.toAbsolutePath().toString());
            data.addProperty("terrainReliefPreview", reliefPreview.toAbsolutePath().toString());
            data.addProperty("terrainSeafloorPreview", seafloorPreview.toAbsolutePath().toString());
            return data;
        } catch (IOException | RuntimeException | LinkageError e) {
            throw new IllegalStateException("Failed to audit terrain relief: " + rootMessage(e), e);
        }
    }

    /**
     * Captures cold full-chunk generation latency at representative locations.
     * The integrated server performs the requests asynchronously; this method
     * only waits from AutoMine's HTTP worker for their completion.
     */
    public static JsonObject captureChunkGenerationAudit(String requestedOutput, List<String> targets) {
        Minecraft minecraft = Minecraft.getInstance();
        MinecraftServer server = minecraft.getSingleplayerServer();
        if (server == null) {
            throw new IllegalStateException("Cannot audit chunk generation before a singleplayer world is loaded.");
        }
        try {
            JsonObject report = ChunkGenerationAudit.audit(server, targets);
            Path output = terrainAuditPath(requestedOutput);
            Files.writeString(output, GSON.toJson(report));
            JsonObject data = new JsonObject();
            data.addProperty("chunkGenerationAudit", output.toAbsolutePath().toString());
            data.addProperty("chunkGenerationAuditRequestedChunks", report.get("requestedChunks").getAsInt());
            data.addProperty("chunkGenerationAuditGeneratedChunks", report.get("generatedChunks").getAsInt());
            data.addProperty("chunkGenerationAuditMaximumChunkElapsedMs", report.get("maximumChunkElapsedMs").getAsLong());
            return data;
        } catch (IOException | RuntimeException | LinkageError e) {
            throw new IllegalStateException("Failed to audit chunk generation: " + rootMessage(e), e);
        }
    }

    public static boolean openRoadWeaverMapResampleAndScreenshot(String requestedName,
                                                                  int zoomOutSteps,
                                                                  int delayTicks,
                                                                  int mapCenterX,
                                                                  int mapCenterZ,
                                                                  int mapRadiusBlocks) {
        return openRoadWeaverMap(requestedName, zoomOutSteps, delayTicks, true,
                mapCenterX, mapCenterZ, mapRadiusBlocks);
    }

    private static boolean openRoadWeaverMap(String requestedName, int zoomOutSteps, int delayTicks, boolean resample) {
        return openRoadWeaverMap(requestedName, zoomOutSteps, delayTicks, resample, 0, 0, 0);
    }

    private static boolean openRoadWeaverMap(String requestedName,
                                             int zoomOutSteps,
                                             int delayTicks,
                                             boolean resample,
                                             int mapCenterX,
                                             int mapCenterZ,
                                             int mapRadiusBlocks) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!isInWorld()) {
            writeStatus("error", "Cannot open the RoadWeaver map before a world is loaded.", null);
            return false;
        }
        try {
            Class<?> screenClass = Class.forName(ROADWEAVER_MAP_SCREEN);
            Object screen = screenClass.getConstructor().newInstance();
            if (!(screen instanceof Screen roadMap)) {
                writeStatus("error", "RoadWeaver map class is not a Minecraft Screen.", null);
                return false;
            }
            runOnClientThread(minecraft, () -> minecraft.setScreen(roadMap));
            String screenshot = sanitizeScreenshotName(requestedName);
            if (resample) {
                automationActionFailed = false;
                pendingRoadMapResampleCapture = new PendingRoadMapResampleCapture(
                        screenshot, Math.max(0, zoomOutSteps), Math.max(3, delayTicks), currentCommandId,
                        mapCenterX, mapCenterZ, Math.max(0, mapRadiusBlocks));
                writeStatus("pending_roadweaver_resample", "RoadWeaver map opened; waiting for map layout before resampling.", null);
            } else {
                pendingRoadMapCapture = new PendingRoadMapCapture(
                        screenshot, Math.max(0, zoomOutSteps), Math.max(3, delayTicks), currentCommandId);
                writeStatus("pending_roadweaver_map", "RoadWeaver map opened; waiting for map layout.", null);
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            writeStatus("error", "Failed to open RoadWeaver map: " + rootMessage(e), null);
            return false;
        }
    }

    public static void scheduleScreenshot(String requestedName, int delayTicks) {
        String screenshotName = sanitizeScreenshotName(requestedName);
        pendingScreenshot = new PendingScreenshot(screenshotName, Math.max(0, delayTicks), currentCommandId);
        writeStatus("pending_screenshot", "Screenshot scheduled.", screenshotPath(screenshotName));
    }

    public static void writeAmiQuestStatus(String itemId) {
        JsonObject data = new JsonObject();
        try {
            Class<?> questsApi = Class.forName("com.sanhiruzu.ami.api.AmiQuestsApi");
            Method getQuestDocuments = questsApi.getMethod("getQuestDocuments");
            List<?> documents = (List<?>) getQuestDocuments.invoke(null);
            data.addProperty("amiQuestDocuments", documents.size());

            String cleanItemId = itemId == null ? "" : itemId.trim();
            if (!cleanItemId.isEmpty()) {
                Method getQuestMatchesForItem = questsApi.getMethod("getQuestMatchesForItem", ResourceLocation.class);
                List<?> matches = (List<?>) getQuestMatchesForItem.invoke(null, ResourceLocation.parse(cleanItemId));
                data.addProperty("amiQuestItem", cleanItemId);
                data.addProperty("amiQuestMatches", matches.size());
                if (!matches.isEmpty()) {
                    Object quest = matches.getFirst().getClass().getMethod("quest").invoke(matches.getFirst());
                    data.addProperty("amiFirstQuestTitle", String.valueOf(quest.getClass().getMethod("title").invoke(quest)));
                }
            }
            writeStatus("ok", "AMI quest status captured.", null, data);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            writeStatus("error", "Failed to inspect AMI quest status: " + e.getMessage(), null);
        }
    }

    public static void writeAmiSearch(String query, int limit, String viewMode, String lens, boolean compact, String output) {
        try {
            String json = invokeAmiSearch(query, limit, viewMode, lens, compact);
            Path outputPath = writeAmiOutput(output, json);
            JsonObject data = new JsonObject();
            data.addProperty("amiOutput", outputPath.toAbsolutePath().toString());
            data.add("ami", JsonParser.parseString(json));
            writeStatus("ok", "AMI search captured.", null, data);
        } catch (ReflectiveOperationException | IOException | RuntimeException | LinkageError e) {
            writeStatus("error", "Failed to run AMI search: " + e.getMessage(), null);
        }
    }

    public static void requestAmiReindex(boolean forceProviderRebuild) {
        try {
            String json = invokeAmiReindex(forceProviderRebuild);
            JsonObject data = new JsonObject();
            data.add("ami", JsonParser.parseString(json));
            writeStatus("ok", "AMI reindex requested.", null, data);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            writeStatus("error", "Failed to request AMI reindex: " + e.getMessage(), null);
        }
    }

    public static void requestAmiReindexAndSearch(String query, int limit, String viewMode, String lens,
                                                  boolean compact, boolean forceProviderRebuild, String output) {
        try {
            String json = invokeAmiReindex(forceProviderRebuild);
            pendingAmiSearch = new PendingAmiSearch(
                    query,
                    limit,
                    viewMode,
                    lens,
                    compact,
                    output,
                    DEFAULT_AMI_REINDEX_TIMEOUT_TICKS,
                    currentCommandId
            );
            JsonObject data = new JsonObject();
            data.add("ami", JsonParser.parseString(json));
            writeStatus("pending_ami_reindex", "AMI reindex requested; waiting to search.", null, data);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            writeStatus("error", "Failed to request AMI reindex: " + e.getMessage(), null);
        }
    }

    public static String statusSnapshotJson() {
        return GSON.toJson(buildStatus("ready", "Status refreshed.", null, null));
    }

    public static String lastStatusJson() {
        return lastStatusJson;
    }

    public static String amiStatusJson() throws ReflectiveOperationException {
        return invokeAmiStatus().toString();
    }

    public static String amiSearchJson(String query, int limit, String viewMode, String lens, boolean compact)
            throws ReflectiveOperationException {
        return invokeAmiSearch(query, limit, viewMode, lens, compact);
    }

    public static String amiReindexJson(boolean forceProviderRebuild) throws ReflectiveOperationException {
        return invokeAmiReindex(forceProviderRebuild);
    }

    public static String entitySnapshotJson(double radius, boolean includePlayers, List<String> typeFilters,
                                            List<String> categoryFilters, String nameContains, int maxResults) {
        JsonObject out = new JsonObject();
        Minecraft minecraft = Minecraft.getInstance();
        MinecraftServer server = minecraft.getSingleplayerServer();
        if (server == null || minecraft.player == null || minecraft.level == null) {
            out.addProperty("ready", false);
            out.addProperty("error", "Cannot inspect entities before an integrated world is loaded.");
            return GSON.toJson(out);
        }

        ServerLevel level = server.getLevel(minecraft.level.dimension());
        if (level == null) {
            out.addProperty("ready", false);
            out.addProperty("error", "Current client dimension is not available on the integrated server.");
            return GSON.toJson(out);
        }

        ServerPlayer anchor = null;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.getUUID().equals(minecraft.player.getUUID())) {
                anchor = player;
                break;
            }
        }
        if (anchor == null) {
            out.addProperty("ready", false);
            out.addProperty("error", "Could not find the local player on the integrated server.");
            return GSON.toJson(out);
        }

        double effectiveRadius = radius <= 0.0D ? 128.0D : radius;
        double radiusSqr = effectiveRadius * effectiveRadius;
        int effectiveMaxResults = maxResults <= 0 ? 1000 : maxResults;
        List<String> normalizedTypes = normalizeFilters(typeFilters);
        List<String> normalizedCategories = normalizeFilters(categoryFilters);
        String normalizedName = nameContains == null ? "" : nameContains.toLowerCase(Locale.ROOT).trim();

        JsonArray entities = new JsonArray();
        Map<String, Integer> countsByType = new HashMap<>();
        Map<String, Integer> countsByCategory = new HashMap<>();
        int matched = 0;
        int omitted = 0;

        for (Entity entity : level.getAllEntities()) {
            if (!includePlayers && entity instanceof ServerPlayer) {
                continue;
            }

            double distanceSqr = entity.distanceToSqr(anchor);
            if (distanceSqr > radiusSqr) {
                continue;
            }

            ResourceLocation typeId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
            String type = typeId == null ? "" : typeId.toString();
            String category = entity.getType().getCategory().getName();
            String displayName = entity.getDisplayName().getString();
            if (!normalizedTypes.isEmpty() && !normalizedTypes.contains(type)) {
                continue;
            }
            if (!normalizedCategories.isEmpty() && !normalizedCategories.contains(category)) {
                continue;
            }
            if (!normalizedName.isEmpty() && !displayName.toLowerCase(Locale.ROOT).contains(normalizedName)) {
                continue;
            }

            matched++;
            countsByType.merge(type, 1, Integer::sum);
            countsByCategory.merge(category, 1, Integer::sum);
            if (entities.size() >= effectiveMaxResults) {
                omitted++;
                continue;
            }

            JsonObject row = new JsonObject();
            row.addProperty("uuid", entity.getUUID().toString());
            row.addProperty("id", entity.getId());
            row.addProperty("type", type);
            row.addProperty("category", category);
            row.addProperty("name", displayName);
            row.addProperty("x", entity.getX());
            row.addProperty("y", entity.getY());
            row.addProperty("z", entity.getZ());
            row.addProperty("distance", Math.sqrt(distanceSqr));
            row.addProperty("alive", entity.isAlive());
            entities.add(row);
        }

        JsonObject anchorJson = new JsonObject();
        anchorJson.addProperty("name", anchor.getScoreboardName());
        anchorJson.addProperty("x", anchor.getX());
        anchorJson.addProperty("y", anchor.getY());
        anchorJson.addProperty("z", anchor.getZ());
        anchorJson.addProperty("dimension", level.dimension().location().toString());

        out.addProperty("ready", true);
        out.add("anchor", anchorJson);
        out.addProperty("radius", effectiveRadius);
        out.addProperty("matched", matched);
        out.addProperty("returned", entities.size());
        out.addProperty("omitted", omitted);
        out.add("countsByType", sortedCountObject(countsByType));
        out.add("countsByCategory", sortedCountObject(countsByCategory));
        out.add("entities", entities);
        return GSON.toJson(out);
    }

    public static boolean runCommand(String command) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null || minecraft.player.connection == null) {
            writeStatus("error", "Cannot run command before a world is loaded.", null);
            return false;
        }

        String cleanCommand = command == null ? "" : command.trim();
        if (cleanCommand.startsWith("/")) {
            cleanCommand = cleanCommand.substring(1);
        }
        if (cleanCommand.isBlank()) {
            writeStatus("error", "Command must not be blank.", null);
            return false;
        }

        minecraft.player.connection.sendCommand(cleanCommand);
        writeStatus("ok", "Command sent: " + cleanCommand, null);
        return true;
    }

    public static boolean runChat(String message) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null || minecraft.player.connection == null) {
            writeStatus("error", "Cannot send chat before a world is loaded.", null);
            return false;
        }

        String cleanMessage = message == null ? "" : message.trim();
        if (cleanMessage.isBlank()) {
            writeStatus("error", "Chat message must not be blank.", null);
            return false;
        }

        minecraft.player.connection.sendChat(cleanMessage);
        writeStatus("ok", "Chat sent.", null);
        return true;
    }

    public static boolean interactBlock(int x, int y, int z, int button) {
        return interactBlock(x, y, z, button, net.minecraft.core.Direction.UP, null);
    }

    public static boolean interactBlock(int x, int y, int z, int button,
                                       net.minecraft.core.Direction face, Boolean sneaking) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            writeStatus("error", "Cannot interact with block before a world is loaded.", null);
            return false;
        }

        net.minecraft.core.BlockPos blockPos = new net.minecraft.core.BlockPos(x, y, z);
        if (!minecraft.level.isLoaded(blockPos)) {
            writeStatus("error", "Block at " + blockPos + " is not loaded.", null);
            return false;
        }

        try {
            runOnClientThread(minecraft, () -> {
                // Create a hit result pointing at the block
                net.minecraft.world.phys.Vec3 hitVec = net.minecraft.world.phys.Vec3.atCenterOf(blockPos);
                net.minecraft.world.phys.BlockHitResult hit = new net.minecraft.world.phys.BlockHitResult(
                    hitVec,
                    face,
                    blockPos,
                    false
                );

                // Set the target as if player looked at it
                minecraft.hitResult = hit;

                // Perform the interaction
                withSneaking(minecraft, sneaking, () -> {
                if (button == 0) {
                    // Left-click (destroy)
                    minecraft.gameMode.destroyBlock(blockPos);
                } else if (button == 1) {
                    // Right-click (use)
                    minecraft.gameMode.useItemOn(minecraft.player, net.minecraft.world.InteractionHand.MAIN_HAND, hit);
                }
                });
            });
            String action = button == 0 ? "destroyed" : "right-clicked";
            writeStatus("ok", "Block " + action + " at " + blockPos, null);
            return true;
        } catch (Exception e) {
            writeStatus("error", "Failed to interact with block: " + e.getMessage(), null);
            return false;
        }
    }

    /** Sends ordinary gameplay packets so held-item menus are tested through real server authority. */
    public static boolean useHeldItem(Boolean sneaking) {
        return useHeldItem(sneaking, 0);
    }

    public static boolean useHeldItem(Boolean sneaking, int holdTicks) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.gameMode == null || minecraft.level == null
                || minecraft.screen != null || holdTicks < 0 || holdTicks > 200) {
            writeStatus("error", "Item use requires an interactive world and a hold of 0 to 200 ticks.", null);
            return false;
        }
        runOnClientThread(minecraft, () -> {
            if (holdTicks > 0) {
                if (pendingUseTicks == 0) previousUseDown = minecraft.options.keyUse.isDown();
                pendingUseTicks = holdTicks + 1;
                minecraft.options.keyUse.setDown(true);
            }
            withSneaking(minecraft, sneaking,
                () -> minecraft.gameMode.useItem(minecraft.player, net.minecraft.world.InteractionHand.MAIN_HAND));
        });
        writeStatus("ok", "Used main-hand item through normal gameplay.", null);
        return true;
    }

    /** Uses an actual loaded entity through the ordinary interaction packet path. */
    public static boolean interactEntity(int entityId, Boolean sneaking) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null || minecraft.gameMode == null) {
            writeStatus("error", "Cannot interact with an entity before a world is loaded.", null);
            return false;
        }
        Entity target = minecraft.level.getEntity(entityId);
        if (target == null || !target.isAlive()
                || minecraft.player.distanceToSqr(target) > 36) {
            writeStatus("error", "Entity must be alive, loaded and within six blocks.", null);
            return false;
        }
        runOnClientThread(minecraft, () -> withSneaking(minecraft, sneaking, () -> {
            var result = minecraft.gameMode.interactAt(minecraft.player, target,
                new net.minecraft.world.phys.EntityHitResult(target),
                net.minecraft.world.InteractionHand.MAIN_HAND);
            if (!result.consumesAction()) minecraft.gameMode.interact(minecraft.player, target,
                net.minecraft.world.InteractionHand.MAIN_HAND);
        }));
        writeStatus("ok", "Interacted with loaded entity through normal gameplay.", null);
        return true;
    }

    private static void withSneaking(Minecraft minecraft, Boolean requested, Runnable action) {
        boolean previous = minecraft.player.isShiftKeyDown();
        boolean change = requested != null && requested != previous;
        if (change) sendSneaking(minecraft, requested);
        try {
            action.run();
        } finally {
            if (change) sendSneaking(minecraft, previous);
        }
    }

    private static void sendSneaking(Minecraft minecraft, boolean sneaking) {
        minecraft.player.setShiftKeyDown(sneaking);
        var action = sneaking
            ? net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket.Action.PRESS_SHIFT_KEY
            : net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket.Action.RELEASE_SHIFT_KEY;
        minecraft.player.connection.send(
            new net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket(minecraft.player, action));
    }

    public static boolean openAtelierCodex() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            writeStatus("error", "Cannot open Atelier codex before a world is loaded.", null);
            return false;
        }

        try {
            Class<?> screenClass = Class.forName("com.sanhiruzu.atelier.ui.client.AlchemistCodexScreen");
            Object screen = screenClass.getConstructor().newInstance();
            if (!(screen instanceof net.minecraft.client.gui.screens.Screen minecraftScreen)) {
                writeStatus("error", "Atelier codex class is not a Minecraft Screen.", null);
                return false;
            }
            runOnClientThread(minecraft, () -> minecraft.setScreen(minecraftScreen));
            writeStatus("ok", "Atelier codex screen opened.", null);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            writeStatus("error", "Failed to open Atelier codex: " + e.getMessage(), null);
            return false;
        }
    }

    public static boolean openScreen(String screenName) {
        return openScreen(screenName, "", "");
    }

    public static boolean openScreen(String screenName, String menuClassName, String screenTitle) {
        Minecraft minecraft = Minecraft.getInstance();
        String cleanScreenName = screenName == null ? "" : screenName.trim();
        if (cleanScreenName.isEmpty()) {
            writeStatus("error", "Screen class must not be blank.", null);
            return false;
        }

        try {
            Class<?> screenClass = resolveScreenClass(cleanScreenName);
            Screen screen = instantiateScreen(screenClass, menuClassName, screenTitle, minecraft);
            runOnClientThread(minecraft, () -> minecraft.setScreen(screen));
            writeStatus("ok", "Screen opened: " + screenClass.getName(), null);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            writeStatus("error", "Failed to open screen '" + cleanScreenName + "': " + rootMessage(e), null);
            return false;
        }
    }

    public static boolean clickScreen(double x, double y, int button) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen == null) {
            writeStatus("error", "Cannot click screen when no screen is open.", null);
            return false;
        }
        runOnClientThread(minecraft, () -> {
            if (minecraft.screen != null) {
                Screen clickedScreen = minecraft.screen;
                clickedScreen.mouseClicked(x, y, button);
                if (minecraft.screen == clickedScreen) clickedScreen.mouseReleased(x, y, button);
            }
        });
        writeStatus("ok", "Screen clicked at " + x + "," + y + " button " + button, null);
        return true;
    }

    public static boolean pressKey(int keyCode, int scanCode, int modifiers) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen == null) {
            writeStatus("error", "Cannot press key when no screen is open.", null);
            return false;
        }
        runOnClientThread(minecraft, () -> {
            if (minecraft.screen != null) {
                minecraft.screen.keyPressed(keyCode, scanCode, modifiers);
            }
        });
        writeStatus("ok", "Key pressed: " + keyCode, null);
        return true;
    }

    public static boolean hoverScreen(double x, double y) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen == null || !Double.isFinite(x) || !Double.isFinite(y)
                || x < 0 || y < 0 || x >= minecraft.screen.width || y >= minecraft.screen.height) {
            writeStatus("error", "Pointer target requires an open screen and valid scaled coordinates.", null);
            return false;
        }
        runOnClientThread(minecraft, () -> {
            double windowX = x * minecraft.getWindow().getScreenWidth() / minecraft.getWindow().getGuiScaledWidth();
            double windowY = y * minecraft.getWindow().getScreenHeight() / minecraft.getWindow().getGuiScaledHeight();
            org.lwjgl.glfw.GLFW.glfwSetCursorPos(minecraft.getWindow().getWindow(), windowX, windowY);
        });
        writeStatus("ok", "Screen pointer moved to " + x + "," + y, null);
        return true;
    }

    public static boolean scrollScreen(double x, double y, double horizontal, double vertical) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen == null || !Double.isFinite(x) || !Double.isFinite(y)
                || !Double.isFinite(horizontal) || !Double.isFinite(vertical)
                || Math.abs(horizontal) > 64 || Math.abs(vertical) > 64
                || x < 0 || y < 0 || x >= minecraft.screen.width || y >= minecraft.screen.height) {
            writeStatus("error", "Scrolling requires an open screen, valid scaled coordinates and bounded deltas.", null);
            return false;
        }
        runOnClientThread(minecraft, () -> {
            if (minecraft.screen != null) minecraft.screen.mouseScrolled(x, y, horizontal, vertical);
        });
        writeStatus("ok", "Screen scrolled " + horizontal + "," + vertical, null);
        return true;
    }

    public static boolean tapWorldKey(int keyCode) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen != null || minecraft.level == null || minecraft.player == null
                || keyCode < 32 || keyCode > 348) {
            writeStatus("error", "World key requires an interactive world without a screen and a valid key code.", null);
            return false;
        }
        runOnClientThread(minecraft, () -> net.minecraft.client.KeyMapping.click(
                com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM.getOrCreate(keyCode)));
        writeStatus("ok", "World key mapping clicked: " + keyCode, null);
        return true;
    }

    public static boolean typeScreenText(String text) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen == null || text == null || text.length() > 256) {
            writeStatus("error", "Text entry requires an open screen and at most 256 characters.", null);
            return false;
        }
        runOnClientThread(minecraft, () -> {
            Screen typingScreen = minecraft.screen;
            for (char character : text.toCharArray()) {
                if (typingScreen != null && minecraft.screen == typingScreen
                        && !Character.isISOControl(character)) typingScreen.charTyped(character, 0);
            }
        });
        writeStatus("ok", "Screen received " + text.length() + " text characters.", null);
        return true;
    }

    public static void writeScreenInfo() {
        Minecraft minecraft = Minecraft.getInstance();
        JsonObject data = new JsonObject();
        data.addProperty("windowWidth", minecraft.getWindow().getWidth());
        data.addProperty("windowHeight", minecraft.getWindow().getHeight());
        data.addProperty("guiScaledWidth", minecraft.getWindow().getGuiScaledWidth());
        data.addProperty("guiScaledHeight", minecraft.getWindow().getGuiScaledHeight());
        data.addProperty("guiScale", minecraft.getWindow().getGuiScale());
        if (minecraft.screen != null) {
            data.addProperty("screenClass", minecraft.screen.getClass().getName());
            data.addProperty("screenTitle", minecraft.screen.getTitle().getString());
            data.addProperty("screenWidth", minecraft.screen.width);
            data.addProperty("screenHeight", minecraft.screen.height);
        }
        writeStatus("ok", "Screen info captured.", null, data);
    }

    public static void writeScreenTargets() {
        Minecraft minecraft = Minecraft.getInstance();
        JsonObject data = callOnClientThread(minecraft, ScreenTargetInspector::inspectCurrentScreen);
        writeStatus("ok", "Screen targets captured.", null, data);
    }

    public static boolean clickScreenTarget(String targetId, int button) {
        JsonObject data = clickScreenTargetWithStatus(targetId, button, false);
        return data.has("clicked") && data.get("clicked").getAsBoolean();
    }

    public static JsonObject clickScreenTargetWithStatus(String targetId, int button, boolean onlyScreenshotOnChange) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen == null) {
            JsonObject data = new JsonObject();
            data.addProperty("targetId", targetId == null ? "" : targetId.trim());
            data.addProperty("targetFound", false);
            data.addProperty("clicked", false);
            data.addProperty("stateChanged", false);
            writeStatus("error", "Cannot click screen target when no screen is open.", null, data);
            return data;
        }

        JsonObject data = callOnClientThread(minecraft, () -> ScreenTargetInspector.clickTarget(targetId, button));
        boolean targetFound = data.has("targetFound") && data.get("targetFound").getAsBoolean();
        boolean clicked = data.has("clicked") && data.get("clicked").getAsBoolean();
        boolean stateChanged = data.has("stateChanged") && data.get("stateChanged").getAsBoolean();
        String cleanTargetId = targetId == null ? "" : targetId.trim();
        boolean screenshotSkipped = onlyScreenshotOnChange && !shouldScheduleTargetScreenshot(data, true);

        if (!targetFound) {
            if (screenshotSkipped) {
                data.addProperty("screenshotSkipped", true);
            }
            writeStatus("error", "Unknown screen target: " + cleanTargetId, null, data);
            return data;
        }

        String message = "Clicked screen target: " + cleanTargetId;
        if (stateChanged) {
            message += " (state changed)";
        } else if (!clicked) {
            message += " (click returned false)";
        } else {
            message += " (no state change observed)";
        }
        if (screenshotSkipped) {
            data.addProperty("screenshotSkipped", true);
            message += " (screenshot skipped)";
        }
        writeStatus("ok", message, null, data);
        return data;
    }

    public static boolean shouldScheduleTargetScreenshot(JsonObject data, boolean onlyScreenshotOnChange) {
        if (!onlyScreenshotOnChange) {
            return true;
        }
        boolean targetFound = data.has("targetFound") && data.get("targetFound").getAsBoolean();
        boolean clicked = data.has("clicked") && data.get("clicked").getAsBoolean();
        boolean stateChanged = data.has("stateChanged") && data.get("stateChanged").getAsBoolean();
        return !targetFound || !clicked || stateChanged;
    }

    private static Class<?> resolveScreenClass(String screenName) throws ClassNotFoundException {
        if (screenName.contains(".")) {
            return Class.forName(screenName);
        }

        List<String> packages = List.of(
                "com.sanhiruzu.atelier.ui.client.",
                "net.minecraft.client.gui.screens.",
                "net.minecraft.client.gui.screens.inventory."
        );
        ClassNotFoundException last = null;
        for (String prefix : packages) {
            try {
                return Class.forName(prefix + screenName);
            } catch (ClassNotFoundException e) {
                last = e;
            }
        }
        throw last == null ? new ClassNotFoundException(screenName) : last;
    }

    private static Screen instantiateScreen(Class<?> screenClass, String menuClassName, String screenTitle, Minecraft minecraft)
            throws ReflectiveOperationException {
        if (screenClass.getName().equals("com.sanhiruzu.atelier.ui.client.SynthesisStationScreen")) {
            return instantiateAtelierSynthesisScreen(screenClass, minecraft);
        }

        String cleanMenuClassName = menuClassName == null ? "" : menuClassName.trim();
        if (!cleanMenuClassName.isEmpty()) {
            return instantiateContainerScreen(screenClass, cleanMenuClassName, screenTitle, minecraft);
        }

        Object screen = tryConstructScreen(screenClass, minecraft);
        if (screen instanceof Screen minecraftScreen) {
            return minecraftScreen;
        }
        throw new IllegalArgumentException(screenClass.getName() + " is not a Minecraft Screen.");
    }

    private static Screen instantiateContainerScreen(Class<?> screenClass, String menuClassName, String screenTitle, Minecraft minecraft)
            throws ReflectiveOperationException {
        if (minecraft.player == null || minecraft.level == null) {
            throw new IllegalStateException("Container screen construction requires a loaded world.");
        }

        Class<?> menuClass = Class.forName(menuClassName);
        Object menu = instantiateMenu(menuClass, minecraft);
        Component title = screenTitle == null || screenTitle.isBlank()
                ? Component.literal("AutoMine")
                : Component.translatable(screenTitle);

        for (Constructor<?> constructor : screenClass.getConstructors()) {
            Class<?>[] parameterTypes = constructor.getParameterTypes();
            if (parameterTypes.length == 3
                    && parameterTypes[0].isAssignableFrom(menuClass)
                    && parameterTypes[1].isAssignableFrom(net.minecraft.world.entity.player.Inventory.class)
                    && parameterTypes[2].isAssignableFrom(Component.class)) {
                return (Screen) constructor.newInstance(menu, minecraft.player.getInventory(), title);
            }
        }

        throw new NoSuchMethodException("No supported container-screen constructor found for " + screenClass.getName());
    }

    private static Screen instantiateAtelierSynthesisScreen(Class<?> screenClass, Minecraft minecraft)
            throws ReflectiveOperationException {
        if (minecraft.player == null || minecraft.level == null) {
            throw new IllegalStateException("Atelier synthesis screen requires a loaded world.");
        }

        Class<?> menuClass = Class.forName("com.sanhiruzu.atelier.synthesis.menu.SynthesisStationMenu");
        Object menu = menuClass
                .getConstructor(int.class, net.minecraft.world.entity.player.Inventory.class, ContainerLevelAccess.class)
                .newInstance(0, minecraft.player.getInventory(), ContainerLevelAccess.NULL);
        return (Screen) screenClass
                .getConstructor(menuClass, net.minecraft.world.entity.player.Inventory.class, Component.class)
                .newInstance(menu, minecraft.player.getInventory(),
                        Component.translatable("container.zen_atelier.synthesis_station"));
    }

    private static Object instantiateMenu(Class<?> menuClass, Minecraft minecraft) throws ReflectiveOperationException {
        for (Constructor<?> constructor : menuClass.getConstructors()) {
            Class<?>[] parameterTypes = constructor.getParameterTypes();
            if (parameterTypes.length == 2
                    && parameterTypes[0] == int.class
                    && parameterTypes[1].isAssignableFrom(net.minecraft.world.entity.player.Inventory.class)) {
                return constructor.newInstance(0, minecraft.player.getInventory());
            }
            if (parameterTypes.length == 3
                    && parameterTypes[0] == int.class
                    && parameterTypes[1].isAssignableFrom(net.minecraft.world.entity.player.Inventory.class)
                    && parameterTypes[2].getName().equals(ContainerLevelAccess.class.getName())) {
                return constructor.newInstance(0, minecraft.player.getInventory(), ContainerLevelAccess.NULL);
            }
        }
        throw new NoSuchMethodException("No supported menu constructor found for " + menuClass.getName());
    }

    private static Object tryConstructScreen(Class<?> screenClass, Minecraft minecraft) throws ReflectiveOperationException {
        for (Constructor<?> constructor : screenClass.getConstructors()) {
            Class<?>[] parameterTypes = constructor.getParameterTypes();
            if (parameterTypes.length == 0) {
                return constructor.newInstance();
            }
            if (parameterTypes.length == 1) {
                if (parameterTypes[0].isAssignableFrom(Minecraft.class)) {
                    return constructor.newInstance(minecraft);
                }
                if (parameterTypes[0].isAssignableFrom(Component.class)) {
                    return constructor.newInstance(Component.literal("AutoMine"));
                }
            }
        }
        throw new NoSuchMethodException("No supported constructor found for " + screenClass.getName());
    }

    private static String rootMessage(Throwable throwable) {
        Throwable root = throwable;
        if (throwable instanceof InvocationTargetException invocationTargetException
                && invocationTargetException.getTargetException() != null) {
            root = invocationTargetException.getTargetException();
        }
        String message = root.getMessage();
        return message == null || message.isBlank() ? root.getClass().getName() : message;
    }

    private static void runOnClientThread(Minecraft minecraft, Runnable action) {
        callOnClientThread(minecraft, () -> {
            action.run();
            return null;
        });
    }

    private static <T> T callOnClientThread(Minecraft minecraft, Supplier<T> action) {
        if (minecraft.isSameThread()) {
            return action.get();
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        minecraft.execute(() -> {
            try {
                future.complete(action.get());
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        });
        return future.join();
    }

    public static Path automationDir() {
        return AUTOMATION_DIR;
    }

    public static Path commandFile() {
        return COMMAND_FILE;
    }

    public static Path statusFile() {
        return STATUS_FILE;
    }

    private static void processAutoWorldOpen(Minecraft minecraft) {
        if (!AUTO_OPEN_WORLD) {
            return;
        }
        if (isInWorld()) {
            automationPhase = "in_world";
            automationMessage = "World loaded.";
            autoWorldRetryTicks = 0;
            return;
        }
        if (!(minecraft.screen instanceof TitleScreen)) {
            if (!automationPhase.equals("opening_world") && !automationPhase.equals("creating_world")) {
                automationPhase = "starting";
                automationMessage = "Waiting for title screen.";
            }
            return;
        }
        if (autoWorldRetryTicks > 0) {
            autoWorldRetryTicks--;
            return;
        }
        if (autoWorldAttempts >= AUTO_WORLD_MAX_ATTEMPTS) {
            automationPhase = "error";
            automationMessage = "Timed out opening AutoMine world: " + AUTO_WORLD_NAME;
            return;
        }

        autoWorldAttempts++;
        autoWorldRetryTicks = AUTO_WORLD_RETRY_TICKS;
        if (worldExists(AUTO_WORLD_NAME)) {
            openWorld(AUTO_WORLD_NAME);
        } else if (AUTO_CREATE_WORLD) {
            createWorld(AUTO_WORLD_NAME);
        } else {
            automationPhase = "error";
            automationMessage = "AutoMine world does not exist: " + AUTO_WORLD_NAME;
        }
    }

    private static void dismissAccessibilityOnboarding(Minecraft minecraft) {
        if (!(minecraft.screen instanceof AccessibilityOnboardingScreen)) {
            return;
        }
        minecraft.options.onboardAccessibility = true;
        minecraft.options.save();
        minecraft.setScreen(new TitleScreen());
        automationPhase = "starting";
        automationMessage = "Dismissed Minecraft's one-time accessibility onboarding screen.";
    }

    private static void processPendingClientStop(Minecraft minecraft) {
        if (pendingClientStopTicks < 0) {
            return;
        }
        if (pendingClientStopTicks-- > 0) {
            return;
        }
        pendingClientStopTicks = -1;
        minecraft.stop();
    }

    private static boolean worldExists(String worldName) {
        String cleanWorldName = worldName == null ? "" : worldName.trim();
        if (cleanWorldName.isEmpty()) {
            return false;
        }
        Path worldDir = GAME_DIRECTORY.resolve("saves").resolve(cleanWorldName);
        return Files.isRegularFile(worldDir.resolve("level.dat"))
                || Files.isRegularFile(worldDir.resolve("level.dat_old"));
    }

    private static void processCommandFile(Minecraft minecraft) {
        if (!Files.isRegularFile(COMMAND_FILE)) {
            return;
        }

        JsonObject command;
        try {
            command = JsonParser.parseString(Files.readString(COMMAND_FILE)).getAsJsonObject();
            Files.deleteIfExists(COMMAND_FILE);
        } catch (Exception e) {
            writeStatus("error", "Failed to read command.json: " + e.getMessage(), null);
            moveBadCommand();
            return;
        }

        currentCommandId = getString(command, "id", "");
        try {
            String action = getString(command, "action", "").toLowerCase(Locale.ROOT);
            String screenshot = getString(command, "screenshot", "automine.png");
            int delayTicks = getInt(command, "delayTicks", DEFAULT_SCREENSHOT_DELAY_TICKS);
            String world = getString(command, "world", "");
            String item = getString(command, "item", "");
            String commandText = getString(command, "command", item);
            String screen = getString(command, "screen", getString(command, "class", ""));
            String menuClass = getString(command, "menuClass", "");
            String screenTitle = getString(command, "screenTitle", "");
            String target = getString(command, "target", "");
            String query = getString(command, "query", "");
            double x = getDouble(command, "x", 0.0D);
            double y = getDouble(command, "y", 0.0D);
            int button = getInt(command, "button", 0);
            int keyCode = getInt(command, "keyCode", 0);
            int scanCode = getInt(command, "scanCode", 0);
            int modifiers = getInt(command, "modifiers", 0);
            String viewMode = getString(command, "viewMode", "LIST");
            String lens = getString(command, "lens", "ALL");
            String output = getString(command, "output", "");
            int limit = getInt(command, "limit", DEFAULT_AMI_SEARCH_LIMIT);
            boolean compact = getBoolean(command, "compact", false);
            boolean forceProviderRebuild = getBoolean(command, "forceProviderRebuild", true);
            boolean onlyScreenshotOnChange = getBoolean(command, "onlyScreenshotOnChange", false);

            switch (action) {
                case "open_world" -> openWorld(world);
                case "close_world" -> closeWorld();
                case "create_world" -> createWorld(world, command.has("seed") ? command.get("seed").getAsLong() : null);
                case "ami_quest_status" -> writeAmiQuestStatus(item);
                case "ami_search" -> writeAmiSearch(query, limit, viewMode, lens, compact, output);
                case "ami_reindex" -> requestAmiReindex(forceProviderRebuild);
                case "ami_reindex_and_search" -> requestAmiReindexAndSearch(query, limit, viewMode, lens, compact, forceProviderRebuild, output);
                case "run_command" -> runCommand(commandText);
                case "run_chat" -> runChat(commandText);
                case "entity_snapshot" -> writeEntitySnapshot(
                        getDouble(command, "radius", 128.0D),
                        getBoolean(command, "includePlayers", false),
                        splitCsv(getString(command, "types", getString(command, "entityTypes", ""))),
                        splitCsv(getString(command, "categories", "")),
                        getString(command, "nameContains", ""),
                        getInt(command, "maxResults", 1000));
                case "open_atelier_codex" -> openAtelierCodex();
                case "open_screen" -> openScreen(screen, menuClass, screenTitle);
                case "screen_info" -> writeScreenInfo();
                case "screen_targets" -> writeScreenTargets();
                case "click_screen" -> clickScreen(x, y, button);
                case "click_screen_target" -> clickScreenTarget(target, button);
                case "press_key" -> pressKey(keyCode, scanCode, modifiers);
                case "open_inventory" -> openInventory();
                case "close_screen" -> closeScreen();
                case "screenshot" -> scheduleScreenshot(screenshot, delayTicks);
                case "open_roadweaver_map_and_screenshot" -> openRoadWeaverMapAndScreenshot(
                        screenshot, getInt(command, "zoomOutSteps", 8), delayTicks);
                case "resample_roadweaver_map_and_screenshot" -> openRoadWeaverMapResampleAndScreenshot(
                        screenshot,
                        getInt(command, "zoomOutSteps", 8),
                        delayTicks,
                        getInt(command, "mapCenterX", 0),
                        getInt(command, "mapCenterZ", 0),
                        getInt(command, "mapRadiusBlocks", 0));
                case "open_screen_and_screenshot" -> {
                    if (openScreen(screen, menuClass, screenTitle)) {
                        scheduleScreenshot(screenshot, delayTicks);
                    }
                }
                case "click_screen_and_screenshot" -> {
                    if (clickScreen(x, y, button)) {
                        scheduleScreenshot(screenshot, delayTicks);
                    }
                }
                case "click_screen_target_and_screenshot" -> {
                    JsonObject data = clickScreenTargetWithStatus(target, button, onlyScreenshotOnChange);
                    if (shouldScheduleTargetScreenshot(data, onlyScreenshotOnChange)) {
                        scheduleScreenshot(screenshot, delayTicks);
                    }
                }
                case "press_key_and_screenshot" -> {
                    if (pressKey(keyCode, scanCode, modifiers)) {
                        scheduleScreenshot(screenshot, delayTicks);
                    }
                }
                case "open_inventory_and_screenshot" -> {
                    if (openInventory()) {
                        scheduleScreenshot(screenshot, delayTicks);
                    }
                }
                case "status" -> writeStatus("ready", "Status refreshed.", null);
                default -> writeStatus("error", "Unknown action: " + action, null);
            }
        } finally {
            if (pendingScreenshot == null || !pendingScreenshot.commandId.equals(currentCommandId)) {
                rememberCommandId(currentCommandId);
                currentCommandId = "";
            }
        }
    }

    private static void processPendingAmiSearch() {
        if (pendingAmiSearch == null) {
            return;
        }

        String commandId = pendingAmiSearch.commandId;
        currentCommandId = commandId;
        try {
            JsonObject status = invokeAmiStatus();
            JsonElement failure = status.get("lastRebuildFailure");
            if (failure != null && !failure.isJsonNull()) {
                pendingAmiSearch = null;
                JsonObject data = new JsonObject();
                data.add("ami", status);
                writeStatus("error", "AMI reindex failed: " + failure.getAsString(), null, data);
                rememberCommandId(commandId);
                return;
            }
            boolean ready = status.has("ready") && status.get("ready").getAsBoolean();
            if (!ready) {
                pendingAmiSearch.timeoutTicks--;
                if (pendingAmiSearch.timeoutTicks <= 0) {
                    pendingAmiSearch = null;
                    JsonObject data = new JsonObject();
                    data.add("ami", status);
                    writeStatus("error", "Timed out waiting for AMI reindex.", null, data);
                    rememberCommandId(commandId);
                } else if (pendingAmiSearch.timeoutTicks % 20 == 0) {
                    JsonObject data = new JsonObject();
                    data.add("ami", status);
                    writeStatus("pending_ami_reindex", "Waiting for AMI reindex.", null, data);
                }
                return;
            }

            PendingAmiSearch search = pendingAmiSearch;
            pendingAmiSearch = null;
            writeAmiSearch(search.query, search.limit, search.viewMode, search.lens, search.compact, search.output);
            rememberCommandId(commandId);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            pendingAmiSearch = null;
            writeStatus("error", "Failed while waiting for AMI reindex: " + e.getMessage(), null);
            rememberCommandId(commandId);
        } finally {
            currentCommandId = "";
        }
    }

    private static void processPendingScreenshot(Minecraft minecraft) {
        if (pendingScreenshot == null) {
            return;
        }

        if (pendingScreenshot.delayTicks > 0) {
            pendingScreenshot.delayTicks--;
            return;
        }

        String screenshotName = pendingScreenshot.name;
        String commandId = pendingScreenshot.commandId;
        pendingScreenshot = null;
        Path expectedPath = screenshotPath(screenshotName);
        Screenshot.grab(minecraft.gameDirectory, screenshotName, minecraft.getMainRenderTarget(), message -> {
            minecraft.execute(() -> {
                currentCommandId = commandId;
                if (minecraft.player != null) {
                    minecraft.player.displayClientMessage(message.copy().withStyle(ChatFormatting.GRAY), false);
                }
                writeStatus("screenshot_requested", "Screenshot requested: " + expectedPath.toAbsolutePath(), expectedPath);
                rememberCommandId(commandId);
                currentCommandId = "";
            });
        });
    }

    private static void processPendingRoadMapCapture(Minecraft minecraft) {
        if (pendingRoadMapCapture == null) {
            return;
        }
        Screen screen = minecraft.screen;
        if (screen == null || !ROADWEAVER_MAP_SCREEN.equals(screen.getClass().getName())
                || screen.width <= 0 || screen.height <= 0) {
            return;
        }
        PendingRoadMapCapture capture = pendingRoadMapCapture;
        if (!capture.zoomApplied) {
            if (capture.layoutReadyTicks > 0) {
                capture.layoutReadyTicks--;
                return;
            }
            double centerX = screen.width / 2.0D;
            double centerY = screen.height / 2.0D;
            for (int step = 0; step < capture.zoomOutSteps; step++) {
                screen.mouseScrolled(centerX, centerY, 0.0D, -1.0D);
            }
            capture.zoomApplied = true;
            capture.zoomDebounceTicks = 20;
            writeStatus("pending_roadweaver_map", "RoadWeaver map zoomed out; waiting for the viewport to settle.",
                    null, roadWeaverViewBounds(screen));
            return;
        }
        if (capture.zoomDebounceTicks > 0) {
            capture.zoomDebounceTicks--;
            return;
        }
        pendingRoadMapCapture = null;
        pendingScreenshot = new PendingScreenshot(capture.name, capture.delayTicks, capture.commandId);
        writeStatus("pending_screenshot", "RoadWeaver map viewport settled; screenshot scheduled.", screenshotPath(capture.name));
    }

    private static void processPendingRoadMapResampleCapture(Minecraft minecraft) {
        PendingRoadMapResampleCapture capture = pendingRoadMapResampleCapture;
        if (capture == null) {
            return;
        }
        Screen screen = minecraft.screen;
        if (screen == null || !ROADWEAVER_MAP_SCREEN.equals(screen.getClass().getName())
                || screen.width <= 0 || screen.height <= 0) {
            return;
        }
        try {
            if (!capture.zoomApplied) {
                if (capture.layoutReadyTicks > 0) {
                    capture.layoutReadyTicks--;
                    return;
                }
                if (capture.mapRadiusBlocks > 0) {
                    setRoadWeaverViewBounds(screen, capture.mapCenterX, capture.mapCenterZ, capture.mapRadiusBlocks);
                    requestRoadWeaverViewportRefresh(screen);
                } else {
                    double centerX = screen.width / 2.0D;
                    double centerY = screen.height / 2.0D;
                    for (int step = 0; step < capture.sampleZoomOutSteps; step++) {
                        screen.mouseScrolled(centerX, centerY, 0.0D, -1.0D);
                    }
                }
                capture.zoomApplied = true;
                capture.settleTicks = 20;
                writeStatus("pending_roadweaver_resample",
                        "RoadWeaver map framed to the requested continent view; preparing a forced resample.",
                        null, roadWeaverViewBounds(screen));
                return;
            }
            if (capture.settleTicks > 0) {
                capture.settleTicks--;
                return;
            }
            if (!capture.tilePlanCreated) {
                createRoadWeaverMapSamplingPlan(screen, capture);
                writeStatus("pending_roadweaver_resample",
                        "RoadWeaver will resample the requested continent view.",
                        null, roadWeaverSamplingBounds(screen, capture.sampleTile));
                return;
            }
            if (!capture.samplingStarted) {
                String result = startRoadWeaverMapSampling(minecraft, capture, capture.sampleTile);
                if (!"STARTED".equals(result)) {
                    failRoadWeaverResample("RoadWeaver rejected the continent resample: " + result + '.');
                    return;
                }
                capture.samplingStarted = true;
                capture.lastPercent = -1;
                capture.lastStage = "";
                writeStatus("resampling_roadweaver_map",
                        "RoadWeaver is resampling the requested continent view (0%).",
                        null, roadWeaverSamplingBounds(screen, capture.sampleTile));
                return;
            }

            Object snapshot = capture.samplingService.getClass().getMethod("snapshot").invoke(capture.samplingService);
            String stage = String.valueOf(snapshot.getClass().getMethod("stage").invoke(snapshot));
            int percent = ((Number) snapshot.getClass().getMethod("percent").invoke(snapshot)).intValue();
            if (percent != capture.lastPercent || !stage.equals(capture.lastStage)) {
                capture.lastPercent = percent;
                capture.lastStage = stage;
                writeStatus("resampling_roadweaver_map",
                        "RoadWeaver continent resample: " + stage + " (" + percent + "%).",
                        null, roadWeaverSamplingBounds(screen, capture.sampleTile));
            }
            if ("COMPLETED".equals(stage)) {
                if (capture.mapRadiusBlocks <= 0) {
                    double centerX = screen.width / 2.0D;
                    double centerY = screen.height / 2.0D;
                    for (int step = capture.sampleZoomOutSteps; step < capture.zoomOutSteps; step++) {
                        screen.mouseScrolled(centerX, centerY, 0.0D, -1.0D);
                    }
                }
                if (!capture.overlayRefreshRequested) {
                    requestRoadWeaverViewportRefresh(screen);
                    capture.overlayRefreshRequested = true;
                    capture.overlaySettleTicks = 40;
                    writeStatus("pending_roadweaver_overlay",
                            "RoadWeaver terrain resample is complete; waiting for town and road overlay data.",
                            null, roadWeaverOverlayState(screen));
                    return;
                }
                if (roadWeaverOverlayLoading(screen)) {
                    writeStatus("pending_roadweaver_overlay",
                            "RoadWeaver is loading town and road overlay data for the sampled continent.",
                            null, roadWeaverOverlayState(screen));
                    return;
                }
                if (capture.overlaySettleTicks > 0) {
                    capture.overlaySettleTicks--;
                    return;
                }
                pendingRoadMapResampleCapture = null;
                pendingScreenshot = new PendingScreenshot(capture.name, capture.delayTicks, capture.commandId);
                writeStatus("pending_screenshot",
                        "RoadWeaver terrain, town, and road overlay data are ready; overview screenshot scheduled.",
                        screenshotPath(capture.name), roadWeaverOverlayState(screen));
            } else if ("FAILED".equals(stage)) {
                failRoadWeaverResample("RoadWeaver map resample reported FAILED; no screenshot was taken.");
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            failRoadWeaverResample("Failed while resampling the RoadWeaver map: " + rootMessage(e));
        }
    }

    private static void createRoadWeaverMapSamplingPlan(Screen screen, PendingRoadMapResampleCapture capture)
            throws ReflectiveOperationException {
        Field viewField = screen.getClass().getDeclaredField("view");
        viewField.setAccessible(true);
        Object view = viewField.get(screen);
        Field samplingField = screen.getClass().getDeclaredField("mapSampling");
        samplingField.setAccessible(true);
        capture.samplingService = samplingField.get(screen);
        ClassLoader loader = screen.getClass().getClassLoader();
        Class<?> mapViewClass = Class.forName("net.shiroha233.roadweaver.client.map.MapView", true, loader);
        Class<?> viewportControllerClass = Class.forName(
                "net.shiroha233.roadweaver.client.map.MapViewportController", true, loader);
        Object requestRect = viewportControllerClass.getMethod("currentRequestRect", mapViewClass).invoke(null, view);
        Class<?> rectClass = requestRect.getClass();
        int minX = ((Number) rectClass.getMethod("minX").invoke(requestRect)).intValue();
        int minZ = ((Number) rectClass.getMethod("minZ").invoke(requestRect)).intValue();
        int maxX = ((Number) rectClass.getMethod("maxX").invoke(requestRect)).intValue();
        int maxZ = ((Number) rectClass.getMethod("maxZ").invoke(requestRect)).intValue();
        capture.sampleTile = new RoadWeaverSamplingTile(minX, minZ, maxX, maxZ);
        capture.tilePlanCreated = true;
    }

    private static void setRoadWeaverViewBounds(Screen screen, int centerX, int centerZ, int radiusBlocks)
            throws ReflectiveOperationException {
        Field viewField = screen.getClass().getDeclaredField("view");
        viewField.setAccessible(true);
        Object view = viewField.get(screen);
        double radius = Math.max(1, radiusBlocks);
        setDoubleField(view, "minX", centerX - radius);
        setDoubleField(view, "maxX", centerX + radius);
        setDoubleField(view, "minZ", centerZ - radius);
        setDoubleField(view, "maxZ", centerZ + radius);
    }

    /**
     * A direct view-bounds adjustment bypasses RoadMapScreen's normal mouse-driven
     * debounce. Explicitly request a new snapshot so structures and connections use
     * the same rectangle as the terrain sampling plan.
     */
    private static void requestRoadWeaverViewportRefresh(Screen screen) throws ReflectiveOperationException {
        screen.getClass().getMethod("onRequestView").invoke(screen);
    }

    private static boolean roadWeaverOverlayLoading(Screen screen) {
        try {
            Field loadSession = screen.getClass().getDeclaredField("loadSession");
            loadSession.setAccessible(true);
            return loadSession.get(screen) != null;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // If an older RoadWeaver does not expose this implementation detail, a
            // bounded settle interval still prevents an immediate stale capture.
            return false;
        }
    }

    private static JsonObject roadWeaverOverlayState(Screen screen) {
        JsonObject data = roadWeaverViewBounds(screen);
        try {
            Field snapshotField = screen.getClass().getDeclaredField("snapshot");
            snapshotField.setAccessible(true);
            Object snapshot = snapshotField.get(screen);
            if (snapshot != null) {
                int structures = ((Number) snapshot.getClass().getMethod("structuresCount").invoke(snapshot)).intValue();
                int planned = ((Number) snapshot.getClass().getMethod("plannedCount").invoke(snapshot)).intValue();
                int generating = ((Number) snapshot.getClass().getMethod("generatingCount").invoke(snapshot)).intValue();
                int completed = ((Number) snapshot.getClass().getMethod("completedCount").invoke(snapshot)).intValue();
                data.addProperty("roadWeaverOverlayStructures", structures);
                data.addProperty("roadWeaverOverlayPlanned", planned);
                data.addProperty("roadWeaverOverlayGenerating", generating);
                data.addProperty("roadWeaverOverlayCompleted", completed);
            }
            data.addProperty("roadWeaverOverlayLoading", roadWeaverOverlayLoading(screen));
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            data.addProperty("roadWeaverOverlayState", "unavailable");
        }
        return data;
    }

    private static void setDoubleField(Object target, String name, double value) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.setDouble(target, value);
    }

    private static Path biomeAuditPath(String requestedOutput) throws IOException {
        String fileName = requestedOutput == null || requestedOutput.isBlank()
                ? "biome_contiguity_audit.json"
                : Path.of(requestedOutput).getFileName().toString();
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".json")) fileName += ".json";
        ensureAutomationDir();
        return AUTOMATION_DIR.resolve(fileName);
    }

    private static Path terrainAuditPath(String requestedOutput) throws IOException {
        String fileName = requestedOutput == null || requestedOutput.isBlank()
                ? "terrain_relief_audit.json"
                : Path.of(requestedOutput).getFileName().toString();
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".json")) fileName += ".json";
        ensureAutomationDir();
        return AUTOMATION_DIR.resolve(fileName);
    }

    private static String startRoadWeaverMapSampling(Minecraft minecraft,
                                                      PendingRoadMapResampleCapture capture,
                                                      RoadWeaverSamplingTile tile)
            throws ReflectiveOperationException {
        if (minecraft.getSingleplayerServer() == null) {
            return "WORLD_UNAVAILABLE";
        }
        ClassLoader loader = capture.samplingService.getClass().getClassLoader();
        Class<?> boundsClass = Class.forName(
                "net.shiroha233.roadweaver.map.tile.sampling.MapSamplingBounds", true, loader);
        Object bounds = boundsClass.getConstructor(int.class, int.class, int.class, int.class)
                .newInstance(tile.minX, tile.minZ, tile.maxX, tile.maxZ);
        Method start = null;
        for (Method candidate : capture.samplingService.getClass().getMethods()) {
            if (candidate.getName().equals("start") && candidate.getParameterCount() == 2) {
                start = candidate;
                break;
            }
        }
        if (start == null) {
            throw new NoSuchMethodException("RoadWeaver MapTerrainSamplingService.start");
        }
        return String.valueOf(start.invoke(capture.samplingService, minecraft.getSingleplayerServer().overworld(), bounds));
    }

    private static void failRoadWeaverResample(String message) {
        pendingRoadMapResampleCapture = null;
        automationActionFailed = true;
        writeStatus("error", message, null);
    }

    private static JsonObject roadWeaverViewBounds(Screen screen) {
        JsonObject data = new JsonObject();
        try {
            Field viewField = screen.getClass().getDeclaredField("view");
            viewField.setAccessible(true);
            Object view = viewField.get(screen);
            double minX = ((Number) view.getClass().getMethod("getMinX").invoke(view)).doubleValue();
            double maxX = ((Number) view.getClass().getMethod("getMaxX").invoke(view)).doubleValue();
            double minZ = ((Number) view.getClass().getMethod("getMinZ").invoke(view)).doubleValue();
            double maxZ = ((Number) view.getClass().getMethod("getMaxZ").invoke(view)).doubleValue();
            data.addProperty("roadWeaverMinX", minX);
            data.addProperty("roadWeaverMaxX", maxX);
            data.addProperty("roadWeaverMinZ", minZ);
            data.addProperty("roadWeaverMaxZ", maxZ);
            data.addProperty("roadWeaverViewWidth", maxX - minX);
            data.addProperty("roadWeaverViewHeight", maxZ - minZ);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            data.addProperty("roadWeaverViewBounds", "unavailable");
        }
        return data;
    }

    private static JsonObject roadWeaverSamplingBounds(Screen screen, RoadWeaverSamplingTile tile) {
        JsonObject data = roadWeaverViewBounds(screen);
        if (tile != null) {
            data.addProperty("roadWeaverSampleMinX", tile.minX);
            data.addProperty("roadWeaverSampleMaxX", tile.maxX);
            data.addProperty("roadWeaverSampleMinZ", tile.minZ);
            data.addProperty("roadWeaverSampleMaxZ", tile.maxZ);
            data.addProperty("roadWeaverSampleWidth", tile.maxX - tile.minX + 1L);
            data.addProperty("roadWeaverSampleHeight", tile.maxZ - tile.minZ + 1L);
        }
        return data;
    }

    private static boolean hasPendingAutomationAction() {
        return pendingScreenshot != null
                || pendingRoadMapCapture != null
                || pendingRoadMapResampleCapture != null
                || pendingAmiSearch != null
                || pendingClientStopTicks >= 0
                || automationActionFailed;
    }

    private static void writeStatus(String state, String message, Path screenshotPath) {
        writeStatus(state, message, screenshotPath, null);
    }

    private static void writeStatus(String state, String message, Path screenshotPath, JsonObject data) {
        ensureAutomationDir();
        JsonObject status = buildStatus(state, message, screenshotPath, data);
        String json = GSON.toJson(status);
        lastStatusJson = json;

        try {
            Files.writeString(STATUS_FILE, json);
            Files.writeString(READY_FILE, json);
        } catch (IOException e) {
            AutoMineTesting.LOGGER.warn("Failed to write AutoMine status", e);
        }
    }

    private static JsonObject buildStatus(String state, String message, Path screenshotPath, JsonObject data) {
        Minecraft minecraft = Minecraft.getInstance();
        boolean inWorld = minecraft.level != null && minecraft.player != null;
        boolean worldReady = isWorldReadyForInteraction();
        boolean readyForWorldCreation = !inWorld && minecraft.screen instanceof TitleScreen;
        if (readyForWorldCreation && "error".equals(automationPhase) && !"error".equals(state)) {
            automationPhase = "manual";
            automationMessage = "";
        }
        if (worldReady && !"error".equals(state)) {
            automationPhase = "in_world";
            automationMessage = "World loaded.";
            if (worldLoadStartedAtMs > 0L && worldLoadFinishedAtMs < 0L) {
                worldLoadFinishedAtMs = System.currentTimeMillis();
            }
        }
        if ("error".equals(state)) {
            automationPhase = "error";
            automationMessage = message == null ? "Automation action failed." : message;
        }
        JsonObject status = new JsonObject();
        status.addProperty("state", state);
        status.addProperty("time", Instant.now().toString());
        status.addProperty("inWorld", inWorld);
        status.addProperty("worldReady", worldReady);
        status.addProperty("readyForWorldCreation", readyForWorldCreation);
        status.addProperty("automationPhase", automationPhase);
        status.addProperty("automationWorld", AUTO_WORLD_NAME);
        if (worldLoadStartedAtMs > 0L) {
            long finish = worldLoadFinishedAtMs > 0L ? worldLoadFinishedAtMs : System.currentTimeMillis();
            status.addProperty("worldLoadElapsedMs", Math.max(0L, finish - worldLoadStartedAtMs));
        }
        status.addProperty("screen", minecraft.screen == null ? "" : minecraft.screen.getClass().getName());
        status.addProperty("windowWidth", minecraft.getWindow().getWidth());
        status.addProperty("windowHeight", minecraft.getWindow().getHeight());
        status.addProperty("guiScaledWidth", minecraft.getWindow().getGuiScaledWidth());
        status.addProperty("guiScaledHeight", minecraft.getWindow().getGuiScaledHeight());
        status.addProperty("guiScale", minecraft.getWindow().getGuiScale());
        if (minecraft.screen != null) {
            status.addProperty("screenWidth", minecraft.screen.width);
            status.addProperty("screenHeight", minecraft.screen.height);
            status.addProperty("screenTitle", minecraft.screen.getTitle().getString());
        }
        status.addProperty("commandFile", COMMAND_FILE.toAbsolutePath().toString());
        if (!automationMessage.isBlank()) {
            status.addProperty("automationMessage", automationMessage);
        }
        if (!currentCommandId.isBlank()) {
            status.addProperty("commandId", currentCommandId);
        } else if (!lastCommandId.isBlank() && lastCommandStatusTicks > 0) {
            status.addProperty("commandId", lastCommandId);
            lastCommandStatusTicks--;
        }
        if (message != null && !message.isBlank()) {
            status.addProperty("message", message);
        }
        if (screenshotPath != null) {
            status.addProperty("screenshot", screenshotPath.toAbsolutePath().toString());
        }
        if (data != null) {
            for (String key : data.keySet()) {
                status.add(key, data.get(key));
            }
        }
        return status;
    }

    private static void rememberCommandId(String commandId) {
        if (commandId == null || commandId.isBlank()) {
            return;
        }
        lastCommandId = commandId;
        lastCommandStatusTicks = 200;
    }

    private static void ensureAutomationDir() {
        try {
            Files.createDirectories(AUTOMATION_DIR);
        } catch (IOException e) {
            AutoMineTesting.LOGGER.warn("Failed to create AutoMine automation directory", e);
        }
    }

    private static void beginWorldLoad(String phase, String message) {
        automationPhase = phase;
        automationMessage = message;
        worldLoadStartedAtMs = System.currentTimeMillis();
        worldLoadFinishedAtMs = -1L;
    }

    private static String sanitizeScreenshotName(String requestedName) {
        String name = requestedName == null || requestedName.isBlank() ? "automine.png" : requestedName;
        name = Path.of(name).getFileName().toString();
        name = name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (!name.toLowerCase(Locale.ROOT).endsWith(".png")) {
            name = name + ".png";
        }
        return name;
    }

    private static Path screenshotPath(String screenshotName) {
        return GAME_DIRECTORY.resolve("screenshots").resolve(screenshotName);
    }

    private static String getString(JsonObject object, String key, String fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : fallback;
    }

    private static List<String> splitCsv(String value) {
        List<String> out = new ArrayList<>();
        if (value == null || value.isBlank()) {
            return out;
        }
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    private static List<String> normalizeFilters(List<String> values) {
        List<String> out = new ArrayList<>();
        if (values == null) {
            return out;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                out.add(value.trim().toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    private static JsonObject sortedCountObject(Map<String, Integer> counts) {
        JsonObject out = new JsonObject();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .forEach(entry -> out.addProperty(entry.getKey(), entry.getValue()));
        return out;
    }

    private static void writeEntitySnapshot(double radius, boolean includePlayers, List<String> typeFilters,
                                            List<String> categoryFilters, String nameContains, int maxResults) {
        JsonObject data = new JsonObject();
        data.add("entitySnapshot", JsonParser.parseString(entitySnapshotJson(
                radius, includePlayers, typeFilters, categoryFilters, nameContains, maxResults)));
        writeStatus("ok", "Entity snapshot captured.", null, data);
    }

    private static int getInt(JsonObject object, String key, int fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsInt() : fallback;
    }

    private static double getDouble(JsonObject object, String key, double fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsDouble() : fallback;
    }

    private static boolean getBoolean(JsonObject object, String key, boolean fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsBoolean() : fallback;
    }

    private static String invokeAmiSearch(String query, int limit, String viewMode, String lens, boolean compact)
            throws ReflectiveOperationException {
        Class<?> api = Class.forName("com.sanhiruzu.ami.api.AmiRuntimeDebugApi");
        Method method = api.getMethod("searchJson", String.class, int.class, String.class, String.class, boolean.class);
        return (String) method.invoke(null, query, limit, viewMode, lens, compact);
    }

    private static String invokeAmiReindex(boolean forceProviderRebuild) throws ReflectiveOperationException {
        Class<?> api = Class.forName("com.sanhiruzu.ami.api.AmiRuntimeDebugApi");
        Method method = api.getMethod("requestReindexJson", boolean.class);
        return (String) method.invoke(null, forceProviderRebuild);
    }

    private static JsonObject invokeAmiStatus() throws ReflectiveOperationException {
        Class<?> api = Class.forName("com.sanhiruzu.ami.api.AmiRuntimeDebugApi");
        Method method = api.getMethod("statusJson");
        return JsonParser.parseString((String) method.invoke(null)).getAsJsonObject();
    }

    private static Path writeAmiOutput(String requestedOutput, String json) throws IOException {
        ensureAutomationDir();
        Path outputPath = DEFAULT_AMI_SEARCH_OUTPUT;
        if (requestedOutput != null && !requestedOutput.isBlank()) {
            outputPath = AUTOMATION_DIR.resolve(Path.of(requestedOutput).getFileName().toString());
        }
        Files.writeString(outputPath, json);
        return outputPath;
    }

    private static void moveBadCommand() {
        try {
            Files.move(COMMAND_FILE, AUTOMATION_DIR.resolve("command.bad.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
        }
    }

    private static final class PendingScreenshot {
        private final String name;
        private final String commandId;
        private int delayTicks;

        private PendingScreenshot(String name, int delayTicks, String commandId) {
            this.name = name;
            this.delayTicks = delayTicks;
            this.commandId = commandId == null ? "" : commandId;
        }
    }

    private static final class PendingRoadMapCapture {
        private final String name;
        private final int zoomOutSteps;
        private final int delayTicks;
        private final String commandId;
        private int layoutReadyTicks = 20;
        private int zoomDebounceTicks;
        private boolean zoomApplied;

        private PendingRoadMapCapture(String name, int zoomOutSteps, int delayTicks, String commandId) {
            this.name = name;
            this.zoomOutSteps = zoomOutSteps;
            this.delayTicks = delayTicks;
            this.commandId = commandId == null ? "" : commandId;
        }
    }

    private static final class PendingRoadMapResampleCapture {
        private final String name;
        private final int zoomOutSteps;
        private final int sampleZoomOutSteps;
        private final int delayTicks;
        private final String commandId;
        private final int mapCenterX;
        private final int mapCenterZ;
        private final int mapRadiusBlocks;
        private boolean zoomApplied;
        private boolean samplingStarted;
        private int layoutReadyTicks = 20;
        private int settleTicks;
        private int lastPercent = -1;
        private String lastStage = "";
        private Object samplingService;
        private boolean tilePlanCreated;
        private RoadWeaverSamplingTile sampleTile;
        private boolean overlayRefreshRequested;
        private int overlaySettleTicks;

        private PendingRoadMapResampleCapture(String name,
                                              int zoomOutSteps,
                                              int delayTicks,
                                              String commandId,
                                              int mapCenterX,
                                              int mapCenterZ,
                                              int mapRadiusBlocks) {
            this.name = name;
            this.zoomOutSteps = zoomOutSteps;
            this.sampleZoomOutSteps = Math.min(zoomOutSteps, ROADWEAVER_MAX_SAMPLE_ZOOM_OUT_STEPS);
            this.delayTicks = delayTicks;
            this.commandId = commandId == null ? "" : commandId;
            this.mapCenterX = mapCenterX;
            this.mapCenterZ = mapCenterZ;
            this.mapRadiusBlocks = mapRadiusBlocks;
        }
    }

    private static final class RoadWeaverSamplingTile {
        private final int minX;
        private final int minZ;
        private final int maxX;
        private final int maxZ;

        private RoadWeaverSamplingTile(int minX, int minZ, int maxX, int maxZ) {
            this.minX = minX;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxZ = maxZ;
        }
    }

    private static final class PendingAmiSearch {
        private final String query;
        private final int limit;
        private final String viewMode;
        private final String lens;
        private final boolean compact;
        private final String output;
        private final String commandId;
        private int timeoutTicks;

        private PendingAmiSearch(String query, int limit, String viewMode, String lens, boolean compact,
                                 String output, int timeoutTicks, String commandId) {
            this.query = query == null ? "" : query;
            this.limit = limit;
            this.viewMode = viewMode == null ? "LIST" : viewMode;
            this.lens = lens == null ? "ALL" : lens;
            this.compact = compact;
            this.output = output == null ? "" : output;
            this.timeoutTicks = timeoutTicks;
            this.commandId = commandId == null ? "" : commandId;
        }
    }
}
