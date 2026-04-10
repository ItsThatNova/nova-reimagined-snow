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
 * The texture is chunk-granularity: one texel per chunk column (XZ).
 * Texel layout is player-relative — the player's anchor chunk always maps
 * to texel (HALF_SIZE, HALF_SIZE). This prevents chunk coordinate aliasing
 * on large worlds where absolute modular indexing would cause distant chunks
 * to overwrite nearby chunks sharing the same texel.
 *
 * 1. snowBiomeMap (compsnow:snow_biome_map)
 *    Square texture, one texel per chunk.
 *    Written at floorMod(chunkX - anchorX + HALF_SIZE, size).
 *    Red channel: 0xFF = snow eligible, 0x00 = not eligible.
 *
 * 2. snowBiomeMeta (compsnow:snow_biome_meta)
 *    1x1 RGBA:
 *      R = (log2(size) - 5) / 5.0  for sizes 32-256
 *      G = floorMod(anchorChunkX, 256) / 255.0  (anchor X, mod 256)
 *      B = floorMod(anchorChunkZ, 256) / 255.0  (anchor Z, mod 256)
 *      A = 255 (signals mod is active)
 *
 *    The shader decodes anchorX as round(meta.g * 255) and uses it to
 *    apply the same player-relative offset when sampling the map.
 */
public class SnowBiomeTexture {

    private static final Logger LOGGER = LoggerFactory.getLogger("compsnow.texture");

    public static final Identifier MAP_ID  = Identifier.of("compsnow", "snow_biome_map");
    public static final Identifier META_ID = Identifier.of("compsnow", "snow_biome_meta");

    // Texture size in chunks. 256 gives a 128-chunk radius around the player anchor,
    // covering any practical DH render distance (192 chunks) with margin to spare.
    // At 256x256 the upload cost is ~256KB -- effectively free even when dirty every tick.
    public static final int MAX_SIZE = 256;

    // Half the texture size. The player anchor always sits at texel (HALF_SIZE, HALF_SIZE).
    public static final int HALF_SIZE = MAX_SIZE / 2;

    private NativeImageBackedTexture mapTexture;
    private NativeImageBackedTexture metaTexture;

    private int size;

    // Player-relative anchor. Chunk (cx, cz) maps to texel:
    //   tx = floorMod(cx - anchorChunkX + HALF_SIZE, size)
    //   tz = floorMod(cz - anchorChunkZ + HALF_SIZE, size)
    // The anchor is encoded in the G and B channels of the meta texture so the
    // shader applies the same offset. Only the mod-256 value is needed since the
    // shader's modular arithmetic is periodic with the same period.
    private int anchorChunkX = 0;
    private int anchorChunkZ = 0;

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

    public void clear() {
        if (!isAllocated()) return;
        NativeImage image = mapTexture.getImage();
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                image.setColor(x, z, 0xFF000000);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Anchor
    // -------------------------------------------------------------------------

    /**
     * Updates the player-relative anchor to the given chunk position.
     * All subsequent chunk reads and writes use this anchor to compute texel
     * coordinates. The meta texture is updated immediately so the shader sees
     * the new anchor on the next upload cycle.
     *
     * Called on world join (before allocate so the initial upload carries the
     * correct anchor) and on re-anchor events (teleport, periodic drift check).
     */
    public void setAnchor(int chunkX, int chunkZ) {
        this.anchorChunkX = chunkX;
        this.anchorChunkZ = chunkZ;
        updateMeta();
    }

    public int getAnchorX() { return anchorChunkX; }
    public int getAnchorZ() { return anchorChunkZ; }

    // -------------------------------------------------------------------------
    // Chunk writes
    // -------------------------------------------------------------------------

    /**
     * Maps a world chunk X coordinate to a texture X coordinate using the
     * current player-relative anchor. The player anchor maps to HALF_SIZE.
     */
    public int texXForChunk(int chunkX) {
        return size <= 0 ? 0 : Math.floorMod(chunkX - anchorChunkX + HALF_SIZE, size);
    }

    /**
     * Maps a world chunk Z coordinate to a texture Z coordinate using the
     * current player-relative anchor. The player anchor maps to HALF_SIZE.
     */
    public int texZForChunk(int chunkZ) {
        return size <= 0 ? 0 : Math.floorMod(chunkZ - anchorChunkZ + HALF_SIZE, size);
    }

    public int getChunkColor(int chunkX, int chunkZ) {
        if (!isAllocated()) return 0;
        return mapTexture.getImage().getColor(texXForChunk(chunkX), texZForChunk(chunkZ));
    }

    public boolean setChunk(int chunkX, int chunkZ, float intensity) {
        if (!isAllocated()) return false;
        int tx = texXForChunk(chunkX);
        int tz = texZForChunk(chunkZ);
        // NativeImage ABGR: A=255, B=0, G=0, R=intensityByte (0-255)
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
     * Encodes texture size and player anchor into the 1x1 meta texture.
     *
     * Channel layout (ABGR in NativeImage int, RGBA in shader):
     *   R = (log2(size) - 5) / 5.0   size encoding, 32-256 -> 0.0-0.8
     *   G = floorMod(anchorChunkX, 256) / 255.0   anchor X mod 256
     *   B = floorMod(anchorChunkZ, 256) / 255.0   anchor Z mod 256
     *   A = 255 (mod active flag)
     *
     * The shader decodes anchor as int(round(meta.g * 255)) and uses it in
     * the same modular formula as texXForChunk to arrive at the correct texel.
     * Passing only mod-256 is sufficient because modular arithmetic is periodic
     * with the same 256-chunk period as the texture.
     */
    private void updateMeta() {
        if (metaTexture == null) return;
        float sizeEncoded = (log2(size) - 5.0f) / 5.0f;
        int r = clamp255(Math.round(sizeEncoded * 255));
        int g = Math.floorMod(anchorChunkX, 256);   // 0-255, anchor X mod 256
        int b = Math.floorMod(anchorChunkZ, 256);   // 0-255, anchor Z mod 256
        // NativeImage ABGR: A=255, B=b, G=g, R=r
        int packed = (255 << 24) | (b << 16) | (g << 8) | r;
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
