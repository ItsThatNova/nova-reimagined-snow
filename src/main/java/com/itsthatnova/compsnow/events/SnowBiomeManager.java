package com.itsthatnova.compsnow.events;

import com.itsthatnova.compsnow.config.SnowVariantConfig;
import com.itsthatnova.compsnow.texture.ResolvedSnowVariantSet;
import com.itsthatnova.compsnow.texture.SnowBiomeTexture;
import com.itsthatnova.compsnow.texture.SnowVariantResolver;
import com.itsthatnova.compsnow.texture.SnowVariantTextureSet;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.resource.ResourceManager;
import net.minecraft.text.Text;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Central coordinator for Nova Reimagined Snow.
 *
 * All data sources now flow through the same chunk-update routes:
 *   - vanilla chunk load queue (refinement / ground truth)
 *   - DH listener (terrain-repo sampling)
 *   - DH scanner (background terrain-repo sampling)
 *   - cache restore on join/reallocation
 */
public class SnowBiomeManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("compsnow.manager");
    private static final Logger WEATHER_LOGGER = LoggerFactory.getLogger("compsnow");
    public static final SnowBiomeManager INSTANCE = new SnowBiomeManager();

    private static final float DEBUG_EPSILON = 0.000001f;
    private static final float ACTIVE_THRESHOLD = 0.001f;

    private final SnowBiomeTexture texture = new SnowBiomeTexture();
    private final ChunkUpdateQueue queue = new ChunkUpdateQueue();
    private final CacheManager cacheManager = new CacheManager();
    private final DhChunkScanner dhScanner = new DhChunkScanner();

    private final SnowVariantResolver snowVariantResolver = new SnowVariantResolver();
    private final SnowVariantTextureSet snowVariantTextures = new SnowVariantTextureSet();
    private SnowVariantConfig snowVariantConfig = SnowVariantConfig.defaults();

    private int lastRenderDistance = -1;
    private boolean needsUpload = false;

    private float currentRainGradient = 0.0f;
    private float currentThunderGradient = 0.0f;
    private boolean currentIsRaining = false;
    private boolean currentIsThundering = false;

    private float lastRainGradient = Float.NaN;
    private float lastThunderGradient = Float.NaN;
    private boolean lastIsRaining = false;
    private boolean lastIsThundering = false;
    private boolean lastWeatherActive = false;

    private int weatherDebugBurstTicks = 0;
    private long weatherSampleIndex = 0;

    private SnowBiomeManager() {
        LOGGER.warn("Nova Reimagined Snow manager init (DH mod present: {}, API ready: {})",
                DhLevelResolver.isDhModPresent(), DhLevelResolver.isDhApiReady());
    }

    public synchronized void initializeSnowVariantConfig() {
        snowVariantConfig = SnowVariantConfig.loadOrCreate();
    }

    public void refreshSnowVariantTextures(String reason) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            LOGGER.warn("Skipping snow variant refresh [{}] because MinecraftClient is null", reason);
            return;
        }

        ResourceManager resourceManager = client.getResourceManager();
        if (resourceManager == null) {
            LOGGER.warn("Skipping snow variant refresh [{}] because ResourceManager is null", reason);
            return;
        }

        refreshSnowVariantTextures(reason, resourceManager);
    }

    public void refreshSnowVariantTextures(String reason, ResourceManager resourceManager) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || resourceManager == null) {
            LOGGER.warn("Skipping snow variant refresh [{}] because client or resource manager was null", reason);
            return;
        }

        client.execute(() -> refreshSnowVariantTexturesNow(reason, resourceManager));
    }

    private synchronized void refreshSnowVariantTexturesNow(String reason, ResourceManager resourceManager) {
        try {
            snowVariantConfig = SnowVariantConfig.loadOrCreate();
            ResolvedSnowVariantSet resolved = snowVariantResolver.resolve(resourceManager, snowVariantConfig);
            snowVariantTextures.replace(resolved, reason);
        } catch (Exception e) {
            LOGGER.warn("Failed to rebuild resolved snow variants [{}]; keeping previous textures. {}",
                    reason, e.getMessage());
        }
    }

    public void onWorldJoin(ClientWorld world, String worldKey) {
        int vanillaRenderDist = getVanillaRenderDistance();
        boolean dhPresent = DhLevelResolver.isDhModPresent();
        int texSize = dhPresent
                ? SnowBiomeTexture.MAX_SIZE
                : computeTextureSize(vanillaRenderDist);

        lastRenderDistance = vanillaRenderDist;

        texture.allocate(texSize);

        cacheManager.onWorldJoin(worldKey);
        restoreCachedTexture("world join");
        resetWeatherDebugState();
        refreshSnowVariantTextures("world join");

        queue.clear();
        queue.enqueueAll(texture);

        if (dhPresent) {
            MinecraftClient mc = MinecraftClient.getInstance();
            int playerChunkX = mc.player != null ? (int) mc.player.getX() >> 4 : 0;
            int playerChunkZ = mc.player != null ? (int) mc.player.getZ() >> 4 : 0;
            boolean singleplayer = DhLevelResolver.isSingleplayerSession();

            dhScanner.start(playerChunkX, playerChunkZ, vanillaRenderDist, texSize, singleplayer);
            // Queue a background re-verification of any previously cached DH chunks
            // so state changes since the last session (e.g. seasonal transitions)
            // are detected and corrected without requiring a cache wipe.
            dhScanner.startVerificationSweep(cacheManager.getNonVanillaCachedChunks());
            DhChunkListener.register();
        }

        LOGGER.warn("World joined — texture {}x{} chunks, {} in cache, DH mod present: {}, API ready: {}, scanner: {}, listener: {}",
                texSize, texSize, cacheManager.cacheSize(), dhPresent, DhLevelResolver.isDhApiReady(),
                dhPresent ? (dhScanner.isScanEnabled() ? "active(" + dhScanner.describeMode() + ")" : "inactive") : "inactive",
                dhPresent ? "active" : "inactive");
    }

    public void onWorldLeave() {
        if (DhLevelResolver.isDhModPresent()) {
            DhChunkListener.unregister();
            dhScanner.drainCompleted(cacheManager, texture);
            dhScanner.stop();
        }
        queue.clear();
        texture.release();
        cacheManager.onWorldLeave();
        resetWeatherDebugState();
        lastRenderDistance = -1;
        needsUpload = false;
        LOGGER.warn("World left — biome textures released, cache flushed");
    }

    public void onDimensionChange(ClientWorld newWorld) {
        if (DhLevelResolver.isDhModPresent()) {
            DhChunkListener.unregister();
            dhScanner.stop();

            // Restart the scanner for the new dimension and immediately queue
            // a verification sweep — returning to the overworld after time in
            // another dimension is exactly when seasonal changes may have made
            // previously cached DH snow states stale.
            MinecraftClient mc = MinecraftClient.getInstance();
            int playerChunkX = mc.player != null ? (int) mc.player.getX() >> 4 : 0;
            int playerChunkZ = mc.player != null ? (int) mc.player.getZ() >> 4 : 0;
            int vanillaRenderDist = mc.options.getViewDistance().getValue();
            int texSize = texture.isAllocated() ? texture.getSize() : SnowBiomeTexture.MAX_SIZE;
            boolean singleplayer = DhLevelResolver.isSingleplayerSession();

            dhScanner.start(playerChunkX, playerChunkZ, vanillaRenderDist, texSize, singleplayer);
            dhScanner.startVerificationSweep(cacheManager.getNonVanillaCachedChunks());
            DhChunkListener.register();
        }
        queue.clear();
        resetWeatherDebugState();
        if (texture.isAllocated()) {
            restoreCachedTexture("dimension change");
        }
    }

    public void onClientTick(MinecraftClient mc) {
        if (!texture.isAllocated()) return;
        if (mc.world == null || mc.player == null) return;

        if (!DhLevelResolver.isDhModPresent()) {
            int currentDist = getVanillaRenderDistance();
            if (currentDist != lastRenderDistance) {
                LOGGER.warn("Render distance changed {} -> {} — reallocating",
                        lastRenderDistance, currentDist);
                queue.clear();
                texture.allocate(computeTextureSize(currentDist));
                restoreCachedTexture("render distance reallocation");
                queue.enqueueAll(texture);
                lastRenderDistance = currentDist;
            }
        }

        if (processVanillaQueue(mc.world)) {
            markTextureDirty("vanilla queue");
        }

        if (DhLevelResolver.isDhModPresent() && dhScanner.isActive()) {
            if (dhScanner.tick(mc.world, texture, cacheManager, mc)) {
                markTextureDirty("dh worker results");
            }
        }

        cacheManager.tickFlush();
        sampleAndLogWeather(mc);

        if (needsUpload) {
            LOGGER.debug("Uploading snow biome textures to GPU");
            texture.upload();
            needsUpload = false;
        }
    }

    public void onChunkLoad(int chunkX, int chunkZ) {
        queue.enqueue(chunkX, chunkZ);
    }

    public void onDhChunkModified(IDhApiLevelWrapper levelWrapper, int chunkX, int chunkZ) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientWorld world = mc.world;
        if (world == null || !texture.isAllocated()) return;
        if (!World.OVERWORLD.equals(world.getRegistryKey())) return;

        IDhApiLevelWrapper resolvedWrapper = levelWrapper != null ? levelWrapper : DhLevelResolver.resolveActiveLevelWrapper(world);
        if (resolvedWrapper == null) {
            LOGGER.debug("Ignoring DH chunk ({}, {}) because no active level wrapper was available", chunkX, chunkZ);
            return;
        }

        dhScanner.enqueueDhChunk(resolvedWrapper, chunkX, chunkZ, "dh-listener");
    }

    public boolean isChunkCached(int chunkX, int chunkZ) {
        return cacheManager.hasCached(chunkX, chunkZ);
    }

    public boolean isActive() {
        return texture.isAllocated();
    }

    public float getCurrentRainGradient() {
        return currentRainGradient;
    }

    public float getCurrentThunderGradient() {
        return currentThunderGradient;
    }

    public boolean isRainActive() {
        return currentRainGradient > ACTIVE_THRESHOLD || currentIsRaining;
    }

    public boolean isStormActive() {
        return currentThunderGradient > ACTIVE_THRESHOLD || currentIsThundering;
    }

    private void resetWeatherDebugState() {
        currentRainGradient = 0.0f;
        currentThunderGradient = 0.0f;
        currentIsRaining = false;
        currentIsThundering = false;

        lastRainGradient = Float.NaN;
        lastThunderGradient = Float.NaN;
        lastIsRaining = false;
        lastIsThundering = false;
        lastWeatherActive = false;

        weatherDebugBurstTicks = 0;
        weatherSampleIndex = 0;
    }

    private void sampleAndLogWeather(MinecraftClient mc) {
        float rainGradient = mc.world.getRainGradient(1.0f);
        float thunderGradient = mc.world.getThunderGradient(1.0f);
        boolean isRaining = mc.world.isRaining();
        boolean isThundering = mc.world.isThundering();

        currentRainGradient = rainGradient;
        currentThunderGradient = thunderGradient;
        currentIsRaining = isRaining;
        currentIsThundering = isThundering;

        boolean firstSample = Float.isNaN(lastRainGradient) || Float.isNaN(lastThunderGradient);

        float rainDelta = firstSample ? 0.0f : (rainGradient - lastRainGradient);
        float thunderDelta = firstSample ? 0.0f : (thunderGradient - lastThunderGradient);

        boolean rainGradientChanged = firstSample || Math.abs(rainDelta) > DEBUG_EPSILON;
        boolean thunderGradientChanged = firstSample || Math.abs(thunderDelta) > DEBUG_EPSILON;
        boolean rainFlagChanged = firstSample || (isRaining != lastIsRaining);
        boolean thunderFlagChanged = firstSample || (isThundering != lastIsThundering);

        boolean weatherActive =
                rainGradient > ACTIVE_THRESHOLD ||
                thunderGradient > ACTIVE_THRESHOLD ||
                isRaining ||
                isThundering;

        boolean activityStateChanged = firstSample || (weatherActive != lastWeatherActive);
        boolean anythingChanged =
                rainGradientChanged ||
                thunderGradientChanged ||
                rainFlagChanged ||
                thunderFlagChanged ||
                activityStateChanged;

        if (anythingChanged) {
            weatherDebugBurstTicks = Math.max(weatherDebugBurstTicks, 100);
        }

        if (weatherDebugBurstTicks > 0) {
            weatherDebugBurstTicks--;

            if (snowVariantConfig.isDebugLogging() && (anythingChanged || (weatherDebugBurstTicks % 5) == 0)) {
                String msg = String.format(
                        "CompSnow weather #%d | rain=%.6f (d=%+.6f) thunder=%.6f (d=%+.6f) flags: rain %s->%s thunder %s->%s active %s->%s burst=%d",
                        weatherSampleIndex,
                        rainGradient, rainDelta,
                        thunderGradient, thunderDelta,
                        lastIsRaining, isRaining,
                        lastIsThundering, isThundering,
                        lastWeatherActive, weatherActive,
                        weatherDebugBurstTicks
                );

                WEATHER_LOGGER.info(msg);

                if (activityStateChanged || rainFlagChanged || thunderFlagChanged) {
                    mc.player.sendMessage(Text.literal(msg), false);
                }
            }
        }

        lastRainGradient = rainGradient;
        lastThunderGradient = thunderGradient;
        lastIsRaining = isRaining;
        lastIsThundering = isThundering;
        lastWeatherActive = weatherActive;
        weatherSampleIndex++;
    }

    private boolean processVanillaQueue(ClientWorld world) {
        if (!texture.isAllocated() || world == null) return false;

        boolean anyWritten = false;
        int processed = 0;

        while (!queue.isEmpty() && processed < 64) {
            long[] entry = queue.poll();
            if (entry == null) break;

            int chunkX = (int) entry[0];
            int chunkZ = (int) entry[1];
            if (applyChunkUpdateFromWorld(world, chunkX, chunkZ, "vanilla-chunk-load")) {
                anyWritten = true;
            }
            processed++;
        }
        return anyWritten;
    }

    private boolean applyChunkUpdateFromWorld(ClientWorld world, int chunkX, int chunkZ, String source) {
        Float previous = cacheManager.getCachedSnowState(chunkX, chunkZ);
        float intensity = cacheManager.sampleAndCache(world, chunkX, chunkZ, texture, source + "-refine");
        dhScanner.markVanillaConfirmed(chunkX, chunkZ);
        if (previous == null) {
            LOGGER.debug("{} populated previously-uncached chunk ({}, {}) -> intensity={}", source, chunkX, chunkZ, intensity);
        } else if (Math.abs(previous - intensity) >= 0.004f) {
            LOGGER.debug("{} refined chunk ({}, {}) from intensity={} -> {}", source, chunkX, chunkZ, previous, intensity);
        } else {
            LOGGER.debug("{} confirmed cached chunk ({}, {}) intensity={}", source, chunkX, chunkZ, intensity);
        }
        return true;
    }

    private void restoreCachedTexture(String source) {
        int restored = cacheManager.writeAllCachedToTexture(texture);
        if (restored > 0) {
            LOGGER.warn("Cache restore for {} wrote {} chunk texels and queued an upload", source, restored);
            markTextureDirty("cache restore: " + source);
        }
    }

    private void markTextureDirty(String reason) {
        if (!needsUpload) {
            LOGGER.debug("Queued texture upload ({})", reason);
        }
        needsUpload = true;
    }

    private int getVanillaRenderDistance() {
        return MinecraftClient.getInstance().options.getViewDistance().getValue();
    }

    public static int computeTextureSize(int renderDistanceChunks) {
        int diameter = renderDistanceChunks * 2;
        int size = 256;
        while (size < diameter) size <<= 1;
        return Math.min(size, SnowBiomeTexture.MAX_SIZE);
    }
}
