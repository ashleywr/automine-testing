package com.sanhiruzu.automine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.Biome;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Samples the live biome source without loading terrain chunks, then groups equal
 * samples into four-neighbour connected zones.  It deliberately reports the
 * sampling resolution: a zone is a map-scale biome instance, not a claim about
 * every individual four-block noise cell in the generator.
 */
final class BiomeContiguityAudit {
    private BiomeContiguityAudit() {
    }

    static JsonObject audit(ServerLevel level, int centerX, int centerZ, int radiusBlocks, int sampleStepBlocks) {
        int radius = Math.max(sampleStepBlocks, radiusBlocks);
        int step = Math.max(4, sampleStepBlocks);
        int minX = centerX - radius;
        int minZ = centerZ - radius;
        int width = ((radius * 2) / step) + 1;
        int height = width;
        int total = Math.multiplyExact(width, height);
        int sampleY = level.getSeaLevel();
        String[] biomes = new String[total];
        Map<String, BiomeStats> statsByBiome = new HashMap<>();
        BiomeSource biomeSource = level.getChunkSource().getGenerator().getBiomeSource();
        Climate.Sampler climateSampler = level.getChunkSource().randomState().sampler();

        for (int zIndex = 0; zIndex < height; zIndex++) {
            int z = minZ + zIndex * step;
            for (int xIndex = 0; xIndex < width; xIndex++) {
                int x = minX + xIndex * step;
                String biomeId = biomeId(level, biomeSource, climateSampler, x, sampleY, z);
                int index = zIndex * width + xIndex;
                biomes[index] = biomeId;
                statsByBiome.computeIfAbsent(biomeId, ignored -> new BiomeStats()).samples++;
            }
        }

        boolean[] visited = new boolean[total];
        int boundaryEdges = 0;
        for (int z = 0; z < height; z++) {
            for (int x = 0; x < width; x++) {
                int index = z * width + x;
                if (x + 1 < width && !biomes[index].equals(biomes[index + 1])) boundaryEdges++;
                if (z + 1 < height && !biomes[index].equals(biomes[index + width])) boundaryEdges++;
                if (visited[index]) continue;

                String biomeId = biomes[index];
                int zoneSamples = floodFill(biomes, visited, width, height, index, biomeId);
                BiomeStats stats = statsByBiome.get(biomeId);
                stats.zones++;
                stats.largestZoneSamples = Math.max(stats.largestZoneSamples, zoneSamples);
                if (zoneSamples <= 4) {
                    stats.tinyZones++;
                    stats.tinyZoneSamples += zoneSamples;
                }
            }
        }

        List<Map.Entry<String, BiomeStats>> ordered = new ArrayList<>(statsByBiome.entrySet());
        ordered.sort(Comparator.<Map.Entry<String, BiomeStats>>comparingInt(entry -> entry.getValue().samples)
                .reversed().thenComparing(Map.Entry::getKey));
        JsonArray biomeRows = new JsonArray();
        int totalZones = 0;
        int fragmentedBiomes = 0;
        for (Map.Entry<String, BiomeStats> entry : ordered) {
            BiomeStats stats = entry.getValue();
            totalZones += stats.zones;
            if (stats.zones > 1) fragmentedBiomes++;
            JsonObject row = new JsonObject();
            row.addProperty("biome", entry.getKey());
            row.addProperty("samples", stats.samples);
            row.addProperty("coveragePercent", percent(stats.samples, total));
            row.addProperty("estimatedAreaBlocks", (long) stats.samples * step * step);
            row.addProperty("zones", stats.zones);
            row.addProperty("largestZoneSamples", stats.largestZoneSamples);
            row.addProperty("largestZonePercentOfBiome", percent(stats.largestZoneSamples, stats.samples));
            row.addProperty("tinyZonesAtOrBelowFourSamples", stats.tinyZones);
            row.addProperty("tinyZoneSamples", stats.tinyZoneSamples);
            biomeRows.add(row);
        }

        JsonObject report = new JsonObject();
        report.addProperty("method", "live_biome_source_four_neighbour_components");
        report.addProperty("dimension", level.dimension().location().toString());
        report.addProperty("centerX", centerX);
        report.addProperty("centerZ", centerZ);
        report.addProperty("radiusBlocks", radius);
        report.addProperty("sampleStepBlocks", step);
        report.addProperty("sampleY", sampleY);
        report.addProperty("gridWidth", width);
        report.addProperty("gridHeight", height);
        report.addProperty("sampleCount", total);
        report.addProperty("biomeTypes", ordered.size());
        report.addProperty("connectedZones", totalZones);
        report.addProperty("fragmentedBiomeTypes", fragmentedBiomes);
        report.addProperty("differentBiomeBoundaryEdges", boundaryEdges);
        report.add("biomes", biomeRows);
        return report;
    }

    private static String biomeId(ServerLevel level,
                                  BiomeSource biomeSource,
                                  Climate.Sampler climateSampler,
                                  int x,
                                  int y,
                                  int z) {
        Holder<Biome> biome = biomeSource.getNoiseBiome(
                QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z), climateSampler);
        ResourceLocation id = level.registryAccess().registryOrThrow(Registries.BIOME).getKey(biome.value());
        return id == null ? "unknown" : id.toString();
    }

    private static int floodFill(String[] biomes, boolean[] visited, int width, int height, int start, String target) {
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(start);
        visited[start] = true;
        int count = 0;
        while (!queue.isEmpty()) {
            int index = queue.removeFirst();
            count++;
            int x = index % width;
            int z = index / width;
            if (x > 0) enqueueIfMatch(biomes, visited, queue, index - 1, target);
            if (x + 1 < width) enqueueIfMatch(biomes, visited, queue, index + 1, target);
            if (z > 0) enqueueIfMatch(biomes, visited, queue, index - width, target);
            if (z + 1 < height) enqueueIfMatch(biomes, visited, queue, index + width, target);
        }
        return count;
    }

    private static void enqueueIfMatch(String[] biomes, boolean[] visited, ArrayDeque<Integer> queue, int index, String target) {
        if (!visited[index] && target.equals(biomes[index])) {
            visited[index] = true;
            queue.addLast(index);
        }
    }

    private static double percent(int numerator, int denominator) {
        return denominator <= 0 ? 0.0D : Math.round(numerator * 10_000.0D / denominator) / 100.0D;
    }

    private static final class BiomeStats {
        private int samples;
        private int zones;
        private int largestZoneSamples;
        private int tinyZones;
        private int tinyZoneSamples;
    }
}
