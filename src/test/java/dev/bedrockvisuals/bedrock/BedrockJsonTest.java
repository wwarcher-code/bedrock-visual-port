package dev.bedrockvisuals.bedrock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;

class BedrockJsonTest {
	private static final float EPSILON = 1e-5f;

	@Test
	void parsesFilesWithCommentsAndByteOrderMark() {
		JsonObject json = BedrockJson.parse("﻿/* header */\n{\n  // note\n  \"a\": 1\n}");
		assertEquals(1, json.get("a").getAsInt());
	}

	@Test
	void parsesColorShapes() {
		assertEquals(new Rgba(1, 0, 0, 1), BedrockJson.color(BedrockJson.parse("{\"c\": [255, 0, 0]}").get("c")));
		assertEquals(new Rgba(0, 1, 0, 0), BedrockJson.color(BedrockJson.parse("{\"c\": [0, 255, 0, 0]}").get("c")));
		assertEquals(new Rgba(1, 1, 1, 1), BedrockJson.color(new JsonPrimitive("#FFFFFF")));
		Rgba withAlpha = BedrockJson.color(new JsonPrimitive("#ff000080"));
		assertEquals(128 / 255f, withAlpha.a(), EPSILON);
		assertThrows(JsonParseException.class, () -> BedrockJson.color(new JsonPrimitive("red")));
	}

	@Test
	void parsesVectorShapes() {
		JsonObject json = BedrockJson.parse("{\"a\": [1, 2, 3], \"o\": {\"x\": 4, \"y\": 5, \"z\": 6}, \"n\": 7}");
		assertEquals(new Vec3(1, 2, 3), BedrockJson.vec3(json.get("a")));
		assertEquals(new Vec3(4, 5, 6), BedrockJson.vec3(json.get("o")));
		assertEquals(Vec3.of(7), BedrockJson.vec3(json.get("n")));
	}

	@Test
	void keyframesAreSortedInterpolatedAndClamped() {
		// Out of order, as vanilla files write them.
		JsonObject json = BedrockJson.parse("{\"v\": {\"0.75\": 30.0, \"0.25\": 10.0, \"0.5\": 20.0}}");
		Curve<Float> curve = BedrockJson.floatCurve(json, "v", 0);
		assertFalse(curve.isConstant());
		assertEquals(10, curve.sample(0.0f), EPSILON);
		assertEquals(10, curve.sample(0.25f), EPSILON);
		assertEquals(15, curve.sample(0.375f), EPSILON);
		assertEquals(30, curve.sample(1.0f), EPSILON);
	}

	@Test
	void colorKeyframesInterpolatePerChannel() {
		JsonObject json = BedrockJson.parse("{\"c\": {\"0.0\": [0, 0, 0], \"1.0\": \"#FF0000\"}}");
		Rgba mid = BedrockJson.colorCurve(json, "c", Rgba.WHITE).sample(0.5f);
		assertEquals(0.5f, mid.r(), EPSILON);
		assertEquals(0, mid.g(), EPSILON);
	}

	@Test
	void missingValuesUseTheFallback() {
		JsonObject json = new JsonObject();
		assertTrue(BedrockJson.floatCurve(json, "v", 3).isConstant());
		assertEquals(3, BedrockJson.floatCurve(json, "v", 3).sample(0.3f), EPSILON);
	}

	@Test
	void mergeReplacesKeyframesWholeAndMergesObjects() {
		JsonObject base = BedrockJson.parse("{\"sun\": {\"illuminance\": {\"0.0\": 1, \"0.5\": 2}, \"color\": \"#FFFFFF\"}}");
		JsonObject override = BedrockJson.parse("{\"sun\": {\"illuminance\": {\"0.25\": 9}}}");
		JsonObject sun = BedrockJson.merge(base, override).getAsJsonObject("sun");
		assertEquals(1, sun.getAsJsonObject("illuminance").size());
		assertEquals("#FFFFFF", sun.get("color").getAsString());
	}
}
