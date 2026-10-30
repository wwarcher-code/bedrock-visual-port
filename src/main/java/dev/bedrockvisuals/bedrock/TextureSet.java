package dev.bedrockvisuals.bedrock;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * textures/**&#47;*.texture_set.json ("minecraft:texture_set"): the PBR layers for one texture.
 *
 * <p>Each layer is either an image next to the texture set or a constant. The MERS layer packs metalness,
 * emissive, roughness and (optionally) subsurface into R, G, B and A.
 *
 * @param name       path relative to textures/blocks (or textures/), without extension
 * @param hasSubsurface true when the material layer is MERS; false for the older 3-channel MER
 * @param normal     a normal map image, or null
 * @param heightmap  a height map image, or null; only used when there is no normal map
 */
public record TextureSet(String name, Layer color, Layer material, boolean hasSubsurface, Path normal, Path heightmap) {

	/** Either an image file or a uniform value for the whole texture. */
	public record Layer(Path image, Rgba constant) {
		static final Layer NONE = new Layer(null, null);

		public boolean isPresent() {
			return image != null || constant != null;
		}
	}

	private static final List<String> IMAGE_EXTENSIONS = List.of(".png", ".tga", ".jpg", ".jpeg");

	static final String KEY = "minecraft:texture_set";

	static TextureSet parse(String name, Path folder, JsonObject set) {
		boolean mers = set.has("metalness_emissive_roughness_subsurface");
		String materialKey = mers ? "metalness_emissive_roughness_subsurface" : "metalness_emissive_roughness";
		return new TextureSet(
				name,
				layer(set.get("color"), folder, false),
				layer(set.get(materialKey), folder, !mers),
				mers,
				image(set.get("normal"), folder),
				image(set.get("heightmap"), folder));
	}

	/** @param noSubsurface a 3-channel MER value, whose missing fourth channel means no subsurface */
	private static Layer layer(JsonElement value, Path folder, boolean noSubsurface) {
		if (value == null) {
			return Layer.NONE;
		}
		if (value.isJsonArray() || value.getAsString().startsWith("#")) {
			Rgba constant = BedrockJson.color(value);
			if (noSubsurface) {
				constant = new Rgba(constant.r(), constant.g(), constant.b(), 0);
			}
			return new Layer(null, constant);
		}
		return new Layer(image(value, folder), null);
	}

	private static Path image(JsonElement value, Path folder) {
		if (value == null) {
			return null;
		}
		String base = value.getAsString();
		for (String extension : IMAGE_EXTENSIONS) {
			Path file = folder.resolve(base + extension);
			if (Files.isRegularFile(file)) {
				return file;
			}
		}
		throw new JsonParseException("no image for \"" + base + "\" in " + folder);
	}
}
