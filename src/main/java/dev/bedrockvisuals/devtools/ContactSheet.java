package dev.bedrockvisuals.devtools;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Stitches a capture's screenshots into one labelled grid, so a whole test round is a single image to send.
 * Rows are given by the caller; a missing image leaves its cell dark.
 */
final class ContactSheet {
	private static final int TILE_WIDTH = 480;
	private static final int LABEL_HEIGHT = 22;

	/** One cell: the file to load and the label drawn over it. */
	record Cell(Path image, String label) {
	}

	private ContactSheet() {
	}

	static void write(List<List<Cell>> rows, Path output) throws IOException {
		int columns = rows.stream().mapToInt(List::size).max().orElse(1);
		int tileHeight = tileHeight(rows);
		BufferedImage sheet = new BufferedImage(columns * TILE_WIDTH, rows.size() * tileHeight, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = sheet.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setColor(new Color(24, 24, 24));
		g.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
		g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));

		for (int row = 0; row < rows.size(); row++) {
			for (int column = 0; column < rows.get(row).size(); column++) {
				Cell cell = rows.get(row).get(column);
				int x = column * TILE_WIDTH;
				int y = row * tileHeight;
				if (Files.isRegularFile(cell.image())) {
					BufferedImage image = ImageIO.read(cell.image().toFile());
					g.drawImage(image, x, y, TILE_WIDTH, tileHeight, null);
				}
				g.setColor(new Color(0, 0, 0, 170));
				g.fillRect(x, y, g.getFontMetrics().stringWidth(cell.label()) + 12, LABEL_HEIGHT);
				g.setColor(Color.WHITE);
				g.drawString(cell.label(), x + 6, y + 16);
				g.setColor(new Color(60, 60, 60));
				g.drawRect(x, y, TILE_WIDTH - 1, tileHeight - 1);
			}
		}
		g.dispose();
		ImageIO.write(sheet, "png", output.toFile());
	}

	/** Keeps the screenshots' aspect ratio, taken from the first one that exists. */
	private static int tileHeight(List<List<Cell>> rows) throws IOException {
		for (List<Cell> row : rows) {
			for (Cell cell : row) {
				if (Files.isRegularFile(cell.image())) {
					BufferedImage image = ImageIO.read(cell.image().toFile());
					return Math.max(1, TILE_WIDTH * image.getHeight() / image.getWidth());
				}
			}
		}
		return TILE_WIDTH * 9 / 16;
	}
}
