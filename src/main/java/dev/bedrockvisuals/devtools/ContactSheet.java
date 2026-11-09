package dev.bedrockvisuals.devtools;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * Stitches a capture's screenshots into labelled sheets, one set per section of the capture, sized to be read
 * when sent: two shots a line at half the screen's width (960 pixels for 1920), four lines a page, so a page is
 * under about 2000 pixels each way and isn't shrunk on the way. (One sheet of every shot, nine across, came
 * out a fifth of the screen per shot and too small to read.) A capture row of more than two shots wraps onto
 * further lines; a missing image leaves its cell dark.
 */
final class ContactSheet {
	private static final int COLUMNS = 2;
	private static final int TILE_WIDTH = 960;
	private static final int LINES_PER_PAGE = 4;
	private static final int LABEL_HEIGHT = 26;

	/** One cell: the file to load and the label drawn over it. */
	record Cell(Path image, String label) {
	}

	private ContactSheet() {
	}

	/**
	 * Writes each section's pages as sheet-section-1.png and so on into the folder.
	 *
	 * @return the files written, in order
	 */
	static List<Path> write(Map<String, List<List<Cell>>> sections, Path folder) throws IOException {
		List<Path> written = new ArrayList<>();
		for (Map.Entry<String, List<List<Cell>>> section : sections.entrySet()) {
			List<List<Cell>> lines = new ArrayList<>();
			for (List<Cell> row : section.getValue()) {
				for (int i = 0; i < row.size(); i += COLUMNS) {
					lines.add(row.subList(i, Math.min(row.size(), i + COLUMNS)));
				}
			}
			if (lines.isEmpty()) {
				continue;
			}
			int tileHeight = tileHeight(lines);
			int pages = (lines.size() + LINES_PER_PAGE - 1) / LINES_PER_PAGE;
			for (int page = 0; page < pages; page++) {
				List<List<Cell>> onPage = lines.subList(page * LINES_PER_PAGE,
						Math.min(lines.size(), (page + 1) * LINES_PER_PAGE));
				Path output = folder.resolve("sheet-" + section.getKey() + (pages > 1 ? "-" + (page + 1) : "") + ".png");
				writePage(onPage, tileHeight, output);
				written.add(output);
			}
		}
		return written;
	}

	private static void writePage(List<List<Cell>> lines, int tileHeight, Path output) throws IOException {
		BufferedImage sheet = new BufferedImage(COLUMNS * TILE_WIDTH, lines.size() * tileHeight,
				BufferedImage.TYPE_INT_RGB);
		Graphics2D g = sheet.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setColor(new Color(24, 24, 24));
		g.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
		g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
		for (int line = 0; line < lines.size(); line++) {
			for (int column = 0; column < lines.get(line).size(); column++) {
				Cell cell = lines.get(line).get(column);
				int x = column * TILE_WIDTH;
				int y = line * tileHeight;
				if (Files.isRegularFile(cell.image())) {
					BufferedImage image = ImageIO.read(cell.image().toFile());
					g.drawImage(image, x, y, TILE_WIDTH, tileHeight, null);
				}
				g.setColor(new Color(0, 0, 0, 170));
				g.fillRect(x, y, g.getFontMetrics().stringWidth(cell.label()) + 14, LABEL_HEIGHT);
				g.setColor(Color.WHITE);
				g.drawString(cell.label(), x + 7, y + 19);
				g.setColor(new Color(60, 60, 60));
				g.drawRect(x, y, TILE_WIDTH - 1, tileHeight - 1);
			}
		}
		g.dispose();
		ImageIO.write(sheet, "png", output.toFile());
	}

	/** Keeps the screenshots' aspect ratio, taken from the first one that exists. */
	private static int tileHeight(List<List<Cell>> lines) throws IOException {
		for (List<Cell> line : lines) {
			for (Cell cell : line) {
				if (Files.isRegularFile(cell.image())) {
					BufferedImage image = ImageIO.read(cell.image().toFile());
					return Math.max(1, TILE_WIDTH * image.getHeight() / image.getWidth());
				}
			}
		}
		return TILE_WIDTH * 9 / 16;
	}
}
