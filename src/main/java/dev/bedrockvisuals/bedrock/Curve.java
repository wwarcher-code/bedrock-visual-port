package dev.bedrockvisuals.bedrock;

import java.util.Arrays;
import java.util.List;

/**
 * A value that is either constant or keyframed over the day. Keys run from 0 (noon) to 1 (the next noon);
 * values between keys are interpolated linearly. The day is a cycle, so a time before the first key or after
 * the last blends between the last key and the first across the wrap: vanilla's sun color has keys from
 * 0.14 to 1.0, and noon (0) sits between its 1.0 and 0.14 keys.
 */
public final class Curve<T> {
	private final float[] keys;
	private final List<T> values;
	private final Lerp<T> lerp;

	@FunctionalInterface
	public interface Lerp<T> {
		T apply(T from, T to, float t);
	}

	Curve(float[] keys, List<T> values, Lerp<T> lerp) {
		this.keys = keys;
		this.values = values;
		this.lerp = lerp;
	}

	public static Curve<Float> constant(float value) {
		return new Curve<>(new float[] {0}, List.of(value), Curve::lerpFloat);
	}

	public static Curve<Rgba> constant(Rgba value) {
		return new Curve<>(new float[] {0}, List.of(value), Rgba::lerp);
	}

	static float lerpFloat(Float from, Float to, float t) {
		return from + (to - from) * t;
	}

	public boolean isConstant() {
		return keys.length == 1;
	}

	/** @param timeOfDay Bedrock time of day, see {@link TimeOfDay} */
	public T sample(float timeOfDay) {
		int next = Arrays.binarySearch(keys, timeOfDay);
		if (next >= 0) {
			return values.get(next);
		}
		next = -next - 1;
		if (next == 0 || next == keys.length) {
			// Across the wrap, from the last key to the first key of the next cycle.
			float from = keys[keys.length - 1];
			float to = keys[0] + 1;
			float time = next == 0 ? timeOfDay + 1 : timeOfDay;
			float span = to - from;
			float t = span <= 0 ? 0 : (time - from) / span;
			return lerp.apply(values.getLast(), values.getFirst(), t);
		}
		float t = (timeOfDay - keys[next - 1]) / (keys[next] - keys[next - 1]);
		return lerp.apply(values.get(next - 1), values.get(next), t);
	}

	@Override
	public String toString() {
		return isConstant() ? String.valueOf(values.getFirst()) : keys.length + " keyframes";
	}
}
