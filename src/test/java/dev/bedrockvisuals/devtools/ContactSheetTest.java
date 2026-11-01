package dev.bedrockvisuals.devtools;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContactSheetTest {
	@TempDir
	Path temp;

	@Test
	void gridKeepsTheScreenshotAspectRatioAndToleratesMissingImages() throws IOException {
		Path shot = temp.resolve("shot.png");
		BufferedImage image = new BufferedImage(1920, 1080, BufferedImage.TYPE_INT_RGB);
		image.setRGB(960, 540, 0xFF0000);
		ImageIO.write(image, "png", shot.toFile());

		Path sheet = temp.resolve("sheet.png");
		ContactSheet.write(List.of(
				List.of(new ContactSheet.Cell(shot, "noon lit"), new ContactSheet.Cell(temp.resolve("missing.png"), "noon vanilla")),
				List.of(new ContactSheet.Cell(shot, "night lit"))), sheet);

		BufferedImage result = ImageIO.read(sheet.toFile());
		assertEquals(2 * 480, result.getWidth());
		assertEquals(2 * 270, result.getHeight());
	}
}
