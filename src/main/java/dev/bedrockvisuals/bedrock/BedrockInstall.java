package dev.bedrockvisuals.bedrock;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Finds the Bedrock resource packs to read. A packs file, one folder per line, wins when present. Otherwise
 * the vanilla stack is taken from the Xbox app's install folder (Bedrock 1.21.120 and later) on any drive,
 * with Mojang's bedrock-samples pack on top when it has been downloaded to the user's home folder: the
 * install keeps its texture images in a form the mod can't read, so the material maps come from there.
 *
 * <p>Bedrock builds vanilla from several packs: vanilla_base, vanilla, then one vanilla_&lt;version&gt;
 * pack per release with that release's changes. Later versions override earlier ones.
 */
public final class BedrockInstall {
	static final Path INSTALL_SUFFIX = Path.of("XboxGames", "Minecraft for Windows", "Content", "data", "resource_packs");
	static final Path SAMPLES = Path.of(System.getProperty("user.home"), "bedrock-samples", "resource_pack");
	private static final Pattern VERSIONED = Pattern.compile("vanilla_(\\d+(\\.\\d+)*)");

	private BedrockInstall() {
	}

	/**
	 * The install's data folder (Content\data), which holds the engine's own renderer files
	 * (renderer/caustics.png, the platform configurations); empty when Bedrock isn't installed.
	 */
	public static java.util.Optional<Path> dataFolder() {
		for (Path root : FileSystems.getDefault().getRootDirectories()) {
			Path data = root.resolve(INSTALL_SUFFIX).getParent();
			if (Files.isDirectory(data.resolve("renderer"))) {
				return java.util.Optional.of(data);
			}
		}
		return java.util.Optional.empty();
	}

	/**
	 * @param packsFile config file listing pack folders, lowest priority first; lines starting with # are
	 *                  comments
	 * @return the pack folders, lowest priority first; empty when none were found
	 */
	public static List<Path> findPacks(Path packsFile) {
		if (Files.isRegularFile(packsFile)) {
			return readPacksFile(packsFile);
		}
		List<Path> stack = new ArrayList<>();
		for (Path root : FileSystems.getDefault().getRootDirectories()) {
			Path packs = root.resolve(INSTALL_SUFFIX);
			if (Files.isDirectory(packs.resolve("vanilla"))) {
				stack.addAll(vanillaStack(packs));
				break;
			}
		}
		if (Files.isDirectory(SAMPLES)) {
			stack.add(SAMPLES);
		}
		return stack;
	}

	/**
	 * Expands each folder: an install's resource_packs folder becomes its vanilla stack, anything else is
	 * one pack.
	 */
	public static List<Path> expand(List<Path> folders) {
		List<Path> stack = new ArrayList<>();
		for (Path folder : folders) {
			if (Files.isDirectory(folder.resolve("vanilla"))) {
				stack.addAll(vanillaStack(folder));
			} else {
				stack.add(folder);
			}
		}
		return stack;
	}

	/** vanilla_base, vanilla, then the versioned vanilla packs oldest first. */
	static List<Path> vanillaStack(Path resourcePacks) {
		List<Path> stack = new ArrayList<>();
		for (String name : List.of("vanilla_base", "vanilla")) {
			if (Files.isDirectory(resourcePacks.resolve(name))) {
				stack.add(resourcePacks.resolve(name));
			}
		}
		try (Stream<Path> folders = Files.list(resourcePacks)) {
			folders.filter(Files::isDirectory)
					.filter(p -> VERSIONED.matcher(p.getFileName().toString()).matches())
					.sorted(Comparator.comparing(BedrockInstall::version, Arrays::compare))
					.forEach(stack::add);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return stack;
	}

	private static int[] version(Path pack) {
		String version = pack.getFileName().toString().substring("vanilla_".length());
		return Arrays.stream(version.split("\\.")).mapToInt(Integer::parseInt).toArray();
	}

	private static List<Path> readPacksFile(Path packsFile) {
		try {
			List<Path> packs = new ArrayList<>();
			for (String line : Files.readAllLines(packsFile, StandardCharsets.UTF_8)) {
				String trimmed = line.strip();
				if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
					packs.add(Path.of(trimmed));
				}
			}
			return packs;
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
