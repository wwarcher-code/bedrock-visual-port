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
 * the vanilla stack is taken from the Xbox app's install folder (Bedrock 1.21.120 and later) on any drive.
 *
 * <p>Bedrock builds vanilla from several packs: vanilla_base, vanilla, then one vanilla_&lt;version&gt;
 * pack per release with that release's changes. Later versions override earlier ones.
 */
public final class BedrockInstall {
	static final Path INSTALL_SUFFIX = Path.of("XboxGames", "Minecraft for Windows", "Content", "data", "resource_packs");
	private static final Pattern VERSIONED = Pattern.compile("vanilla_(\\d+(\\.\\d+)*)");

	private BedrockInstall() {
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
		for (Path root : FileSystems.getDefault().getRootDirectories()) {
			Path packs = root.resolve(INSTALL_SUFFIX);
			if (Files.isDirectory(packs.resolve("vanilla"))) {
				return vanillaStack(packs);
			}
		}
		return List.of();
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
