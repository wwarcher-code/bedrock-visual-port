# Ray tracing for offline exports: notes

Notes for the offline preset's ray tracing (design doc, "Offline-only extras": ray-traced shadows and
reflections). They draw on NVIDIA's RTX Remix for Minecraft
([NVIDIAGameWorks/rtx-remix-minecraft](https://github.com/NVIDIAGameWorks/rtx-remix-minecraft), Apache-2.0,
read on 2026-11-18).

## What RTX Remix for Minecraft is

- **The mod:** a Fabric/Forge/NeoForge mod for Minecraft 1.21.1, which renders through OpenGL. It captures the
  scene and submits it to NVIDIA's RTX Remix runtime (dxvk-remix, `d3d9.dll`) through the Remix C API (0.16),
  over a JNI bridge (`rtxremix-java/`).
- **The scene it submits:** meshes, materials, instances, lights and a camera. Particles, sky and clouds are
  submitted too.
- **The renderer:** the runtime is a complete path tracer, with ReSTIR-style light sampling, a neural radiance
  cache (NRC/SHaRC), its own volumetrics, denoising, DLSS, auto exposure and ACES tone mapping. The mod only
  feeds it a scene and does none of the lighting itself.
- **Output:** the runtime presents into the game's window, or into an interop image (below).
- **Requirements:** RTX 30-series or newer and NVIDIA driver 616.92+. The runtime ships separately, with
  telemetry and crash reports on by default.

## What could carry over

**Host integration (`HostGraphicsIntegration`, `remix_c.h`).** With `StartupInfo.interopImage`, Remix renders
into an image it exports:
- the image's memory as a Win32 handle (`imageMemory_Handle`), plus a read-only primary depth image;
- two timeline semaphores, `dxvkDoneSemaphore` and `clientDoneSemaphore`.

The game imports the image and waits for `dxvkDone`. It draws its post effects and UI on top, then signals
`clientDone`. Remix then presents, so the game's own swapchain present must go.

The mod does this in OpenGL (`glImportMemoryWin32HandleEXT`, `glWaitSemaphoreEXT`). A Vulkan host would do
the same with `VK_KHR_external_memory_win32` and `VK_KHR_external_semaphore_win32`. Minecraft 26.3's
`com.mojang.renderpearl.backend.vulkan.VulkanDevice` exposes `vkDevice()` (LWJGL) and `vma()`, so the mod
could make those calls. `dxvk_CopyRenderingOutput` also hands out the depth, normals and picking buffers.

**Scene capture, as they found it has to work for a path tracer:**
- **Terrain:** section meshes come from Minecraft's own builders and are merged into chunk-column assets, split
  into byte-capped pages. The render thread publishes them under a frame budget and retires replaced pages
  through a bounded queue.
  - Static terrain gets its own BLAS, built once (`rtx.minimizeBlasMerging`). Merged BLASes were rebuilt every
    frame.
  - Chunk delivery and unloads are paced by measured render-thread cost.
- **Entities:** captured in place from vanilla's own submissions, so each model is evaluated once. They are
  captured on the render thread only, because vanilla shares mutable model parts.
  - Entities outside the frustum are swept up, closest first under a budget, because a path tracer needs
    them for shadows and reflections.
  - Coplanar decals (banner layers) go in as one mesh in paint order.
- **First person:** the hand and held items use a separate view-model camera, so they don't shadow or reflect
  in the world.
- **Particles:** prepared on a worker and published one frame late.
- **Lights:** block lights come from resource-pack rules keyed by block state, as sphere lights. They're
  submitted per frame, so culling a light is simply not drawing it, and they're culled by a power-aware reach
  with hysteresis.
  - A camera-locked light gets volumetric radiance scale 0. The volumetric grid reuses last frame's
    radiance, so a moving emitter would smear.
- **Sun and moon:** distant lights with angular diameters, their radiance dimmed by an air-mass
  transmittance.
- **Materials:** LabPBR is converted:
  - roughness = (1 − smoothness)²;
  - metallic = f0 ≥ 230;
  - AO from the normal map's blue channel multiplied into the albedo;
  - emission from the specular map's alpha, with vanilla block luminance not counted as emission.
- **Scale:** Minecraft's units are metres, and Remix's volumetrics assume centimetre-scale D3D9 games, so they
  set `rtx.sceneScale` to 0.01.
- **Timing:** their camera paths have a `fixed-60` clock (one 60 Hz step per rendered frame) for
  deterministic replays, like RenderClock's export clock.

## What it means for this renderer

**Using Remix itself for exports** (submit our scene, take its interop image) would get a full path tracer
quickly, but it is a different renderer:
- Remix lights, fogs, exposes and tone maps the scene its own way, not Bedrock's. That is the opposite of
  "fidelity to Bedrock first".
- It needs its own runtime and NVIDIA-only hardware.
- It would mean a second Vulkan device beside Minecraft's, and a Vulkan port of their OpenGL interop.
- Their mod targets 1.21.1 (OpenGL), not 26.3, so its capture code can't be used as it is.

**Our own ray tracing inside the deferred pipeline** keeps Bedrock's look, which is what the design doc
calls for: ray-traced shadows and reflections only, everything else as now. Using Vulkan's ray-query
extensions (`VK_KHR_acceleration_structure`, `VK_KHR_ray_query`) from the lighting and reflection passes, it
would need:
- **The extensions enabled at device creation.** renderpearl picks its device features
  (`backend/vulkan/init/FeatureSet`) when it creates the device, so this means a mixin there.
- **BLASes from Sodium's section meshes.** One per section, built when Sodium uploads a section and kept
  until it changes, as Remix's lesson says. Plus a TLAS each frame, with entities captured from Minecraft's
  submissions (we already intercept them) and swept outside the frustum.
- **Distant Horizons' terrain** as coarse BLASes, or left to the screen-space and height-map paths it has
  now.
- **Where the rays replace the screen-space paths:**
  - reflections where SSR misses, in place of the probe;
  - sun shadows past the cascades, in place of the height-map march;
  - point-light shadows.
- **Accumulation:** the offline preset's sub-frames already average jittered samples, so the rays can take
  one sample a sub-frame without a denoiser.

Their capture lessons carry over to either path: section BLASes built once, render-thread-only object
lifetimes under frame budgets, off-frustum entities for shadows and reflections, and the view-model split.
