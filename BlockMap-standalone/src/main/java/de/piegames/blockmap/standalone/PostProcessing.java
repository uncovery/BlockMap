package de.piegames.blockmap.standalone;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.Set;
import java.util.stream.Collectors;

import javax.imageio.ImageIO;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.joml.Vector2i;

import de.piegames.blockmap.Region;
import de.piegames.blockmap.RegionFolder;
import de.piegames.blockmap.renderer.RegionRenderer;
import de.piegames.blockmap.renderer.RenderSettings;

/** This class contains a collection of methods that transform the rendered map into another, more accessible representation */
public class PostProcessing {

	private static Log log = LogFactory.getLog(RegionRenderer.class);

	private PostProcessing() {
	}

	public static void createTileHtml(RegionFolder world, Path outputDir, RenderSettings settings) {
		log.info("Writing HTML tiles...");
		if (world.regions.isEmpty()) {
			log.warn("The world is empty, there is nothing to do!");
			return;
		}

		Path cssFile = outputDir.resolve("tiles.css");
		// Copy css file from local resources to destination
		if (!Files.exists(cssFile)) {
			try (InputStream cssInputStream = PostProcessing.class.getResourceAsStream("tiles.css");) {
				Files.copy(cssInputStream, cssFile);
			} catch (IOException e) {
				log.error("Could not copy style sheet file", e);
				return;
			}
		}

		Set<Vector2i> allowedBlocks = world.regions.keySet().stream().filter(v -> inBounds(v.x, settings.minX, settings.maxX) && inBounds(v.y, settings.minZ,
				settings.maxZ)).collect(Collectors.toSet());
		int minX = allowedBlocks.stream().mapToInt(v -> v.x).min().getAsInt();
		int maxX = allowedBlocks.stream().mapToInt(v -> v.x).max().getAsInt();
		int minZ = allowedBlocks.stream().mapToInt(v -> v.y).min().getAsInt();
		int maxZ = allowedBlocks.stream().mapToInt(v -> v.y).max().getAsInt();

		try (Writer w = Files.newBufferedWriter(outputDir.resolve("tiles.html"));) {
			w.write("<html><head>\n");
			w.write("<link rel=\"stylesheet\" type=\"text/css\" href=\"tiles.css\"/>\n");
			w.write("</head><body>\n");
			w.write("<div style=\"height: " + (maxZ - minZ + 1) * 512 + "px\">");

			for (int z = minZ; z <= maxZ; z++) {
				for (int x = minX; x <= maxX; x++) {
					Region region = world.regions.get(new Vector2i(x, z));
					if (region != null && region.renderedPath != null && Files.exists(region.renderedPath)) {
						int top = (z - minZ) * 512, left = (x - minX) * 512;
						String title = "Region " + x + ", " + z;
						String name = "r." + x + "." + z;
						String style = "width: " + 512 + "px; height: " + 512 + "px; " +
								"position: absolute; top: " + top + "px; left: " + left + "px; " +
								"background-image: url(" + outputDir.relativize(region.renderedPath) + ")";
						w.write("<a\n" +
								"\tclass=\"tile\"\n" +
								"\tstyle=\"" + style + "\"\n" +
								"\ttitle=\"" + title + "\"\n" +
								"\tname=\"" + name + "\"\n" +
								"\thref=\"" + outputDir.relativize(region.renderedPath) + "\"\n" +
								">&nbsp;</a>");
					}
				}
			}

			w.write("</div>\n");
			w.write("<p class=\"notes\">");
			w.write("Page rendered at " + new Date().toString());
			w.write("</p>\n");
			w.write("</body></html>");
		} catch (IOException e) {
			log.error("Could not write html file", e);
		}
	}

	public static void createBigImage(RegionFolder world, Path outputDir, RenderSettings settings) {
		log.info("Creating big image...");
		if (world.regions.isEmpty()) {
			log.warn("The world is empty, there is nothing to do!");
			return;
		}

		/** The bounds of the rendered files, in region coordinates */
		Set<Vector2i> allowedBlocks = world.regions.keySet().stream().filter(v -> inBounds(v.x, settings.minX, settings.maxX) && inBounds(v.y, settings.minZ,
				settings.maxZ)).collect(Collectors.toSet());
		int minX = allowedBlocks.stream().mapToInt(v -> v.x).min().getAsInt();
		int maxX = allowedBlocks.stream().mapToInt(v -> v.x).max().getAsInt();
		int minZ = allowedBlocks.stream().mapToInt(v -> v.y).min().getAsInt();
		int maxZ = allowedBlocks.stream().mapToInt(v -> v.y).max().getAsInt();

		/** The bounds of the selected area intersected with the rendered area. */
		int minPixelX = (minX << 9) < settings.minX ? settings.minX : (minX << 9);
		int maxPixelX = (maxX << 9) + 512 > settings.maxX ? settings.maxX : (maxX << 9) + 512;
		int minPixelZ = (minZ << 9) < settings.minZ ? settings.minZ : (minZ << 9);
		int maxPixelZ = (maxZ << 9) + 512 > settings.maxZ ? settings.maxZ : (maxZ << 9) + 512;

		int width = maxPixelX - minPixelX;
		int height = maxPixelZ - minPixelZ;
		log.debug("Dimension: " + width + ", " + height);
		BufferedImage bigImage = null;
		try {
			bigImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		} catch (Throwable t) {
			log.error("Could not create image, is it too big?", t);
			return;
		}

		for (Region r : world.regions.values()) {
			if (r.renderedPath == null)
				continue;
			int x = r.position.x();
			int z = r.position.y();
			BufferedImage region = null;
			try {
				region = ImageIO.read(Files.newInputStream(r.renderedPath));
			} catch (IOException e) {
				log.warn("Could not load image " + r.renderedPath.toAbsolutePath(), e);
				continue;
			}
			bigImage.createGraphics().drawImage(region, x * 512 - minPixelX, z * 512 - minPixelZ, null);
			log.debug("Region " + x + ", " + z + " drawn to " + (x * 512 - minPixelX) + ", " + (z * 512 - minPixelZ));
		}
		try {
			ImageIO.write(bigImage, "png", new File(outputDir + "/big.png"));
		} catch (IOException e) {
			log.error("Could not write big image to " + outputDir + "/big.png", e);
		}
	}

	/** Test if the given region file contains blocks that should be rendered. The bounds are given in world space. */
	public static boolean inBounds(int region, int min, int max) {
		return (min >> 9) >= region && region <= (max << 9);
	}
}
