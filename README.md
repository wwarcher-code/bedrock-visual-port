# Bedrock Visuals

A personal-use Fabric mod for Minecraft Java 26.3 that reproduces Bedrock's Vibrant Visuals as a deferred
PBR renderer on the native Vulkan backend, alongside Sodium and Distant Horizons. Not for distribution.

Design doc: https://claude.ai/artifact/Sf95jvy5UQoAoqr1DMriZm

## Build

```
./gradlew build
```

Gradle downloads the Java 25 JVM it needs (pinned in `gradle/gradle-daemon-jvm.properties`). The mod jar
lands in `build/libs/`. `./gradlew runClient` starts a dev client with Sodium and Distant Horizons.

## Pinned versions

All versions live in `gradle.properties`: Minecraft 26.3, Fabric Loader 0.20.1, Fabric API 0.165.0,
Sodium 0.9.2, Distant Horizons 3.3.4, Replay Mod 2.6.28. At startup the mod logs whether each loaded mod
matches its pin. Mixins target those exact builds; bump one at a time and re-run the spikes.

## Layout

- `compat/` holds one adapter per hooked mod (`SodiumAdapter`, `DistantHorizonsAdapter`, `ReplayModAdapter`).
  Only adapters and their mixins touch another mod's code.
- `lod/` holds `DistantTerrainSource`, the interface the renderer reads distant terrain through. Distant
  Horizons implements it today; Voxy becomes a second implementation once it runs on Vulkan.
- `mixin/<mod>/` holds that mod's mixins. `BedrockVisualsMixinPlugin` skips them when the mod isn't loaded.

## Step 1 spikes

Each spike logs a `[spike 1x]` line in `logs/latest.log`. Run with the Vulkan backend selected and DH's rendering engine on Blaze3D (its default on 26.3).

| Spike | Setup | Pass when the log shows |
| --- | --- | --- |
| 1a Sodium terrain | `runClient`, load a world | `[spike 1a] Sodium terrain pass ...` once per pass (solid, cutout, translucent) |
| 1b Distant Horizons | `runClient`, load a world, wait for LODs to appear | `[spike 1b] ... engine=BLAZE_3D`, then `[spike 1b] DH frame captured` with texture sizes |
| 1c Replay Mod | Add the Replay Mod jar, record, export a short clip | `[spike 1c]` start and finish lines, and a video with correct frames |

1a only proves the hook is reached; the target redirect comes in step 2. 1b only captures DH's output:
DH keeps compositing and fogging as normal until the renderer's composite exists (step 2) and calls
`setRendererOwnsComposite(true)`.

## Distant Horizons

Level 1 uses DH's public API only, no mixins. Before DH composites its LODs onto Minecraft's frame, the
adapter captures DH's color and depth textures and its matrices. Once the renderer owns the composite, it
also cancels DH's composite and fog. DH's color is already lit and it outputs no normals or materials, so
the renderer treats it as albedo, rebuilds normals from depth and uses a generic material.

DH runs its own TAA and jitters its geometry for it. Turn DH's TAA off in its settings before offline
exports; its public API doesn't expose that setting.

Known before running: Replay Mod's frame capture reads back through OpenGL pixel buffers, so 1c likely
fails at the capture stage even if the start and finish hooks fire.
