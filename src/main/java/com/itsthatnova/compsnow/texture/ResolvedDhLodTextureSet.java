package com.itsthatnova.compsnow.texture;

import net.minecraft.client.texture.NativeImage;
import net.minecraft.util.Identifier;

import java.util.List;

/**
 * Final resolved DH LOD terrain texture set published to Iris.
 */
public record ResolvedDhLodTextureSet(List<Slot> slots) {
    public record Slot(NativeImage image, Identifier sourceId, SourceType sourceType) {}

    public enum SourceType {
        CONFIGURED,
        PROPAGATED,
        NEUTRAL_FALLBACK,
        DISABLED
    }
}
