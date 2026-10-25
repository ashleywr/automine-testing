package com.sanhiruzu.automine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluids;

import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Measures cold, full-status chunk requests without ever waiting on the
 * integrated server thread.  This is intentionally separate from the cheap
 * generator-column relief audit: it represents the expensive path a player
 * takes while first entering an unexplored area.
 */
final class ChunkGenerationAudit {
    private static final long PER_CHUNK_TIMEOUT_SECONDS = 180L;
    /** The vertical band above Terra's ordinary land-carving ceiling, used to validate landmark mountain caverns. */
    private static final int UPPER_MOUNTAIN_CAVERN_MIN_Y = 141;
    private static final int UPPER_MOUNTAIN_CAVERN_MAX_Y = 210;

    private ChunkGenerationAudit() {
    }

    static JsonObject audit(MinecraftServer server, List<String> requestedTargets) {
        ServerLevel level = server.overworld();
        List<Target> targets = parseTargets(requestedTargets);
        JsonArray rows = new JsonArray();
        long totalElapsedMs = 0L;
        long totalGenerationElapsedMs = 0L;
        long totalVerificationScanElapsedMs = 0L;
        long maximumElapsedMs = 0L;

        for (Target target : targets) {
            long startedAt = System.nanoTime();
            JsonObject row = new JsonObject();
            row.addProperty("label", target.label());
            row.addProperty("blockX", target.blockX());
            row.addProperty("blockZ", target.blockZ());
            row.addProperty("chunkX", target.blockX() >> 4);
            row.addProperty("chunkZ", target.blockZ() >> 4);
            try {
                long generationStartedAt = System.nanoTime();
                requestFullChunk(server, level, target.blockX() >> 4, target.blockZ() >> 4)
                        .get(PER_CHUNK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                long generationElapsedMs = elapsedMillis(generationStartedAt);
                long verificationStartedAt = System.nanoTime();
                UndergroundSample underground = sampleUnderground(
                        server, level, target.blockX() >> 4, target.blockZ() >> 4)
                        .get(PER_CHUNK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                long verificationElapsedMs = elapsedMillis(verificationStartedAt);
                long elapsedMs = elapsedMillis(startedAt);
                row.addProperty("status", "generated_full");
                row.addProperty("elapsedMs", elapsedMs);
                row.addProperty("generationElapsedMs", generationElapsedMs);
                row.addProperty("verificationScanElapsedMs", verificationElapsedMs);
                row.add("underground", underground.toJson());
                totalElapsedMs += elapsedMs;
                totalGenerationElapsedMs += generationElapsedMs;
                totalVerificationScanElapsedMs += verificationElapsedMs;
                maximumElapsedMs = Math.max(maximumElapsedMs, generationElapsedMs);
            } catch (Exception exception) {
                row.addProperty("status", "failed");
                row.addProperty("elapsedMs", elapsedMillis(startedAt));
                row.addProperty("error", rootMessage(exception));
            }
            rows.add(row);
        }

        JsonObject report = new JsonObject();
        report.addProperty("method", "server_scheduled_cold_full_chunk_request");
        report.addProperty("dimension", level.dimension().location().toString());
        report.addProperty("perChunkTimeoutSeconds", PER_CHUNK_TIMEOUT_SECONDS);
        report.addProperty("requestedChunks", targets.size());
        report.addProperty("generatedChunks", generatedCount(rows));
        report.addProperty("totalGeneratedElapsedMs", totalElapsedMs);
        report.addProperty("totalGenerationElapsedMs", totalGenerationElapsedMs);
        report.addProperty("totalVerificationScanElapsedMs", totalVerificationScanElapsedMs);
        report.addProperty("maximumChunkGenerationElapsedMs", maximumElapsedMs);
        // Preserve the existing HTTP response key, now measuring generation
        // alone rather than generation plus the diagnostic block scan.
        report.addProperty("maximumChunkElapsedMs", maximumElapsedMs);
        report.add("chunks", rows);
        return report;
    }

    private static CompletableFuture<Void> requestFullChunk(MinecraftServer server,
                                                              ServerLevel level,
                                                              int chunkX,
                                                              int chunkZ) {
        CompletableFuture<Void> completion = new CompletableFuture<>();
        // getChunkFuture must be called from the server executor.  Completion
        // is observed from the HTTP worker, leaving ticks free to advance the
        // generation pipeline and avoiding the common server-thread join deadlock.
        server.execute(() -> {
            try {
                level.getChunkSource().getChunkFuture(chunkX, chunkZ, ChunkStatus.FULL, true)
                        .thenAccept(ignored -> completion.complete(null))
                        .exceptionally(error -> {
                            completion.completeExceptionally(error);
                            return null;
                        });
            } catch (Throwable error) {
                completion.completeExceptionally(error);
            }
        });
        return completion;
    }

    /**
     * Scan only an already-complete chunk and only from the server executor.
     * This verifies Terra's own carving/ore stages without triggering vanilla
     * population a second time or reading world state from the HTTP thread.
     */
    private static CompletableFuture<UndergroundSample> sampleUnderground(MinecraftServer server,
                                                                            ServerLevel level,
                                                                            int chunkX,
                                                                            int chunkZ) {
        CompletableFuture<UndergroundSample> completion = new CompletableFuture<>();
        server.execute(() -> {
            try {
                ChunkAccess chunk = level.getChunk(chunkX, chunkZ);
                ChunkPos pos = chunk.getPos();
                UndergroundSample sample = new UndergroundSample();
                BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
                for (int localX = 0; localX < 16; localX++) {
                    for (int localZ = 0; localZ < 16; localZ++) {
                        int blockX = pos.getMinBlockX() + localX;
                        int blockZ = pos.getMinBlockZ() + localZ;
                        int surface = level.getHeight(Heightmap.Types.WORLD_SURFACE, blockX, blockZ);
                        int oceanFloor = level.getHeight(Heightmap.Types.OCEAN_FLOOR, blockX, blockZ);
                        int seabedY = Math.max(level.getMinBuildHeight(), oceanFloor - 1);
                        var seabedState = chunk.getBlockState(cursor.set(blockX, seabedY, blockZ));
                        sample.seabedBlocks.merge(BuiltInRegistries.BLOCK.getKey(seabedState.getBlock()).toString(), 1, Integer::sum);
                        sample.seabedBiomes.merge(level.getBiome(cursor).unwrapKey()
                                .map(key -> key.location().toString()).orElse("unregistered"), 1, Integer::sum);
                        int ceiling = Math.max(level.getMinBuildHeight(), surface - 4);
                        boolean hasCaveAir = false;
                        boolean hasUpperMountainCavernAir = false;
                        for (int y = level.getMinBuildHeight() + 4; y < ceiling; y++) {
                            var state = chunk.getBlockState(cursor.set(blockX, y, blockZ));
                            sample.includeRegionsUnexploredCavernDecor(state);
                            sample.undergroundBlocks++;
                            if (y < oceanFloor) {
                                if (state.isAir()) {
                                    sample.belowOceanFloorAirBlocks++;
                                    if (sample.belowOceanFloorAirSamples.size() < 8) {
                                        JsonObject point = new JsonObject();
                                        point.addProperty("x", blockX);
                                        point.addProperty("y", y);
                                        point.addProperty("z", blockZ);
                                        point.addProperty("floorHeight", oceanFloor);
                                        sample.belowOceanFloorAirSamples.add(point);
                                    }
                                }
                                var fluid = state.getFluidState();
                                if (!fluid.isEmpty()) sample.belowOceanFloorFluidBlocks++;
                                if (fluid.is(Fluids.WATER)) sample.belowOceanFloorWaterBlocks++;
                                if (fluid.is(Fluids.LAVA)) sample.belowOceanFloorLavaBlocks++;
                            }
                            boolean inUpperMountainCavernBand = y >= UPPER_MOUNTAIN_CAVERN_MIN_Y
                                    && y <= UPPER_MOUNTAIN_CAVERN_MAX_Y;
                            if (inUpperMountainCavernBand) sample.upperMountainBandBlocks++;
                            if (state.isAir()) {
                                sample.undergroundAirBlocks++;
                                hasCaveAir = true;
                                if (inUpperMountainCavernBand) {
                                    sample.upperMountainBandAirBlocks++;
                                    hasUpperMountainCavernAir = true;
                                    sample.includeUpperMountainCavernAir(blockX, y, blockZ);
                                    sample.includeUpperMountainCavernBiome(level, cursor);
                                }
                            }
                            var blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
                            String path = blockId.getPath();
                            if (path.endsWith("_ore")) sample.oreBlocks++;
                            if (!state.isAir() && (!blockId.getNamespace().equals("minecraft")
                                    || path.equals("kelp") || path.equals("kelp_plant")
                                    || path.equals("seagrass") || path.equals("tall_seagrass")
                                    || path.contains("coral") || path.equals("sea_pickle"))) {
                                sample.observedBlocks.merge(blockId.toString(), 1, Integer::sum);
                            }
                        }
                        if (hasCaveAir) sample.columnsWithCaveAir++;
                        if (hasUpperMountainCavernAir) sample.columnsWithUpperMountainCavernAir++;
                    }
                }
                completion.complete(sample);
            } catch (Throwable error) {
                completion.completeExceptionally(error);
            }
        });
        return completion;
    }

    private static List<Target> parseTargets(List<String> requestedTargets) {
        List<Target> targets = new ArrayList<>();
        if (requestedTargets != null) {
            for (String rawTarget : requestedTargets) {
                if (rawTarget == null || rawTarget.isBlank()) continue;
                String[] parts = rawTarget.trim().split("[,;]", 3);
                if (parts.length != 3) {
                    throw new IllegalArgumentException("Chunk target must be label,x,z: " + rawTarget);
                }
                try {
                    targets.add(new Target(parts[0].trim(), Integer.parseInt(parts[1].trim()), Integer.parseInt(parts[2].trim())));
                } catch (NumberFormatException error) {
                    throw new IllegalArgumentException("Chunk target has invalid coordinates: " + rawTarget, error);
                }
            }
        }
        if (!targets.isEmpty()) return targets;

        // These deliberately sit away from the initial spawn chunks and cover
        // an inland route, river valley, highland and coastline in the Kanto
        // benchmark map.  They are useful defaults, not worldgen assumptions:
        // callers may supply an explicit target list for another profile.
        targets.add(new Target("western_route", -4096, -3072));
        targets.add(new Target("river_valley", -512, -6016));
        targets.add(new Target("northern_highlands", 2048, -5376));
        targets.add(new Target("eastern_coast", 5120, 1024));
        return targets;
    }

    private static int generatedCount(JsonArray rows) {
        int count = 0;
        for (int index = 0; index < rows.size(); index++) {
            JsonObject row = rows.get(index).getAsJsonObject();
            if ("generated_full".equals(row.get("status").getAsString())) count++;
        }
        return count;
    }

    private static long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private static String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String message = root.getMessage();
        return message == null || message.isBlank() ? root.getClass().getName() : message;
    }

    private record Target(String label, int blockX, int blockZ) {
    }

    private static final class UndergroundSample {
        private int undergroundBlocks;
        private int undergroundAirBlocks;
        private int columnsWithCaveAir;
        private int oreBlocks;
        private int belowOceanFloorAirBlocks;
        private int belowOceanFloorFluidBlocks;
        private int belowOceanFloorWaterBlocks;
        private int belowOceanFloorLavaBlocks;
        private final JsonArray belowOceanFloorAirSamples = new JsonArray();
        private int upperMountainBandBlocks;
        private int upperMountainBandAirBlocks;
        private int columnsWithUpperMountainCavernAir;
        private int upperMountainMinX = Integer.MAX_VALUE;
        private int upperMountainMinY = Integer.MAX_VALUE;
        private int upperMountainMinZ = Integer.MAX_VALUE;
        private int upperMountainMaxX = Integer.MIN_VALUE;
        private int upperMountainMaxY = Integer.MIN_VALUE;
        private int upperMountainMaxZ = Integer.MIN_VALUE;
        private final Map<String, Integer> observedBlocks = new TreeMap<>();
        private final Map<String, Integer> seabedBlocks = new TreeMap<>();
        private final Map<String, Integer> seabedBiomes = new TreeMap<>();
        private final Map<String, Integer> upperMountainCavernAirBiomes = new TreeMap<>();
        private final Map<String, Integer> regionsUnexploredCavernDecor = new TreeMap<>();

        private void includeUpperMountainCavernAir(int x, int y, int z) {
            upperMountainMinX = Math.min(upperMountainMinX, x);
            upperMountainMinY = Math.min(upperMountainMinY, y);
            upperMountainMinZ = Math.min(upperMountainMinZ, z);
            upperMountainMaxX = Math.max(upperMountainMaxX, x);
            upperMountainMaxY = Math.max(upperMountainMaxY, y);
            upperMountainMaxZ = Math.max(upperMountainMaxZ, z);
        }

        private void includeUpperMountainCavernBiome(ServerLevel level, BlockPos position) {
            String biome = level.getBiome(position).unwrapKey()
                    .map(key -> key.location().toString())
                    .orElse("unregistered");
            upperMountainCavernAirBiomes.merge(biome, 1, Integer::sum);
        }

        private void includeRegionsUnexploredCavernDecor(net.minecraft.world.level.block.state.BlockState state) {
            String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            if (id.equals("regions_unexplored:prismoss_sprout")
                    || id.equals("regions_unexplored:cave_hyssop")
                    || id.equals("regions_unexplored:prismarite_cluster")
                    || id.equals("regions_unexplored:large_prismarite_cluster")
                    || id.equals("regions_unexplored:hanging_prismarite_cluster")) {
                regionsUnexploredCavernDecor.merge(id, 1, Integer::sum);
            }
        }

        JsonObject toJson() {
            JsonObject result = new JsonObject();
            result.addProperty("method", "post_full_chunk_underground_block_scan");
            result.addProperty("undergroundBlocks", undergroundBlocks);
            result.addProperty("undergroundAirBlocks", undergroundAirBlocks);
            result.addProperty("columnsWithCaveAir", columnsWithCaveAir);
            result.addProperty("oreBlocks", oreBlocks);
            result.addProperty("belowOceanFloorAirBlocks", belowOceanFloorAirBlocks);
            result.addProperty("belowOceanFloorFluidBlocks", belowOceanFloorFluidBlocks);
            result.addProperty("belowOceanFloorWaterBlocks", belowOceanFloorWaterBlocks);
            result.addProperty("belowOceanFloorLavaBlocks", belowOceanFloorLavaBlocks);
            result.add("belowOceanFloorAirSamples", belowOceanFloorAirSamples);
            JsonObject seabedBlockCounts = new JsonObject();
            seabedBlocks.forEach(seabedBlockCounts::addProperty);
            result.add("seabedTopBlocks", seabedBlockCounts);
            JsonObject seabedBiomeCounts = new JsonObject();
            seabedBiomes.forEach(seabedBiomeCounts::addProperty);
            result.add("seabedBiomes", seabedBiomeCounts);
            if (!observedBlocks.isEmpty()) {
                JsonObject observed = new JsonObject();
                observedBlocks.forEach(observed::addProperty);
                result.add("observedNonVanillaAndMarineBlocks", observed);
            }
            if (!regionsUnexploredCavernDecor.isEmpty()) {
                JsonObject decor = new JsonObject();
                regionsUnexploredCavernDecor.forEach(decor::addProperty);
                result.add("regionsUnexploredCavernDecorBlocks", decor);
            }
            result.addProperty("undergroundAirPercent", undergroundBlocks == 0 ? 0D
                    : Math.round(undergroundAirBlocks * 10_000D / undergroundBlocks) / 100D);
            JsonObject upperMountainBand = new JsonObject();
            upperMountainBand.addProperty("minY", UPPER_MOUNTAIN_CAVERN_MIN_Y);
            upperMountainBand.addProperty("maxY", UPPER_MOUNTAIN_CAVERN_MAX_Y);
            upperMountainBand.addProperty("blocks", upperMountainBandBlocks);
            upperMountainBand.addProperty("airBlocks", upperMountainBandAirBlocks);
            upperMountainBand.addProperty("columnsWithAir", columnsWithUpperMountainCavernAir);
            upperMountainBand.addProperty("airPercent", upperMountainBandBlocks == 0 ? 0D
                    : Math.round(upperMountainBandAirBlocks * 10_000D / upperMountainBandBlocks) / 100D);
            if (upperMountainBandAirBlocks > 0) {
                JsonObject airBounds = new JsonObject();
                airBounds.addProperty("minX", upperMountainMinX);
                airBounds.addProperty("minY", upperMountainMinY);
                airBounds.addProperty("minZ", upperMountainMinZ);
                airBounds.addProperty("maxX", upperMountainMaxX);
                airBounds.addProperty("maxY", upperMountainMaxY);
                airBounds.addProperty("maxZ", upperMountainMaxZ);
                upperMountainBand.add("airBounds", airBounds);
                JsonObject airBiomes = new JsonObject();
                upperMountainCavernAirBiomes.forEach(airBiomes::addProperty);
                upperMountainBand.add("airBiomeBlocks", airBiomes);
            }
            result.add("upperMountainCavernBand", upperMountainBand);
            return result;
        }
    }
}
