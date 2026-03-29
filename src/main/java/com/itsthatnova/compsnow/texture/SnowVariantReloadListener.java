package com.itsthatnova.compsnow.texture;

import com.itsthatnova.compsnow.events.SnowBiomeManager;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Rebuilds resolved snow textures whenever the active resource pack stack reloads.
 */
public final class SnowVariantReloadListener implements SimpleSynchronousResourceReloadListener {
    private static final Logger LOGGER = LoggerFactory.getLogger("compsnow.variants");
    private static final Identifier ID = Identifier.of("compsnow", "snow_variant_reload_listener");

    @Override
    public Identifier getFabricId() {
        return ID;
    }

    @Override
    public void reload(ResourceManager manager) {
        LOGGER.warn("Resource reload detected — rebuilding resolved snow variants");
        SnowBiomeManager.INSTANCE.refreshSnowVariantTextures("resource reload", manager);
    }
}
