package com.itsthatnova.compsnow.texture;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Publishes 4 resolved snow textures for shader sampling.
 */
public final class SnowVariantTextureSet {
    private static final Logger LOGGER = LoggerFactory.getLogger("compsnow.variants");

    public static final Identifier VARIANT_0_ID = Identifier.of("compsnow", "snow_variant_0");
    public static final Identifier VARIANT_1_ID = Identifier.of("compsnow", "snow_variant_1");
    public static final Identifier VARIANT_2_ID = Identifier.of("compsnow", "snow_variant_2");
    public static final Identifier VARIANT_3_ID = Identifier.of("compsnow", "snow_variant_3");

    private static final List<Identifier> IDS = List.of(VARIANT_0_ID, VARIANT_1_ID, VARIANT_2_ID, VARIANT_3_ID);

    private final List<NativeImageBackedTexture> textures = new ArrayList<>();

    public void replace(ResolvedSnowVariantSet variantSet, String reason) {
        release();

        MinecraftClient client = MinecraftClient.getInstance();
        for (int i = 0; i < IDS.size(); i++) {
            NativeImageBackedTexture texture = new NativeImageBackedTexture(variantSet.slots().get(i).image());
            client.getTextureManager().registerTexture(IDS.get(i), texture);
            texture.upload();
            textures.add(texture);
        }

        LOGGER.warn("Uploaded {} resolved snow variant textures [{}]", textures.size(), reason);
    }

    public void release() {
        MinecraftClient client = MinecraftClient.getInstance();
        for (int i = 0; i < textures.size() && i < IDS.size(); i++) {
            client.getTextureManager().destroyTexture(IDS.get(i));
            textures.get(i).close();
        }
        textures.clear();
    }
}
