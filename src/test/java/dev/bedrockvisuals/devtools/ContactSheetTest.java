package dev.bedrockvisuals.devtools;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContactSheetTest {
	@TempDir
	Path temp;

	@Test
	void gridKeepsTheScreenshotAspectRatioAndToleratesMissingImages() throws IOException {
		Path shot = shot();
		Map<String, List<List<ContactSheet.Cell>>> sections = new LinkedHashMap<>();
		sections.put("times", List.of(
				List.of(new ContactSheet.Cell(shot, "noon lit"), new ContactSheet.Cell(temp.resolve("missing.png"), "noon vanilla")),
				List.of(new ContactSheet.Cell(shot, "night lit"))));

		List<Path> written = ContactSheet.write(sections, temp);

		assertEquals(List.of(temp.resolve("sheet-times.png")), written);
		BufferedImage result = ImageIO.read(written.get(0).toFile());
		assertEquals(2 * 960, result.getWidth());
		assertEquals(2 * 540, result.getHeight());
	}

	@Test
	void longRowsWrapAndSectionsSplitIntoPagesOfFourLines() throws IOException {
		Path shot = shot();
		List<ContactSheet.Cell> seven = new ArrayList<>();
		for (int i = 0; i < 7; i++) {
			seven.add(new ContactSheet.Cell(shot, "view " + i));
		}
		Map<String, List<List<ContactSheet.Cell>>> sections = new LinkedHashMap<>();
		sections.put("spot-ocean", List.of(seven, List.of(new ContactSheet.Cell(shot, "low sun lit"))));
		sections.put("rain", List.of(List.of(new ContactSheet.Cell(shot, "rain lit"))));

		List<Path> written = ContactSheet.write(sections, temp);

		// Seven shots wrap onto four lines and the eighth onto a fifth: two pages.
		assertEquals(List.of(temp.resolve("sheet-spot-ocean-1.png"), temp.resolve("sheet-spot-ocean-2.png"),
				temp.resolve("sheet-rain.png")), written);
		assertEquals(4 * 540, ImageIO.read(written.get(0).toFile()).getHeight());
		assertEquals(540, ImageIO.read(written.get(1).toFile()).getHeight());
	}

	private Path shot() throws IOException {
		Path shot = temp.resolve("shot.png");
		BufferedImage image = new BufferedImage(1920, 1080, BufferedImage.TYPE_INT_RGB);
		image.setRGB(960, 540, 0xFF0000);
		ImageIO.write(image, "png", shot.toFile());
		return shot;
	}
}
