package dev.bedrockvisuals.devtools;

import dev.bedrockvisuals.render.AutoExposure;
import dev.bedrockvisuals.render.GBufferDebugView.View;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.imageio.ImageIO;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/**
 * The far terrain's flicker test, part of the F9 capture: with the camera still, the same picture is taken a
 * dozen times (six frames a tick apart, then six half a second apart) in the lit view and in each view of an
 * input to Distant Horizons' terrain's lighting (its colour as DH hands it over, its depth, the normal rebuilt
 * from that depth, the sun's visibility). Over the far terrain's pixels it then measures how much each view
 * changes from shot to shot: the input that flickers with the lit view is where the flicker comes from. Each
 * view also gets a difference image (the largest change at each pixel, eight times brighter; the far terrain's
 * unchanged pixels dark blue, everything else black).
 */
final class FlickerTest {
	static final View[] VIEWS = {View.OFF, View.DISTANT_COLOR, View.DISTANT_DEPTH, View.DISTANT_NORMAL,
			View.DISTANT_SHADOW};
	/** Shots per view, and the ticks before each: six consecutive, then six spread over three seconds. */
	static final int[] TICKS_BEFORE = {0, 1, 1, 1, 1, 1, 10, 10, 10, 10, 10, 10};
	static final int FRAMES = TICKS_BEFORE.length;
	/** A channel change above this (of 255) counts as a changed pixel. */
	private static final int THRESHOLD = 6;

	private record Pose(Vec3 position, float yaw, float pitch, float exposure) {
	}

	private static final Map<View, List<Pose>> POSES = new EnumMap<>(View.class);

	private FlickerTest() {
	}

	static void reset() {
		POSES.clear();
	}

	static String label(View view) {
		return view == View.OFF ? "lit" : view.label;
	}

	static Path frame(Path folder, View view, int k) {
		return folder.resolve(("flicker_" + label(view) + "_" + k + ".png").replace(' ', '-'));
	}

	static Path difference(Path folder, View view) {
		return folder.resolve(("flicker_" + label(view) + "_difference.png").replace(' ', '-'));
	}

	/** Called as each shot is taken: where the camera was, and the exposure. */
	static void record(View view) {
		Camera camera = Minecraft.getInstance().gameRenderer.mainCamera();
		POSES.computeIfAbsent(view, v -> new ArrayList<>())
				.add(new Pose(camera.position(), camera.yRot(), camera.xRot(), AutoExposure.exposure()));
	}

	/** Compares the shots (off the render thread, once they're saved) and writes the difference images. */
	static String analyse(Path folder) {
		StringBuilder out = new StringBuilder("\n== Far terrain flicker test\n");
		try {
			boolean[] mask = mask(folder);
			int maskCount = 0;
			for (boolean m : mask) {
				maskCount += m ? 1 : 0;
			}
			if (mask.length == 0 || maskCount == 0) {
				out.append("no Distant Horizons terrain on screen (the far depth view was black): face it and run again\n");
				return out.toString();
			}
			out.append(String.format(Locale.ROOT, "far terrain covers %.1f%% of the screen; a pixel counts as "
					+ "changed above %d/255 in any channel; shots 1-6 a tick apart, 7-12 half a second apart%n",
					100.0 * maskCount / mask.length, THRESHOLD));
			for (View view : VIEWS) {
				out.append(compare(folder, view, mask, maskCount)).append('\n');
				out.append(poses(view)).append('\n');
			}
			out.append("""
					Reading it: the lit view's change is the flicker. The input view that changes with it is its \
					source: far colour = Distant Horizons' own picture (its LODs or its own lighting change); far \
					depth = DH's depth (its projection or depth precision moves); far normal = the normal this mod \
					rebuilds from that depth; far shadow = the shadow cascades (red), the far terrain's heights \
					(green) or both (blue). If only the lit view changes, it is later in the lighting (exposure, \
					the sky probe, the fog grid). A camera that moved makes every view change: stand still.
					""");
		} catch (IOException | RuntimeException e) {
			out.append("could not compare the shots: ").append(e).append('\n');
		}
		return out.toString();
	}

	/** The far terrain's pixels: those not black in the first far depth shot. */
	private static boolean[] mask(Path folder) throws IOException {
		Path first = frame(folder, View.DISTANT_DEPTH, 0);
		if (!Files.isRegularFile(first)) {
			return new boolean[0];
		}
		BufferedImage image = ImageIO.read(first.toFile());
		int w = image.getWidth();
		int h = image.getHeight();
		boolean[] mask = new boolean[w * h];
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int rgb = image.getRGB(x, y);
				mask[y * w + x] = ((rgb >> 16) & 255) + ((rgb >> 8) & 255) + (rgb & 255) > 12;
			}
		}
		return mask;
	}

	private static String compare(Path folder, View view, boolean[] mask, int maskCount) throws IOException {
		List<BufferedImage> frames = new ArrayList<>();
		for (int k = 0; k < FRAMES; k++) {
			Path file = frame(folder, view, k);
			if (Files.isRegularFile(file)) {
				frames.add(ImageIO.read(file.toFile()));
			}
		}
		String name = String.format(Locale.ROOT, "%-11s", label(view));
		if (frames.size() < 2) {
			return name + ": not enough shots saved";
		}
		int w = frames.get(0).getWidth();
		int h = frames.get(0).getHeight();
		if (w * h != mask.length) {
			return name + ": shots differ in size from the far depth view's";
		}
		int[] largest = new int[w * h];
		double worstShare = 0;
		double shareSum = 0;
		double meanSum = 0;
		int worstPair = 0;
		int[] previous = frames.get(0).getRGB(0, 0, w, h, null, 0, w);
		for (int k = 1; k < frames.size(); k++) {
			int[] current = frames.get(k).getRGB(0, 0, w, h, null, 0, w);
			long changed = 0;
			long total = 0;
			for (int i = 0; i < current.length; i++) {
				if (!mask[i]) {
					continue;
				}
				int a = previous[i];
				int b = current[i];
				int d = Math.max(Math.abs(((a >> 16) & 255) - ((b >> 16) & 255)),
						Math.max(Math.abs(((a >> 8) & 255) - ((b >> 8) & 255)), Math.abs((a & 255) - (b & 255))));
				largest[i] = Math.max(largest[i], d);
				total += d;
				if (d > THRESHOLD) {
					changed++;
				}
			}
			double share = (double) changed / maskCount;
			shareSum += share;
			meanSum += (double) total / maskCount;
			if (share > worstShare) {
				worstShare = share;
				worstPair = k;
			}
			previous = current;
		}
		int pairs = frames.size() - 1;
		BufferedImage diff = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		int everChanged = 0;
		for (int i = 0; i < largest.length; i++) {
			int rgb;
			if (!mask[i]) {
				rgb = 0;
			} else if (largest[i] > THRESHOLD) {
				int v = Math.min(255, largest[i] * 8);
				rgb = (v << 16) | (v << 8) | v;
				everChanged++;
			} else {
				rgb = 0x101830;
			}
			diff.setRGB(i % w, i / w, rgb);
		}
		ImageIO.write(diff, "png", difference(folder, view).toFile());
		return String.format(Locale.ROOT, "%s: %.2f%% of the far terrain changes between shots on average, %.2f%% at "
				+ "worst (shots %d to %d); %.2f%% changed at some point; mean change %.2f/255",
				name, 100 * shareSum / pairs, 100 * worstShare, worstPair, worstPair + 1,
				100.0 * everChanged / maskCount, meanSum / pairs);
	}

	private static String poses(View view) {
		List<Pose> poses = POSES.getOrDefault(view, List.of());
		if (poses.isEmpty()) {
			return "             camera: not recorded";
		}
		Pose first = poses.get(0);
		double moved = 0;
		double turned = 0;
		float minExposure = Float.MAX_VALUE;
		float maxExposure = -Float.MAX_VALUE;
		for (Pose p : poses) {
			moved = Math.max(moved, p.position().distanceTo(first.position()));
			turned = Math.max(turned, Math.max(Math.abs(p.yaw() - first.yaw()), Math.abs(p.pitch() - first.pitch())));
			minExposure = Math.min(minExposure, p.exposure());
			maxExposure = Math.max(maxExposure, p.exposure());
		}
		return String.format(Locale.ROOT, "             camera moved %.4f blocks, turned %.3f°; exposure %.4f to %.4f",
				moved, turned, minExposure, maxExposure);
	}
}
