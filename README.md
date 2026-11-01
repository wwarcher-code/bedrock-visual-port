# Bedrock Visuals

A personal-use Fabric mod for Minecraft Java 26.3 that reproduces Bedrock's Vibrant Visuals as a deferred
PBR renderer on the native Vulkan backend, alongside Sodium and Distant Horizons. Not for distribution.

Design doc: https://claude.ai/artifact/Sf95jvy5UQoAoqr1DMriZm

To test a change in game, see [TESTING.md](TESTING.md): `.\test.cmd` pulls and launches into the test world,
**F9** captures every view at four times of day into one contact sheet and copies a diagnostics report to
the clipboard, and `/bv scene` builds a standard test scene.

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
| material | metalness, emissive, roughness, subsurface, from Bedrock's material maps (step 4) |
| light | red: block light, green: sky light |

Press **F7** in game to cycle the screen through albedo, normal, material, light, the shadow test (white
lit, black shadowed, red outside the shadow map) and back to normal (rebind it under Controls,
Miscellaneous).
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
  from the Bedrock lighting settings for the player's biome. Shadows come from the shadow map below.
- **Sky fill:** the share of the sky each face sees, in Bedrock's zenith sky color (shaded walls come out
  blue, as in Bedrock), plus sun, moon and sky light bouncing off the ground onto faces turned towards it;
  scaled by sky light.
- **Reflected sky:** smooth and metallic surfaces mirror the sky gradient in the reflected direction,
  weighted by Fresnel and roughness, until real reflections exist.
- **Ambient, block light and emissive:** Bedrock's ambient floor, a warm torch color by block light (faint
  by day and strong at night, as Bedrock's exposure makes it), and the material buffer's emissive channel.
- **Surface model:** Lambert diffuse plus GGX specular from the material buffer, then Bedrock's color
  grading and tone mapping (step 7).
- **Exposure:** a stand-in for Bedrock's automatic exposure brightens the image as the sun and moon fade,
  by up to 1.44x, so evenings stay nearly as bright as noon.

Bedrock's files give brightness curves but not how its renderer exposes them. The sky fill, bounce, ambient,
moon, block light and exposure factors in `LightingState`, and the "generic" tone map's saturation, were
fitted to Vibrant Visuals screenshots of the test scene's grass and a shaded wall at four times of day (TESTING.md, "Bedrock reference shots"). The log shows `[lighting] first frame lit
by the deferred lighting pass` once it runs; if the pass fails it logs `[lighting] deferred lighting failed`
and falls back to vanilla terrain.

## Shadows (step 5)

Before the G-buffer pass, Sodium's solid and cutout terrain is drawn a third time, from the sun (the moon
at night), into a 2048x2048 depth map covering 96 blocks around the camera. The lighting pass tests each
pixel against it with 3x3 filtering; beyond the map, and near its edge, it fades back to the sky-light
approximation. Cut-out blocks such as leaves cast shadows of their actual shape.

Sodium hands every pass the chunks the camera can see, so for now terrain behind the camera or hidden
from it casts no shadows into view; sky light still darkens caves, whose roofs may be missing from the
map. The log shows `[shadows] first shadow map drawn` once the pass runs; if it fails it logs
`[shadows] shadow pass failed` and the lighting carries on without shadows.

## Materials (step 4)

`MaterialAtlas` builds a second texture laid out exactly like Java's block atlas, with each Java sprite's
rectangle holding the Bedrock material map (MERS) of the texture set the texture map points it at, and
Bedrock's PBR fallback everywhere else. The G-buffer shader reads it at the block texture coordinates, with
the block atlas's sampler, so materials filter and mip like colors. It is rebuilt whenever Minecraft
recreates the block atlas and logs `[materials] built ... material atlas`. Animated Bedrock textures (a
strip of frames) contribute their first frame. Subsurface scattering is stored but not used yet.

Bedrock's material maps are TGA files, which Minecraft can't read; `TgaImage` decodes them, and matches
Pillow on all 3,328 TGA files in the 1.26.60 samples.

## Color grading and tone mapping (step 7)

The lighting pass ends as Bedrock documents it: the biome's color grading on the HDR color (temperature,
then saturation, contrast about the scene's average luminance, gain and offset, with optional shadow and
highlight bands), then the biome's tone-mapping operator, then its gamma as the final encoding. All six
operators are implemented. Two pieces are approximations: "generic", vanilla's operator, is Mojang's
unpublished hand-tuned curve, approximated as hue-preserving ACES with its saturation fitted to Bedrock
screenshots; and the average luminance is fixed at
middle grey until the renderer measures it.

## Checking shaders

`tools\check_shaders.py` compiles every shader with glslangValidator, resolving includes from the mod and
the Sodium jar, with and without the cutout define, so GLSL errors show up without launching the game. It
needs Python and glslangValidator (from the Vulkan SDK) on the path:

```powershell
py tools\check_shaders.py "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\maven.modrinth\sodium\mc26.3-0.9.2-fabric\<hash>\sodium-mc26.3-0.9.2-fabric.jar"
```

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
`tools\texture_map_overrides.json`. To regenerate after a version bump, run the generator against a full
(not sparse) `bedrock-samples` checkout of the new version, since it needs every block image's name and the
install's images aren't readable files (needs Python, and the 26.3 client jar from Gradle's cache):

```powershell
py tools\gen_texture_map.py "$env:USERPROFILE\.gradle\caches\fabric-loom\26.3\minecraft-client.jar" "<full bedrock-samples>\resource_pack"
```
