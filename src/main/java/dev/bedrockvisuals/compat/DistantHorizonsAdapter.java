package dev.bedrockvisuals.compat;

import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.config.client.IDhApiFogConfig;
import com.seibel.distanthorizons.api.interfaces.config.client.IDhApiGraphicsConfig;
import com.seibel.distanthorizons.api.enums.config.EDhApiDepthDirection;
import com.seibel.distanthorizons.api.enums.config.EDhApiDepthRange;
import com.seibel.distanthorizons.api.enums.config.EDhApiLodShading;
import com.seibel.distanthorizons.api.enums.config.EDhApiRenderingEngine;
import com.seibel.distanthorizons.api.interfaces.render.IDhApiBlazeTextureWrapper;
import com.seibel.distanthorizons.api.interfaces.render.IDhApiRenderProxy;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeApplyShaderRenderEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeFogRenderEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeRenderPassEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeRenderSetupEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiCancelableEventParam;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.api.objects.DhApiResult;
import com.seibel.distanthorizons.api.objects.math.DhApiMat4f;
import dev.bedrockvisuals.BedrockVisualsClient;
import dev.bedrockvisuals.lod.DistantTerrain;
import dev.bedrockvisuals.lod.DistantTerrainFrame;
import dev.bedrockvisuals.lod.DistantTerrainSource;
import java.util.Optional;
import org.joml.Matrix4f;

/**
 * Distant Horizons as a {@link DistantTerrainSource}, level 1: public API only, no mixins.
 *
 * <p>DH draws its LODs into its own color and depth textures. Just before DH would composite them onto
 * Minecraft's frame, this captures those textures and DH's matrices; once the renderer owns the composite,
 * it also cancels DH's composite and fog. DH's color is already lit by the lightmap and carries no normal
 * or material output, so the frame leaves those empty.
 *
 * <p>Requires DH's Blaze3D rendering engine, the only one that runs on Vulkan.
 */
public final class DistantHorizonsAdapter implements DistantTerrainSource {
	private volatile boolean rendererOwnsComposite;
	private volatile DistantTerrainFrame latest;
	private volatile boolean reportedFrame;
	/*
	 * DH's terrain before its water: DH draws its opaque terrain, then (with its transparency set to complete
	 * and not deferred) its translucent terrain, each after a DhApiBeforeRenderPassEvent, into the same colour
	 * and depth textures. Copying them at the second event keeps the terrain under the water, so the lighting
	 * pass can light the seabed and shade the water surface over it (where the final depth is nearer).
	 */
	private int terrainPasses;
	private boolean opaqueTaken;
	private boolean snapshotFailed;
	private GpuTexture opaqueColor;
	private GpuTexture opaqueDepth;
	private GpuTextureView opaqueColorView;
	private GpuTextureView opaqueDepthView;

	private DistantHorizonsAdapter() {
	}

	/** Call only when DH is loaded; this class links against DH's API. */
	public static void register() {
		DistantHorizonsAdapter adapter = new DistantHorizonsAdapter();
		DhApi.events.bind(DhApiBeforeApplyShaderRenderEvent.class, new DhApiBeforeApplyShaderRenderEvent() {
			@Override
			public void beforeRender(DhApiCancelableEventParam<DhApiRenderParam> event) {
				adapter.captureFrame(event.value);
				if (adapter.rendererOwnsComposite) {
					event.cancelEvent();
				}
			}
		});
		DhApi.events.bind(DhApiBeforeRenderSetupEvent.class, new DhApiBeforeRenderSetupEvent() {
			@Override
			public void beforeSetup(DhApiEventParam<DhApiRenderParam> event) {
				adapter.terrainPasses = 0;
				adapter.opaqueTaken = false;
			}
		});
		DhApi.events.bind(DhApiBeforeRenderPassEvent.class, new DhApiBeforeRenderPassEvent() {
			@Override
			public void beforeRender(DhApiEventParam<DhApiRenderParam> event) {
				adapter.terrainPasses++;
				if (adapter.terrainPasses == 2 && adapter.rendererOwnsComposite) {
					adapter.snapshotOpaque();
				}
			}
		});
		DhApi.events.bind(DhApiBeforeFogRenderEvent.class, new DhApiBeforeFogRenderEvent() {
			@Override
			public void beforeRender(DhApiCancelableEventParam<DhApiBeforeFogRenderEvent.EventParam> event) {
				if (adapter.rendererOwnsComposite) {
					event.cancelEvent();
				}
			}
		});
		DistantTerrain.setSource(adapter);
	}

	@Override
	public String name() {
		return "Distant Horizons " + DhApi.getModVersion();
	}

	@Override
	public Optional<DistantTerrainFrame> latestFrame() {
		return Optional.ofNullable(latest);
	}

	@Override
	public void setRendererOwnsComposite(boolean owns) {
		if (owns && !plainColoursSet) {
			// Once a session, not on every switch: some of these make DH rebuild its LOD data, and test
			// captures switch the lighting off and on many times. DH's own drawing (lighting off) keeps them
			// off too.
			plainColoursSet = true;
			plainColours(true);
		}
		rendererOwnsComposite = owns;
	}

	/**
	 * While this renderer lights DH's terrain, DH's colour stands in for albedo, so the effects DH bakes into
	 * it are switched off for the session (API overrides, leaving its saved settings alone): its per-face
	 * shading, its noise texture and its ambient occlusion. The lighting adds Bedrock's own.
	 */
	private volatile boolean plainColoursSet;

	private static void plainColours(boolean plain) {
		try {
			var graphics = DhApi.Delayed.configs.graphics();
			if (plain) {
				graphics.lodShading().setValue(EDhApiLodShading.DISABLED);
				graphics.noiseTexture().noiseEnabled().setValue(false);
				graphics.ambientOcclusion().enabled().setValue(false);
				// Its water in a pass of its own, so the terrain under it can be copied first (snapshotOpaque).
				graphics.transparency().setValue(
						com.seibel.distanthorizons.api.enums.rendering.EDhApiTransparency.COMPLETE);
			} else {
				graphics.lodShading().clearValue();
				graphics.noiseTexture().noiseEnabled().clearValue();
				graphics.ambientOcclusion().enabled().clearValue();
				graphics.transparency().clearValue();
			}
		} catch (RuntimeException e) {
			BedrockVisualsClient.LOGGER.warn("[distant] couldn't {} Distant Horizons' shading, noise and occlusion",
					plain ? "turn off" : "restore", e);
		}
	}

	/** Copies DH's colour and depth as they are before its translucent pass. */
	private void snapshotOpaque() {
		if (snapshotFailed) {
			return;
		}
		IDhApiRenderProxy proxy = DhApi.Delayed.renderProxy;
		GpuTextureView color = textureView(proxy.getDhColorTextureBlazeWrapper());
		GpuTextureView depth = textureView(proxy.getDhDepthTextureBlazeWrapper());
		if (color == null || depth == null) {
			return;
		}
		try {
			GpuTexture colorTexture = color.texture();
			GpuTexture depthTexture = depth.texture();
			int width = colorTexture.getWidth(0);
			int height = colorTexture.getHeight(0);
			if (opaqueColor == null || opaqueColor.getWidth(0) != width || opaqueColor.getHeight(0) != height
					|| opaqueColor.getFormat() != colorTexture.getFormat()
					|| opaqueDepth.getFormat() != depthTexture.getFormat()) {
				closeSnapshot();
				var device = RenderSystem.getDevice();
				int usage = GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST;
				opaqueColor = device.createTexture(() -> "Bedrock Visuals DH terrain under water", usage,
						colorTexture.getFormat(), width, height, 1, 1);
				opaqueDepth = device.createTexture(() -> "Bedrock Visuals DH depth under water", usage,
						depthTexture.getFormat(), depthTexture.getWidth(0), depthTexture.getHeight(0), 1, 1);
				opaqueColorView = device.createTextureView(opaqueColor);
				opaqueDepthView = device.createTextureView(opaqueDepth);
			}
			var encoder = RenderSystem.getDevice().createCommandEncoder();
			encoder.copyTextureToTexture(colorTexture, opaqueColor, 0, 0, 0, 0, 0, width, height);
			encoder.copyTextureToTexture(depthTexture, opaqueDepth, 0, 0, 0, 0, 0, depthTexture.getWidth(0),
					depthTexture.getHeight(0));
			opaqueTaken = true;
		} catch (RuntimeException e) {
			snapshotFailed = true;
			closeSnapshot();
			BedrockVisualsClient.LOGGER.warn("[distant] could not copy DH's terrain before its water; distant water stays as DH draws it", e);
		}
	}

	private void closeSnapshot() {
		if (opaqueColorView != null) {
			opaqueColorView.close();
			opaqueDepthView.close();
			opaqueColor.close();
			opaqueDepth.close();
		}
		opaqueColor = null;
		opaqueDepth = null;
		opaqueColorView = null;
		opaqueDepthView = null;
	}

	/** Whether DH's water is being shaded here (for diagnostics). */
	@Override
	public String waterStatus() {
		return snapshotFailed ? "failed (see log)" : opaqueTaken ? "shaded here" : "as DH draws it (no translucent pass seen)";
	}

	private void captureFrame(DhApiRenderParam param) {
		IDhApiRenderProxy proxy = DhApi.Delayed.renderProxy;
		GpuTextureView color = textureView(proxy.getDhColorTextureBlazeWrapper());
		GpuTextureView depth = textureView(proxy.getDhDepthTextureBlazeWrapper());
		if (color == null || depth == null) {
			return;
		}

		latest = new DistantTerrainFrame(color, depth, null, null,
				toJoml(param.dhProjectionMatrix), toJoml(param.dhModelViewMatrix),
				param.nearClipPlane, param.farClipPlane,
				proxy.getDepthDirection() == EDhApiDepthDirection.REVERSE_Z,
				proxy.getDepthRange() == EDhApiDepthRange.ZERO_TO_POS_ONE,
				opaqueTaken ? opaqueColorView : null, opaqueTaken ? opaqueDepthView : null);

		if (!reportedFrame) {
			reportedFrame = true;
			// Reported here rather than on DH's init event, which fires before this mod subscribes.
			EDhApiRenderingEngine engine = proxy.getRenderingEngine();
			BedrockVisualsClient.LOGGER.info("[spike 1b] {}: engine={} api={}", name(), engine, proxy.getRenderingApi());
			if (engine != EDhApiRenderingEngine.BLAZE_3D) {
				BedrockVisualsClient.LOGGER.warn("[spike 1b] DH is not using its Blaze3D engine; distant terrain won't composite on Vulkan");
			}
			BedrockVisualsClient.LOGGER.info("[spike 1b] DH frame captured: color {}x{} depth {}x{} near={} far={} reversedZ={} zeroToOne={}",
					color.getWidth(0), color.getHeight(0), depth.getWidth(0), depth.getHeight(0),
					latest.nearClip(), latest.farClip(), latest.reversedZ(), latest.zeroToOneDepth());
		}
	}

	/** DH's Blaze wrappers unwrap to {@code [GpuTexture, GpuTextureView, GpuSampler]}. */
	private static GpuTextureView textureView(DhApiResult<IDhApiBlazeTextureWrapper> result) {
		if (!result.success || result.payload == null) {
			return null;
		}
		Object[] unwrapped = (Object[]) result.payload.getWrappedMcObject();
		return (GpuTextureView) unwrapped[1];
	}

	/** DH matrices are row-major; JOML's array constructor reads column-major. */
	private static Matrix4f toJoml(DhApiMat4f matrix) {
		return new Matrix4f().set(matrix.getValuesAsArray()).transpose();
	}

	/** Follows Distant Horizons' own fog settings: its rendering switch and its vanilla fog toggle. */
	@Override
	public boolean wantsRenderDistanceFog() {
		try {
			IDhApiGraphicsConfig graphics = DhApi.Delayed.configs.graphics();
			if (!Boolean.TRUE.equals(graphics.renderingEnabled().getValue())) {
				return true;
			}
			IDhApiFogConfig fog = graphics.fog();
			Boolean enable = fog.enableVanillaFog().getValue();
			return enable != null ? enable : !Boolean.TRUE.equals(fog.disableVanillaFog().getValue());
		} catch (RuntimeException e) {
			// The config isn't ready (DH still loading): no fog, as with DH drawing.
			return false;
		}
	}

	private volatile boolean cloudsReported;

	/** Uses DH's API override, which sits on top of the player's own setting and isn't saved. */
	@Override
	public int renderDistanceBlocks() {
		try {
			var graphics = DhApi.Delayed.configs.graphics();
			if (!Boolean.TRUE.equals(graphics.renderingEnabled().getValue())) {
				return 0;
			}
			Integer chunks = graphics.chunkRenderDistance().getValue();
			return chunks == null ? 0 : chunks * 16;
		} catch (RuntimeException e) {
			return 0;
		}
	}

	@Override
	public void hideOwnClouds(boolean hide) {
		try {
			var clouds = DhApi.Delayed.configs.graphics().genericRendering().cloudRenderingEnabled();
			if (hide) {
				boolean overridden = clouds.setValue(false);
				if (cloudsReported) {
					return;
				}
				cloudsReported = true;
				BedrockVisualsClient.LOGGER.info("[clouds] Distant Horizons' clouds {}", overridden
						? "hidden for this session"
						: "could not be hidden (its setting doesn't take API overrides): turn off cloud rendering in"
								+ " Distant Horizons' settings to see the Bedrock-lit clouds");
			} else {
				clouds.clearValue();
			}
		} catch (RuntimeException e) {
			BedrockVisualsClient.LOGGER.warn("[clouds] couldn't {} Distant Horizons' clouds", hide ? "hide" : "restore", e);
		}
	}
}
