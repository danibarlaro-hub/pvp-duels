package com.pvpduels.worldgen;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A box of blocks written as plain text ("minecraft:oak_stairs[facing=north,half=bottom]"). It knows nothing about
 * Minecraft, so the same island can be drawn as a picture on any computer and then placed into a real world.
 * Coordinates are "local": the village is around (0, y, 0) and the grass of the village is at {@link IslandGen#GY}.
 */
public final class BlockGrid {
	public final int minX, minY, minZ, sizeX, sizeY, sizeZ;
	private final short[] cells;
	private final List<String> palette = new ArrayList<>();
	private final Map<String, Integer> index = new HashMap<>();

	public BlockGrid(int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ) {
		this.minX = minX;
		this.minY = minY;
		this.minZ = minZ;
		this.sizeX = sizeX;
		this.sizeY = sizeY;
		this.sizeZ = sizeZ;
		this.cells = new short[sizeX * sizeY * sizeZ];
		palette.add(null); // index 0 = nothing (air)
	}

	public boolean inside(int x, int y, int z) {
		return x >= minX && x < minX + sizeX && y >= minY && y < minY + sizeY && z >= minZ && z < minZ + sizeZ;
	}

	private int at(int x, int y, int z) {
		return ((y - minY) * sizeZ + (z - minZ)) * sizeX + (x - minX);
	}

	/** Puts a block there. "minecraft:air" (or null) clears the cell. Cells outside the box are ignored. */
	public void set(int x, int y, int z, String block) {
		if (!inside(x, y, z)) return;
		if (block == null || block.equals("minecraft:air") || block.equals("air")) {
			cells[at(x, y, z)] = 0;
			return;
		}
		Integer i = index.get(block);
		if (i == null) {
			i = palette.size();
			palette.add(block);
			index.put(block, i);
		}
		cells[at(x, y, z)] = (short) (int) i;
	}

	/** The block there, or null when it is air (or outside the box). */
	public String get(int x, int y, int z) {
		if (!inside(x, y, z)) return null;
		return palette.get(cells[at(x, y, z)]);
	}

	public boolean isAir(int x, int y, int z) {
		return get(x, y, z) == null;
	}

	/** True when the block there is exactly this one (with its properties). */
	public boolean is(int x, int y, int z, String block) {
		return block.equals(get(x, y, z));
	}

	/** True when the block id there (without properties) is this one, e.g. "minecraft:grass_block". */
	public boolean isId(int x, int y, int z, String id) {
		String b = get(x, y, z);
		if (b == null) return false;
		int br = b.indexOf('[');
		return (br < 0 ? b : b.substring(0, br)).equals(id);
	}

	public void box(int x1, int y1, int z1, int x2, int y2, int z2, String block) {
		for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++)
			for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++)
				for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) set(x, y, z, block);
	}

	public int paletteSize() {
		return palette.size();
	}

	public String paletteAt(int i) {
		return palette.get(i);
	}

	public int raw(int x, int y, int z) {
		return inside(x, y, z) ? cells[at(x, y, z)] : 0;
	}

	public long count() {
		long n = 0;
		for (short c : cells) if (c != 0) n++;
		return n;
	}

	/** Number of blocks of each kind (id without properties), for the report. */
	public Map<String, Integer> census() {
		Map<String, Integer> out = new HashMap<>();
		for (short c : cells) {
			if (c == 0) continue;
			String b = palette.get(c);
			int br = b.indexOf('[');
			out.merge(br < 0 ? b : b.substring(0, br), 1, Integer::sum);
		}
		return out;
	}
}
