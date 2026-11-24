package dev.bedrockvisuals.raytrace;

import com.mojang.blaze3d.platform.NativeImage;
import dev.bedrockvisuals.mixin.minecraft.SpriteContentsAccessor;
import dev.bedrockvisuals.render.MaterialAtlas;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3f;

/**
 * How a block that gives off Java light glows as a traced light: which of its texels glow ({@link Mode}) and the
 * scale on their glow that makes the light the whole block gives off match its Java light level.
 *
 * <p>The block's output is its model's glowing texels summed, each face whole (faces another block hides
 * included): luminance of the glow times area. It is scaled to {@link #target}: glowstone's own output at level
 * 15, times (level / 15) squared. Squared, the traced light's reach (where power over distance squared fades out,
 * EmissiveLights) goes as the level, as Java's light reaches as many blocks as its level; and a level 7 block gives
 * about a quarter of a level 14 one, as Java's brightness curve has it too. A face another block hides gives off
 * nothing, so a buried lamp gives less light, each face it shows as bright as ever.
 *
 * <p>A block that gives off no Java light gives no traced light, whatever Bedrock's maps mark.
 */
final class BlockEmission {
	/** Which texels glow, and in what colour (RtEmissive's lightGlow, by the light's mode). */
	enum Mode {
		/** The texels Bedrock marks, each in its own colour times its emissive (torches, glowstone, lanterns). */
		TEXELS,
		/**
		 * Bedrock marks texels only faintly (glow lichen's berries, 5 of 255): the whole quad glows in the
		 * brightest one's colour, even light instead of a few bright specks.
		 */
		MASKED,
		/** Bedrock marks none (lava): every texel glows in its colour (a 4 x 4 texel average, against speckle). */
		ALL_OVER
	}

	/**
	 * A block state's glow: its mode, the scale on its texels' glow, the brightest marked texel (MASKED: its atlas
	 * index plus one), its light level, and its model's quads (atlas rectangle, mean glow luminance) for each
	 * world quad's power.
	 */
	record Emission(Mode mode, float scale, int marked, int level, float[] rects, float meanLuminance) {
		/**
		 * The mean glow luminance (before the scale) over a quad's atlas rectangle: the model quad's whose
		 * rectangle holds its centre, or the block's mean.
		 */
		float luminance(float u0, float v0, float u1, float v1) {
			float u = (u0 + u1) * 0.5f;
			float v = (v0 + v1) * 0.5f;
			for (int i = 0; i < rects.length; i += 5) {
				if (u >= rects[i] && u <= rects[i + 2] && v >= rects[i + 1] && v <= rects[i + 3]) {
					return rects[i + 4];
				}
			}
			return meanLuminance;
		}
	}

	/**
	 * Below this, Bedrock's brightest mark on a block is faint (glow lichen's berries at 5 of 255) and the block
	 * glows MASKED: its few marked texels each glowing alone made every sample of its light a gamble.
	 */
	private static final float FAINT = 0.1f;
	/** A block's output without glowstone to measure (no material maps yet): a cube glowing half on every face. */
	private static final float FALLBACK_TARGET = 3f;

	private static final Map<BlockState, Emission> CACHE = new ConcurrentHashMap<>();
	private static final Emission NONE = new Emission(Mode.TEXELS, 0, 0, 0, new float[0], 0);
	private static volatile int generation = -1;
	private static volatile float fullTarget = -1;

	private BlockEmission() {
	}

	/** The block state's glow, or null if it gives off no light. Any thread. */
	static Emission of(BlockState state) {
		int level = state.getLightEmission();
		if (level <= 0 || MaterialAtlas.emissiveWidth() == 0) {
			return null;
		}
		if (generation != MaterialAtlas.generation()) {
			CACHE.clear();
			fullTarget = -1;
			generation = MaterialAtlas.generation();
		}
		Emission emission = CACHE.computeIfAbsent(state, BlockEmission::compute);
		return emission == NONE ? null : emission;
	}

	/** What a block of this light level gives off in all (see the class comment). */
	private static float target(int level) {
		float full = fullTarget;
		if (full < 0) {
			Measured glowstone = measure(Blocks.GLOWSTONE.defaultBlockState());
			full = glowstone == null || glowstone.output <= 0 ? FALLBACK_TARGET : glowstone.output;
			fullTarget = full;
		}
		float share = level / 15f;
		return full * share * share;
	}

	private static Emission compute(BlockState state) {
		try {
			Measured measured = measure(state);
			if (measured == null || measured.output <= 0) {
				return NONE;
			}
			int level = state.getLightEmission();
			float scale = target(level) / measured.output;
			dev.bedrockvisuals.BedrockVisualsClient.LOGGER.info(
					"[raytrace] {} light {}: glows {}, output {} over {} blocks squared, scale {}", state, level,
					measured.mode, String.format(java.util.Locale.ROOT, "%.4f", measured.output),
					String.format(java.util.Locale.ROOT, "%.3f", measured.area),
					String.format(java.util.Locale.ROOT, "%.3f", scale));
			return new Emission(measured.mode, scale, measured.marked, level, measured.rects,
					measured.output / Math.max(measured.area, 1e-6f));
		} catch (RuntimeException e) {
			dev.bedrockvisuals.BedrockVisualsClient.LOGGER.warn("[raytrace] no glow worked out for {}", state, e);
			return NONE;
		}
	}

	/** A block's glow before scaling: its output (glow luminance x area), mode, and its quads. */
	private record Measured(Mode mode, float output, float area, int marked, float[] rects) {
	}

	/** One quad of a model: its sprite, its atlas rectangle and its area (blocks squared). */
	private record Face(TextureAtlasSprite sprite, float u0, float v0, float u1, float v1, float area) {
	}

	private static Measured measure(BlockState state) {
		List<Face> faces = faces(state);
		if (faces.isEmpty()) {
			return null;
		}
		// The mode from the brightest mark anywhere on the model.
		float brightest = 0;
		int marked = 0;
		for (Face face : faces) {
			Texels texels = texels(face);
			if (texels.brightest > brightest) {
				brightest = texels.brightest;
				marked = texels.brightestIndex;
			}
		}
		Mode mode = brightest >= FAINT ? Mode.TEXELS : brightest > 0 ? Mode.MASKED : Mode.ALL_OVER;
		float markedLuminance = mode == Mode.MASKED ? markedLuminance(faces, marked) : 0;
		float output = 0;
		float area = 0;
		float[] rects = new float[faces.size() * 5];
		for (int i = 0; i < faces.size(); i++) {
			Face face = faces.get(i);
			Texels texels = texels(face);
			float luminance = switch (mode) {
				case TEXELS -> texels.glowing;
				case MASKED -> markedLuminance * texels.opaque;
				case ALL_OVER -> texels.colour;
			};
			output += luminance * face.area;
			area += face.area;
			rects[i * 5] = face.u0;
			rects[i * 5 + 1] = face.v0;
			rects[i * 5 + 2] = face.u1;
			rects[i * 5 + 3] = face.v1;
			rects[i * 5 + 4] = luminance;
		}
		return new Measured(mode, output, area, marked, rects);
	}

	/**
	 * A block state's model quads, every face (those another block would hide too). A fluid, with no model: a cube
	 * of its particle texture.
	 */
	private static List<Face> faces(BlockState state) {
		var models = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
		List<Face> faces = new ArrayList<>();
		if (!(state.getBlock() instanceof LiquidBlock)) {
			List<BlockStateModelPart> parts = new ArrayList<>();
			models.get(state).collectParts(RandomSource.create(42), parts);
			for (BlockStateModelPart part : parts) {
				for (Direction direction : Direction.values()) {
					for (BakedQuad quad : part.getQuads(direction)) {
						faces.add(face(quad));
					}
				}
				for (BakedQuad quad : part.getQuads(null)) {
					faces.add(face(quad));
				}
			}
		}
		if (faces.isEmpty() && state.getBlock() instanceof LiquidBlock) {
			TextureAtlasSprite sprite = models.getParticleMaterial(state).sprite();
			for (int i = 0; i < 6; i++) {
				faces.add(new Face(sprite, sprite.getU0(), sprite.getV0(), sprite.getU1(), sprite.getV1(), 1));
			}
		}
		return faces;
	}

	private static Face face(BakedQuad quad) {
		float u0 = Float.MAX_VALUE;
		float v0 = Float.MAX_VALUE;
		float u1 = -Float.MAX_VALUE;
		float v1 = -Float.MAX_VALUE;
		for (int i = 0; i < 4; i++) {
			long packed = quad.packedUV(i);
			u0 = Math.min(u0, UVPair.unpackU(packed));
			v0 = Math.min(v0, UVPair.unpackV(packed));
			u1 = Math.max(u1, UVPair.unpackU(packed));
			v1 = Math.max(v1, UVPair.unpackV(packed));
		}
		Vector3f e1 = new Vector3f(quad.position1()).sub(quad.position0());
		Vector3f e2 = new Vector3f(quad.position3()).sub(quad.position0());
		float area = e1.cross(e2).length();
		return new Face(quad.materialInfo().sprite(), u0, v0, u1, v1, area);
	}

	/**
	 * A quad's texels, means over all of them (a see-through texel glows nothing): glow luminance (linear colour
	 * times emissive), colour luminance, the share that is opaque; and its brightest marked texel.
	 */
	private record Texels(float glowing, float colour, float opaque, float brightest, int brightestIndex) {
	}

	private static Texels texels(Face face) {
		TextureAtlasSprite sprite = face.sprite;
		NativeImage image = ((SpriteContentsAccessor) sprite.contents()).bedrockvisuals$originalImage();
		if (image == null) {
			return new Texels(0, 0, 0, 0, 0);
		}
		int w = sprite.contents().width();
		int h = sprite.contents().height();
		float du = sprite.getU1() - sprite.getU0();
		float dv = sprite.getV1() - sprite.getV0();
		int x0 = Math.clamp((int) Math.floor((face.u0 - sprite.getU0()) / du * w + 0.01), 0, w - 1);
		int x1 = Math.clamp((int) Math.ceil((face.u1 - sprite.getU0()) / du * w - 0.01), x0 + 1, w);
		int y0 = Math.clamp((int) Math.floor((face.v0 - sprite.getV0()) / dv * h + 0.01), 0, h - 1);
		int y1 = Math.clamp((int) Math.ceil((face.v1 - sprite.getV0()) / dv * h - 0.01), y0 + 1, h);
		int atlasX = Math.round(sprite.getU0() * MaterialAtlas.emissiveWidth());
		int atlasY = Math.round(sprite.getV0() * MaterialAtlas.emissiveHeight());
		double glowing = 0;
		double colour = 0;
		int opaque = 0;
		float brightest = 0;
		int brightestIndex = 0;
		for (int y = y0; y < y1; y++) {
			for (int x = x0; x < x1; x++) {
				int argb = image.getPixel(x, y);
				if ((argb >>> 24) < 128) {
					continue;
				}
				opaque++;
				double luminance = luminance(argb);
				float glow = MaterialAtlas.emissiveAt(atlasX + x, atlasY + y);
				glowing += luminance * glow;
				colour += luminance;
				if (glow > brightest) {
					brightest = glow;
					brightestIndex = (atlasY + y) * MaterialAtlas.emissiveWidth() + atlasX + x + 1;
				}
			}
		}
		int count = (x1 - x0) * (y1 - y0);
		return new Texels((float) (glowing / count), (float) (colour / count), opaque / (float) count, brightest,
				brightestIndex);
	}

	/** The luminance of the marked texel (atlas index plus one) among the faces' sprites. */
	private static float markedLuminance(List<Face> faces, int marked) {
		int index = marked - 1;
		int ax = index % MaterialAtlas.emissiveWidth();
		int ay = index / MaterialAtlas.emissiveWidth();
		for (Face face : faces) {
			TextureAtlasSprite sprite = face.sprite;
			int atlasX = Math.round(sprite.getU0() * MaterialAtlas.emissiveWidth());
			int atlasY = Math.round(sprite.getV0() * MaterialAtlas.emissiveHeight());
			int x = ax - atlasX;
			int y = ay - atlasY;
			if (x >= 0 && y >= 0 && x < sprite.contents().width() && y < sprite.contents().height()) {
				NativeImage image = ((SpriteContentsAccessor) sprite.contents()).bedrockvisuals$originalImage();
				return image == null ? 0 : (float) luminance(image.getPixel(x, y));
			}
		}
		return 0;
	}

	/** A texel's linear luminance (Rec. 709). */
	private static double luminance(int argb) {
		double r = Math.pow((argb >> 16 & 0xFF) / 255.0, 2.2);
		double g = Math.pow((argb >> 8 & 0xFF) / 255.0, 2.2);
		double b = Math.pow((argb & 0xFF) / 255.0, 2.2);
		return 0.2126 * r + 0.7152 * g + 0.0722 * b;
	}
}
