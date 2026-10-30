# Bedrock Visuals

A personal-use Fabric mod for Minecraft Java 26.3 that reproduces Bedrock's Vibrant Visuals as a deferred
PBR renderer on the native Vulkan backend, alongside Sodium and Voxy. Not for distribution.

Design doc: https://claude.ai/artifact/Sf95jvy5UQoAoqr1DMriZm

## Build

```
./gradlew build
```

Gradle downloads the Java 25 JVM it needs (pinned in `gradle/gradle-daemon-jvm.properties`). The mod jar
lands in `build/libs/`. `./gradlew runClient` starts a dev client with Sodium.

## Pinned versions

All versions live in `gradle.properties`: Minecraft 26.3, Fabric Loader 0.20.1, Fabric API 0.165.0,
Sodium 0.9.2, Voxy 0.2.20-beta, Replay Mod 2.6.28. At startup the mod logs whether each loaded mod
matches its pin. Mixins target those exact builds; bump one at a time and re-run the spikes.

## Layout

- `compat/` holds one adapter per hooked mod (`SodiumAdapter`, `VoxyAdapter`, `ReplayModAdapter`). Only
  adapters and their mixins touch another mod's internals.
- `mixin/<mod>/` holds that mod's mixins. `BedrockVisualsMixinPlugin` skips them when the mod isn't loaded.

## Step 1 spikes

Each spike logs a `[spike 1x]` line in `logs/latest.log`. Run with the Vulkan backend selected.

| Spike | Setup | Pass when the log shows |
| --- | --- | --- |
| 1a Sodium terrain | `runClient`, load a world | `[spike 1a] Sodium terrain pass ...` once per pass (solid, cutout, translucent) |
| 1b Voxy LODs | Add the Voxy jar to `run/mods` | `[spike 1b] Voxy pipeline ... running` |
| 1c Replay Mod | Add the Replay Mod jar, record, export a short clip | `[spike 1c]` start and finish lines, and a video with correct frames |

1a only proves the hook is reached; the target redirect comes in step 2.

Known before running: Voxy 0.2.20 refuses the Vulkan backend ("Voxy does not presently support vulkan"),
so 1b fails on Vulkan. Replay Mod's frame capture reads back through OpenGL pixel buffers, so 1c likely
fails at the capture stage even if the start and finish hooks fire.
