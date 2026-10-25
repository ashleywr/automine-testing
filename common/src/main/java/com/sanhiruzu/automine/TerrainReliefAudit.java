package com.sanhiruzu.automine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Arrays;

/**
 * Measures unloaded world-generator columns. It intentionally uses the same
 * public base-height API RoadWeaver uses, so the report checks terrain without
 * force-loading or decorating thousands of chunks.
 */
final class TerrainReliefAudit {
    private TerrainReliefAudit() {
    }

    static JsonObject audit(ServerLevel level, int centerX, int centerZ, int radiusBlocks, int sampleStepBlocks,
                            Path waterPreview, Path reliefPreview, Path seafloorPreview) throws IOException {
        int step = Math.max(32, sampleStepBlocks);
        int radius = Math.max(step, radiusBlocks);
        int width = ((radius * 2) / step) + 1;
        if (width > 257) {
            throw new IllegalArgumentException("Terrain audit grid is limited to 257 samples per side.");
        }

        int total = Math.multiplyExact(width, width);
        int[] heights = new int[total];
        boolean[] land = new boolean[total];
        boolean[] water = new boolean[total];
        boolean[] carvedWater = new boolean[total];
        HeightStats all = new HeightStats();
        HeightStats seabedY = new HeightStats();
        HeightStats depthBelowSea = new HeightStats();
        int[] seabedHeights = new int[total];
        int[] depthsBelowSea = new int[total];
        HeightStats landHeights = new HeightStats();
        HeightStats waterHeights = new HeightStats();
        Map<String, Integer> biomeSampleCounts = new HashMap<>();
        Map<String, HeightStats> seafloorsByBiome = new HashMap<>();
        Map<String, JsonArray> biomeSamplePoints = new HashMap<>();
        int carvedWaterSamples = 0;
        HeightStats adjacentLandDeltas = new HeightStats();
        Map<String, HeightStats> heightsByBiome = new HashMap<>();
        var chunkSource = level.getChunkSource();
        var generator = chunkSource.getGenerator();
        RandomState randomState = chunkSource.getGeneratorState().randomState();
        BiomeSource biomeSource = generator.getBiomeSource();
        Climate.Sampler climateSampler = chunkSource.randomState().sampler();
        int seaLevel = level.getSeaLevel();
        int minX = centerX - radius;
        int minZ = centerZ - radius;
        int peakLandHeight = Integer.MIN_VALUE;
        int peakLandX = centerX;
        int peakLandZ = centerZ;
        String peakLandBiome = "unknown";

        for (int zIndex = 0; zIndex < width; zIndex++) {
            int z = minZ + zIndex * step;
            for (int xIndex = 0; xIndex < width; xIndex++) {
                int x = minX + xIndex * step;
                int index = zIndex * width + xIndex;
                int height = generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, randomState);
                heights[index] = height;
                all.add(height);
                // Unlike WORLD_SURFACE_WG, which sees the ocean surface, this
                // directly measures the top solid block under the water.
                int floor = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, randomState) - 1;
                int depth = seaLevel - floor;
                seabedHeights[index] = floor;
                depthsBelowSea[index] = depth;
                seabedY.add(floor);
                depthBelowSea.add(depth);
                String biome = biomeId(level, biomeSource, climateSampler, x, seaLevel, z);
                biomeSampleCounts.merge(biome, 1, Integer::sum);
                seafloorsByBiome.computeIfAbsent(biome, ignored -> new HeightStats()).add(floor);
                JsonArray samplePoints = biomeSamplePoints.computeIfAbsent(biome, ignored -> new JsonArray());
                if (samplePoints.size() < 4) {
                    JsonObject point = new JsonObject();
                    point.addProperty("x", x);
                    point.addProperty("z", z);
                    point.addProperty("seafloorY", floor);
                    point.addProperty("waterDepth", depth);
                    samplePoints.add(point);
                }
                // Terrain packs may carve physical rivers and lakes without
                // creating a separate river biome.  Surface height is the
                // reliable common signal for those packs and for vanilla.
                boolean waterBiome = isWaterBiome(biome);
                boolean isWater = waterBiome || height <= seaLevel;
                boolean isCarvedWater = isWater && !waterBiome;
                if (isCarvedWater) carvedWaterSamples++;
                boolean isLand = !isWater;
                land[index] = isLand;
                water[index] = isWater;
                carvedWater[index] = isCarvedWater;
                if (isLand) {
                    landHeights.add(height);
                    heightsByBiome.computeIfAbsent(biome, ignored -> new HeightStats()).add(height);
                    if (height > peakLandHeight) {
                        peakLandHeight = height;
                        peakLandX = x;
                        peakLandZ = z;
                        peakLandBiome = biome;
                    }
                } else {
                    waterHeights.add(height);
                }
            }
        }

        for (int z = 0; z < width; z++) {
            for (int x = 0; x < width; x++) {
                int index = z * width + x;
                if (!land[index]) continue;
                if (x + 1 < width && land[index + 1]) {
                    adjacentLandDeltas.add(Math.abs(heights[index] - heights[index + 1]));
                }
                if (z + 1 < width && land[index + width]) {
                    adjacentLandDeltas.add(Math.abs(heights[index] - heights[index + width]));
                }
            }
        }

        JsonObject report = new JsonObject();
        report.addProperty("method", "unloaded_chunk_generator_base_height");
        report.addProperty("dimension", level.dimension().location().toString());
        report.addProperty("centerX", centerX);
        report.addProperty("centerZ", centerZ);
        report.addProperty("radiusBlocks", radius);
        report.addProperty("sampleStepBlocks", step);
        report.addProperty("gridWidth", width);
        report.addProperty("gridHeight", width);
        report.addProperty("sampleCount", total);
        report.addProperty("seaLevel", seaLevel);
        report.add("surface", all.toJson());
        report.add("seafloorY", seabedY.toJson());
        report.add("seafloorYPercentiles", percentiles(seabedHeights));
        report.add("waterDepthBelowSea", depthBelowSea.toJson());
        report.add("waterDepthPercentiles", percentiles(depthsBelowSea));
        report.add("waterDepthBands", waterDepthBands(depthsBelowSea));
        report.add("landSurface", landHeights.toJson());
        report.add("waterSurface", waterHeights.toJson());
        report.addProperty("carvedWaterSamplesInLandBiomes", carvedWaterSamples);
        report.add("inlandWater", inlandWaterReport(water, width, step, minX, minZ));
        writeWaterPreview(heights, water, carvedWater, width, seaLevel, waterPreview);
        report.addProperty("physicalWaterPreview", waterPreview.toAbsolutePath().toString());
        writeReliefPreview(heights, water, carvedWater, width, seaLevel, reliefPreview);
        report.addProperty("reliefPreview", reliefPreview.toAbsolutePath().toString());
        writeSeafloorPreview(seabedHeights, width, seaLevel, seafloorPreview);
        report.addProperty("seafloorPreview", seafloorPreview.toAbsolutePath().toString());
        if (peakLandHeight != Integer.MIN_VALUE) {
            JsonObject peakLand = new JsonObject();
            peakLand.addProperty("x", peakLandX);
            peakLand.addProperty("z", peakLandZ);
            peakLand.addProperty("height", peakLandHeight);
            peakLand.addProperty("aboveSeaLevel", peakLandHeight - seaLevel);
            peakLand.addProperty("biome", peakLandBiome);
            report.add("highestLandSample", peakLand);
        }
        report.add("adjacentLandHeightDelta", adjacentLandDeltas.toJson());
        JsonArray biomeRows = new JsonArray();
        List<Map.Entry<String, HeightStats>> orderedBiomes = new ArrayList<>(heightsByBiome.entrySet());
        orderedBiomes.sort(Comparator.comparing(Map.Entry::getKey));
        for (Map.Entry<String, HeightStats> entry : orderedBiomes) {
            JsonObject row = entry.getValue().toJson();
            row.addProperty("biome", entry.getKey());
            biomeRows.add(row);
        }
        report.add("landBiomeSurfaces", biomeRows);
        JsonArray biomeSampleRows = new JsonArray();
        List<String> sampledBiomeIds = new ArrayList<>(biomeSampleCounts.keySet());
        sampledBiomeIds.sort(String::compareTo);
        for (String id : sampledBiomeIds) {
            JsonObject row = seafloorsByBiome.get(id).toJson();
            row.addProperty("biome", id);
            row.addProperty("sampleCount", biomeSampleCounts.get(id));
            row.add("exampleCoordinates", biomeSamplePoints.get(id));
            biomeSampleRows.add(row);
        }
        report.add("seafloorSamplesByBiome", biomeSampleRows);
        return report;
    }

    private static JsonObject percentiles(int[] values) {
        int[] sorted = values.clone();
        Arrays.sort(sorted);
        JsonObject result = new JsonObject();
        result.addProperty("p05", percentile(sorted, 0.05));
        result.addProperty("p25", percentile(sorted, 0.25));
        result.addProperty("p50", percentile(sorted, 0.50));
        result.addProperty("p75", percentile(sorted, 0.75));
        result.addProperty("p95", percentile(sorted, 0.95));
        return result;
    }

    private static int percentile(int[] sorted, double percentile) {
        return sorted[Math.min(sorted.length - 1, (int) Math.ceil(percentile * sorted.length) - 1)];
    }

    private static JsonObject waterDepthBands(int[] depths) {
        JsonObject result = new JsonObject();
        int[] counts = new int[6];
        for (int depth : depths) {
            if (depth < 0) counts[0]++;
            else if (depth < 100) counts[1]++;
            else if (depth < 200) counts[2]++;
            else if (depth < 300) counts[3]++;
            else if (depth < 400) counts[4]++;
            else counts[5]++;
        }
        result.addProperty("aboveSeaLevelLand", counts[0]);
        result.addProperty("0To99", counts[1]);
        result.addProperty("100To199", counts[2]);
        result.addProperty("200To299", counts[3]);
        result.addProperty("300To399", counts[4]);
        result.addProperty("400Plus", counts[5]);
        return result;
    }

    /** Writes physical water, which remains visible even when the biome identity stays land. */
    private static void writeWaterPreview(int[] heights, boolean[] water, boolean[] carvedWater,
                                          int width, int seaLevel, Path output) throws IOException {
        final int scale = 4;
        BufferedImage image = new BufferedImage(width * scale, width * scale, BufferedImage.TYPE_INT_RGB);
        for (int z = 0; z < width; z++) {
            for (int x = 0; x < width; x++) {
                int index = z * width + x;
                int rgb;
                if (carvedWater[index]) rgb = 0x2498DB;
                else if (water[index]) rgb = 0x174B96;
                else {
                    int elevation = Math.min(110, Math.max(0, heights[index] - seaLevel));
                    rgb = ((62 + elevation / 7) << 16) | ((112 + elevation / 2) << 8) | (55 + elevation / 10);
                }
                for (int dy = 0; dy < scale; dy++) {
                    for (int dx = 0; dx < scale; dx++) image.setRGB(x * scale + dx, z * scale + dy, rgb);
                }
            }
        }
        ImageIO.write(image, "png", output.toFile());
    }

    /**
     * A compact hill-shaded elevation map for comparing terrain candidates.
     * Unlike RoadWeaver's biome map this makes one mountain, its ridges, and
     * water-cut valleys legible at continent scale without loading chunks.
     */
    private static void writeReliefPreview(int[] heights, boolean[] water, boolean[] carvedWater,
                                           int width, int seaLevel, Path output) throws IOException {
        final int scale = 6;
        BufferedImage image = new BufferedImage(width * scale, width * scale, BufferedImage.TYPE_INT_RGB);
        for (int z = 0; z < width; z++) {
            for (int x = 0; x < width; x++) {
                int index = z * width + x;
                int rgb;
                if (carvedWater[index]) {
                    rgb = 0x35A9E8;
                } else if (water[index]) {
                    rgb = 0x173D7A;
                } else {
                    int west = heights[z * width + Math.max(0, x - 1)];
                    int east = heights[z * width + Math.min(width - 1, x + 1)];
                    int north = heights[Math.max(0, z - 1) * width + x];
                    int south = heights[Math.min(width - 1, z + 1) * width + x];
                    // Light comes from the north-west.  Cap the shade so a
                    // single sampled cliff does not hide the underlying form.
                    int shade = Math.max(-28, Math.min(28, (west - east + north - south) / 4));
                    double normalized = Math.min(1.0D, Math.max(0.0D, (heights[index] - seaLevel) / 155.0D));
                    rgb = terrainColor(normalized, shade);
                }
                for (int dy = 0; dy < scale; dy++) {
                    for (int dx = 0; dx < scale; dx++) image.setRGB(x * scale + dx, z * scale + dy, rgb);
                }
            }
        }
        ImageIO.write(image, "png", output.toFile());
    }

    private static void writeSeafloorPreview(int[] floors, int width, int seaLevel, Path output) throws IOException {
        final int scale = 4;
        BufferedImage image = new BufferedImage(width * scale, width * scale, BufferedImage.TYPE_INT_RGB);
        for (int z = 0; z < width; z++) {
            for (int x = 0; x < width; x++) {
                int index = z * width + x;
                int depth = seaLevel - floors[index];
                int west = floors[z * width + Math.max(0, x - 1)];
                int east = floors[z * width + Math.min(width - 1, x + 1)];
                int north = floors[Math.max(0, z - 1) * width + x];
                int south = floors[Math.min(width - 1, z + 1) * width + x];
                int shade = Math.max(-32, Math.min(32, (west - east + north - south) / 3));
                int[] color = depth < 120 ? new int[]{27, 133, 160}
                        : depth < 240 ? new int[]{29, 103, 153}
                        : depth < 360 ? new int[]{28, 72, 123}
                        : depth < 480 ? new int[]{32, 49, 99}
                        : new int[]{58, 39, 105};
                int rgb = (clampColor(color[0] + shade) << 16)
                        | (clampColor(color[1] + shade) << 8)
                        | clampColor(color[2] + shade);
                for (int dy = 0; dy < scale; dy++) {
                    for (int dx = 0; dx < scale; dx++) image.setRGB(x * scale + dx, z * scale + dy, rgb);
                }
            }
        }
        ImageIO.write(image, "png", output.toFile());
    }

    private static int terrainColor(double normalized, int shade) {
        // Lowland green -> upland ochre -> rocky grey -> pale summit.
        int[] low = {78, 126, 67};
        int[] middle = {142, 132, 78};
        int[] high = {112, 106, 99};
        int[] summit = {214, 213, 202};
        int[] base;
        if (normalized < 0.45D) {
            base = blend(low, middle, normalized / 0.45D);
        } else if (normalized < 0.80D) {
            base = blend(middle, high, (normalized - 0.45D) / 0.35D);
        } else {
            base = blend(high, summit, (normalized - 0.80D) / 0.20D);
        }
        int red = clampColor(base[0] + shade);
        int green = clampColor(base[1] + shade);
        int blue = clampColor(base[2] + shade);
        return (red << 16) | (green << 8) | blue;
    }

    private static int[] blend(int[] first, int[] second, double amount) {
        return new int[]{
                (int) Math.round(first[0] + (second[0] - first[0]) * amount),
                (int) Math.round(first[1] + (second[1] - first[1]) * amount),
                (int) Math.round(first[2] + (second[2] - first[2]) * amount)
        };
    }

    private static int clampColor(int value) {
        return Math.max(0, Math.min(255, value));
    }

    /**
     * Reports sampled water components that do not reach the audit boundary.
     * This intentionally measures lakes and enclosed river corridors without
     * mistaking the surrounding ocean for inland hydrology.
     */
    private static JsonObject inlandWaterReport(boolean[] water, int width, int sampleStepBlocks, int minX, int minZ) {
        boolean[] visited = new boolean[water.length];
        int components = 0;
        int samples = 0;
        int largest = 0;
        List<WaterComponent> largestComponents = new ArrayList<>();
        for (int index = 0; index < water.length; index++) {
            if (!water[index] || visited[index]) continue;
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            queue.add(index);
            visited[index] = true;
            boolean touchesBoundary = false;
            int size = 0;
            int minComponentX = width;
            int maxComponentX = 0;
            int minComponentZ = width;
            int maxComponentZ = 0;
            long sumX = 0;
            long sumZ = 0;
            while (!queue.isEmpty()) {
                int current = queue.removeFirst();
                int x = current % width;
                int z = current / width;
                size++;
                minComponentX = Math.min(minComponentX, x);
                maxComponentX = Math.max(maxComponentX, x);
                minComponentZ = Math.min(minComponentZ, z);
                maxComponentZ = Math.max(maxComponentZ, z);
                sumX += x;
                sumZ += z;
                touchesBoundary |= x == 0 || z == 0 || x == width - 1 || z == width - 1;
                visitWaterNeighbor(current - 1, x > 0, water, visited, queue);
                visitWaterNeighbor(current + 1, x + 1 < width, water, visited, queue);
                visitWaterNeighbor(current - width, z > 0, water, visited, queue);
                visitWaterNeighbor(current + width, z + 1 < width, water, visited, queue);
            }
            if (!touchesBoundary) {
                components++;
                samples += size;
                largest = Math.max(largest, size);
                largestComponents.add(new WaterComponent(
                        size,
                        minX + (int) ((sumX / (double) size) * sampleStepBlocks),
                        minZ + (int) ((sumZ / (double) size) * sampleStepBlocks),
                        minX + minComponentX * sampleStepBlocks,
                        minZ + minComponentZ * sampleStepBlocks,
                        minX + maxComponentX * sampleStepBlocks,
                        minZ + maxComponentZ * sampleStepBlocks));
            }
        }
        JsonObject result = new JsonObject();
        result.addProperty("components", components);
        result.addProperty("samples", samples);
        result.addProperty("largestComponentSamples", largest);
        result.addProperty("sampledCellAreaBlocks", sampleStepBlocks * sampleStepBlocks);
        largestComponents.sort(Comparator.comparingInt(WaterComponent::samples).reversed());
        JsonArray largestRows = new JsonArray();
        for (int index = 0; index < Math.min(8, largestComponents.size()); index++) {
            WaterComponent component = largestComponents.get(index);
            JsonObject row = new JsonObject();
            row.addProperty("samples", component.samples());
            row.addProperty("centerX", component.centerX());
            row.addProperty("centerZ", component.centerZ());
            row.addProperty("minX", component.minX());
            row.addProperty("minZ", component.minZ());
            row.addProperty("maxX", component.maxX());
            row.addProperty("maxZ", component.maxZ());
            largestRows.add(row);
        }
        result.add("largestComponents", largestRows);
        return result;
    }

    private record WaterComponent(int samples, int centerX, int centerZ, int minX, int minZ, int maxX, int maxZ) {
    }

    private static void visitWaterNeighbor(int index, boolean inBounds, boolean[] water, boolean[] visited,
                                           ArrayDeque<Integer> queue) {
        if (inBounds && water[index] && !visited[index]) {
            visited[index] = true;
            queue.addLast(index);
        }
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

    private static boolean isWaterBiome(String biome) {
        String path = biome.substring(Math.max(0, biome.lastIndexOf('/') + 1));
        // River-bank biomes are deliberately land. Match the terminal biome
        // identity rather than treating every descriptive name containing
        // "river" as water.
        return path.equals("ocean") || path.equals("river") || path.equals("marsh");
    }

    private static final class HeightStats {
        private int count;
        private int minimum = Integer.MAX_VALUE;
        private int maximum = Integer.MIN_VALUE;
        private long sum;
        private double sumSquares;

        void add(int value) {
            count++;
            minimum = Math.min(minimum, value);
            maximum = Math.max(maximum, value);
            sum += value;
            sumSquares += (double) value * value;
        }

        JsonObject toJson() {
            JsonObject data = new JsonObject();
            data.addProperty("samples", count);
            if (count == 0) return data;
            double mean = sum / (double) count;
            double variance = Math.max(0.0D, sumSquares / count - mean * mean);
            data.addProperty("minimum", minimum);
            data.addProperty("maximum", maximum);
            data.addProperty("range", maximum - minimum);
            data.addProperty("mean", round(mean));
            data.addProperty("standardDeviation", round(Math.sqrt(variance)));
            return data;
        }

        private static double round(double value) {
            return Math.round(value * 100.0D) / 100.0D;
        }
    }
}
