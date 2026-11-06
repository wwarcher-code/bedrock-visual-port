package dev.bedrockvisuals.render;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.bedrockvisuals.BedrockVisualsClient;
import dev.bedrockvisuals.bedrock.BedrockInstall;
import dev.bedrockvisuals.bedrock.Rgba;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * Bedrock's weather look (renderer/atmospheric_weather.json in the install): rain turns the sky, the sun's and
 * moon's colour and the clouds grey, thunder darker still. The sun keeps its illuminance: GPU captures in rain and
 * thunder at noon have SunColor (0.404, 0.404, 0.404, 100) and (0.251, 0.251, 0.251, 100), the same grey as the
 * sky (rain_sky_color, then base_thunder_sky_color by its contribution), and CloudColor 0.762 and 0.150. Read
 * from the install; Bedrock 1.26.60's values are the fallback.
 */
public final class Weather {
	private static float[] rainSky = {103 / 255f, 103 / 255f, 103 / 255f};
	private static float[] rainCloud = {191 / 255f, 191 / 255f, 191 / 255f};
	private static float rainCloudContribution = 0.95f;
	private static float[] thunderSky = {51 / 255f, 51 / 255f, 51 / 255f};
	private static float thunderSkyContribution = 0.75f;
	private static float[] thunderCloud = {30 / 255f, 30 / 255f, 30 / 255f};
	private static float thunderCloudContribution = 0.95f;
	private static boolean loaded;

	private Weather() {
	}

	private static void load() {
		if (loaded) {
			return;
		}
		loaded = true;
		Path file = BedrockInstall.dataFolder().map(data -> data.resolve("renderer/atmospheric_weather.json")).orElse(null);
		if (file == null || !Files.isRegularFile(file)) {
			return;
		}
		try {
			String text = Files.readString(file, StandardCharsets.UTF_8).replaceAll("(?m)//.*$", "");
			JsonObject json = JsonParser.parseString(text).getAsJsonObject();
			rainSky = color(json, "rain_sky_color", rainSky);
			rainCloud = color(json, "base_rain_cloud_color", rainCloud);
			rainCloudContribution = number(json, "rain_cloud_color_contribution", rainCloudContribution);
			thunderSky = color(json, "base_thunder_sky_color", thunderSky);
			thunderSkyContribution = number(json, "thunder_sky_color_contribution", thunderSkyContribution);
			thunderCloud = color(json, "base_thunder_cloud_color", thunderCloud);
			thunderCloudContribution = number(json, "thunder_cloud_color_contribution", thunderCloudContribution);
		} catch (IOException | RuntimeException e) {
			BedrockVisualsClient.LOGGER.warn("[weather] could not read {}; using Bedrock 1.26.60's values", file, e);
		}
	}

	private static float number(JsonObject json, String key, float fallback) {
		return json.has(key) ? json.get(key).getAsFloat() : fallback;
	}

	private static float[] color(JsonObject json, String key, float[] fallback) {
		if (!json.has(key)) {
			return fallback;
		}
		JsonArray array = json.getAsJsonArray(key);
		return new float[] {array.get(0).getAsFloat() / 255f, array.get(1).getAsFloat() / 255f,
				array.get(2).getAsFloat() / 255f};
	}

	/**
	 * How much rain thickens the air for the volumetric fog: AirAlbedoExtinction.w is 0.001 in clear plains and
	 * 0.004 in rain and in thunder (GPU captures at noon, rain level about 1), so the extinction is scaled by
	 * {@code 1 + 3 × rain}.
	 */
	private static final float RAIN_AIR_EXTINCTION = 4f;

	/** The volumetric air's extinction multiplier under the current weather. */
	static float airExtinctionScale() {
		return 1 + (RAIN_AIR_EXTINCTION - 1) * rain();
	}

	/**
	 * The distance fog under the current weather: Bedrock blends from the air fog towards the fog file's
	 * {@code weather} entry (default: start 0.23, end 0.7, #666666) by the rain level. Captured in rain:
	 * FogAndDistanceControl (0.23071, 2.12948), which is exactly (0.75, 1) blended to (0.23, 0.7) at rain
	 * 0.9986. Returns {start, end}.
	 */
	static float[] fogRange(float start, float end, float weatherStart, float weatherEnd) {
		float rain = rain();
		return new float[] {start + (weatherStart - start) * rain, end + (weatherEnd - end) * rain};
	}

	private static float rain() {
		ClientLevel level = Minecraft.getInstance().level;
		return level == null ? 0 : level.getRainLevel(1f);
	}

	private static float thunder() {
		ClientLevel level = Minecraft.getInstance().level;
		return level == null ? 0 : level.getThunderLevel(1f);
	}

	/** A sky colour (sRGB, as in the atmosphere files) under the current weather. */
	static Rgba sky(Rgba clear) {
		load();
		float rain = rain();
		float thunder = thunder() * thunderSkyContribution;
		return new Rgba(blend(clear.r(), rainSky[0], thunderSky[0], rain, thunder),
				blend(clear.g(), rainSky[1], thunderSky[1], rain, thunder),
				blend(clear.b(), rainSky[2], thunderSky[2], rain, thunder), clear.a());
	}

	/** CloudColor at midnight in GPU captures of Bedrock (white by day). */
	private static final float[] NIGHT_CLOUD = {0.1f, 0.1f, 0.15f};

	/**
	 * The cloud colour (as Bedrock sends it) under the current weather: white by day, Bedrock's night colour
	 * when the sun is down (TUNE: the blend between them follows the sun's illuminance up to 1; the captures
	 * only show noon, low sun and midnight), greyed by rain and thunder.
	 */
	static float[] cloud(float sunIlluminance) {
		load();
		float day = Math.clamp(sunIlluminance, 0f, 1f);
		float rain = rain() * rainCloudContribution;
		float thunder = thunder() * thunderCloudContribution;
		float[] out = new float[3];
		for (int i = 0; i < 3; i++) {
			float base = NIGHT_CLOUD[i] + (1 - NIGHT_CLOUD[i]) * day;
			out[i] = blend(base, base * rainCloud[i], base * thunderCloud[i], rain, thunder);
		}
		return out;
	}

	private static float blend(float clear, float rainy, float stormy, float rain, float thunder) {
		float value = clear + (rainy - clear) * rain;
		return value + (stormy - value) * thunder;
	}

	public static String status() {
		return String.format(java.util.Locale.ROOT, "rain %.2f, thunder %.2f, sky and sun grey %.3f", rain(), thunder(),
				sky(new dev.bedrockvisuals.bedrock.Rgba(1, 1, 1, 1)).r());
	}
}
