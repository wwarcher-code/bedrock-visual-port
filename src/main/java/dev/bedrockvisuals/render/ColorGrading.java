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
	public static final int DATA_SIZE = 18 * 16;
	/**
	 * The average luminance the grading works about. Bedrock grades before exposing, about the measured average
	 * clamped to [0.015625, 2] (ColorPostProcessing's v45), then exposes by 0.18 / that average (key 1:
	 * ExposureCompensation mode 0 in GPU captures). This renderer exposes first, by the same 0.18 / average
	 * (AutoExposure), so the average sits at 0.18 when it grades: the same result.
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
		boolean temperatureOn = settings != null && settings.temperature().enabled();
		float[] matrix = temperatureOn ? whiteBalance(settings.temperature().kelvin(), !settings.temperature().inverted())
				: new float[] {1, 0, 0, 0, 1, 0, 0, 0, 1};
		builder.putVec4(matrix[0], matrix[1], matrix[2], temperatureOn ? 1 : 0);
		builder.putVec4(matrix[3], matrix[4], matrix[5], 0);
		builder.putVec4(matrix[6], matrix[7], matrix[8], 0);
		builder.putVec4(shadows.enabled() ? 1 : 0, shadows.threshold(), highlights.enabled() ? 1 : 0,
				highlights.threshold());
		int operator = settings == null ? ColorGradingSettings.ToneMapping.GENERIC.ordinal()
				: settings.toneMapping().ordinal();
		builder.putVec4(operator, AVERAGE_LUMINANCE, mid.enabled() ? 1 : 0, 0);
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
	 * Bedrock's white balance matrix (ColorPostProcessing): the temperature's white point on the Planckian locus
	 * (Krystek's CIE 1960 uv fit), to CIE xy and XYZ, into CAT02 cone space, each cone scaled against D65's
	 * (0.949253, 1.035419, 1.087183), and back to linear RGB, all folded into one 3 x 3 matrix as the shader
	 * computes it. A "color_temperature" setting (mode 1 in GPU captures) tints by the white point over D65;
	 * "white_balance" (mode 0) by D65 over it. Rows, row-major.
	 */
	static float[] whiteBalance(float kelvin, boolean colorTemperature) {
		double t = kelvin;
		double u = (0.8601177 + 1.541183e-4 * t + 1.286412e-7 * t * t) / (1 + 8.424202e-4 * t + 7.081451e-7 * t * t);
		double v = (0.3173987 + 4.228063e-5 * t + 4.204817e-8 * t * t) / (1 - 2.897418e-5 * t + 1.614561e-7 * t * t);
		double denominator = 2 * u - 8 * v + 4;
		double x = 3 * u / denominator;
		double y = 2 * v / denominator;
		double bigX = x / y;
		double bigZ = (1 - x - y) / y;
		double l = 0.7328 * bigX + 0.4296 - 0.1624 * bigZ;
		double m = -0.7036 * bigX + 1.6975 + 0.0061 * bigZ;
		double s = 0.0030 * bigX + 0.0136 + 0.9834 * bigZ;
		double gl = colorTemperature ? l * 1.05346 : 0.949253 / l;
		double gm = colorTemperature ? m * 0.9657925 : 1.035419 / m;
		double gs = colorTemperature ? s * 0.919808 : 1.087183 / s;
		double[] mm = {gm * 0.071, gm * 0.963, gm * 0.001};
		double[] ms = {gs * 0.023, gs * 0.128, gs * 0.936};
		double[] r0 = {gl * 1.11501, gl * 1.57245, gl * 0.025731};
		double[] r1 = {gl * -0.08189999, gl * -0.1155, gl * -0.00189};
		double[] r2 = {gl * -0.01638, gl * -0.0231, gl * -0.000378};
		float[] matrix = new float[9];
		for (int i = 0; i < 3; i++) {
			matrix[i] = (float) (r0[i] - 1.629 * mm[i] - 0.025 * ms[i]);
			matrix[3 + i] = (float) (r1[i] + 1.158 * mm[i]);
			matrix[6 + i] = (float) (r2[i] - 0.118 * mm[i] + 1.069 * ms[i]);
		}
		return matrix;
	}
}
