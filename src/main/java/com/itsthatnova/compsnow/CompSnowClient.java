package com.itsthatnova.compsnow;

import com.itsthatnova.compsnow.events.SnowBiomeManager;
import com.itsthatnova.compsnow.texture.SnowVariantReloadListener;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.resource.ResourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client-side entrypoint for Nova Reimagined Snow.
 */
@Environment(EnvType.CLIENT)
public class CompSnowClient implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("compsnow");
    public static final String BUILD_MARKER = "snow-variant-resolver-2026-03-27";

    private ClientWorld lastWorld = null;

    @Override
    public void onInitializeClient() {
        LOGGER.warn("Nova Reimagined Snow initialising [{}]", BUILD_MARKER);

        SnowBiomeManager.INSTANCE.initializeSnowVariantConfig();
        ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES)
                .registerReloadListener(new SnowVariantReloadListener());
        SnowBiomeManager.INSTANCE.refreshSnowVariantTextures("client init");

        // World join
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
            client.execute(() -> {
                lastWorld = client.world;
                String worldKey = resolveWorldKey(client);
                SnowBiomeManager.INSTANCE.onWorldJoin(client.world, worldKey);
            })
        );

        // World leave / disconnect
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
            client.execute(() -> {
                lastWorld = null;
                SnowBiomeManager.INSTANCE.onWorldLeave();
            })
        );

        // Chunk load
        ClientChunkEvents.CHUNK_LOAD.register((world, chunk) ->
            SnowBiomeManager.INSTANCE.onChunkLoad(chunk.getPos().x, chunk.getPos().z)
        );

        // Chunk unload — no-op now since cache keeps data valid
        // ClientChunkEvents.CHUNK_UNLOAD kept for future use

        // Per-tick
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.world == null || client.player == null) return;

            // Detect dimension change
            if (lastWorld != null && lastWorld != client.world) {
                LOGGER.warn("Dimension change detected [{}]", BUILD_MARKER);
                lastWorld = client.world;
                SnowBiomeManager.INSTANCE.onDimensionChange(client.world);
            }

            SnowBiomeManager.INSTANCE.onClientTick(client);
        });

        LOGGER.warn("Nova Reimagined Snow ready [{}]", BUILD_MARKER);
    }

    /**
     * Resolves a unique world key for cache file naming.
     * Singleplayer: levelName_seed
     * Multiplayer:  serverAddress
     */
    private String resolveWorldKey(MinecraftClient client) {
        // Multiplayer
        ServerInfo serverInfo = client.getCurrentServerEntry();
        if (serverInfo != null) {
            return "server_" + serverInfo.address;
        }

        // Singleplayer — get level name and seed
        try {
            if (client.getServer() != null) {
                long seed = client.getServer().getOverworld().getSeed();
                String levelName = client.getServer().getSaveProperties().getLevelName();
                return levelName + "_" + seed;
            }
        } catch (Exception e) {
            LOGGER.warn("Could not resolve singleplayer world key: {}", e.getMessage());
        }

        // Fallback
        return "unknown_world";
    }

}
