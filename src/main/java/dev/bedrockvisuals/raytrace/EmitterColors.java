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
	 * Whether a block is a liquid that gives off Java light while its textures don't glow in Bedrock's maps
	 * (lava): its whole surface then stands in for its glow, every texel glowing at its light level, as the traced
	 * light takes it to (EmissiveLights). Blocks with glowing texels of their own (torches, lanterns, glowstone)
	 * glow by those. A test of the mean glow over a block's textures took in nearly every light (a torch's flame
	 * or glowstone's bright flecks are a small share of their texels), and they glowed all over, sticks and
	 * frames included; the solid blocks with no glowing texels at all (enchanting tables, candles, amethyst,
	 * lit redstone ore) don't look lit up in Java or Bedrock either. Any thread (the light volume's scan,
	 * LightVolume).
	 */
	public static boolean glowsAllOver(BlockState state) {
		if (state.getLightEmission() == 0 || !(state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock)
				|| MaterialAtlas.emissiveWidth() == 0) {
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
				return all[3] > 0 && glowing[3] < MIN_GLOW;
			} catch (RuntimeException e) {
				return false;
			}
		});
	}

	/**
	 * Logs, for every block state that gives off Java light, its textures' glow in Bedrock's maps: summed (in
	 * texels at full glow), strongest, and over how many texels; and whether it glows all over. (/bv light emitters)
	 */
	public static int report() {
		int states = 0;
		for (Block block : net.minecraft.core.registries.BuiltInRegistries.BLOCK) {
			for (BlockState state : block.getStateDefinition().getPossibleStates()) {
				if (state.getLightEmission() == 0) {
					continue;
				}
				double[] glowing = new double[5];
				double[] all = new double[4];
				for (TextureAtlasSprite sprite : sprites(state)) {
					add(sprite, glowing, all);
				}
				dev.bedrockvisuals.BedrockVisualsClient.LOGGER.info(
						"[emitters] {} light {}: glow sum {} max {} over {} texels; all over {}", state,
						state.getLightEmission(), String.format(java.util.Locale.ROOT, "%.2f", glowing[3]),
						String.format(java.util.Locale.ROOT, "%.2f", glowing[4]), Math.round(all[3]),
						glowsAllOver(state));
				states++;
			}
		}
		return states;
	}

	/** Every texture a block's default model uses; a fluid's particle texture, having no model. */
	private static Set<TextureAtlasSprite> sprites(Block block) {
		return sprites(block.defaultBlockState());
	}

	/** Every texture a block state's model uses; a fluid's particle texture, having no model. */
	private static Set<TextureAtlasSprite> sprites(BlockState state) {
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
				if (glowing.length > 4) {
					glowing[4] = Math.max(glowing[4], glow);
				}
				all[0] += r;
				all[1] += g;
				all[2] += b;
				all[3] += 1;
			}
		}
	}
}
