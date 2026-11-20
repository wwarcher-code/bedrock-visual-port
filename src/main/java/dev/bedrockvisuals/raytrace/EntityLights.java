package dev.bedrockvisuals.raytrace;

import dev.bedrockvisuals.render.BlockLightColors;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.entity.monster.breeze.Breeze;
import net.minecraft.world.entity.monster.creaking.Creaking;
import net.minecraft.world.entity.monster.spider.Spider;
import net.minecraft.world.entity.animal.squid.GlowSquid;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.cubemob.MagmaCube;
import net.minecraft.world.entity.projectile.arrow.SpectralArrow;
import net.minecraft.world.entity.projectile.hurtingprojectile.DragonFireball;
import net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball;
import net.minecraft.world.entity.projectile.hurtingprojectile.SmallFireball;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Light that moves: entities carrying or giving off light (LambDynamicLights' idea), traced like the emissive
 * texels but kept out of the texel cache's averages, which would smear a light that moves.
 *
 * <p>Each frame, the entities near the camera that give off light: a glowing block or a lava bucket held in
 * either hand (at the hand) or lying on the ground, a falling glowing block, anything on fire, blazes, magma
 * cubes, glow squids, allays, fireballs, spectral arrows and experience orbs. Each is a small sphere of light,
 * its strength its light level (as the block would give off placed) and its colour the mean of the block's
 * glowing texels (EmitterColors; fire's for burning things and blazes, lava's for a lava bucket and magma cubes),
 * or a fitting one for the entities that aren't blocks. Mobs whose eyes glow (their eyes texture drawn
 * full bright: spiders, endermen, phantoms, the ender dragon, breezes, creakings while theirs glow, copper golems)
 * give off a little light from their eyes, coloured as the mean of that texture's drawn pixels. The camera's own entity counts in first person too: the light in
 * the player's hand comes from where the hand is drawn.
 *
 * <p>The ray-traced light (RtEmissive) takes their light, unshadowed, straight from these positions every frame,
 * and only how much of it each texel sees (the share of shadow rays reaching them) from the cache, averaged over a
 * few frames: a moving torch's light moves with it at once, its shadows a frame or two behind.
 */
public final class EntityLights {
	/** The most lights a frame (the nearest and brightest). */
	static final int MAX = 32;
	/** Two vec4s a light: position (relative to the scene's origin) and radius; colour times strength, reach. */
	static final int BYTES = 32;
	/** How far from the camera entities light the world (blocks). */
	private static final double RANGE = 48;
	/**
	 * A light level 15 source's intensity, in the ray-traced light's units before its gain (TUNE: set so a held
	 * torch lights the floor about as a placed one does).
	 */
	static final float INTENSITY = 0.08f;
	/** Below this (intensity over distance squared, before the gain) a light's reach ends. */
	private static final float CUTOFF = 0.0004f;
	private static final float[] WARM = {1.0f, 0.78f, 0.52f};
	private static final float[] FIRE = {1.0f, 0.62f, 0.3f};
	private static final float[] LAVA = {1.0f, 0.45f, 0.15f};

	record Light(double x, double y, double z, float radius, float level, float[] color) {
	}

	private static final List<Light> FOUND = new ArrayList<>();
	/** Eye textures' colours (eyeColor), until the resources reload. */
	private static final java.util.Map<String, float[]> EYE_COLORS = new java.util.HashMap<>();
	private static int eyeGeneration = -1;
	private static int lastCount;

	private EntityLights() {
	}

	/** This frame's lights, nearest and brightest first, at most {@link #MAX}. */
	static List<Light> collect(double cameraX, double cameraY, double cameraZ) {
		FOUND.clear();
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null) {
			lastCount = 0;
			return FOUND;
		}
		float partial = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		Entity cameraEntity = minecraft.getCameraEntity();
		boolean firstPerson = minecraft.options.getCameraType() == CameraType.FIRST_PERSON;
		for (Entity entity : minecraft.level.entitiesForRendering()) {
			Vec3 at = entity.getPosition(partial);
			double dx = at.x - cameraX;
			double dy = at.y - cameraY;
			double dz = at.z - cameraZ;
			if (dx * dx + dy * dy + dz * dz > RANGE * RANGE) {
				continue;
			}
			add(entity, at, partial, entity == cameraEntity && firstPerson, cameraX, cameraY, cameraZ);
		}
		FOUND.sort(Comparator.comparingDouble(light -> {
			double x = light.x() - cameraX;
			double y = light.y() - cameraY;
			double z = light.z() - cameraZ;
			return -light.level() / (x * x + y * y + z * z + 1);
		}));
		while (FOUND.size() > MAX) {
			FOUND.removeLast();
		}
		lastCount = FOUND.size();
		return FOUND;
	}

	private static void add(Entity entity, Vec3 at, float partial, boolean firstPerson, double cameraX,
			double cameraY, double cameraZ) {
		double centreY = at.y + entity.getBbHeight() * 0.5;
		float body = Math.max(entity.getBbWidth() * 0.5f, 0.1f);
		if (entity.displayFireAnimation()) {
			light(at.x, centreY, at.z, body, 15, colorOf(Blocks.FIRE, FIRE));
		}
		switch (entity) {
			case Blaze blaze -> light(at.x, centreY, at.z, 0.4f, 12, colorOf(Blocks.FIRE, FIRE));
			case MagmaCube cube -> light(at.x, centreY, at.z, body, 8, colorOf(Blocks.MAGMA_BLOCK, LAVA));
			case GlowSquid squid -> {
				// Dark for a while after it is hurt, as its texture is.
				if (squid.getDarkTicksRemaining() <= 0) {
					light(at.x, centreY, at.z, body, 8, new float[] {0.35f, 1.0f, 0.9f});
				}
			}
			case Allay allay -> light(at.x, centreY, at.z, 0.2f, 6, new float[] {0.55f, 0.85f, 1.0f});
			case SmallFireball fireball -> light(at.x, centreY, at.z, 0.15f, 12, colorOf(Blocks.FIRE, FIRE));
			case LargeFireball fireball -> light(at.x, centreY, at.z, 0.4f, 15, colorOf(Blocks.FIRE, FIRE));
			case DragonFireball fireball -> light(at.x, centreY, at.z, 0.4f, 15, new float[] {0.85f, 0.35f, 1.0f});
			case SpectralArrow arrow -> light(at.x, centreY, at.z, 0.1f, 8, new float[] {1.0f, 0.9f, 0.45f});
			case ExperienceOrb orb -> light(at.x, centreY, at.z, 0.1f, 6, new float[] {0.7f, 1.0f, 0.35f});
			// Glowing eyes (the eyes layers' textures): a little light, at the eyes. Before LivingEntity, so these
			// mobs' hands aren't looked at (none of them holds a light).
			case Spider spider -> eyes(spider, partial, 1, "spider/spider_eyes");
			case Enderman enderman -> eyes(enderman, partial, 1, "enderman/enderman_eyes");
			case Phantom phantom -> eyes(phantom, partial, 1, "phantom/phantom_eyes");
			case EnderDragon dragon -> {
				Vec3 head = dragon.head.getPosition(partial);
				light(head.x, head.y + dragon.head.getBbHeight() * 0.5, head.z, 0.3f, 8,
						eyeColor("enderdragon/dragon_eyes", new float[] {0.8f, 0.3f, 1.0f}));
			}
			case Breeze breeze -> eyes(breeze, partial, 2, "breeze/breeze_eyes");
			case Creaking creaking -> {
				if (creaking.hasGlowingEyes()) {
					eyes(creaking, partial, 2, "creaking/creaking_eyes");
				}
			}
			case CopperGolem golem -> eyes(golem, partial, 1, "copper_golem/copper_golem_eyes");
			case ItemEntity item -> held(item.getItem(), at.x, at.y + 0.25, at.z, 0.15f);
			case FallingBlockEntity falling -> {
				int level = falling.getBlockState().getLightEmission();
				if (level > 0) {
					light(at.x, centreY, at.z, 0.4f, level, colorOf(falling.getBlockState().getBlock(), WARM));
				}
			}
			case ItemFrame frame -> held(frame.getItem(), at.x, at.y, at.z, 0.15f);
			case LivingEntity living -> hands(living, at, partial, firstPerson, cameraX, cameraY, cameraZ);
			default -> {
			}
		}
	}

	/** Light from a mob's glowing eyes: a small light just in front of its eyes. */
	private static void eyes(LivingEntity mob, float partial, int level, String texture) {
		Vec3 eye = mob.getEyePosition(partial);
		Vec3 look = mob.getViewVector(partial);
		double forward = mob.getBbWidth() * 0.5 + 0.05;
		light(eye.x + look.x * forward, eye.y + look.y * forward, eye.z + look.z * forward, 0.08f, level,
				eyeColor(texture, new float[] {1.0f, 0.4f, 0.4f}));
	}

	/**
	 * The mean linear colour of an eyes texture's drawn pixels (textures/entity/&lt;name&gt;.png, weighed by alpha),
	 * its largest channel 1; the fallback without it.
	 */
	private static float[] eyeColor(String name, float[] fallback) {
		if (eyeGeneration != dev.bedrockvisuals.render.MaterialAtlas.generation()) {
			eyeGeneration = dev.bedrockvisuals.render.MaterialAtlas.generation();
			EYE_COLORS.clear();
		}
		return EYE_COLORS.computeIfAbsent(name, key -> {
			var id = net.minecraft.resources.Identifier.withDefaultNamespace("textures/entity/" + key + ".png");
			try (var stream = Minecraft.getInstance().getResourceManager().open(id);
					var image = com.mojang.blaze3d.platform.NativeImage.read(stream)) {
				double r = 0;
				double g = 0;
				double b = 0;
				double w = 0;
				for (int y = 0; y < image.getHeight(); y++) {
					for (int x = 0; x < image.getWidth(); x++) {
						int argb = image.getPixel(x, y);
						double alpha = (argb >>> 24) / 255.0;
						if (alpha <= 0) {
							continue;
						}
						r += Math.pow((argb >> 16 & 0xFF) / 255.0, 2.2) * alpha;
						g += Math.pow((argb >> 8 & 0xFF) / 255.0, 2.2) * alpha;
						b += Math.pow((argb & 0xFF) / 255.0, 2.2) * alpha;
						w += alpha;
					}
				}
				double max = Math.max(Math.max(r, g), Math.max(b, 1e-6));
				if (w <= 0) {
					return fallback;
				}
				float[] color = {(float) (r / max), (float) (g / max), (float) (b / max)};
				dev.bedrockvisuals.BedrockVisualsClient.LOGGER.info("[raytrace] {} eye light colour {} {} {}", key,
						color[0], color[1], color[2]);
				return color;
			} catch (java.io.IOException | RuntimeException e) {
				return fallback;
			}
		});
	}

	/** The light of what a living entity holds, at its hands. */
	private static void hands(LivingEntity living, Vec3 at, float partial, boolean firstPerson, double cameraX,
			double cameraY, double cameraZ) {
		ItemStack main = living.getMainHandItem();
		ItemStack off = living.getOffhandItem();
		if (level(main) == 0 && level(off) == 0) {
			return;
		}
		boolean rightHanded = living.getMainArm() == HumanoidArm.RIGHT;
		if (firstPerson) {
			// Where the first-person hand holds it: in front of the eye, low, to the side.
			Vec3 eye = new Vec3(cameraX, cameraY, cameraZ);
			Vec3 forward = living.getViewVector(partial);
			double yaw = Math.toRadians(living.getYRot(partial));
			double rightX = -Math.cos(yaw);
			double rightZ = -Math.sin(yaw);
			for (int hand = 0; hand < 2; hand++) {
				double side = (hand == 0) == rightHanded ? 1 : -1;
				held(hand == 0 ? main : off, clear(living, eye, cameraX + forward.x * 0.55 + rightX * side * 0.35,
						cameraY + forward.y * 0.55 - 0.3, cameraZ + forward.z * 0.55 + rightZ * side * 0.35), 0.1f);
			}
			return;
		}
		Vec3 eye = living.getEyePosition(partial);
		double bodyYaw = Math.toRadians(net.minecraft.util.Mth.lerp(partial, living.yBodyRotO, living.yBodyRot));
		double rightX = -Math.cos(bodyYaw);
		double rightZ = -Math.sin(bodyYaw);
		double forwardX = -Math.sin(bodyYaw);
		double forwardZ = Math.cos(bodyYaw);
		double scale = living.getBbHeight() / 1.8;
		double handY = at.y + living.getBbHeight() * 0.45;
		for (int hand = 0; hand < 2; hand++) {
			double side = ((hand == 0) == rightHanded ? 1 : -1) * 0.35 * scale;
			held(hand == 0 ? main : off, clear(living, eye, at.x + rightX * side + forwardX * 0.3 * scale, handY,
					at.z + rightZ * side + forwardZ * 0.3 * scale), 0.1f);
		}
	}

	/**
	 * The hand's point, or short of the block between the eye and it: an entity against a wall holds its torch
	 * on its own side of the wall, not inside it.
	 */
	private static Vec3 clear(Entity entity, Vec3 eye, double x, double y, double z) {
		Vec3 hand = new Vec3(x, y, z);
		BlockHitResult hit = entity.level().clip(new ClipContext(eye, hand, ClipContext.Block.COLLIDER,
				ClipContext.Fluid.NONE, entity));
		if (hit.getType() == HitResult.Type.MISS) {
			return hand;
		}
		Vec3 path = hit.getLocation().subtract(eye);
		double length = path.length();
		return length < 1e-4 ? eye : eye.add(path.scale(Math.max(length - 0.15, 0) / length));
	}

	private static void held(ItemStack stack, Vec3 at, float radius) {
		held(stack, at.x, at.y, at.z, radius);
	}

	/** The light level an item gives off held: a glowing block's, or a lava bucket's. */
	private static int level(ItemStack stack) {
		if (stack.isEmpty()) {
			return 0;
		}
		if (stack.getItem() instanceof BlockItem blockItem) {
			return blockItem.getBlock().defaultBlockState().getLightEmission();
		}
		return stack.is(Items.LAVA_BUCKET) ? 15 : 0;
	}

	private static void held(ItemStack stack, double x, double y, double z, float radius) {
		int level = level(stack);
		if (level == 0) {
			return;
		}
		Block block = stack.getItem() instanceof BlockItem blockItem ? blockItem.getBlock() : Blocks.LAVA;
		light(x, y, z, radius, level, colorOf(block, block == Blocks.LAVA ? LAVA : WARM));
	}

	private static void light(double x, double y, double z, float radius, int level, float[] color) {
		if (level <= 0) {
			return;
		}
		FOUND.add(new Light(x, y, z, radius, level / 15f, color));
	}

	/**
	 * A block's light colour: the mean of its glowing texels (EmitterColors), else the packs' light colour for it,
	 * else the fallback.
	 */
	private static float[] colorOf(Block block, float[] fallback) {
		float[] texels = EmitterColors.of(block);
		if (texels != null) {
			return texels;
		}
		float[] declared = BlockLightColors.colorOf(block);
		return declared != null ? declared : fallback;
	}

	/**
	 * Writes the lights for the shaders (relative to the scene's origin): per light, position and radius, then
	 * colour times strength and reach. Returns how many.
	 */
	static int write(List<Light> lights, ByteBuffer out, int at, int originX, int originY, int originZ) {
		int count = Math.min(lights.size(), MAX);
		for (int i = 0; i < count; i++) {
			Light light = lights.get(i);
			float strength = INTENSITY * light.level();
			float brightest = strength * Math.max(light.color()[0], Math.max(light.color()[1], light.color()[2]));
			float reach = (float) Math.min(Math.sqrt(brightest / CUTOFF), 32);
			int o = at + i * BYTES;
			out.putFloat(o, (float) (light.x() - originX)).putFloat(o + 4, (float) (light.y() - originY))
					.putFloat(o + 8, (float) (light.z() - originZ)).putFloat(o + 12, light.radius())
					.putFloat(o + 16, light.color()[0] * strength).putFloat(o + 20, light.color()[1] * strength)
					.putFloat(o + 24, light.color()[2] * strength).putFloat(o + 28, reach);
		}
		return count;
	}

	/**
	 * This frame's lights for the entity shader (RtEntityData, render/LightingPass): per light its position
	 * relative to the camera and radius, then colour times strength and reach, as {@link #write}'s; at most
	 * {@code max}. Returns how many.
	 */
	public static int writeCameraRelative(float[] out, int max, double cameraX, double cameraY, double cameraZ) {
		int count = Math.min(Math.min(FOUND.size(), MAX), max);
		for (int i = 0; i < count; i++) {
			Light light = FOUND.get(i);
			float strength = INTENSITY * light.level();
			float brightest = strength * Math.max(light.color()[0], Math.max(light.color()[1], light.color()[2]));
			int o = i * 8;
			out[o] = (float) (light.x() - cameraX);
			out[o + 1] = (float) (light.y() - cameraY);
			out[o + 2] = (float) (light.z() - cameraZ);
			out[o + 3] = light.radius();
			out[o + 4] = light.color()[0] * strength;
			out[o + 5] = light.color()[1] * strength;
			out[o + 6] = light.color()[2] * strength;
			out[o + 7] = (float) Math.min(Math.sqrt(brightest / CUTOFF), 32);
		}
		return count;
	}

	static String status() {
		return String.format(Locale.ROOT, "%d entity lights", lastCount);
	}
}
