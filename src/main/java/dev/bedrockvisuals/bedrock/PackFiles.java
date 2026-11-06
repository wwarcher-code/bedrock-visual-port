package dev.bedrockvisuals.bedrock;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * One view of the files across a stack of packs, as Bedrock sees them: a later pack's file replaces an
 * earlier pack's file at the same path, and within a pack an archived file replaces a loose one. Paths
 * are relative to the pack root with forward slashes, e.g. "textures/blocks/stone.texture_set.json".
 */
final class PackFiles {
	private static final String ARCHIVE_FOLDER = "__brarchive";
	private static final String ARCHIVE_SUFFIX = ".brarchive";
	private static final List<String> IMAGE_EXTENSIONS = List.of(".png", ".tga", ".jpg", ".jpeg");

	private sealed interface Source permits Loose, Archived {
		byte[] read() throws IOException;
	}

	private record Loose(Path file) implements Source {
		@Override
		public byte[] read() throws IOException {
			return Files.readAllBytes(file);
		}
	}

	private record Archived(byte[] content) implements Source {
		@Override
		public byte[] read() {
			return content;
		}
	}

	private final TreeMap<String, Source> files = new TreeMap<>();

	private PackFiles() {
	}

	/** @param problems receives archives that can't be read */
	static PackFiles of(List<Path> packRoots, List<String> problems) {
		PackFiles view = new PackFiles();
		for (Path root : packRoots) {
			if (!Files.isDirectory(root)) {
				continue;
			}
			try {
				// Files.walk doesn't follow a pack folder that is itself a link.
				root = root.toRealPath();
			} catch (IOException e) {
				problems.add(root + ": " + e.getMessage());
				continue;
			}
			view.addLoose(root);
			view.addArchives(root, problems);
		}
		return view;
	}

	private void addLoose(Path root) {
		try (Stream<Path> walk = Files.walk(root)) {
			walk.filter(Files::isRegularFile).forEach(file -> {
				String path = relative(root, file);
				if (!path.startsWith(ARCHIVE_FOLDER + "/")) {
					files.put(path, new Loose(file));
				}
			});
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** __brarchive/textures/blocks.brarchive holds the files of textures/blocks. */
	private void addArchives(Path root, List<String> problems) {
		Path archives = root.resolve(ARCHIVE_FOLDER);
		if (!Files.isDirectory(archives)) {
			return;
		}
		try (Stream<Path> walk = Files.walk(archives)) {
			for (Path archive : walk.filter(f -> f.toString().endsWith(ARCHIVE_SUFFIX)).sorted().toList()) {
				String folder = relative(archives, archive);
				folder = folder.substring(0, folder.length() - ARCHIVE_SUFFIX.length());
				try {
					for (Map.Entry<String, byte[]> entry : Brarchive.read(archive).entrySet()) {
						String name = entry.getKey().replace('\\', '/');
						String path = name.startsWith(folder + "/") ? name : folder + "/" + name;
						files.put(path, new Archived(entry.getValue()));
					}
				} catch (IOException e) {
					problems.add(archive + ": " + e.getMessage());
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static String relative(Path root, Path file) {
		return root.relativize(file).toString().replace('\\', '/');
	}

	/** Paths directly in {@code folder}, or anywhere below it when {@code recursive}. */
	List<String> list(String folder, boolean recursive) {
		String prefix = folder + "/";
		return files.subMap(prefix, prefix + Character.MAX_VALUE).keySet().stream()
				.filter(path -> recursive || path.indexOf('/', prefix.length()) < 0)
				.toList();
	}

	boolean exists(String path) {
		return files.containsKey(path);
	}

	JsonObject json(String path) throws IOException {
		Source source = files.get(path);
		if (source == null) {
			throw new IOException("no file " + path);
		}
		return BedrockJson.parse(new String(source.read(), StandardCharsets.UTF_8));
	}

	/** The image file for a path without extension; only loose files, since archives hold no images. */
	Optional<Path> image(String pathWithoutExtension) {
		for (String extension : IMAGE_EXTENSIONS) {
			if (files.get(pathWithoutExtension + extension) instanceof Loose loose) {
				return Optional.of(loose.file());
			}
		}
		return Optional.empty();
	}

	/**
	 * The image for a path without extension, loose or from an archive (newer installs keep some images in
	 * their .brarchive files), with the name it was found under; empty when no pack has one.
	 */
	Optional<java.util.Map.Entry<String, byte[]>> imageBytes(String pathWithoutExtension) {
		for (String extension : IMAGE_EXTENSIONS) {
			Source source = files.get(pathWithoutExtension + extension);
			if (source != null) {
				try {
					return Optional.of(java.util.Map.entry(pathWithoutExtension + extension, source.read()));
				} catch (IOException e) {
					return Optional.empty();
				}
			}
		}
		return Optional.empty();
	}

	int size() {
		return files.size();
	}
}
