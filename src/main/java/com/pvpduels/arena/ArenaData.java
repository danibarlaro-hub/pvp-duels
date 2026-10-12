package com.pvpduels.arena;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A custom arena: every block of a 64x64x64 box and the two spawn points. The box is centered on the middle of the
 * build plot (x and z from -32 to 31) and goes from {@link #MIN_Y_OFFSET} blocks under the floor line upwards, so a
 * floor at the "floor line" is where the templates put it. Spawn points are stored relative to that center.
 */
public final class ArenaData {
	public static final int SIZE = 64;
	/** Lowest layer of the box, in blocks below the floor line. */
	public static final int MIN_Y_OFFSET = -16;
	public static final int VOLUME = SIZE * SIZE * SIZE;
	private static final int VERSION = 1;

	public final String name;
	public final UUID owner;
	public final String ownerName;
	/** Which template it started from ("void", "flat" or "box"); only informative. */
	public final String template;
	public final BlockState[] blocks;
	/** x, y, z (relative to the plot center and floor line) and yaw of the spawn of player 0 and 1, or null while not set. */
	public double[] spawn0;
	public double[] spawn1;

	public ArenaData(String name, UUID owner, String ownerName, String template, BlockState[] blocks) {
		if (blocks.length != VOLUME) throw new IllegalArgumentException("an arena has exactly " + VOLUME + " blocks");
		this.name = name;
		this.owner = owner;
		this.ownerName = ownerName;
		this.template = template;
		this.blocks = blocks;
	}

	/** Position in {@link #blocks} of the block at box coordinates 0..63 (x, y from the lowest layer, z). */
	public static int index(int x, int y, int z) {
		return (y * SIZE + z) * SIZE + x;
	}

	public boolean hasBothSpawns() {
		return spawn0 != null && spawn1 != null;
	}

	public int solidBlocks() {
		int n = 0;
		for (BlockState s : blocks) if (!s.isAir()) n++;
		return n;
	}

	// ------------------------------------------------------------------ files

	public CompoundTag toTag() {
		CompoundTag root = new CompoundTag();
		root.putInt("version", VERSION);
		root.putString("name", name);
		root.putString("owner", owner == null ? "" : owner.toString());
		root.putString("ownerName", ownerName == null ? "" : ownerName);
		root.putString("template", template == null ? "" : template);
		Map<BlockState, Integer> ids = new HashMap<>();
		List<BlockState> palette = new ArrayList<>();
		int[] data = new int[VOLUME];
		for (int i = 0; i < VOLUME; i++) {
			BlockState state = blocks[i];
			Integer id = ids.get(state);
			if (id == null) {
				id = palette.size();
				ids.put(state, id);
				palette.add(state);
			}
			data[i] = id;
		}
		root.putInt("paletteSize", palette.size());
		for (int i = 0; i < palette.size(); i++) root.put("p" + i, NbtUtils.writeBlockState(palette.get(i)));
		root.putIntArray("blocks", data);
		putSpawn(root, "spawn0", spawn0);
		putSpawn(root, "spawn1", spawn1);
		return root;
	}

	private static void putSpawn(CompoundTag root, String key, double[] spawn) {
		if (spawn == null) return;
		CompoundTag tag = new CompoundTag();
		tag.putDouble("x", spawn[0]);
		tag.putDouble("y", spawn[1]);
		tag.putDouble("z", spawn[2]);
		tag.putDouble("yaw", spawn[3]);
		root.put(key, tag);
	}

	private static double[] readSpawn(CompoundTag root, String key) {
		if (!root.contains(key)) return null;
		CompoundTag tag = root.getCompoundOrEmpty(key);
		return new double[] {tag.getDoubleOr("x", 0), tag.getDoubleOr("y", 0), tag.getDoubleOr("z", 0), tag.getDoubleOr("yaw", 0)};
	}

	/** Reads an arena saved by {@link #toTag()}. Throws IllegalArgumentException when the file is not a good arena. */
	public static ArenaData fromTag(CompoundTag root) {
		int paletteSize = root.getIntOr("paletteSize", -1);
		int[] data = root.getIntArray("blocks").orElse(null);
		if (paletteSize <= 0 || data == null || data.length != VOLUME) throw new IllegalArgumentException("the arena file is damaged");
		BlockState[] palette = new BlockState[paletteSize];
		for (int i = 0; i < paletteSize; i++) {
			BlockState state = NbtUtils.readBlockState(BuiltInRegistries.BLOCK, root.getCompoundOrEmpty("p" + i));
			palette[i] = state == null ? Blocks.AIR.defaultBlockState() : state;
		}
		BlockState[] blocks = new BlockState[VOLUME];
		for (int i = 0; i < VOLUME; i++) {
			int id = data[i];
			blocks[i] = id >= 0 && id < paletteSize ? palette[id] : Blocks.AIR.defaultBlockState();
		}
		UUID owner = null;
		try {
			String text = root.getStringOr("owner", "");
			if (!text.isEmpty()) owner = UUID.fromString(text);
		} catch (IllegalArgumentException ignored) {
			// an arena without a known owner is still an arena
		}
		ArenaData arena = new ArenaData(root.getStringOr("name", "arena"), owner, root.getStringOr("ownerName", ""), root.getStringOr("template", ""), blocks);
		arena.spawn0 = readSpawn(root, "spawn0");
		arena.spawn1 = readSpawn(root, "spawn1");
		return arena;
	}
}
