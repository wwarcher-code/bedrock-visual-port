package dev.bedrockvisuals.bedrock;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Finds the Bedrock resource packs to read. A packs file, one folder per line, wins when present; otherwise
 * the vanilla pack is looked for in the Xbox app's install folder (Bedrock 1.21.120 and later) on every drive.
 */
public final class BedrockInstall {
	static final Path INSTALL_SUFFIX = Path.of("XboxGames", "Minecraft for Windows", "Content", "data", "resource_packs");

	private BedrockInstall() {
	}

	/**
	 * @param packsFile config file listing pack folders, lowest priority first; lines starting with # are
	 *                  comments
	 * @return the pack folders, empty when none were found
	 */
	public static List<Path> findPacks(Path packsFile) {
		if (Files.isRegularFile(packsFile)) {
			return readPacksFile(packsFile);
		}
		for (Path root : FileSystems.getDefault().getRootDirectories()) {
			Path vanilla = root.resolve(INSTALL_SUFFIX).resolve("vanilla");
			if (Files.isDirectory(vanilla)) {
				return List.of(vanilla);
			}
		}
		return List.of();
	}

	/** The pack folder names next to {@code pack}, to see what else the install ships. */
	public static List<String> siblingPacks(Path pack) {
		Path parent = pack.getParent();
		if (parent == null || !Files.isDirectory(parent)) {
			return List.of();
		}
		try (Stream<Path> folders = Files.list(parent)) {
			return folders.filter(Files::isDirectory).map(p -> p.getFileName().toString()).sorted().toList();
		} catch (IOException e) {
			return List.of();
		}
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
