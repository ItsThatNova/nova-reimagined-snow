package com.itsthatnova.compsnow.texture;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages two textures used by the Nova Reimagined Snow shader.
 *
 * The texture is now chunk-granularity: one texel per chunk column (XZ),
 * rather than one texel per block column. This reduces the maximum texture
 * size from 16384×16384 (~1GB) to 1024×1024 (~4MB), making uploads fast
 * enough to run without perceptible stutter.
 *
 * 1. snowBiomeMap (compsnow:snow_biome_map)
 *    Square texture, one texel per chunk.
 *    Written at (chunkX mod size, chunkZ mod size).
 *    Red channel: 0xFF = snow eligible, 0x00 = not eligible.
 *
 * 2. snowBiomeMeta (compsnow:snow_biome_meta)
 *    1x1 RGBA encoding texture size and active flag:
 *      R = (log2(size) - 5) / 5.0  for sizes 32–1024
 *      A = 255 (signals mod is active)
 *
 * The shader decodes size as: pow(2.0, meta.r * 5.0 + 5.0)
 * and converts world position to chunk coords via floor(worldPos.xz / 16.0)
 * before sampling — matching how columns are written here.
 */
public class SnowBiomeTexture {

    private static final Logger LOGGER = LoggerFactory.getLogger("compsnow.texture");

    public static final Identifier MAP_ID  = Identifier.of("compsnow", "snow_biome_map");
    public static final Identifier META_ID = Identifier.of("compsnow", "snow_biome_meta");

    // Maximum supported texture size in chunks.
    // 1024 chunks = 16384 blocks radius, covering any practical DH render distance.
    public static final int MAX_SIZE = 1024;

    private NativeImageBackedTexture mapTexture;
    private NativeImageBackedTexture metaTexture;

    private int size;

    public SnowBiomeTexture() {}

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    public void allocate(int newSize) {
        release();
        this.size = newSize;

        NativeImage mapImage = new NativeImage(NativeImage.Format.RGBA, size, size, false);
        mapTexture = new NativeImageBackedTexture(mapImage);
        MinecraftClient.getInstance().getTextureManager()
                .registerTexture(MAP_ID, mapTexture);

        NativeImage metaImage = new NativeImage(NativeImage.Format.RGBA, 1, 1, false);
        metaTexture = new NativeImageBackedTexture(metaImage);
        MinecraftClient.getInstance().getTextureManager()
                .registerTexture(META_ID, metaTexture);

        updateMeta();

        // Upload immediately so the shader sees meta.a = 1.0 (mod active) from the
        // very first frame rather than waiting for the first dirty-chunk tick.
        upload();

        LOGGER.warn("Allocated snow biome textures ({}x{} chunks)", size, size);
    }

    public void release() {
        if (mapTexture != null) {
            MinecraftClient.getInstance().getTextureManager().destroyTexture(MAP_ID);
            mapTexture.close();
            mapTexture = null;
        }
        if (metaTexture != null) {
            MinecraftClient.getInstance().getTextureManager().destroyTexture(META_ID);
            metaTexture.close();
            metaTexture = null;
        }
        size = 0;
        LOGGER.warn("Released snow biome textures");
    }

    public boolean isAllocated() { return mapTexture != null; }

    // -------------------------------------------------------------------------
    // Chunk writes
    // -------------------------------------------------------------------------

    /**
     * Writes snow intensity for a chunk column.
     * Uses modular indexing: texel = (chunkX mod size, chunkZ mod size).
     * The shader uses the same formula to sample — no offset needed.
     *
     * Intensity is encoded in the red channel as a byte (0–255), where
     * 0 = no snow and 255 = full intensity. The shader reads this as a
     * float in [0.0, 1.0] and applies it as a weight on the snow draw,
     * giving intermediate values for transitional biomes at altitude.
     */
    public int texXForChunk(int chunkX) {
        return size <= 0 ? 0 : Math.floorMod(chunkX, size);
    }

    public int texZForChunk(int chunkZ) {
        return size <= 0 ? 0 : Math.floorMod(chunkZ, size);
    }

    public int getChunkColor(int chunkX, int chunkZ) {
        if (!isAllocated()) return 0;
        return mapTexture.getImage().getColor(texXForChunk(chunkX), texZForChunk(chunkZ));
    }

    public boolean setChunk(int chunkX, int chunkZ, float intensity) {
        if (!isAllocated()) return false;
        int tx = texXForChunk(chunkX);
        int tz = texZForChunk(chunkZ);
        // NativeImage ABGR: A=255, B=0, G=0, R=intensityByte (0–255)
        int intensityByte = Math.max(0, Math.min(255, Math.round(intensity * 255.0f)));
        int color = 0xFF000000 | intensityByte;
        int previous = mapTexture.getImage().getColor(tx, tz);
        mapTexture.getImage().setColor(tx, tz, color);
        return previous != color;
    }

    // -------------------------------------------------------------------------
    // Metadata
    // -------------------------------------------------------------------------

    /**
     * Encodes texture size into the metadata texture.
     * R encodes (log2(size) - 5) / 5.0, mapping sizes 32–1024 to 0.0–1.0.
     * A = 255 signals the mod is active to the shader.
     */
    private void updateMeta() {
        if (metaTexture == null) return;
        float sizeEncoded = (log2(size) - 5.0f) / 5.0f;
        int r = clamp255(Math.round(sizeEncoded * 255));
        // ABGR: A=255 (active), B=0, G=0, R=sizeEncoded
        int packed = (255 << 24) | r;
        metaTexture.getImage().setColor(0, 0, packed);
    }

    // -------------------------------------------------------------------------
    // Upload
    // -------------------------------------------------------------------------

    public void upload() {
        if (!isAllocated()) return;
        mapTexture.upload();
        metaTexture.upload();
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public int getSize() { return size; }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static float log2(int value) {
        return (float)(Math.log(value) / Math.log(2));
    }

    private static int clamp255(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
