# Bedrock Visuals

A personal-use Fabric mod for Minecraft Java 26.3 that reproduces Bedrock's Vibrant Visuals as a deferred
PBR renderer on the native Vulkan backend, alongside Sodium and Distant Horizons. Not for distribution.

Design doc: https://claude.ai/artifact/Sf95jvy5UQoAoqr1DMriZm

## Build

In PowerShell, from the project folder:

```powershell
.\gradlew.bat build
```

Gradle downloads the Java 25 JVM it needs (pinned in `gradle\gradle-daemon-jvm.properties`); starting it
needs any Java 17 or newer. The mod jar lands in `build\libs`. `.\gradlew.bat runClient` starts a dev client
with Sodium, Distant Horizons and Flashback.

## Pinned versions

All versions live in `gradle.properties`: Minecraft 26.3, Fabric Loader 0.20.1, Fabric API 0.165.0,
Sodium 0.9.2, Distant Horizons 3.3.4, Flashback 0.43.6. At startup the mod logs whether each loaded mod
matches its pin. Hooks target those exact builds; bump one at a time and re-run the spikes.

The Bedrock reference is 1.26.60, the latest release.

## Layout

- `compat/` holds one adapter per hooked mod (`SodiumAdapter`, `DistantHorizonsAdapter`, `FlawlessFrames`).
  Only adapters and their mixins touch another mod's code.
- `lod/` holds `DistantTerrainSource`, the interface the renderer reads distant terrain through. Distant
  Horizons implements it today; Voxy becomes a second implementation once it runs on Vulkan.
- `bedrock/` reads Bedrock resource packs: texture sets, lighting, atmosphere, color grading, water, fog,
  client biomes, local lights and the PBR fallback. It has no Minecraft dependencies and is unit tested.

## Step 1 spikes

Each spike logs a `[spike 1x]` line in `run\logs\latest.log`. Run with the Vulkan backend selected and DH's
rendering engine on Blaze3D (its default on 26.3).

| Spike | Setup | Pass when the log shows |
| --- | --- | --- |
| 1a Sodium terrain | `.\gradlew.bat runClient`, load a world | `[spike 1a] Sodium terrain pass ...` once per pass (solid, cutout, translucent) |
| 1b Distant Horizons | Load a world, wait for LODs to appear | `[spike 1b] ... engine=BLAZE_3D`, then `[spike 1b] DH frame captured` with texture sizes |
| 1c Flashback | Record a few seconds with Flashback, then export a short clip | `[spike 1c] listening for exports from flashback`, then `flashback export started` and `finished`, and a video with correct frames |

To pull the spike lines out of the log after closing the game:

```powershell
Select-String -Path run\logs\latest.log -Pattern "\[spike|\[bedrock\]|matches the pinned|is pinned"
```

1a only proves the hook is reached; the target redirect comes in step 2. 1b only captures DH's output:
DH keeps compositing and fogging as normal until the renderer's composite exists (step 2) and calls
`setRendererOwnsComposite(true)`.

## G-buffer (step 2)

Before Minecraft's main pass, the mod draws Sodium's solid and cutout terrain a second time into its own
G-buffer: four 8-bit RGBA targets plus a depth buffer, sized to the window.

| Target | Contents |
| --- | --- |
| albedo | block color: texture times vertex tint and ambient occlusion, no lighting |
| normal | world-space face normal, mapped to 0–1 |
| material | metalness, emissive, roughness, subsurface; for now Bedrock's fallback (fully rough) |
| light | red: block light, green: sky light |

Press **F7** in game to cycle the
screen through albedo, normal, material, light and back to normal (rebind it under Controls, Miscellaneous).
The log shows `[gbuffer] first terrain pass drawn into <width>x<height> G-buffer` once the pass runs. If the
pass fails, it logs `[gbuffer] terrain pass failed` with the error and switches itself off; the game keeps
running.

Known limits for now: translucent terrain (water, glass), entities and Distant Horizons terrain aren't in the
G-buffer yet. Albedo has Minecraft's per-face shading divided out using the overworld's values, so Nether
block tops and bottoms are slightly off.

## Deferred lighting (step 3)

Right after the G-buffer pass, a full-screen pass lights it into the main image; pixels without terrain are
left alone so the sky shows through. The G-buffer's depth is then copied over the main depth, and
Minecraft's main pass skips Sodium's solid and cutout terrain, which is already drawn. Entities, water,
glass and Distant Horizons terrain still draw the vanilla way on top. Press **F8** to switch deferred
lighting off and compare with vanilla.

What lights each pixel:

- **Sun and moon:** direction from the time of day (and Bedrock's orbital offset); color and brightness
  from the Bedrock lighting settings for the player's biome. No shadows until step 5, so a face's sky
  light stands in for whether the sun reaches it.
- **Sky fill:** Bedrock's zenith sky color, scaled by sky light.
- **Ambient, block light and emissive:** Bedrock's ambient floor, a warm torch color by block light, and
  the material buffer's emissive channel.
- **Surface model:** Lambert diffuse plus GGX specular from the material buffer (all fully rough until the
  Bedrock material maps are wired in), then a filmic tone curve until Bedrock's tone mapping in step 7.

Bedrock's files give brightness curves but not how its renderer exposes them, so the scale factors marked
`TUNE` in `LightingState` are placeholders for the fidelity pass. The log shows `[lighting] first frame lit
by the deferred lighting pass` once it runs; if the pass fails it logs `[lighting] deferred lighting failed`
and falls back to vanilla terrain.

## Flashback

Flashback is reached through FREX's flawless frames API, which it already uses to tell renderers an export
is running. No mixins, so a Flashback update can't break the hook, and Replay Mod would work the same way.
Flashback captures frames through Mojang's rendering API rather than OpenGL, so it should work on Vulkan;
spike 1c confirms that. It renders exports into their own target at the export resolution, so 4K exports
don't depend on the window size.

## Distant Horizons

Level 1 uses DH's public API only, no mixins. Before DH composites its LODs onto Minecraft's frame, the
adapter captures DH's color and depth textures and its matrices. Once the renderer owns the composite, it
also cancels DH's composite and fog. DH's color is already lit and it outputs no normals or materials, so
the renderer treats it as albedo, rebuilds normals from depth and uses a generic material.

DH runs its own TAA and jitters its geometry for it. Turn DH's TAA off in its settings before offline
exports; its public API doesn't expose that setting.

## Bedrock inputs

At startup the mod reads Bedrock's vanilla packs and logs a `[bedrock]` summary. It takes two sources:

- **Your Bedrock install**, for lighting, atmosphere, color grading, water, fog and biome settings.
  Bedrock builds vanilla from a stack: `vanilla_base`, `vanilla`, then one `vanilla_<version>` pack per
  release, newest winning. The mod finds it on any drive under
  `XboxGames\Minecraft for Windows\Content\data\resource_packs`, where the Xbox app installs Bedrock since
  1.21.120. The install bundles its JSON into `.brarchive` files, which the mod reads.
- **Mojang's `bedrock-samples` repo**, for the material maps: each block's `_mers` image (metalness,
  emissive, roughness, subsurface) and its few `_normal` maps. The install stores its texture images in a
  form the mod can't read. Block colors come from Java's own textures, so only these extra maps are needed.

Download the material maps once, pinned to Bedrock 1.26.60 (about 46 MB):

```powershell
git clone --depth 1 --branch v1.26.60.5 --filter=blob:none --sparse https://github.com/Mojang/bedrock-samples "$HOME\bedrock-samples"
```
```powershell
git -C "$HOME\bedrock-samples" sparse-checkout set --no-cone "/resource_pack/textures/**/*.texture_set.json" "/resource_pack/textures/**/*_mers.*" "/resource_pack/textures/**/*_normal.tga"
```

The mod picks up `$HOME\bedrock-samples\resource_pack` automatically and layers it over the install. To use
other folders instead, create `run\config\bedrockvisuals-packs.txt` (for the dev client) listing one folder
per line, lowest priority first; an install's `resource_packs` folder expands to its vanilla stack.

To test the loader against both sources, separated by a semicolon:

```powershell
.\gradlew.bat test --rerun "-PbedrockPack=C:\XboxGames\Minecraft for Windows\Content\data\resource_packs;$HOME\bedrock-samples\resource_pack"
```

### Texture map

Java and Bedrock name many block textures differently (`oak_log` against `log_oak`).
`src\main\resources\bedrockvisuals\texture_map.json` maps 1,137 of Java 26.3's 1,300 block textures to
Bedrock's. Most of the rest are beds (entity textures in Bedrock), block-breaking stages and debug blocks.
It is generated by pairing each block's faces in both editions, plus hand fixes in
`tools\texture_map_overrides.json`. To regenerate after a version bump (needs Python, and the 26.3 client
jar from Gradle's cache):

```powershell
py tools\gen_texture_map.py "$env:USERPROFILE\.gradle\caches\fabric-loom\26.3\minecraft-client.jar" "C:\XboxGames\Minecraft for Windows\Content\data\resource_packs\vanilla"
```
