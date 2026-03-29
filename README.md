# Nova Reimagined Snow

**A companion mod for Nova Reimagined that provides accurate per-block biome snow detection and snow texture variant support.**

---

## What it does

Nova Reimagined includes a shader-driven snow system that applies a snow appearance to surfaces in snowy biomes. Without this mod, the shader uses `inSnowy` - a player-position-based smoothed value - which causes snow tinting to visibly bleed across biome borders into adjacent warm biomes.

Nova Reimagined Snow solves this by building a per-block-column biome eligibility map on the GPU each time chunks load. The shader samples this map using exact world XZ coordinates, giving pixel-accurate biome boundaries with zero bleed.

The mod resolves up to four snow surface textures from your active resource pack stack and publishes them for the shader to use when painting snow onto terrain surfaces, paths, and other accent blocks.

### Features

- Per-block-column biome snow eligibility - exact biome boundaries, no bleed
- Automatically sizes the texture to match your render distance setting
- Handles render distance changes mid-session with automatic reallocation
- Correctly suppresses snow tinting in the Nether and End
- Resolves snow surface textures from active resource packs automatically
- Two snow variant modes: Auto and Manual_Override
- Graceful fallback - shader works without the mod using `inSnowy`
- Client-side only - works on any server with no server-side mod required

---

## Requirements

- Minecraft 1.21.x (Java Edition)
- [Fabric Loader](https://fabricmc.net/) 0.15.0+
- [Fabric API](https://modrinth.com/mod/fabric-api)
- [Iris Shaders](https://modrinth.com/mod/iris) 1.6+
- The companion Nova Reimagined shader pack

---

## Installation

1. Install Fabric Loader and Fabric API
2. Install Iris Shaders
3. Place `compsnow-<version>.jar` in your `mods/` folder
4. Install the companion Nova Reimagined shader pack
5. Enable the shader pack in Iris settings
6. Enable surface snow toggles under **Shader Settings > Other > Surface Snow**

---

## Snow Variant Textures

The mod resolves up to four snow surface textures and publishes them to the shader as:

- `compsnow:snow_variant_0`
- `compsnow:snow_variant_1`
- `compsnow:snow_variant_2`
- `compsnow:snow_variant_3`

The shader hashes each block's world position to assign one of the four variants, distributing them naturally across the terrain. All four slots are used if populated - slot order matters.

Texture sourcing is configured via `config/compsnow_snow_variants.json`, which is created automatically on first launch.

### Modes

**Auto** *(default)*
The mod scans your active resource pack stack for snow textures and fills the four slots automatically. This is the recommended mode for most users - install a snow resource pack and the mod handles the rest. Any unfilled slots fall back to vanilla `minecraft:textures/block/snow.png`.

**Manual_Override**
You specify exactly which textures fill each slot using resource identifier paths. Useful if Auto picks up the wrong textures, or if you want to mix textures from different packs. Any slot left as `""` falls back to vanilla snow.

### Manual_Override path format

Paths follow the standard Minecraft resource identifier format:

```
namespace:textures/path/to/texture.png
```

The namespace is the resource pack's namespace (usually `minecraft` for vanilla-replacing packs, or a mod/pack-specific namespace). The path is relative to the root of the pack's `assets/` folder.

Example using [Alacrity](https://modrinth.com/resourcepack/alacrity) snow textures:

```json
{
  "mode": "Manual_Override",
  "manualVariants": [
    "minecraft:textures/block/nature/snow/snow_1.png",
    "minecraft:textures/block/nature/snow/snow_2.png",
    "minecraft:textures/block/nature/snow/snow_3.png",
    "minecraft:textures/block/nature/snow/snow_4.png"
  ]
}
```

Changes take effect after reloading resource packs (F3+T) - a full restart is not required.

---

## Compatibility

| Environment | Status |
|---|---|
| Singleplayer | Yes |
| Multiplayer (no server mod) | Yes - Client-side only |
| Distant Horizons | Yes - DH uses its own render path |
| Other Fabric mods | Yes |
| Shader pack without mod | Yes - Falls back to `inSnowy` |
| Forge / NeoForge | No - Fabric only |
| OptiFine | No - Iris only |

---

## License

MIT - see [LICENSE](LICENSE)

---

## Credits

- **ItsThatNova** - mod author and shader modifications
- **EminGT** - Complementary Reimagined shader pack, the base on which Nova Reimagined is built