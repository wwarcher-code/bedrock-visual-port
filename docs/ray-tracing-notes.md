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

## Progress

**Phase 1, ray queries on Minecraft's device (done, 2026-11-18).**
- `mixin/renderpearl/VulkanFeatureSetsMixin` adds an optional feature set, which renderpearl enables at device
  creation where the GPU has it:
  - extensions `VK_KHR_acceleration_structure`, `VK_KHR_ray_query` and `VK_KHR_deferred_host_operations`;
  - features `accelerationStructure`, `rayQuery` and the 1.2 `bufferDeviceAddress`.
- `raytrace/RayQuery` reports whether the device came up with it. `RayQuerySmokeTest` proves it once a world
  is loaded:
  - it builds a one-triangle bottom level and a top level, all in raw Vulkan through LWJGL;
  - it casts four rays from a compute shader compiled with the game's shaderc (GLSL `GL_EXT_ray_query`);
  - it reads the results back. On the RTX 3080 Ti: hits [1, 0, 0, 1] at t = 1, about 0.3 s, once.
- The status report has a `ray queries` line, and ScriptRunner's `raytest` line runs the test again.
  A `ray-traced scene` line follows it.

**Pitfalls met:**
- renderpearl's VMA isn't created with buffer device addresses, so the ray tracing allocates its own memory
  (`VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT`).
- These LWJGL setters don't fill in their counts:
  - `VkWriteDescriptorSet.pBufferInfo` leaves `descriptorCount`, and the buffer is then silently unbound;
  - `VkWriteDescriptorSetAccelerationStructureKHR.pAccelerationStructures` leaves
    `accelerationStructureCount`;
  - `VkAccelerationStructureBuildGeometryInfoKHR.pGeometries` leaves `geometryCount`.

  Set them explicitly. Without the Vulkan SDK's validation layers, the only symptom was a dispatch that wrote
  nothing.

**Phase 2, the world as ray queries see it (done, 2026-11-18).** `raytrace/RtScene`, off unless turned on
(`rtscene on`, or `rtview`), rebuilt after every render of the level, before the hand:
- **Memory and submissions (`RtDevice`).** The ray tracing has its own VMA allocator on Minecraft's device,
  made with buffer device addresses. Its command buffers go to the game's graphics queue on the render thread,
  each with a fence. Freed memory waits for the next submission to finish.
- **Terrain (`SectionGeometry`).** One bottom-level structure per Sodium section, built once and kept until
  the section changes or unloads:
  - `RenderSectionManagerMixin` copies each pass's vertices out of Sodium's build output before Sodium
    uploads and frees them, and drops sections Sodium unloads.
  - Up to three geometries per section: solid (opaque), cutout and translucent (non-opaque, so shaders can
    alpha-test them later). Each quad is two triangles, (0, 1, 2) and (2, 3, 0), in Sodium's order.
  - Positions are decoded on the CPU into a staging buffer that the build reads and then drops. Sodium's own
    vertices (`CompactChunkVertex`, 20 bytes) are copied to the GPU and kept, for shaders to read a hit's
    texture coordinates, colour and light.
  - At most 96 sections or 2^20 vertices are built a frame, oldest first.
- **Entities (`EntityGeometry`).** Captured from Minecraft's own staged draws between the start of the level
  and the hand, so the hand stays out:
  - draws for the entity shader in the `ENTITY` format, as quads (entities, block entities, items in the
    world), not the blob shadows, glints or emissive layers;
  - their positions are camera-relative, and they are rebuilt into one structure each frame;
  - `EntityRendererMixin` keeps entities within 48 blocks drawn even out of view while the scene is on.
- **Top level.** Instances are placed relative to the camera's block. Each instance's custom index points into
  a table of its vertices' address, kind, stride and its geometries' first vertices and passes.
- **Checked on the RTX 3080 Ti:**
  - `rtview <file>` saves a picture of the scene, traced from the camera and lit by the sun with ray-traced
    shadows. Against the game's own frame, the terrain lines up, and the pig, cow and boat land on the same
    pixels.
  - Sections unload after a 1,500-block teleport (about 900 kept at render distance 8, 115 to 170 MB).
  - The scene costs 0.5 ms of CPU a frame standing still and 0.8 ms flying at 20 blocks a second; frame time
    is unchanged within noise (4.8 ms either way).

**Pitfall met:** LWJGL's `memAddress(buffer)` already includes the buffer's position. Adding the position again
wrote past a buffer and crashed the JVM (an access violation in `jbyte_disjoint_arraycopy`).

**Still to do for the scene:**
- compact the section structures (about half the memory);
- alpha-test cutout geometry against the block atlas in the shaders;
- include particles;
- add Distant Horizons' terrain past the render distance.

**Phase 3, ray-traced sun and moon shadows (done, 2026-11-18).** `raytrace/RtShadows`, chosen with
`/bv shadows maps|traced|compare` (script: `rtshadows off|on|compare`):
- **Where it runs.** renderpearl records a whole frame into its command buffers and submits it once at the end.
  So the trace is recorded into that frame: `VulkanCommandEncoder.execute` takes a command buffer of ours,
  between the G-buffer and the lighting pass.
  - renderpearl keeps every image in the general layout and puts full memory barriers between its passes, so
    the compute shader samples the G-buffer's depth and normals directly.
  - renderpearl textures can't be storage images (no storage usage). The result is written to a buffer and
    copied into a renderpearl texture, `RtShadowMap`.
  - The scene is built just before the trace, once each render of the level. Minecraft has prepared the
    level's entities by then, so the shadows use this frame's entities.
- **Freeing memory.** Our resources are now also used inside renderpearl's submissions. Freed memory, and each
  trace's command buffer, descriptor set and output, therefore wait for renderpearl's timeline semaphore to
  pass the submission that was being recorded (`VulkanCommandEncoderAccessor`). The offline preset traces
  eight times in one renderpearl submission, each trace with its own resources.
- **The test.** It matches the lighting pass's own shadow test:
  - the pixel's world position comes from the same inverse view-projection;
  - axis-aligned faces are snapped to their 1/16 texel's centre;
  - crossed plants (no axis-aligned normal) are turned towards the light, and their rays ignore hits in their
    own block, where the plant's other quad stands;
  - solid and cutout terrain cast shadows, with cutout alpha-tested against the block atlas using Sodium's own
    cutoff (the material bits of the hit quad's vertex);
  - translucent terrain casts no shadow, as in the maps;
  - entities cast shadows;
  - each ray aims at a random point of the light's disc (0.27 degrees by default), new each frame and pixel.
- **In the lighting pass** (`deferred.fsh`), the traced visibility replaces the cascades' test and their edge
  fade, for Java terrain only. The sky-light guards against cave light leaks still apply. So does the far
  terrain's height-map shadow past the loaded chunks.
- **Checked on the RTX 3080 Ti, test scene, morning sun:**
  - On surfaces the light really reaches (N·L at least 0.1), the comparison view's two verdicts, guards
    included, disagree on 4.1% of pixels: single grass-blade pixels and the edges of tree shadows.
  - Lower N·L was compared too, and the disagreements there had the same final brightness either way, since
    such faces get almost no light.
  - The traced frame and the shadow-map frame differ by more than 10 grey levels on 4.7% of pixels.
  - Cost: 0.12 ms of GPU a frame at 854x480 (3.72 against 3.59 ms average).
  - Eight sub-frames a frame (the offline preset) with a 1.5-degree light ran cleanly.

**Pitfall met:** `reload` (resource packs) reads shaders from `build/resources/main`, not from `src`. After
editing a shader, run `gradlew.bat processResources` before reloading, or the change is silently missing.

**Still to do for the shadows:**
- the hand and the item in it, which the scene leaves out, cast no ray-traced shadow;
- Distant Horizons' terrain is still shaded by its height-map march, not by rays;
- the offline preset doesn't switch the traced shadows on by itself yet.

**Phase 4, emissive texels as lights (done, 2026-11-18).** `raytrace/EmissiveLights` and `RtEmissive`, chosen
with `/bv light off|replace|floor` (script: `rtemissive`, `rtgain`, `rtfloor`, `rtsamples`, `rtexportsamples`;
`rtlights <file>` dumps the lights):
- **The lights.** When a section's structure is built, each quad whose texels have emissive in Bedrock's
  material maps becomes an area light, a parallelogram in position and texture coordinates. The material atlas
  now keeps its level-0 emissive channel on the CPU for this.
  - A block that gives off Java light but barely glows in Bedrock's maps (mean emissive under 0.1: lava,
    glowstone, shroomlight) glows by its light level / 15 instead. The block is found just behind the quad,
    and only in sections whose palette has a light-giving block.
  - Fluids draw their surfaces from both sides; those duplicates are dropped.
- **The grid.** Around the camera, 24 x 12 x 24 cells of 4 blocks list the 48 lights that light each cell
  most (power over squared distance to its nearest point). Each light reaches as far as its power allows
  (estimated irradiance 0.004, at most 32 blocks). The grid is world-aligned and rebuilt only when the camera
  changes cell or the lights change; that takes 2 to 10 ms of CPU near the test scene's 263 lights.
  - Each cell keeps its lights' centres and powers next to each other. Weighing them per pixel used to read
    whole light records, which cost about 3 ms; now it's about 0.5 ms.
  - At first a cell kept only 24 lights. That was too few for a 3x3 lava pool drawn from both sides: cells
    dropped some of its faces, and their borders showed as sharp-edged dark rectangles.
- **The trace.** For each pixel, at its texel's centre:
  - one of its cell's lights is picked in proportion to its unshadowed estimate;
  - a uniform point of that light is chosen;
  - the texel's glow there is the block atlas colour times the emissive times 2, as the lighting pass draws
    emissive surfaces;
  - a shadow ray goes to it (cutout alpha-tested, translucent passing light);
  - the light that arrives over pi goes to an RGBA16F texture.
  - On a stand-in light's own surface (lava), alpha is 1, and the lighting keeps Bedrock's block light there:
    lava's look came from block light, not from its texels.
- **In the lighting pass:** `replace` puts the traced light in place of Bedrock's block light term. `floor`
  takes the larger of the traced light and 0.35 of Bedrock's block light.
- **Calibration** (test scene at night, exposure locked with the new `exposure lock` script line, 8 samples):
  - gain 12 matched Bedrock's brightness a block from glowstone, and 10 is the default;
  - big glowing faces (sea lantern, shroomlight) come out brighter than Bedrock's block light;
  - small ones (a torch) come out dimmer, since the light falls off as distance squared, not Minecraft's
    linear levels.
- **Samples:** 1 a frame while playing (noisy; TAA averages it), 4 a sub-frame in exports (32 over the 8
  sub-frames; some grain remains).

**The texel cache (2026-11-18): the grain fixed.** At first every pixel traced its own random light and point
and kept only its own samples: 1 a frame while playing, 32 over an export's sub-frames. That left visible
grain. Now the light is cached per 1/16-block texel of the world, which was the design's per-texel lighting
from the start (`raytrace/TexelCache`):
- **Accumulate.** Every pixel adds its samples to its texel's entry, so a texel gathers its pixels' samples
  across frames instead of each pixel keeping its own. The entries live in a GPU hash table of 2^21 entries,
  48 bytes each, about 100 MB. Samples are summed as 1/1024 fixed point with atomic adds.
- **Blend.** A pass over the entries blends each frame's samples into a running history, capped at 64 samples
  while playing (it still follows changes) and 1024 in exports. Entries unseen for 600 frames are freed.
- **Gather.** Each pixel reads its texel's history.
- **Keys.** A texel is its world texel coordinates and its face's direction. A crossed plant is its 1/16
  cell, since it has no axis-aligned face. Only the G-buffer's terrain is cached, so texels never move.
- **Result.** The test room comes out smooth while playing (after a few seconds), while flying through it,
  and in exports.

**Pitfall met:** GLSL's `%` is also Java's format marker. `String.formatted` on shader source threw at class
load and crashed the game, so shader constants go in with `replace`.

**Still to do for the emissive light:**
- emissive entities and items, and moving lights (entity light sources);
- the forward-lit entities, water and volumetric fog still use Bedrock's block light;
- glossy reflection of the lights;
- in exports the grid is rebuilt every frame the camera changes cell (up to about 10 ms of CPU near many
  lava faces).

**Next: phase 5.** Bounce light from the sun and moon (and the emissive light) off lit surfaces.
