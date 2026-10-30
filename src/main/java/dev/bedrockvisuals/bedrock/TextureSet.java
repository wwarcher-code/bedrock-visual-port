package dev.bedrockvisuals.bedrock;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;

/**
 * textures/**&#47;*.texture_set.json ("minecraft:texture_set"): the PBR layers for one texture.
 *
 * <p>Each layer is either an image in the texture set's folder (in any pack of the stack) or a constant. The MERS layer packs metalness,
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

	static final String KEY = "minecraft:texture_set";

	/** @param images finds the image for a name in the texture set's folder, without extension */
	static TextureSet parse(String name, Function<String, Optional<Path>> images, JsonObject set) {
		boolean mers = set.has("metalness_emissive_roughness_subsurface");
		String materialKey = mers ? "metalness_emissive_roughness_subsurface" : "metalness_emissive_roughness";
		return new TextureSet(
				name,
				layer(set.get("color"), images, false),
				layer(set.get(materialKey), images, !mers),
				mers,
				image(set.get("normal"), images),
				image(set.get("heightmap"), images));
	}

	/** @param noSubsurface a 3-channel MER value, whose missing fourth channel means no subsurface */
	private static Layer layer(JsonElement value, Function<String, Optional<Path>> images, boolean noSubsurface) {
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
		return new Layer(image(value, images), null);
	}

	private static Path image(JsonElement value, Function<String, Optional<Path>> images) {
		if (value == null) {
			return null;
		}
		String base = value.getAsString();
		return images.apply(base).orElseThrow(() -> new JsonParseException("no image for \"" + base + "\""));
	}
}
