package dev.bedrockvisuals.bedrock;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MaterialAtlasImageTest {
	private static final Rgba ROUGH = new Rgba(0, 0, 1, 0);

	private static TextureSet constantSet(Rgba mers, boolean subsurface) {
		return new TextureSet("blocks/test", TextureSet.Layer.NONE, new TextureSet.Layer(null, mers), subsurface, null, null);
	}

	@Test
	void slotsGetTheirTextureSetAndTheRestTheFallback() {
		Rgba metal = new Rgba(1, 0, 0.2f, 0);
		MaterialAtlasImage.Result result = MaterialAtlasImage.build(4, 4, 1,
				List.of(new MaterialAtlasImage.Slot("iron_block", 2, 0, 2, 2)),
				name -> name.equals("iron_block") ? Optional.of(constantSet(metal, false)) : Optional.empty(), ROUGH);
		int[] pixels = result.levels().getFirst();
		assertEquals(MaterialAtlasImage.argb(metal), pixels[2]);
		assertEquals(MaterialAtlasImage.argb(metal), pixels[1 * 4 + 3]);
		assertEquals(MaterialAtlasImage.argb(ROUGH), pixels[0]);
		assertEquals(MaterialAtlasImage.argb(ROUGH), pixels[2 * 4 + 2]);
		assertEquals(1, result.filledSlots());
	}

	@Test
	void paddedSlotsStartAtTheirPixelsAndExtendEdgesIntoThePadding() {
		// A 2x2 image (left column metal, right column rough) in a sprite whose pixels start at (2, 2) inside a
		// cell padded by 2 texels, as Java 26.3's atlas lays sprites out.
		Rgba metal = new Rgba(1, 0, 0, 0);
		int[] image = {MaterialAtlasImage.argb(metal), MaterialAtlasImage.argb(ROUGH),
				MaterialAtlasImage.argb(metal), MaterialAtlasImage.argb(ROUGH)};
		java.nio.file.Path path = java.nio.file.Path.of("test_mers.tga");
		TextureSet set = new TextureSet("blocks/test", TextureSet.Layer.NONE, new TextureSet.Layer(path, null), true,
				null, null);
		Rgba fallback = new Rgba(0, 1, 0, 0);
		MaterialAtlasImage.Result result = MaterialAtlasImage.build(8, 8, 1,
				List.of(new MaterialAtlasImage.Slot("iron_block", 2, 2, 2, 2, 2)), name -> Optional.of(set), fallback,
				p -> new TgaImage(2, 2, image));
		int[] pixels = result.levels().getFirst();
		int metalArgb = MaterialAtlasImage.argb(metal);
		int roughArgb = MaterialAtlasImage.argb(ROUGH);
		assertEquals(metalArgb, pixels[2 * 8 + 2]);
		assertEquals(roughArgb, pixels[2 * 8 + 3]);
		// Padding repeats the nearest edge texel.
		assertEquals(metalArgb, pixels[0]);
		assertEquals(roughArgb, pixels[5 * 8 + 5]);
		assertEquals(MaterialAtlasImage.argb(fallback), pixels[6]);
	}

	@Test
	void mipLevelsAverageTwoByTwo() {
		int[] level0 = {0xFF000000, 0xFF0000FF, 0xFF0000FF, 0xFF000000};
		List<int[]> chain = MaterialAtlasImage.mipChain(level0, 2, 2, 3);
		assertEquals(2, chain.size());
		assertEquals(0xFF000080, chain.get(1)[0]);
	}
}
