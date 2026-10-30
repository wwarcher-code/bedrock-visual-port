package dev.bedrockvisuals.bedrock;

public record Vec3(float x, float y, float z) {
	public static Vec3 of(float value) {
		return new Vec3(value, value, value);
	}
}
