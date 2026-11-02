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
 * Bedrock's weather look (renderer/atmospheric_weather.json in the install): rain turns the sky grey and the
 * clouds grey, thunder darker still, and direct sun and moon light fade out ({@code directional_light_strength}
 * 0). Read from the install; Bedrock 1.26.60's values are the fallback.
 */
public final class Weather {
	private static float directionalStrength = 0f;
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
			directionalStrength = number(json, "directional_light_strength", directionalStrength);
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

	/** The cloud colour (sRGB) under the current weather; white when clear. */
	static float[] cloud() {
		load();
		float rain = rain() * rainCloudContribution;
		float thunder = thunder() * thunderCloudContribution;
		return new float[] {blend(1, rainCloud[0], thunderCloud[0], rain, thunder),
				blend(1, rainCloud[1], thunderCloud[1], rain, thunder), blend(1, rainCloud[2], thunderCloud[2], rain, thunder)};
	}

	/** How much direct sun and moon light reaches the ground: 1 when clear, directional_light_strength in rain. */
	static float directLight() {
		load();
		float rain = rain();
		return 1 + (directionalStrength - 1) * rain;
	}

	private static float blend(float clear, float rainy, float stormy, float rain, float thunder) {
		float value = clear + (rainy - clear) * rain;
		return value + (stormy - value) * thunder;
	}

	public static String status() {
		return String.format(java.util.Locale.ROOT, "rain %.2f, thunder %.2f, direct light x%.2f", rain(), thunder(),
				directLight());
	}
}
