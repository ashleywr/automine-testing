package com.sanhiruzu.automine;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.ParticleStatus;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import java.util.ArrayList;
import java.util.Collections;

/** Loopback diagnostics: temporary settings and fixed-camera frame measurements. */
public final class ClientPerformanceProbe {
    private static final ArrayList<Double> frames = new ArrayList<>();
    private static long end, previous;

    public static synchronized void onFrame() {
        long now = System.nanoTime();
        if (end == 0 || now > end) return;
        if (previous != 0) frames.add((now - previous) / 1e6);
        previous = now;
    }

    private static Object entry(String section, String name) throws Exception {
        return Class.forName("com.seibel.distanthorizons.core.config.Config$" + section).getField(name).get(null);
    }

    private static Object get(Object entry) throws Exception {
        return entry.getClass().getMethod("get").invoke(entry);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void setEnum(Object entry, String value) throws Exception {
        Object current = get(entry);
        Object next = Enum.valueOf((Class) current.getClass(), value);
        entry.getClass().getMethod("setWithoutSaving", Object.class).invoke(entry, next);
    }

    public static synchronized JsonObject action(JsonObject request) throws Exception {
        Minecraft mc = Minecraft.getInstance();
        String action = request.has("action") ? request.get("action").getAsString() : "state";
        if (action.equals("connect")) {
            if (mc.level != null) throw new IllegalStateException("Close the existing world before connecting");
            String address = request.get("address").getAsString();
            ConnectScreen.startConnecting(new TitleScreen(), mc, ServerAddress.parseString(address),
                    new ServerData("AutoMine diagnostic", address, ServerData.Type.OTHER), false, null);
        } else if (action.equals("sample")) {
            if (mc.level == null || mc.screen != null) throw new IllegalStateException("An interactive world is required");
            int seconds = Math.max(1, Math.min(60, request.get("seconds").getAsInt()));
            frames.clear(); previous = 0; end = System.nanoTime() + seconds * 1_000_000_000L;
        } else if (action.equals("settings")) {
            if (request.has("maxFps")) {
                int limit = request.get("maxFps").getAsInt();
                if (limit < 10 || limit > 260) throw new IllegalArgumentException("FPS limit must be between 10 and 260");
                mc.options.framerateLimit().set(limit);
            }
            if (request.has("dhRenderer")) setEnum(entry("Client$Advanced$Debugging", "rendererMode"), request.get("dhRenderer").getAsString());
            if (request.has("dhGeneration")) setEnum(entry("Common$WorldGenerator", "generatorPlan"), request.get("dhGeneration").getAsString());
            if (request.has("particles")) mc.options.particles().set(ParticleStatus.valueOf(request.get("particles").getAsString()));
            if (request.has("renderDistance")) { mc.options.renderDistance().set(request.get("renderDistance").getAsInt()); mc.levelRenderer.allChanged(); }
            if (request.has("entityDistance")) {
                double distance = request.get("entityDistance").getAsDouble();
                if (distance < 0.5 || distance > 5.0) throw new IllegalArgumentException("Entity distance must be between 0.5 and 5.0");
                mc.options.entityDistanceScaling().set(distance);
            }
            if (request.has("yaw") && mc.player != null) mc.player.setYRot(request.get("yaw").getAsFloat());
            if (request.has("pitch") && mc.player != null) mc.player.setXRot(request.get("pitch").getAsFloat());
            if (request.has("shaders")) {
                Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
                // reload() reinitializes IrisConfig from disk; use Iris's own toggle,
                // which saves the requested value before rebuilding the pipeline.
                iris.getMethod("toggleShaders", Minecraft.class, boolean.class).invoke(null, mc, request.get("shaders").getAsBoolean());
            }
        } else if (!action.equals("state")) throw new IllegalArgumentException("Unknown performance action");
        JsonObject out = new JsonObject();
        out.addProperty("worldLoaded", mc.level != null);
        out.addProperty("screen", mc.screen == null ? "" : mc.screen.getClass().getName());
        out.addProperty("fps", mc.getFps());
        out.addProperty("maxFps", mc.options.framerateLimit().get());
        out.addProperty("windowActive", mc.isWindowActive());
        out.addProperty("width", mc.getWindow().getWidth()); out.addProperty("height", mc.getWindow().getHeight());
        out.addProperty("renderDistance", mc.options.renderDistance().get());
        out.addProperty("entityDistance", mc.options.entityDistanceScaling().get());
        out.addProperty("particlesSetting", mc.options.particles().get().name());
        out.addProperty("particleCount", mc.particleEngine.countParticles());
        if (mc.player != null) {
            out.addProperty("x", mc.player.getX()); out.addProperty("y", mc.player.getY()); out.addProperty("z", mc.player.getZ());
            out.addProperty("yaw", mc.player.getYRot()); out.addProperty("pitch", mc.player.getXRot());
            out.addProperty("dimension", mc.level.dimension().location().toString());
            JsonObject counts = new JsonObject();
            for (var entity : mc.level.entitiesForRendering()) {
                String type = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
                counts.addProperty(type, (counts.has(type) ? counts.get(type).getAsInt() : 0) + 1);
            }
            out.add("entities", counts);
        }
        try {
            out.addProperty("dhRenderer", get(entry("Client$Advanced$Debugging", "rendererMode")).toString());
            out.addProperty("dhGeneration", get(entry("Common$WorldGenerator", "generatorPlan")).toString());
            Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
            Object config = iris.getMethod("getIrisConfig").invoke(null);
            out.addProperty("shaders", (Boolean) config.getClass().getMethod("areShadersEnabled").invoke(config));
        } catch (ReflectiveOperationException ignored) { }
        out.addProperty("sampling", end != 0 && System.nanoTime() < end);
        out.addProperty("frameCount", frames.size());
        if (!frames.isEmpty()) {
            var sorted = new ArrayList<>(frames); Collections.sort(sorted);
            double total = frames.stream().mapToDouble(Double::doubleValue).sum();
            out.addProperty("meanFps", frames.size() * 1000.0 / total);
            out.addProperty("medianMs", sorted.get(sorted.size() / 2));
            out.addProperty("p95Ms", sorted.get(Math.min(sorted.size()-1, (int)(sorted.size() * .95))));
            out.addProperty("p99Ms", sorted.get(Math.min(sorted.size()-1, (int)(sorted.size() * .99))));
        }
        return out;
    }
}
