package com.sanhiruzu.automine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;

/** Observational export of the registry values used by SeedGen's fingerprint. */
final class SeedgenWorldgenProbe {
    private SeedgenWorldgenProbe() {}

    static JsonObject snapshot() {
        try {
            Class<?> worldgen = Class.forName("mobius.seedrenderer.dh.ClientWorldgen");
            RegistryAccess access = (RegistryAccess) worldgen.getMethod("get").invoke(null);
            if (access == null) throw new IllegalStateException("SeedGen has no loaded registry");
            JsonObject out = new JsonObject();
            Class<?> sharing = Class.forName("mobius.seedrenderer.net.SeedSharing");
            out.addProperty("fingerprint", (String) sharing.getMethod("fingerprintOf", RegistryAccess.class)
                    .invoke(null, access));
            JsonObject keys = new JsonObject();
            keys.add("biome", keys(access, Registries.BIOME));
            keys.add("placed_feature", keys(access, Registries.PLACED_FEATURE));
            keys.add("noise", keys(access, Registries.NOISE));
            keys.add("multi_noise_biome_source_parameter_list", keys(access, Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST));
            out.add("keys", keys);
            JsonObject values = new JsonObject();
            values.add("noise_settings", values(access, Registries.NOISE_SETTINGS, NoiseGeneratorSettings.DIRECT_CODEC));
            values.add("density_function", values(access, Registries.DENSITY_FUNCTION, DensityFunction.DIRECT_CODEC));
            out.add("values", values);
            return out;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("SeedGen registry probe unavailable", e);
        }
    }

    private static <T> JsonArray keys(RegistryAccess access, ResourceKey<? extends Registry<T>> key) {
        JsonArray out = new JsonArray();
        access.registryOrThrow(key).keySet().stream().map(Object::toString).sorted().forEach(out::add);
        return out;
    }

    private static <T> JsonObject values(RegistryAccess access, ResourceKey<? extends Registry<T>> key, Codec<T> codec) {
        JsonObject out = new JsonObject();
        RegistryOps<com.google.gson.JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, access);
        Registry<T> registry = access.registryOrThrow(key);
        registry.keySet().stream().sorted().forEach(id -> {
            var result = codec.encodeStart(ops, registry.get(id));
            out.addProperty(id.toString(), result.result().map(Object::toString)
                    .orElseGet(() -> "ERROR: " + result.error().map(e -> e.message()).orElse("unknown")));
        });
        return out;
    }
}
