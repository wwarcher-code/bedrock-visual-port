package dev.bedrockvisuals.bedrock;

import java.util.Arrays;
import java.util.List;

/**
 * A value that is either constant or keyframed over the day. Keys run from 0 (noon) to 1 (the next noon);
 * values between keys are interpolated linearly, and times outside the keys take the nearest key's value.
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
		if (next == 0) {
			return values.getFirst();
		}
		if (next == keys.length) {
			return values.getLast();
		}
		float t = (timeOfDay - keys[next - 1]) / (keys[next] - keys[next - 1]);
		return lerp.apply(values.get(next - 1), values.get(next), t);
	}

	@Override
	public String toString() {
		return isConstant() ? String.valueOf(values.getFirst()) : keys.length + " keyframes";
	}
}
