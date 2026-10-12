import com.pvpduels.worldgen.BlockGrid;
import com.pvpduels.worldgen.IslandGen;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import javax.imageio.ImageIO;

/** Draws the lobby island as pictures (no Minecraft needed): java IslandPreview <out-dir> [seed] [block-ids.txt] */
public class IslandPreview {
	static BlockGrid grid;
	static final Map<String, Integer> COLORS = new HashMap<>();

	static void c(String ids, int rgb) {
		for (String id : ids.split(",")) COLORS.put("minecraft:" + id.trim(), rgb);
	}

	static {
		c("grass_block", 0x5fa83a); c("dirt,coarse_dirt,rooted_dirt", 0x8a6a45); c("podzol", 0x6b5230); c("moss_block,moss_carpet", 0x4f8a2c);
		c("stone", 0x8a8a8a); c("andesite", 0x9a9a98); c("tuff", 0x6d6d62); c("cobblestone", 0x777777); c("mossy_cobblestone", 0x667a5a);
		c("deepslate,cobbled_deepslate,polished_deepslate,deepslate_bricks,deepslate_tiles", 0x4a4a4f);
		c("water", 0x3b6fe0); c("clay", 0x9aa3b0); c("gravel", 0x857f7c); c("dirt_path", 0xa08a52);
		c("oak_leaves", 0x3f7f25); c("birch_leaves", 0x6a9a3c); c("spruce_leaves", 0x2c5a35); c("azalea_leaves", 0x55912f); c("flowering_azalea_leaves", 0x80a04a);
		c("oak_log,oak_planks,oak_fence,oak_slab,oak_door,oak_pressure_plate,oak_fence_gate,barrel", 0x9d7a45);
		c("spruce_log,spruce_planks,spruce_slab,spruce_stairs,stripped_spruce_log", 0x6b4d2c); c("dark_oak_log,dark_oak_planks,dark_oak_slab,dark_oak_stairs,stripped_dark_oak_log", 0x44301a);
		c("birch_log,birch_planks,stripped_birch_log", 0xd6c58a);
		c("stone_bricks,stone_brick_slab,stone_brick_stairs", 0x7d7d7d); c("mossy_stone_bricks,mossy_stone_brick_slab,mossy_stone_brick_stairs", 0x6b8460);
		c("cracked_stone_bricks", 0x707070); c("polished_andesite", 0xa5a5a0); c("lime_concrete", 0x70d020); c("verdant_froglight", 0xa6e58f);
		c("oxidized_cut_copper,oxidized_cut_copper_stairs,oxidized_cut_copper_slab", 0x4fb59b);
		c("glass_pane,lime_stained_glass_pane", 0x8fd0c8); c("lantern", 0xffc14d); c("lime_wool", 0x7fd01f); c("white_wool", 0xe8e8e8);
		c("hay_block", 0xc8a41a); c("farmland", 0x6b4a2a); c("wheat", 0xc9b04a); c("carrots", 0xe08a2a); c("potatoes", 0x9ab84a);
		c("short_grass,tall_grass,fern,large_fern", 0x58a030); c("lily_pad", 0x2f7a2f); c("sugar_cane", 0x8bc864);
		c("dandelion", 0xf3de2c); c("poppy", 0xd22b2b); c("azure_bluet", 0xe6eef0); c("oxeye_daisy", 0xf2f2f2); c("cornflower", 0x4f6ed8);
		c("lily_of_the_valley", 0xf5f5f5); c("allium", 0xb06ad8); c("hanging_roots", 0x9b7a5a);
		c("green_bed", 0x4a8a3a); c("crafting_table", 0x8a5d33); c("furnace", 0x707070); c("chest", 0x9a6b2e); c("green_carpet", 0x4f7f2f);
		c("composter", 0x7a5a33); c("azalea", 0x6a9a3a); c("flowering_azalea", 0xb07ab0);
	}

	static final Set<String> THIN = Set.of("short_grass", "tall_grass", "fern", "large_fern", "dandelion", "poppy", "azure_bluet", "oxeye_daisy", "cornflower",
		"lily_of_the_valley", "allium", "lily_pad", "sugar_cane", "moss_carpet", "green_carpet", "wheat", "carrots", "potatoes", "hanging_roots", "oak_pressure_plate");
	static final Set<String> SLENDER = Set.of("oak_fence", "glass_pane", "lime_stained_glass_pane", "lantern", "oak_fence_gate");

	static String id(String b) {
		int i = b.indexOf('[');
		String s = i < 0 ? b : b.substring(0, i);
		return s;
	}

	static String shortId(String b) {
		return id(b).replace("minecraft:", "");
	}

	static int color(String b) {
		Integer v = COLORS.get(id(b));
		if (v != null) return v;
		return 0xff00ff;
	}

	static boolean opaque(String b) {
		if (b == null) return false;
		String s = shortId(b);
		if (THIN.contains(s) || SLENDER.contains(s)) return false;
		if (s.equals("water")) return false;
		return true;
	}

	static int shade(int rgb, double f, int jitter) {
		int r = (int) Math.max(0, Math.min(255, ((rgb >> 16) & 255) * f + jitter));
		int g = (int) Math.max(0, Math.min(255, ((rgb >> 8) & 255) * f + jitter));
		int b = (int) Math.max(0, Math.min(255, (rgb & 255) * f + jitter));
		return (r << 16) | (g << 8) | b;
	}

	public static void main(String[] args) throws Exception {
		System.setProperty("java.awt.headless", "true");
		File out = new File(args[0]);
		out.mkdirs();
		long seed = args.length > 1 ? Long.parseLong(args[1]) : 1234L;
		long t0 = System.currentTimeMillis();
		IslandGen ig = new IslandGen(seed).build();
		grid = ig.grid;
		System.out.println("generated in " + (System.currentTimeMillis() - t0) + " ms, blocks: " + grid.count() + ", palette: " + grid.paletteSize());
		Map<String, Integer> census = grid.census();
		census.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(25).forEach(e -> System.out.println("  " + e.getKey() + " " + e.getValue()));
		if (args.length > 2) {
			Set<String> known = new HashSet<>(Files.readAllLines(Path.of(args[2])));
			Set<String> unknown = new TreeSet<>();
			for (String id : census.keySet()) if (!known.contains(id.replace("minecraft:", ""))) unknown.add(id);
			System.out.println("UNKNOWN BLOCK IDS: " + unknown);
		}
		Set<String> nocolor = new TreeSet<>();
		for (String id : census.keySet()) if (!COLORS.containsKey(id)) nocolor.add(id);
		System.out.println("no preview color: " + nocolor);

		iso(new File(out, "iso-se.png"), 6, 0, 0, 0, 1, false);
		iso(new File(out, "iso-village.png"), 9, -44, -30, 44, 46, true);
		iso(new File(out, "iso-nw.png"), 6, 0, 0, 0, 1, false, true);
		top(new File(out, "top.png"), 7);
		section(new File(out, "section.png"), 8);
	}

	static void iso(File file, int s, int x1, int z1, int x2, int z2, boolean crop) throws Exception {
		iso(file, s, x1, z1, x2, z2, crop, false);
	}

	/** Isometric picture; looking from the south-east (or the north-west). */
	static void iso(File file, int s, int x1, int z1, int x2, int z2, boolean crop, boolean flip) throws Exception {
		int minX = crop ? x1 : grid.minX, maxX = crop ? x2 : grid.minX + grid.sizeX - 1;
		int minZ = crop ? z1 : grid.minZ, maxZ = crop ? z2 : grid.minZ + grid.sizeZ - 1;
		double cw = s * 0.866, ch = s * 0.5;
		int spanXZ = (maxX - minX) + (maxZ - minZ);
		int w = (int) ((spanXZ + 4) * cw) + 40;
		int yTop = crop ? IslandGen.GY + 24 : grid.minY + grid.sizeY;
		int yBot = crop ? IslandGen.GY - 6 : 0;
		int base = (int) ((yTop - yBot) * s + spanXZ * ch / 2 + 30);
		int h = base + (int) (spanXZ * ch / 2) + 30;
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g2 = img.createGraphics();
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
		g2.setColor(new Color(0xdfeaf7));
		g2.fillRect(0, 0, w, h);
		for (int d = 0; d <= (maxX - minX) + (maxZ - minZ); d++) {
			for (int xi = 0; xi <= maxX - minX; xi++) {
				int zi = d - xi;
				if (zi < 0 || zi > maxZ - minZ) continue;
				int x = flip ? maxX - xi : minX + xi;
				int z = flip ? maxZ - zi : minZ + zi;
				for (int y = yBot; y < yTop; y++) {
					String b = grid.get(x, y, z);
					if (b == null) continue;
					int dx = flip ? -1 : 1;
					boolean top = !opaque(grid.get(x, y + 1, z));
					boolean right = !opaque(grid.get(x + dx, y, z));
					boolean left = !opaque(grid.get(x, y, z + dx));
					if (!top && !right && !left) continue;
					String sid = shortId(b);
					double sc = 1, hh = 1;
					if (THIN.contains(sid)) { sc = 0.5; hh = sid.endsWith("carpet") || sid.equals("lily_pad") || sid.equals("oak_pressure_plate") ? 0.12 : 0.55; }
					else if (SLENDER.contains(sid)) { sc = 0.4; hh = 1; }
					else if (sid.endsWith("_slab")) hh = 0.5;
					int jit = (int) (((x * 73856093L) ^ (y * 19349663L) ^ (z * 83492791L)) % 9) - 4;
					int rgb = color(b);
					double u0 = (1 - sc) / 2;
					double ax = (flip ? -(x - (minX + maxX) / 2.0) : (x - (minX + maxX) / 2.0));
					double az = (flip ? -(z - (minZ + maxZ) / 2.0) : (z - (minZ + maxZ) / 2.0));
					int px = (int) (w / 2 + (ax - az) * cw);
					int py = (int) (base - (y - yBot) * s + (ax + az) * ch);
					drawCube(g2, px, py, s, cw, ch, rgb, jit, top, right, left, sc, hh);
				}
			}
		}
		g2.dispose();
		ImageIO.write(img, "png", file);
		System.out.println("wrote " + file + " " + w + "x" + h);
	}

	static void drawCube(Graphics2D g, int px, int py, int s, double cw, double ch, int rgb, int jit, boolean top, boolean right, boolean left, double sc, double hh) {
		// (px,py) = the bottom front corner of the cell's footprint centre; scaled cubes are centred on the cell
		double wx = cw * sc, wz = cw * sc, dh = ch * sc, h = s * hh;
		if (top) {
			int[] xs = {px, (int) (px + wx), px, (int) (px - wx)};
			int[] ys = {(int) (py - h - dh), (int) (py - h), (int) (py - h + dh), (int) (py - h)};
			g.setColor(new Color(shade(rgb, 1.0, jit)));
			g.fillPolygon(xs, ys, 4);
		}
		if (left) {
			int[] xs = {px, px, (int) (px - wx), (int) (px - wx)};
			int[] ys = {(int) (py - h + dh), (int) (py + dh), (int) py, (int) (py - h)};
			g.setColor(new Color(shade(rgb, 0.62, jit)));
			g.fillPolygon(xs, ys, 4);
		}
		if (right) {
			int[] xs = {px, px, (int) (px + wx), (int) (px + wx)};
			int[] ys = {(int) (py - h + dh), (int) (py + dh), (int) py, (int) (py - h)};
			g.setColor(new Color(shade(rgb, 0.8, jit)));
			g.fillPolygon(xs, ys, 4);
		}
	}

	static void top(File file, int s) throws Exception {
		int w = grid.sizeX * s, h = grid.sizeZ * s;
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g2 = img.createGraphics();
		g2.setColor(new Color(0x1d2a3a));
		g2.fillRect(0, 0, w, h);
		for (int x = grid.minX; x < grid.minX + grid.sizeX; x++) {
			for (int z = grid.minZ; z < grid.minZ + grid.sizeZ; z++) {
				int y = grid.minY + grid.sizeY - 1;
				while (y >= 0 && grid.isAir(x, y, z)) y--;
				if (y < 0) continue;
				String b = grid.get(x, y, z);
				int ny = y;
				for (int yy = y; yy > 0 && THIN.contains(shortId(b)); yy--) { if (grid.get(x, yy - 1, z) != null) { b = grid.get(x, yy - 1, z); ny = yy - 1; break; } }
				int west = heightAt(x - 1, z), north = heightAt(x, z - 1);
				double light = 1 + 0.07 * ((y - west) + (y - north));
				g2.setColor(new Color(shade(color(b), Math.max(0.55, Math.min(1.3, light)), 0)));
				g2.fillRect((x - grid.minX) * s, (z - grid.minZ) * s, s, s);
			}
		}
		g2.dispose();
		ImageIO.write(img, "png", file);
		System.out.println("wrote " + file);
	}

	static int heightAt(int x, int z) {
		if (!grid.inside(x, 0, z)) return 0;
		int y = grid.minY + grid.sizeY - 1;
		while (y >= 0 && grid.isAir(x, y, z)) y--;
		return Math.max(y, 0);
	}

	/** The island cut in half along the z = 0 line, seen from the south. */
	static void section(File file, int s) throws Exception {
		int w = grid.sizeX * s, h = grid.sizeY * s;
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g2 = img.createGraphics();
		g2.setColor(new Color(0xdfeaf7));
		g2.fillRect(0, 0, w, h);
		for (int x = grid.minX; x < grid.minX + grid.sizeX; x++) {
			for (int y = 0; y < grid.sizeY; y++) {
				String b = grid.get(x, y, 0);
				if (b == null) continue;
				g2.setColor(new Color(shade(color(b), 1.0, 0)));
				g2.fillRect((x - grid.minX) * s, h - (y + 1) * s, s, s);
			}
		}
		g2.dispose();
		ImageIO.write(img, "png", file);
		System.out.println("wrote " + file);
	}
}
