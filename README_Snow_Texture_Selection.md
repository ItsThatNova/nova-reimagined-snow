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