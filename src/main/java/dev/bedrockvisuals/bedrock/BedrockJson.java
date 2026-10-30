package dev.bedrockvisuals.bedrock;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Reads the JSON dialect Bedrock packs use: comments allowed, and loose value shapes. */
final class BedrockJson {
	private static final Pattern NUMBER_KEY = Pattern.compile("-?\\d+(\\.\\d+)?");

	private BedrockJson() {
	}

	static JsonObject read(Path file) throws IOException {
		return parse(Files.readString(file, StandardCharsets.UTF_8));
	}

	static JsonObject parse(String text) {
		// Some vanilla files start with a byte-order mark.
		if (text.startsWith("﻿")) {
			text = text.substring(1);
		}
		try (Reader in = new StringReader(text)) {
			JsonReader reader = new JsonReader(in);
			reader.setStrictness(Strictness.LENIENT);
			return JsonParser.parseReader(reader).getAsJsonObject();
		} catch (IOException | IllegalStateException e) {
			throw new JsonParseException("not a JSON object", e);
		}
	}

	static JsonObject object(JsonObject parent, String key) {
		JsonElement value = parent.get(key);
		if (value == null || !value.isJsonObject()) {
			throw new JsonParseException("missing object \"" + key + "\"");
		}
		return value.getAsJsonObject();
	}

	static JsonObject optionalObject(JsonObject parent, String key) {
		JsonElement value = parent.get(key);
		return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
	}

	static float number(JsonObject parent, String key, float fallback) {
		JsonElement value = parent.get(key);
		return value == null ? fallback : value.getAsFloat();
	}

	static boolean bool(JsonObject parent, String key, boolean fallback) {
		JsonElement value = parent.get(key);
		return value == null ? fallback : value.getAsBoolean();
	}

	static String string(JsonObject parent, String key, String fallback) {
		JsonElement value = parent.get(key);
		return value == null ? fallback : value.getAsString();
	}

	/** True for an object whose keys are all numbers: a keyframe map. */
	static boolean isKeyframes(JsonElement element) {
		if (!element.isJsonObject() || element.getAsJsonObject().isEmpty()) {
			return false;
		}
		for (String key : element.getAsJsonObject().keySet()) {
			if (!NUMBER_KEY.matcher(key).matches()) {
				return false;
			}
		}
		return true;
	}

	/**
	 * A color as Bedrock writes it: an [r, g, b] or [r, g, b, a] array of 0–255 values, or a "#RRGGBB" or
	 * "#RRGGBBAA" string. Missing alpha is opaque.
	 */
	static Rgba color(JsonElement element) {
		if (element.isJsonArray()) {
			JsonArray array = element.getAsJsonArray();
			if (array.size() != 3 && array.size() != 4) {
				throw new JsonParseException("color array needs 3 or 4 values, got " + array.size());
			}
			float a = array.size() == 4 ? array.get(3).getAsFloat() / 255f : 1f;
			return new Rgba(array.get(0).getAsFloat() / 255f, array.get(1).getAsFloat() / 255f,
					array.get(2).getAsFloat() / 255f, a);
		}
		String hex = element.getAsString();
		if (!hex.startsWith("#") || (hex.length() != 7 && hex.length() != 9)) {
			throw new JsonParseException("bad color \"" + hex + "\"");
		}
		long value = Long.parseLong(hex.substring(1), 16);
		if (hex.length() == 7) {
			value = (value << 8) | 0xFF;
		}
		return new Rgba(((value >> 24) & 0xFF) / 255f, ((value >> 16) & 0xFF) / 255f,
				((value >> 8) & 0xFF) / 255f, (value & 0xFF) / 255f);
	}

	/** A vector as an [x, y, z] array, an {"x", "y", "z"} object, or one number for all three. */
	static Vec3 vec3(JsonElement element) {
		if (element.isJsonArray()) {
			JsonArray array = element.getAsJsonArray();
			return new Vec3(array.get(0).getAsFloat(), array.get(1).getAsFloat(), array.get(2).getAsFloat());
		}
		if (element.isJsonObject()) {
			JsonObject object = element.getAsJsonObject();
			return new Vec3(number(object, "x", 0), number(object, "y", 0), number(object, "z", 0));
		}
		return Vec3.of(element.getAsFloat());
	}

	static Vec3 vec3(JsonObject parent, String key, Vec3 fallback) {
		JsonElement value = parent.get(key);
		return value == null ? fallback : vec3(value);
	}

	static Curve<Float> floatCurve(JsonObject parent, String key, float fallback) {
		JsonElement value = parent.get(key);
		if (value == null) {
			return Curve.constant(fallback);
		}
		if (!isKeyframes(value)) {
			return Curve.constant(value.getAsFloat());
		}
		return keyframes(value.getAsJsonObject(), JsonElement::getAsFloat, Curve::lerpFloat);
	}

	static Curve<Rgba> colorCurve(JsonObject parent, String key, Rgba fallback) {
		JsonElement value = parent.get(key);
		if (value == null) {
			return Curve.constant(fallback);
		}
		if (!isKeyframes(value)) {
			return Curve.constant(color(value));
		}
		return keyframes(value.getAsJsonObject(), BedrockJson::color, Rgba::lerp);
	}

	private static <T> Curve<T> keyframes(JsonObject frames, java.util.function.Function<JsonElement, T> parse,
			Curve.Lerp<T> lerp) {
		List<Map.Entry<Float, T>> entries = new ArrayList<>();
		for (Map.Entry<String, JsonElement> entry : frames.entrySet()) {
			entries.add(Map.entry(Float.parseFloat(entry.getKey()), parse.apply(entry.getValue())));
		}
		// Vanilla files list keyframes out of order.
		entries.sort(Map.Entry.comparingByKey());
		float[] keys = new float[entries.size()];
		List<T> values = new ArrayList<>(entries.size());
		for (int i = 0; i < keys.length; i++) {
			keys[i] = entries.get(i).getKey();
			values.add(entries.get(i).getValue());
		}
		return new Curve<>(keys, values, lerp);
	}

	/**
	 * Lays {@code override} over {@code base}: nested objects merge, everything else (including keyframe
	 * maps, which replace a whole curve) is replaced.
	 */
	static JsonObject merge(JsonObject base, JsonObject override) {
		JsonObject result = base.deepCopy();
		for (Map.Entry<String, JsonElement> entry : override.entrySet()) {
			JsonElement existing = result.get(entry.getKey());
			JsonElement value = entry.getValue();
			if (existing != null && existing.isJsonObject() && value.isJsonObject()
					&& !isKeyframes(existing) && !isKeyframes(value)) {
				result.add(entry.getKey(), merge(existing.getAsJsonObject(), value.getAsJsonObject()));
			} else {
				result.add(entry.getKey(), value.deepCopy());
			}
		}
		return result;
	}
}
