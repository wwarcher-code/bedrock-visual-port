package dev.bedrockvisuals.raytrace;

import com.mojang.blaze3d.platform.NativeImage;
import dev.bedrockvisuals.mixin.minecraft.SpriteContentsAccessor;
import dev.bedrockvisuals.render.MaterialAtlas;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The colour a block's light has when an entity carries it (EntityLights): the mean colour of its glowing texels,
 * each weighed by how much it glows in Bedrock's material maps (MERS green, MaterialAtlas), over every texture its
 * model uses. The same texels glow in the world (EmissiveLights), so a held torch lights in its placed flame's
 * colour. A block whose texels barely glow there (lava) weighs all its texels alike, as its whole surface stands
 * in for its glow. Linear, its largest channel 1; null without the model or the material maps.
 */
public final class EmitterColors {
	/** Below this summed emissive (in texels at full glow) a block's texels are taken as glowing alike. */
	private static final float MIN_GLOW = 0.5f;
	private static final Map<Block, float[]> CACHE = new HashMap<>();
	/** Per block (any thread): whether it glows all over (glowsAllOver). */
	private static final Map<Block, Boolean> ALL_OVER = new java.util.concurrent.ConcurrentHashMap<>();
	private static volatile int allOverGeneration = -1;
	/** Below this mean emissive over its textures, a block that gives off Java light glows all over (lava). */
	private static final float WEAK_EMISSIVE = 0.1f;
	private static final float[] NONE = new float[0];
	private static int generation = -1;

	private EmitterColors() {
	}

	static float[] of(Block block) {
		if (generation != MaterialAtlas.generation()) {
			generation = MaterialAtlas.generation();
			CACHE.clear();
		}
		float[] color = CACHE.computeIfAbsent(block, EmitterColors::compute);
		return color.length == 0 ? null : color;
	}

	/**
	 * Whether a block gives off Java light while its textures barely glow in Bedrock's maps (lava): its whole
	 * surface then stands in for its glow, every texel glowing at its light level, as the traced light takes it
	 * to (EmissiveLights). Blocks with glowing texels of their own (torches, lanterns, glowstone) glow by those.
	 * Any thread (the light volume's scan, LightVolume).
	 */
	public static boolean glowsAllOver(BlockState state) {
		if (state.getLightEmission() == 0 || MaterialAtlas.emissiveWidth() == 0) {
			return false;
		}
		if (allOverGeneration != MaterialAtlas.generation()) {
			allOverGeneration = MaterialAtlas.generation();
			ALL_OVER.clear();
		}
		return ALL_OVER.computeIfAbsent(state.getBlock(), block -> {
			try {
				double[] glowing = new double[4];
				double[] all = new double[4];
				for (TextureAtlasSprite sprite : sprites(block)) {
					add(sprite, glowing, all);
				}
				return all[3] > 0 && glowing[3] / all[3] < WEAK_EMISSIVE;
			} catch (RuntimeException e) {
				return false;
			}
		});
	}

	/** Every texture a block's default model uses; a fluid's particle texture, having no model. */
	private static Set<TextureAtlasSprite> sprites(Block block) {
		BlockState state = block.defaultBlockState();
		var models = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
		Set<TextureAtlasSprite> sprites = new LinkedHashSet<>();
		List<BlockStateModelPart> parts = new ArrayList<>();
		models.get(state).collectParts(RandomSource.create(42), parts);
		for (BlockStateModelPart part : parts) {
			for (Direction direction : Direction.values()) {
				for (BakedQuad quad : part.getQuads(direction)) {
					sprites.add(quad.materialInfo().sprite());
				}
			}
			for (BakedQuad quad : part.getQuads(null)) {
				sprites.add(quad.materialInfo().sprite());
			}
		}
		if (sprites.isEmpty()) {
			sprites.add(models.getParticleMaterial(state).sprite());
		}
		return sprites;
	}

	private static float[] compute(Block block) {
		int width = MaterialAtlas.emissiveWidth();
		if (width == 0) {
			return NONE;
		}
		try {
			Set<TextureAtlasSprite> sprites = sprites(block);
			double[] glowing = new double[4];
			double[] all = new double[4];
			for (TextureAtlasSprite sprite : sprites) {
				add(sprite, glowing, all);
			}
			double[] sum = glowing[3] >= MIN_GLOW ? glowing : all;
			if (sum[3] <= 0) {
				return NONE;
			}
			float r = (float) (sum[0] / sum[3]);
			float g = (float) (sum[1] / sum[3]);
			float b = (float) (sum[2] / sum[3]);
			float max = Math.max(Math.max(r, g), Math.max(b, 1e-4f));
			dev.bedrockvisuals.BedrockVisualsClient.LOGGER.info("[raytrace] {} carried light colour {} {} {} ({} {} texels)",
					net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block), r / max, g / max, b / max,
					sum == glowing ? "glowing" : "all", Math.round(sum[3]));
			return new float[] {r / max, g / max, b / max};
		} catch (RuntimeException e) {
			return NONE;
		}
	}

	/** A sprite's first frame: its texels' linear colours, summed by glow (and plainly), and the weights (w). */
	private static void add(TextureAtlasSprite sprite, double[] glowing, double[] all) {
		NativeImage image = ((SpriteContentsAccessor) sprite.contents()).bedrockvisuals$originalImage();
		if (image == null) {
			return;
		}
		int w = sprite.contents().width();
		int h = sprite.contents().height();
		int atlasX = Math.round(sprite.getU0() * MaterialAtlas.emissiveWidth());
		int atlasY = Math.round(sprite.getV0() * MaterialAtlas.emissiveHeight());
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int argb = image.getPixel(x, y);
				if ((argb >>> 24) < 128) {
					continue;
				}
				double r = Math.pow((argb >> 16 & 0xFF) / 255.0, 2.2);
				double g = Math.pow((argb >> 8 & 0xFF) / 255.0, 2.2);
				double b = Math.pow((argb & 0xFF) / 255.0, 2.2);
				float glow = MaterialAtlas.emissiveAt(atlasX + x, atlasY + y);
				glowing[0] += r * glow;
				glowing[1] += g * glow;
				glowing[2] += b * glow;
				glowing[3] += glow;
				all[0] += r;
				all[1] += g;
				all[2] += b;
				all[3] += 1;
			}
		}
	}
}
