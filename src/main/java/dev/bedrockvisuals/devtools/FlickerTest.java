package dev.bedrockvisuals.devtools;

import dev.bedrockvisuals.render.AutoExposure;
import dev.bedrockvisuals.render.GBufferDebugView.View;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
 * from that depth, the sun's visibility) and of the water's reflections (where each comes from: the
 * screen-space march, the far march or the sky; the reflected sky alone; the reflected scene alone), with the
 * ripples held still. Over the far terrain's pixels, and the water's, it then measures how much each view
 * changes from shot to shot: the input that flickers with the lit view is where the flicker comes from. Each
 * view also gets a difference image (the largest change at each pixel, eight times brighter; the far terrain's
 * unchanged pixels dark blue, everything else black).
 */
final class FlickerTest {
	/** One view shot a dozen times: with the water's ripples held still, or moving. */
	record Pass(View view, boolean rippling) {
		String label() {
			return (view == View.OFF ? "lit" : view.label) + (rippling ? ", rippling" : "");
		}

		boolean overWater() {
			return view == View.REFLECTION_SOURCE || view == View.REFLECTED_SKY || view == View.REFLECTED_SCENE;
		}
	}

	/**
	 * The views, ripples held still; last the reflection sources again with the ripples moving: that view
	 * changes only where a reflection jumps between the screen-space march, the far march and the sky.
	 */
	static final List<Pass> PASSES = List.of(new Pass(View.OFF, false), new Pass(View.DISTANT_COLOR, false),
			new Pass(View.DISTANT_DEPTH, false), new Pass(View.DISTANT_NORMAL, false),
			new Pass(View.DISTANT_SHADOW, false), new Pass(View.REFLECTION_SOURCE, false),
			new Pass(View.REFLECTED_SKY, false), new Pass(View.REFLECTED_SCENE, false),
			new Pass(View.REFLECTION_SOURCE, true));
	/** Shots per view, and the ticks before each: six consecutive, then six spread over three seconds. */
	static final int[] TICKS_BEFORE = {0, 1, 1, 1, 1, 1, 10, 10, 10, 10, 10, 10};
	static final int FRAMES = TICKS_BEFORE.length;
	/** A channel change above this (of 255) counts as a changed pixel. */
	private static final int THRESHOLD = 6;

	private record Pose(Vec3 position, float yaw, float pitch, float exposure) {
	}

	private static final Map<Pass, List<Pose>> POSES = new java.util.HashMap<>();

	private FlickerTest() {
	}

	static void reset() {
		POSES.clear();
	}

	private static String fileLabel(Pass pass) {
		return pass.label().replace(", ", "_").replace(' ', '-');
	}

	static Path frame(Path folder, Pass pass, int k) {
		return folder.resolve("flicker_" + fileLabel(pass) + "_" + k + ".png");
	}

	static Path difference(Path folder, Pass pass) {
		return folder.resolve("flicker_" + fileLabel(pass) + "_difference.png");
	}

	/** Called as each shot is taken: where the camera was, and the exposure. */
	static void record(Pass pass) {
		Camera camera = Minecraft.getInstance().gameRenderer.mainCamera();
		POSES.computeIfAbsent(pass, v -> new ArrayList<>())
				.add(new Pose(camera.position(), camera.yRot(), camera.xRot(), AutoExposure.exposure()));
	}

	/** Compares the shots (off the render thread, once they're saved) and writes the difference images. */
	static String analyse(Path folder) {
		StringBuilder out = new StringBuilder("\n== Flicker test (far terrain and reflections)\n");
		try {
			boolean[] far = mask(folder, new Pass(View.DISTANT_DEPTH, false));
			boolean[] water = mask(folder, new Pass(View.REFLECTION_SOURCE, false));
			int farCount = count(far);
			int waterCount = count(water);
			out.append(String.format(Locale.ROOT, "far terrain covers %.1f%% of the screen, water %.1f%%; a pixel counts "
					+ "as changed above %d/255 in any channel; shots 1-6 a tick apart, 7-12 half a second apart; the "
					+ "water's ripples held still but in the last%n", farCount * 100.0 / Math.max(far.length, 1),
					waterCount * 100.0 / Math.max(water.length, 1), THRESHOLD));
			for (Pass pass : PASSES) {
				View view = pass.view();
				boolean overWater = pass.overWater();
				boolean[] mask = overWater ? water : far;
				int maskCount = overWater ? waterCount : farCount;
				String where = overWater ? "water" : "far terrain";
				if (maskCount == 0) {
					out.append(String.format(Locale.ROOT, "%-17s: no %s on screen%n", pass.label(), where));
				} else {
					out.append(compare(folder, pass, mask, maskCount, where,
							view == View.OFF ? null : difference(folder, pass))).append('\n');
				}
				if (view == View.OFF) {
					if (waterCount > 0) {
						out.append(compare(folder, pass, water, waterCount, "water", null)).append('\n');
					}
					// Its difference image over both.
					boolean[] both = new boolean[Math.max(far.length, water.length)];
					for (int i = 0; i < both.length; i++) {
						both[i] = (i < far.length && far[i]) || (i < water.length && water[i]);
					}
					if (count(both) > 0) {
						compare(folder, pass, both, count(both), "far terrain and water", difference(folder, pass));
					}
				}
				out.append(poses(pass)).append('\n');
			}
			out.append("""
					Reading it: the lit view's change is the flicker. The input view that changes with it is its \
					source: far colour = Distant Horizons' own picture (its LODs or its own lighting change); far \
					depth = DH's depth (its projection or depth precision moves); far normal = the normal this mod \
					rebuilds from that depth; far shadow = the shadow cascades (red), the far terrain's heights \
					(green) or both (blue). Over the water: reflection source = whether each pixel's reflection \
					comes from the screen-space march (red), the far march (green) or the sky (blue), so a change \
					there is a reflection popping between them (with the ripples moving: popping as they move, \
					the likeliest flicker in reflections); reflected sky and reflected scene = each \
					reflection's colour alone. If only the lit view changes, it is later in the lighting \
					(exposure, the sky probe, the fog grid). A camera that moved makes every view change: stand \
					still.
					""");
		} catch (IOException | RuntimeException e) {
			out.append("could not compare the shots: ").append(e).append('\n');
		}
		return out.toString();
	}

	private static int count(boolean[] mask) {
		int n = 0;
		for (boolean m : mask) {
			n += m ? 1 : 0;
		}
		return n;
	}

	/**
	 * The pixels a view shows (not black in its first shot): the far terrain's in the far depth view, the
	 * water's in the reflection source view.
	 */
	private static boolean[] mask(Path folder, Pass pass) throws IOException {
		Path first = frame(folder, pass, 0);
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

	private static String compare(Path folder, Pass pass, boolean[] mask, int maskCount, String where,
			Path differenceImage) throws IOException {
		List<BufferedImage> frames = new ArrayList<>();
		for (int k = 0; k < FRAMES; k++) {
			Path file = frame(folder, pass, k);
			if (Files.isRegularFile(file)) {
				frames.add(ImageIO.read(file.toFile()));
			}
		}
		String name = String.format(Locale.ROOT, "%-26s",
				pass.label() + (pass.view() == View.OFF ? " (" + where + ")" : ""));
		if (frames.size() < 2) {
			return name + ": not enough shots saved";
		}
		int w = frames.get(0).getWidth();
		int h = frames.get(0).getHeight();
		if (w * h != mask.length) {
			return name + ": shots differ in size from the mask's";
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
		if (differenceImage != null) {
			ImageIO.write(diff, "png", differenceImage.toFile());
		}
		return String.format(Locale.ROOT, "%s: %.2f%% of the %s changes between shots on average, %.2f%% at "
				+ "worst (shots %d to %d); %.2f%% changed at some point; mean change %.2f/255",
				name, 100 * shareSum / pairs, where, 100 * worstShare, worstPair, worstPair + 1,
				100.0 * everChanged / maskCount, meanSum / pairs);
	}

	private static String poses(Pass pass) {
		List<Pose> poses = POSES.getOrDefault(pass, List.of());
		if (poses.isEmpty()) {
			return "                   camera: not recorded";
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
		return String.format(Locale.ROOT, "                   camera moved %.4f blocks, turned %.3f°; exposure %.4f to %.4f",
				moved, turned, minExposure, maxExposure);
	}
}
