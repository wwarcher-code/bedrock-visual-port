package dev.bedrockvisuals.render;

import com.mojang.blaze3d.buffers.Std140Builder;
import dev.bedrockvisuals.bedrock.BedrockInputs;
import dev.bedrockvisuals.bedrock.BedrockPack;
import dev.bedrockvisuals.bedrock.ColorGradingSettings;
import dev.bedrockvisuals.bedrock.Vec3;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import org.joml.Vector3f;

/**
 * Bedrock's color grading and tone mapping settings for the player's biome, packed for the GradingData block
 * in deferred.fsh. Following Bedrock's documentation, grading runs on the HDR color, then the tone-mapping
 * operator, then the gamma encodes the result.
 */
public final class ColorGrading {
	/** std140 size of the GradingData block: three grades of four vec4s, then gamma, temperature, bands and operator. */
	public static final int DATA_SIZE = 16 * 16;
	/**
	 * TUNE: Bedrock grades relative to the scene's average luminance, which it measures. Until the renderer
	 * measures it too, middle grey stands in.
	 */
	private static final float AVERAGE_LUMINANCE = 0.18f;
	private static final float NEUTRAL_KELVIN = 6500;

	private ColorGrading() {
	}

	public static ColorGradingSettings current() {
		Optional<BedrockPack> pack = BedrockInputs.pack();
		Minecraft minecraft = Minecraft.getInstance();
		if (pack.isEmpty() || minecraft.level == null || minecraft.player == null) {
			return null;
		}
		String biome = minecraft.level.getBiome(minecraft.player.blockPosition()).unwrapKey()
				.map(key -> key.identifier().toString()).orElse("minecraft:plains");
		return pack.get().colorGrading(pack.get().biome(biome).colorGrading());
	}

	/** The grading of one biome, or null without a pack. */
	static ColorGradingSettings forBiome(String biome) {
		return BedrockInputs.pack().map(pack -> pack.colorGrading(pack.biome(biome).colorGrading())).orElse(null);
	}

	/**
	 * The GradingData block for the biomes in effect (BiomeMix), mixed by their shares term by term; the tone
	 * map's operator (an index, not a quantity) is the largest share's.
	 */
	static java.nio.ByteBuffer mixed(org.lwjgl.system.MemoryStack stack) {
		java.util.List<BiomeMix.Share> shares = BiomeMix.current();
		Std140Builder first = Std140Builder.onStack(stack, DATA_SIZE);
		write(first, forBiome(shares.get(0).biome()));
		java.nio.ByteBuffer result = first.get();
		java.nio.ByteBuffer floats = result.duplicate().order(java.nio.ByteOrder.nativeOrder());
		int start = floats.position();
		int count = DATA_SIZE / 4;
		int operatorIndex = count - 4;
		float[] sum = new float[count];
		for (int i = 0; i < count; i++) {
			sum[i] = floats.getFloat(start + i * 4) * shares.get(0).weight();
		}
		for (int k = 1; k < shares.size(); k++) {
			Std140Builder other = Std140Builder.onStack(stack, DATA_SIZE);
			write(other, forBiome(shares.get(k).biome()));
			java.nio.ByteBuffer data = other.get().duplicate().order(java.nio.ByteOrder.nativeOrder());
			int at = data.position();
			for (int i = 0; i < count; i++) {
				sum[i] += data.getFloat(at + i * 4) * shares.get(k).weight();
			}
		}
		for (int i = 0; i < count; i++) {
			if (i != operatorIndex) {
				floats.putFloat(start + i * 4, sum[i]);
			}
		}
		return result;
	}

	/** Writes the GradingData block; null settings write Bedrock's documented neutral defaults. */
	public static void write(Std140Builder builder, ColorGradingSettings settings) {
		ColorGradingSettings.Grade identity = new ColorGradingSettings.Grade(true, 0, Vec3.of(1), Vec3.of(1),
				Vec3.of(2.2f), Vec3.of(0), Vec3.of(1));
		ColorGradingSettings.Grade mid = settings == null ? identity : settings.midtones();
		ColorGradingSettings.Grade shadows = settings == null ? identity : settings.shadows();
		ColorGradingSettings.Grade highlights = settings == null ? identity : settings.highlights();
		putGrade(builder, mid);
		putGrade(builder, shadows);
		putGrade(builder, highlights);
		putVec3(builder, mid.gamma());
		Vector3f temperature = settings == null || !settings.temperature().enabled() ? new Vector3f(1)
				: temperatureMultiplier(settings.temperature().kelvin(), settings.temperature().inverted());
		builder.putVec4(temperature.x, temperature.y, temperature.z, 0);
		builder.putVec4(shadows.enabled() ? 1 : 0, shadows.threshold(), highlights.enabled() ? 1 : 0,
				highlights.threshold());
		int operator = settings == null ? ColorGradingSettings.ToneMapping.GENERIC.ordinal()
				: settings.toneMapping().ordinal();
		builder.putVec4(operator, AVERAGE_LUMINANCE, 0, 0);
	}

	private static void putGrade(Std140Builder builder, ColorGradingSettings.Grade grade) {
		putVec3(builder, grade.contrast());
		putVec3(builder, grade.gain());
		putVec3(builder, grade.offset());
		putVec3(builder, grade.saturation());
	}

	private static void putVec3(Std140Builder builder, Vec3 value) {
		builder.putVec4(value.x(), value.y(), value.z(), 0);
	}

	/**
	 * Per-channel multiplier for Bedrock's temperature grading, neutral at 6500 K. "white_balance" (inverted)
	 * makes higher values cooler, like tinting by a light of that temperature; "color_temperature" makes
	 * them warmer.
	 */
	static Vector3f temperatureMultiplier(float kelvin, boolean whiteBalance) {
		Vector3f light = blackbody(kelvin);
		Vector3f neutral = blackbody(NEUTRAL_KELVIN);
		return whiteBalance
				? new Vector3f(light.x / neutral.x, light.y / neutral.y, light.z / neutral.z)
				: new Vector3f(neutral.x / light.x, neutral.y / light.y, neutral.z / light.z);
	}

	/** Approximate color of a black body at this temperature (Tanner Helland's fit), each channel 0 to 1. */
	static Vector3f blackbody(float kelvin) {
		double t = Math.clamp(kelvin, 1000, 40000) / 100.0;
		double r = t <= 66 ? 255 : 329.698727446 * Math.pow(t - 60, -0.1332047592);
		double g = t <= 66 ? 99.4708025861 * Math.log(t) - 161.1195681661 : 288.1221695283 * Math.pow(t - 60, -0.0755148492);
		double b = t >= 66 ? 255 : t <= 19 ? 0 : 138.5177312231 * Math.log(t - 10) - 305.0447927307;
		return new Vector3f((float) Math.clamp(r, 1, 255) / 255f, (float) Math.clamp(g, 1, 255) / 255f,
				(float) Math.clamp(b, 1, 255) / 255f);
	}
}
