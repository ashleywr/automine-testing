package com.sanhiruzu.automine;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class AutoMineHttpServer {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int DEFAULT_PORT = 47321;
    private static final int DEFAULT_REINDEX_TIMEOUT_MS = 120_000;
    private static final int DEFAULT_WORLD_TIMEOUT_MS = 180_000;
    private static HttpServer server;
    // Dense, generator-only audits are diagnostic work. They must not keep an
    // HTTP request open long enough for a caller or reverse proxy to cancel it.
    private static CompletableFuture<JsonObject> terrainAuditTask;

    private AutoMineHttpServer() {
    }

    public static synchronized void start() {
        if (server != null) {
            return;
        }

        int port = Integer.getInteger("automine.httpPort", DEFAULT_PORT);
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 0);
            server.createContext("/status", AutoMineHttpServer::handleStatus);
            server.createContext("/performance", AutoMineHttpServer::handlePerformance);
            server.createContext("/automine/status", AutoMineHttpServer::handleStatus);
            server.createContext("/world/open", AutoMineHttpServer::handleWorldOpen);
            server.createContext("/world/create", AutoMineHttpServer::handleWorldCreate);
            server.createContext("/world/close", AutoMineHttpServer::handleWorldClose);
            server.createContext("/client/quit", AutoMineHttpServer::handleClientQuit);
            server.createContext("/roadweaver/map/screenshot", AutoMineHttpServer::handleRoadWeaverMapScreenshot);
            server.createContext("/roadweaver/map/resample-screenshot", AutoMineHttpServer::handleRoadWeaverMapResampleScreenshot);
            server.createContext("/worldgen/biome-audit", AutoMineHttpServer::handleBiomeContiguityAudit);
            server.createContext("/worldgen/seedgen-registry", AutoMineHttpServer::handleSeedgenRegistry);
            server.createContext("/worldgen/terrain-audit", AutoMineHttpServer::handleTerrainReliefAudit);
            server.createContext("/worldgen/terrain-audit/status", AutoMineHttpServer::handleTerrainReliefAuditStatus);
            server.createContext("/worldgen/chunk-generation-audit", AutoMineHttpServer::handleChunkGenerationAudit);
            server.createContext("/inventory/open", AutoMineHttpServer::handleInventoryOpen);
            server.createContext("/inventory/screenshot", AutoMineHttpServer::handleInventoryScreenshot);
            server.createContext("/screen/open", AutoMineHttpServer::handleScreenOpen);
            server.createContext("/screen/info", AutoMineHttpServer::handleScreenInfo);
            server.createContext("/screen/targets", AutoMineHttpServer::handleScreenTargets);
            server.createContext("/screen/click", AutoMineHttpServer::handleScreenClick);
            server.createContext("/screen/click-target", AutoMineHttpServer::handleScreenClickTarget);
            server.createContext("/screen/key", AutoMineHttpServer::handleScreenKey);
            server.createContext("/screen/type", AutoMineHttpServer::handleScreenType);
            server.createContext("/screen/hover", AutoMineHttpServer::handleScreenHover);
            server.createContext("/screen/scroll", AutoMineHttpServer::handleScreenScroll);
            server.createContext("/world/key", AutoMineHttpServer::handleWorldKey);
            server.createContext("/screen/close", AutoMineHttpServer::handleScreenClose);
            server.createContext("/screenshot", AutoMineHttpServer::handleScreenshot);
            server.createContext("/command", AutoMineHttpServer::handleRunCommand);
            server.createContext("/chat", AutoMineHttpServer::handleRunChat);
            server.createContext("/entities/snapshot", AutoMineHttpServer::handleEntitySnapshot);
            server.createContext("/world/interact-block", AutoMineHttpServer::handleInteractBlock);
            server.createContext("/world/interact-entity", AutoMineHttpServer::handleInteractEntity);
            server.createContext("/world/use-item", AutoMineHttpServer::handleUseItem);
            server.createContext("/ami/status", AutoMineHttpServer::handleAmiStatus);
            server.createContext("/ami/quest-status", AutoMineHttpServer::handleAmiQuestStatus);
            server.createContext("/ami/search", AutoMineHttpServer::handleAmiSearch);
            server.createContext("/ami/reindex", AutoMineHttpServer::handleAmiReindex);
            server.createContext("/ami/reindex-and-search", AutoMineHttpServer::handleAmiReindexAndSearch);
            server.setExecutor(Executors.newCachedThreadPool(r -> {
                Thread thread = new Thread(r, "AutoMine HTTP");
                thread.setDaemon(true);
                return thread;
            }));
            server.start();
            AutoMineTesting.LOGGER.info("AutoMine HTTP listening on http://127.0.0.1:{}", port);
        } catch (IOException e) {
            AutoMineTesting.LOGGER.warn("Failed to start AutoMine HTTP server", e);
        }
    }

    private static void handleStatus(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "GET")) return;
        writeJson(exchange, 200, ClientAutomation.statusSnapshotJson());
    }

    private static void handlePerformance(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) return;
        JsonObject request = readJson(exchange);
        try {
            writeJson(exchange, 200, runOnClientThread(() -> GSON.toJson(ClientPerformanceProbe.action(request))));
        } catch (Exception | LinkageError e) {
            writeError(exchange, 500, "Performance diagnostic failed: " + rootMessage(e));
        }
    }

    private static void handleSeedgenRegistry(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "GET")) return;
        try {
            writeJson(exchange, 200, ClientAutomation.seedgenRegistrySnapshotJson());
        } catch (Exception | LinkageError e) {
            writeError(exchange, 500, "Failed to export SeedGen registry: " + rootMessage(e));
        }
    }

    private static void handleWorldOpen(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) return;
        JsonObject request = readJson(exchange);
        try {
            JsonObject title = waitForTitleReady(getInt(request, "worldTimeoutMs", DEFAULT_WORLD_TIMEOUT_MS));
            if (!title.get("ready").getAsBoolean()) {
                writeJson(exchange, 504, GSON.toJson(title));
                return;
            }
            ClientAutomation.openWorldAsync(getString(request, "world", getString(request, "name", "New World")));
            JsonObject world = waitForWorldReady(getInt(request, "worldTimeoutMs", DEFAULT_WORLD_TIMEOUT_MS));
            writeJson(exchange, world.get("ready").getAsBoolean() ? 200 : 504, GSON.toJson(world));
        } catch (Exception | LinkageError e) {
            writeError(exchange, 500, "Failed to open world: " + rootMessage(e));
        }
    }

    private static void handleWorldCreate(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) return;
        JsonObject request = readJson(exchange);
        try {
            JsonObject title = waitForTitleReady(getInt(request, "worldTimeoutMs", DEFAULT_WORLD_TIMEOUT_MS));
            if (!title.get("ready").getAsBoolean()) {
                writeJson(exchange, 504, GSON.toJson(title));
                return;
            }
            ClientAutomation.createWorldAsync(
                    getString(request, "world", getString(request, "name", "New World")),
                    getOptionalLong(request, "seed"),
                    getStringList(request, "dataPacks"),
                    getString(request, "worldPreset", ""));
            JsonObject world = waitForWorldReady(getInt(request, "worldTimeoutMs", DEFAULT_WORLD_TIMEOUT_MS));
            writeJson(exchange, world.get("ready").getAsBoolean() ? 200 : 504, GSON.toJson(world));
        } catch (Exception | LinkageError e) {
            writeError(exchange, 500, "Failed to create world: " + rootMessage(e));
        }
    }

    private static void handleWorldClose(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) return;
        try {
            ClientAutomation.closeWorldAsync();
            JsonObject title = waitForTitleReady(DEFAULT_WORLD_TIMEOUT_MS, false);
            writeJson(exchange, title.get("ready").getAsBoolean() ? 200 : 504, GSON.toJson(title));
        } catch (Exception | LinkageError e) {
            writeError(exchange, 500, "Failed to close world: " + rootMessage(e));
        }
    }

    private static void handleClientQuit(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) return;
        try {
            writeClientAction(exchange, runOnClientThread(() -> {
                ClientAutomation.requestClientStop();
                return ClientAutomation.lastStatusJson();
            }));
        } catch (Exception | LinkageError e) {
            writeError(exchange, 500, "Failed to close the client: " + rootMessage(e));
        }
    }

    private static void handleRoadWeaverMapScreenshot(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) return;
        JsonObject request = readJson(exchange);
        JsonObject world = waitForWorldReady(getInt(request, "worldTimeoutMs", DEFAULT_WORLD_TIMEOUT_MS));
        if (!world.get("ready").getAsBoolean()) {
            writeJson(exchange, 504, GSON.toJson(world));
            return;
        }
        try {
            writeClientAction(exchange, runOnClientThread(() -> {
                ClientAutomation.openRoadWeaverMapAndScreenshot(
                        getString(request, "screenshot", "roadweaver_map.png"),
                        getInt(request, "zoomOutSteps", 8),
                        getInt(request, "delayTicks", 80));
                return ClientAutomation.lastStatusJson();
            }));
        } catch (Exception | LinkageError e) {
            writeError(exchange, 500, "Failed to capture RoadWeaver map: " + rootMessage(e));
        }
    }

    private static void handleRoadWeaverMapResampleScreenshot(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) return;
        JsonObject request = readJson(exchange);
        JsonObject world = waitForWorldReady(getInt(request, "worldTimeoutMs", DEFAULT_WORLD_TIMEOUT_MS));
        if (!world.get("ready").getAsBoolean()) {
            writeJson(exchange, 504, GSON.toJson(world));
            return;
        }
        try {
            writeClientAction(exchange, runOnClientThread(() -> {
                ClientAutomation.openRoadWeaverMapResampleAndScreenshot(
                        getString(request, "screenshot", "roadweaver_map.png"),
                        getInt(request, "zoomOutSteps", 8),
                        getInt(request, "delayTicks", 80),
                        getInt(request, "mapCenterX", 0),
                        getInt(request, "mapCenterZ", 0),
                        getInt(request, "mapRadiusBlocks", 0));
                return ClientAutomation.lastStatusJson();
            }));
        } catch (Exception | LinkageError e) {
            writeError(exchange, 500, "Failed to resample and capture RoadWeaver map: " + rootMessage(e));
        }
    }

    private static void handleBiomeContiguityAudit(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) return;
        JsonObject request = readJson(exchange);
        JsonObject world = waitForWorldReady(getInt(request, "worldTimeoutMs", DEFAULT_WORLD_TIMEOUT_MS));
        if (!world.get("ready").getAsBoolean()) {
            writeJson(exchange, 504, GSON.toJson(world));
            return;
        }
        try {
            JsonObject result = ClientAutomation.captureBiomeContiguityAudit(
                    getString(request, "output", "biome_contiguity_audit.json"),
                    getInt(request, "mapCenterX", 0),
                    getInt(request, "mapCenterZ", 0),
                    getInt(request, "mapRadiusBlocks", 12_288),
                    getInt(request, "sampleStepBlocks", 512));
            writeJson(exchange, 200, GSON.toJson(result));
        } catch (Exception | LinkageError e) {
            writeError(exchange, 500, "Failed to audit biome contiguity: " + rootMessage(e));
        }
    }

    private static void handleTerrainReliefAudit(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) return;
        JsonObject request = readJson(exchange);
        JsonObject world = waitForWorldReady(getInt(request, "worldTimeoutMs", DEFAULT_WORLD_TIMEOUT_MS));
        if (!world.get("ready").getAsBoolean()) {
            writeJson(exchange, 504, GSON.toJson(world));
            return;
        }
        String output = getString(request, "output", "terrain_relief_audit.json");
        int centerX = getInt(request, "mapCenterX", 0);
        int centerZ = getInt(request, "mapCenterZ", 0);
        int radius = getInt(request, "mapRadiusBlocks", 12_288);
        int sampleStep = getInt(request, "sampleStepBlocks", 256);
        boolean asynchronous = request.has("async") && request.get("async").getAsBoolean();
        if (asynchronous) {
            synchronized (AutoMineHttpServer.class) {
                if (terrainAuditTask != null && !terrainAuditTask.isDone()) {
                    writeError(exchange, 409, "A terrain relief audit is already running.");
                    return;
                }
                terrainAuditTask = CompletableFuture.supplyAsync(() -> ClientAutomation.captureTerrainReliefAudit(
                        output, centerX, centerZ, radius, sampleStep));
            }
            JsonObject accepted = new JsonObject();
            accepted.addProperty("state", "RUNNING");
            accepted.addProperty("output", output);
            writeJson(exchange, 202, GSON.toJson(accepted));
            return;
        }
        try {
            JsonObject result = ClientAutomation.captureTerrainReliefAudit(output, centerX, centerZ, radius, sampleStep);
            writeJson(exchange, 200, GSON.toJson(result));
        } catch (Exception | LinkageError e) {
            writeError(exchange, 500, "Failed to audit terrain relief: " + rootMessage(e));
        }
    }

    private static void handleTerrainReliefAuditStatus(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "GET")) return;
        CompletableFuture<JsonObject> task;
        synchronized (AutoMineHttpServer.class) {
            task = terrainAuditTask;
        }
        if (task == null) {
            writeError(exchange, 404, "No terrain relief audit has been requested.");
            return;
        }
        if (!task.isDone()) {
            JsonObject pending = new JsonObject();
            pending.addProperty("state", "RUNNING");
            writeJson(exchange, 202, GSON.toJson(pending));
            return;
        }
        try {
            JsonObject completed = task.join();
            completed.addProperty("state", "COMPLETED");
            writeJson(exchange, 200, GSON.toJson(completed));
        } catch (Exception | LinkageError e) {
            writeError(exchange, 500, "Terrain relief audit failed: " + rootMessage(e));
        }
    }

    private static void handleChunkGenerationAudit(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) return;
        JsonObject request = readJson(exchange);
        JsonObject world = waitForWorldReady(getInt(request, "worldTimeoutMs", DEFAULT_WORLD_TIMEOUT_MS));
        if (!world.get("ready").getAsBoolean()) {
            writeJson(exchange, 504, GSON.toJson(world));
            return;
        }
        try {
            JsonObject result = ClientAutomation.captureChunkGenerationAudit(
                    getString(request, "output", "chunk_generation_audit.json"),
                    getStringList(request, "targets"));
            writeJson(exchange, 200, GSON.toJson(result));
        } catch (Exception | LinkageError e) {
            writeError(exchange, 500, "Failed to audit chunk generation: " + rootMessage(e));
        }
    }

    private static void handleInventoryOpen(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) return;
        JsonObject request = readJson(exchange);
        JsonObject world = waitForWorldReady(getInt(request, "worldTimeoutMs", DEFAULT_WORLD_TIMEOUT_MS));
        if (!world.get("ready").getAsBoolean()) {
            writeJson(exchange, 504, GSON.toJson(world));
            return;
        }
        try {
            writeClientAction(exchange, runOnClientThread(() -> {
                ClientAutomation.openInventory();
                return ClientAutomation.lastStatusJson();
            }));
        } catch (Exception | LinkageError e) {
            writeError(exchange, 500, "Failed to open inventory: " + rootMessage(e));
        }
    }

    private static void handleInventoryScreenshot(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) return;
        JsonObject request = readJson(exchange);
        JsonObject world = waitForWorldReady(getInt(request, "worldTimeoutMs", DEFAULT_WORLD_TIMEOUT_MS));
        if (!world.get("ready").getAsBoolean()) {
            writeJson(exchange, 504, GSON.toJson(world));
            return;
        }
        try {
            writeClientAction(exchange, runOnClientThread(() -> {
                if (ClientAutomation.openInventory()) {
                    ClientAutomation.scheduleScreenshot(
                            getString(request, "screenshot", "inventory.png"),
                            getInt(request, "delayTicks", 3)
                    );
                }
                return ClientAutomation.lastStatusJson();
            }));
        } catch (Exception | LinkageError e) {
            writeError(exchange, 500, "Failed to capture inventory screenshot: " + rootMessage(e));
        }
    }

    private static void handleScreenOpen(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) return;
        JsonObject request = readJson(exchange);
        JsonObject world = waitForWorldReady(getInt(request, "worldTimeoutMs", DEFAULT_WORLD_TIMEOUT_MS));
        if (!world.get("ready").getAsBoolean()) {
            writeJson(exchange, 504, GSON.toJson(world));
            return;
        }
        try {
            writeClientAction(exchange, runOnClientThread(() -> {
                ClientAutomation.openScreen(getString(request, "screen", getString(request, "class", "")));
                return ClientAutomation.lastStatusJson();
            }));
        } catch (Exception | LinkageError e) {
            writeError(exchange, 500, "Failed to open screen: " + rootMessage(e));
        }
    }

    private static void handleScreenInfo(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "GET")) return;
        try {
            writeClientAction(exchange, runOnClientThread(() -> {
                ClientAutomation.writeScreenInfo();
                return ClientAutomation.lastStatusJson();
            }));
        } catch (Exception | LinkageError e) {
            writeError(exchange, 500, "Failed to inspect screen: " + rootMessage(e));
        }
    }

    private static void handleScreenTargets(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "GET")) return;
        try {
            writeClientAction(exchange, runOnClientThread(() -> {
                ClientAutomation.writeScreenTargets();
                return ClientAutomation.lastStatusJson();
            }));
        } catch (Exception | LinkageError e) {
            writeError(exchange, 500, "Failed to inspect screen targets: " + rootMessage(e));
        }
    }

    private static void handleScreenClick(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) return;
        JsonObject request = readJson(exchange);
        try {
            writeClientAction(exchange, runOnClientThread(() -> {
                ClientAutomation.clickScreen(
                        getDouble(request, "x", 0.0D),
                        getDouble(request, "y", 0.0D),
                        getInt(request, "button", 0));
                if (request.has("screenshot")) {
                    ClientAutomation.scheduleScreenshot(
                            getString(request, "screenshot", "screen_click.png"),
                            getInt(request, "delayTicks", 3)
                    );
                }
                return ClientAutomation.lastStatusJson();
            }));
        } catch (Exception | LinkageError e) {
            writeError(exchange, 500, "Failed to click screen: " + rootMessage(e));
        }
    }

    private static void handleScreenClickTarget(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) return;
        JsonObject request = readJson(exchange);
        try {
            writeClientAction(exchange, runOnClientThread(() -> {
                JsonObject data = ClientAutomation.clickScreenTargetWithStatus(
                        getString(request, "target", ""),
                        getInt(request, "button", 0),
                        getBoolean(request, "onlyScreenshotOnChange", false));
                if (request.has("screenshot")
                        && ClientAutomation.shouldScheduleTargetScreenshot(
                        data,
                        getBoolean(request, "onlyScreenshotOnChange", false))) {
                    ClientAutomation.scheduleScreenshot(
                            getString(request, "screenshot", "screen_target_click.png"),
                            getInt(request, "delayTicks", 3)
                    );
                }
                return ClientAutomation.lastStatusJson();
            }));
        } catch (Exception | LinkageError e) {
            writeError(exchange, 500, "Failed to click screen target: " + rootMessage(e));
        }
    }

    private static void handleScreenKey(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) return;
        JsonObject request = readJson(exchange);
        try {
            writeClientAction(exchange, runOnClientThread(() -> {
                ClientAutomation.pressKey(
                        getInt(request, "keyCode", 0),
                        getInt(request, "scanCode", 0),
                        getInt(request, "modifiers", 0));
                if (request.has("screenshot")) {
                    ClientAutomation.scheduleScreenshot(
                            getString(request, "screenshot", "screen_key.png"),
                            getInt(request, "delayTicks", 3)
                    );
                }
                return ClientAutomation.lastStatusJson();
            }));
        } catch (Exception | LinkageError e) {
            writeError(exchange, 500, "Failed to press key: " + rootMessage(e));
        }
    }

    private static void handleScreenType(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) return;
        JsonObject request = readJson(exchange);
        try {
            writeClientAction(exchange, runOnClientThread(() -> {
                ClientAutomation.typeScreenText(getString(request, "text", ""));
                return ClientAutomation.lastStatusJson();
            }));
        } catch (Exception | LinkageError e) {
            writeError(exchange, 500, "Failed to type into screen: " + rootMessage(e));
        }
    }

    private static void handleScreenClose(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) return;
        readJson(exchange);
        try {
            writeClientAction(exchange, runOnClientThread(() -> {
                ClientAutomation.closeScreen();
                return ClientAutomation.lastStatusJson();
            }));
        } catch (Exception | LinkageError e) {
            writeError(exchange, 500, "Failed to close screen: " + rootMessage(e));
        }
    }

    private static void handleScreenHover(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) return;
        JsonObject request = readJson(exchange);
        try {
            writeClientAction(exchange, runOnClientThread(() -> {
                ClientAutomation.hoverScreen(getDouble(request, "x", -1), getDouble(request, "y", -1));
                return ClientAutomation.lastStatusJson();
            }));
        } catch (Exception | LinkageError e) {
            writeError(exchange, 500, "Failed to move screen pointer: " + rootMessage(e));
        }
    }

    private static void handleScreenScroll(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) return;
        JsonObject request = readJson(exchange);
        try {
            writeClientAction(exchange, runOnClientThread(() -> {
                ClientAutomation.scrollScreen(getDouble(request, "x", -1), getDouble(request, "y", -1),
                        getDouble(request, "horizontal", 0), getDouble(request, "vertical", 0));
                return ClientAutomation.lastStatusJson();
            }));
        } catch (Exception | LinkageError e) {
            writeError(exchange, 500, "Failed to scroll screen: " + rootMessage(e));
        }
    }

    private static void handleWorldKey(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) return;
        JsonObject request = readJson(exchange);
        try {
            writeClientAction(exchange, runOnClientThread(() -> {
                ClientAutomation.tapWorldKey(getInt(request, "keyCode", -1));
                return ClientAutomation.lastStatusJson();
            }));
        } catch (Exception | LinkageError e) {
            writeError(exchange, 500, "Failed to tap world key: " + rootMessage(e));
        }
    }

    private static void handleScreenshot(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) return;
        JsonObject request = readJson(exchange);
        try {
            writeClientAction(exchange, runOnClientThread(() -> {
                ClientAutomation.scheduleScreenshot(
                        getString(request, "screenshot", "automine.png"),
                        getInt(request, "delayTicks", 3)
                );
                return ClientAutomation.lastStatusJson();
            }));
        } catch (Exception | LinkageError e) {
            writeError(exchange, 500, "Failed to schedule screenshot: " + rootMessage(e));
        }
    }

    private static void handleRunCommand(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) return;
        JsonObject request = readJson(exchange);
        JsonObject world = waitForWorldReady(getInt(request, "worldTimeoutMs", DEFAULT_WORLD_TIMEOUT_MS));
        if (!world.get("ready").getAsBoolean()) {
            writeJson(exchange, 504, GSON.toJson(world));
            return;
        }
        try {
            writeClientAction(exchange, runOnClientThread(() -> {
                ClientAutomation.runCommand(getString(request, "command", ""));
                return ClientAutomation.lastStatusJson();
            }));
        } catch (Exception | LinkageError e) {
            writeError(exchange, 500, "Failed to run command: " + rootMessage(e));
        }
    }

    private static void handleRunChat(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) return;
        JsonObject request = readJson(exchange);
        JsonObject world = waitForWorldReady(getInt(request, "worldTimeoutMs", DEFAULT_WORLD_TIMEOUT_MS));
        if (!world.get("ready").getAsBoolean()) {
            writeJson(exchange, 504, GSON.toJson(world));
            return;
        }
        try {
            writeClientAction(exchange, runOnClientThread(() -> {
                ClientAutomation.runChat(getString(request, "message", getString(request, "chat", "")));
                return ClientAutomation.lastStatusJson();
            }));
        } catch (Exception | LinkageError e) {
            writeError(exchange, 500, "Failed to send chat: " + rootMessage(e));
        }
    }

    private static void handleEntitySnapshot(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) return;
        JsonObject request = readJson(exchange);
        JsonObject world = waitForWorldReady(getInt(request, "worldTimeoutMs", DEFAULT_WORLD_TIMEOUT_MS));
        if (!world.get("ready").getAsBoolean()) {
            writeJson(exchange, 504, GSON.toJson(world));
            return;
        }
        try {
            writeJson(exchange, 200, runOnClientThread(() -> ClientAutomation.entitySnapshotJson(
                    getDouble(request, "radius", 128.0D),
                    getBoolean(request, "includePlayers", false),
                    getStringList(request, "types"),
                    getStringList(request, "categories"),
                    getString(request, "nameContains", ""),
                    getInt(request, "maxResults", 1000))));
        } catch (Exception | LinkageError e) {
            writeError(exchange, 500, "Failed to inspect entities: " + rootMessage(e));
        }
    }

    private static void handleInteractBlock(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) return;
        JsonObject request = readJson(exchange);
        JsonObject world = waitForWorldReady(getInt(request, "worldTimeoutMs", DEFAULT_WORLD_TIMEOUT_MS));
        if (!world.get("ready").getAsBoolean()) {
            writeJson(exchange, 504, GSON.toJson(world));
            return;
        }
        try {
            writeClientAction(exchange, runOnClientThread(() -> {
                ClientAutomation.interactBlock(
                    getInt(request, "x", 0),
                    getInt(request, "y", 0),
                    getInt(request, "z", 0),
                    getInt(request, "button", 1),
                    net.minecraft.core.Direction.valueOf(getString(request, "face", "UP").toUpperCase(Locale.ROOT)),
                    request.has("sneaking") ? request.get("sneaking").getAsBoolean() : null
                );
                return ClientAutomation.lastStatusJson();
            }));
        } catch (Exception | LinkageError e) {
            writeError(exchange, 500, "Failed to interact with block: " + rootMessage(e));
        }
    }

    private static void handleInteractEntity(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) return;
        JsonObject request = readJson(exchange);
        JsonObject world = waitForWorldReady(getInt(request, "worldTimeoutMs", DEFAULT_WORLD_TIMEOUT_MS));
        if (!world.get("ready").getAsBoolean()) {
            writeJson(exchange, 504, GSON.toJson(world));
            return;
        }
        try {
            writeClientAction(exchange, runOnClientThread(() -> {
                ClientAutomation.interactEntity(getInt(request, "entityId", -1),
                    request.has("sneaking") ? request.get("sneaking").getAsBoolean() : null);
                return ClientAutomation.lastStatusJson();
            }));
        } catch (Exception | LinkageError e) {
            writeError(exchange, 500, "Failed to interact with entity: " + rootMessage(e));
        }
    }

    private static void handleUseItem(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) return;
        JsonObject request = readJson(exchange);
        JsonObject world = waitForWorldReady(getInt(request, "worldTimeoutMs", DEFAULT_WORLD_TIMEOUT_MS));
        if (!world.get("ready").getAsBoolean()) {
            writeJson(exchange, 504, GSON.toJson(world));
            return;
        }
        try {
            writeClientAction(exchange, runOnClientThread(() -> {
                ClientAutomation.useHeldItem(
                    request.has("sneaking") ? request.get("sneaking").getAsBoolean() : null,
                    getInt(request, "holdTicks", 0));
                return ClientAutomation.lastStatusJson();
            }));
        } catch (Exception | LinkageError e) {
            writeError(exchange, 500, "Failed to use held item: " + rootMessage(e));
        }
    }

    private static void handleAmiStatus(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "GET")) return;
        try {
            JsonObject world = waitForWorldReady(DEFAULT_WORLD_TIMEOUT_MS);
            if (!world.get("ready").getAsBoolean()) {
                writeJson(exchange, 504, GSON.toJson(world));
                return;
            }
            writeJson(exchange, 200, ClientAutomation.amiStatusJson());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            writeError(exchange, 500, "Failed to inspect AMI status: " + rootMessage(e));
        }
    }

    private static void handleAmiQuestStatus(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) return;
        JsonObject request = readJson(exchange);
        JsonObject world = waitForWorldReady(getInt(request, "worldTimeoutMs", DEFAULT_WORLD_TIMEOUT_MS));
        if (!world.get("ready").getAsBoolean()) {
            writeJson(exchange, 504, GSON.toJson(world));
            return;
        }
        try {
            writeClientAction(exchange, runOnClientThread(() -> {
                ClientAutomation.writeAmiQuestStatus(getString(request, "item", ""));
                return ClientAutomation.lastStatusJson();
            }));
        } catch (Exception | LinkageError e) {
            writeError(exchange, 500, "Failed to inspect AMI quest status: " + rootMessage(e));
        }
    }

    private static void handleAmiSearch(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) return;
        JsonObject request = readJson(exchange);
        try {
            JsonObject world = waitForWorldReady(getInt(request, "worldTimeoutMs", DEFAULT_WORLD_TIMEOUT_MS));
            if (!world.get("ready").getAsBoolean()) {
                writeJson(exchange, 504, GSON.toJson(world));
                return;
            }
            writeJson(exchange, 200, ClientAutomation.amiSearchJson(
                    getString(request, "query", ""),
                    getInt(request, "limit", 100),
                    getString(request, "viewMode", "LIST"),
                    getString(request, "lens", "ALL"),
                    getBoolean(request, "compact", false)
            ));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            writeError(exchange, 500, "Failed to run AMI search: " + rootMessage(e));
        }
    }

    private static void handleAmiReindex(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) return;
        JsonObject request = readJson(exchange);
        try {
            JsonObject world = waitForWorldReady(getInt(request, "worldTimeoutMs", DEFAULT_WORLD_TIMEOUT_MS));
            if (!world.get("ready").getAsBoolean()) {
                writeJson(exchange, 504, GSON.toJson(world));
                return;
            }
            writeJson(exchange, 202, ClientAutomation.amiReindexJson(getBoolean(request, "forceProviderRebuild", true)));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            writeError(exchange, 500, "Failed to request AMI reindex: " + rootMessage(e));
        }
    }

    private static void handleAmiReindexAndSearch(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) return;
        JsonObject request = readJson(exchange);
        int timeoutMs = Math.max(1_000, getInt(request, "timeoutMs", DEFAULT_REINDEX_TIMEOUT_MS));
        try {
            JsonObject out = new JsonObject();
            JsonObject world = waitForWorldReady(getInt(request, "worldTimeoutMs", DEFAULT_WORLD_TIMEOUT_MS));
            out.add("world", world);
            if (!world.get("ready").getAsBoolean()) {
                writeJson(exchange, 504, GSON.toJson(out));
                return;
            }

            String reindexJson = ClientAutomation.amiReindexJson(getBoolean(request, "forceProviderRebuild", true));
            out.add("reindex", JsonParser.parseString(reindexJson));

            JsonObject ready = waitForAmiReady(timeoutMs);
            out.add("status", ready);
            if (ready.has("lastRebuildFailure")) {
                writeJson(exchange, 500, GSON.toJson(out));
                return;
            }
            if (!ready.has("ready") || !ready.get("ready").getAsBoolean()) {
                out.addProperty("error", "Timed out waiting for AMI reindex.");
                writeJson(exchange, 504, GSON.toJson(out));
                return;
            }

            String searchJson = ClientAutomation.amiSearchJson(
                    getString(request, "query", ""),
                    getInt(request, "limit", 100),
                    getString(request, "viewMode", "LIST"),
                    getString(request, "lens", "ALL"),
                    getBoolean(request, "compact", false)
            );
            out.add("search", JsonParser.parseString(searchJson));
            writeJson(exchange, 200, GSON.toJson(out));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            writeError(exchange, 500, "Failed to reindex and search AMI: " + rootMessage(e));
        }
    }

    private static JsonObject waitForWorldReady(int timeoutMs) {
        long deadline = System.currentTimeMillis() + Math.max(1_000, timeoutMs);
        JsonObject status = JsonParser.parseString(ClientAutomation.statusSnapshotJson()).getAsJsonObject();
        while (System.currentTimeMillis() < deadline) {
            if (ClientAutomation.isWorldReadyForInteraction()) {
                status = JsonParser.parseString(ClientAutomation.statusSnapshotJson()).getAsJsonObject();
                JsonObject out = new JsonObject();
                out.addProperty("ready", true);
                out.add("automine", status);
                return out;
            }
            boolean titleReady = status.has("readyForWorldCreation") && status.get("readyForWorldCreation").getAsBoolean();
            if (!titleReady && status.has("automationPhase") && "error".equals(status.get("automationPhase").getAsString())) {
                JsonObject out = new JsonObject();
                out.addProperty("ready", false);
                out.addProperty("error", status.has("automationMessage")
                        ? status.get("automationMessage").getAsString()
                        : "AutoMine failed to load a world.");
                out.add("automine", status);
                return out;
            }
            try {
                Thread.sleep(250L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            status = JsonParser.parseString(ClientAutomation.statusSnapshotJson()).getAsJsonObject();
        }

        JsonObject out = new JsonObject();
        out.addProperty("ready", false);
        out.addProperty("error", "Timed out waiting for AutoMine to load a world.");
        out.add("automine", status);
        return out;
    }

    private static JsonObject waitForTitleReady(int timeoutMs) {
        return waitForTitleReady(timeoutMs, true);
    }

    private static JsonObject waitForTitleReady(int timeoutMs, boolean allowExistingWorld) {
        long deadline = System.currentTimeMillis() + Math.max(1_000, timeoutMs);
        JsonObject status = JsonParser.parseString(ClientAutomation.statusSnapshotJson()).getAsJsonObject();
        while (System.currentTimeMillis() < deadline) {
            if (status.has("readyForWorldCreation") && status.get("readyForWorldCreation").getAsBoolean()) {
                JsonObject out = new JsonObject();
                out.addProperty("ready", true);
                out.add("automine", status);
                return out;
            }
            if (allowExistingWorld && status.has("inWorld") && status.get("inWorld").getAsBoolean()) {
                JsonObject out = new JsonObject();
                out.addProperty("ready", true);
                out.add("automine", status);
                return out;
            }
            if (status.has("automationPhase") && "error".equals(status.get("automationPhase").getAsString())) {
                JsonObject out = new JsonObject();
                out.addProperty("ready", false);
                out.addProperty("error", status.has("automationMessage")
                        ? status.get("automationMessage").getAsString()
                        : "AutoMine failed before the title screen was ready.");
                out.add("automine", status);
                return out;
            }
            try {
                Thread.sleep(250L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            status = JsonParser.parseString(ClientAutomation.statusSnapshotJson()).getAsJsonObject();
        }

        JsonObject out = new JsonObject();
        out.addProperty("ready", false);
        out.addProperty("error", "Timed out waiting for the title screen to become ready.");
        out.add("automine", status);
        return out;
    }

    private static JsonObject waitForAmiReady(int timeoutMs) throws ReflectiveOperationException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        JsonObject status = JsonParser.parseString(ClientAutomation.amiStatusJson()).getAsJsonObject();
        while (System.currentTimeMillis() < deadline) {
            if (status.has("lastRebuildFailure")) {
                return status;
            }
            if (status.has("ready") && status.get("ready").getAsBoolean()) {
                return status;
            }
            try {
                Thread.sleep(250L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return status;
            }
            status = JsonParser.parseString(ClientAutomation.amiStatusJson()).getAsJsonObject();
        }
        return status;
    }

    private static String runOnClientThread(Callable<String> action)
            throws InterruptedException, ExecutionException, TimeoutException {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.isSameThread()) {
            try {
                return action.call();
            } catch (Exception e) {
                throw new ExecutionException(e);
            }
        }

        CompletableFuture<String> future = new CompletableFuture<>();
        minecraft.execute(() -> {
            try {
                future.complete(action.call());
            } catch (Throwable e) {
                future.completeExceptionally(e);
            }
        });
        return future.get(10, TimeUnit.SECONDS);
    }

    private static void writeClientAction(HttpExchange exchange, String json) throws IOException {
        int statusCode = 200;
        try {
            JsonObject status = JsonParser.parseString(json).getAsJsonObject();
            String state = getString(status, "state", "");
            if ("error".equals(state)) {
                statusCode = 409;
            } else if (state.startsWith("pending")) {
                statusCode = 202;
            }
        } catch (RuntimeException ignored) {
        }
        writeJson(exchange, statusCode, json);
    }

    private static boolean requireMethod(HttpExchange exchange, String method) throws IOException {
        if (method.equalsIgnoreCase(exchange.getRequestMethod())) {
            return true;
        }
        exchange.getResponseHeaders().add("Allow", method.toUpperCase(Locale.ROOT));
        writeError(exchange, 405, "Method not allowed.");
        return false;
    }

    private static JsonObject readJson(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readAllBytes();
        if (bytes.length == 0) {
            return new JsonObject();
        }
        return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static void writeError(HttpExchange exchange, int statusCode, String message) throws IOException {
        JsonObject out = new JsonObject();
        out.addProperty("error", message);
        writeJson(exchange, statusCode, GSON.toJson(out));
    }

    private static void writeJson(HttpExchange exchange, int statusCode, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream body = exchange.getResponseBody()) {
            body.write(bytes);
        }
    }

    private static String getString(JsonObject object, String key, String fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : fallback;
    }

    private static List<String> getStringList(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull() || !object.get(key).isJsonArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonElement element : object.getAsJsonArray(key)) {
            if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                values.add(element.getAsString());
            }
        }
        return List.copyOf(values);
    }

    private static int getInt(JsonObject object, String key, int fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsInt() : fallback;
    }

    private static Long getOptionalLong(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsLong() : null;
    }

    private static double getDouble(JsonObject object, String key, double fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsDouble() : fallback;
    }

    private static boolean getBoolean(JsonObject object, String key, boolean fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsBoolean() : fallback;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return message == null || message.isBlank() ? root.getClass().getName() : message;
    }
}
