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
	void mipLevelsAverageTwoByTwo() {
		int[] level0 = {0xFF000000, 0xFF0000FF, 0xFF0000FF, 0xFF000000};
		List<int[]> chain = MaterialAtlasImage.mipChain(level0, 2, 2, 3);
		assertEquals(2, chain.size());
		assertEquals(0xFF000080, chain.get(1)[0]);
	}
}
