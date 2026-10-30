package dev.bedrockvisuals.bedrock;

/** A color or packed value as written in a Bedrock file, scaled to 0–1. Colors stay in sRGB encoding. */
public record Rgba(float r, float g, float b, float a) {
	public static final Rgba WHITE = new Rgba(1, 1, 1, 1);

	static Rgba lerp(Rgba from, Rgba to, float t) {
		return new Rgba(
				from.r + (to.r - from.r) * t,
				from.g + (to.g - from.g) * t,
				from.b + (to.b - from.b) * t,
				from.a + (to.a - from.a) * t);
	}
}
