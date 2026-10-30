package dev.bedrockvisuals.compat;

import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.config.EDhApiDepthDirection;
import com.seibel.distanthorizons.api.enums.config.EDhApiDepthRange;
import com.seibel.distanthorizons.api.enums.config.EDhApiRenderingEngine;
import com.seibel.distanthorizons.api.interfaces.render.IDhApiBlazeTextureWrapper;
import com.seibel.distanthorizons.api.interfaces.render.IDhApiRenderProxy;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiAfterDhInitEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeApplyShaderRenderEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeFogRenderEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiCancelableEventParam;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
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

	private DistantHorizonsAdapter() {
	}

	/** Call only when DH is loaded; this class links against DH's API. */
	public static void register() {
		DistantHorizonsAdapter adapter = new DistantHorizonsAdapter();
		DhApi.events.bind(DhApiAfterDhInitEvent.class, new DhApiAfterDhInitEvent() {
			@Override
			public void afterDistantHorizonsInit(DhApiEventParam<Void> event) {
				adapter.onDhInit();
			}
		});
		DhApi.events.bind(DhApiBeforeApplyShaderRenderEvent.class, new DhApiBeforeApplyShaderRenderEvent() {
			@Override
			public void beforeRender(DhApiCancelableEventParam<DhApiRenderParam> event) {
				adapter.captureFrame(event.value);
				if (adapter.rendererOwnsComposite) {
					event.cancelEvent();
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
		rendererOwnsComposite = owns;
	}

	private void onDhInit() {
		IDhApiRenderProxy proxy = DhApi.Delayed.renderProxy;
		EDhApiRenderingEngine engine = proxy.getRenderingEngine();
		BedrockVisualsClient.LOGGER.info("[spike 1b] {} initialized: engine={} api={}", name(), engine, proxy.getRenderingApi());
		if (engine != EDhApiRenderingEngine.BLAZE_3D) {
			BedrockVisualsClient.LOGGER.warn("[spike 1b] DH is not using its Blaze3D engine; distant terrain won't composite on Vulkan");
		}
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
				proxy.getDepthRange() == EDhApiDepthRange.ZERO_TO_POS_ONE);

		if (!reportedFrame) {
			reportedFrame = true;
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
}
