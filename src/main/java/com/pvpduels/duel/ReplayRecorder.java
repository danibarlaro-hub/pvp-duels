package com.pvpduels.duel;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Writes down a 1v1 duel while it is fought: on every tick where both players are, how they look and what they hold. The duel asks it to
 * {@link #sample} once per tick and to note the hits and the rounds; when the duel is decided {@link #finish} gives the data to keep.
 */
final class ReplayRecorder {
	private static final EquipmentSlot[] SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND};

	private final Duel duel;
	private final ReplayData data = new ReplayData();
	private final RegistryOps<JsonElement> ops;
	private final Map<String, Integer> paletteIndex = new HashMap<>();
	/** The last stack seen in each slot and its number, so a stack is only turned into text when it changes. */
	private final ItemStack[][] lastStack = new ItemStack[2][SLOTS.length];
	private final int[][] lastIndex = new int[2][SLOTS.length];

	ReplayRecorder(Duel duel) {
		this.duel = duel;
		this.ops = duel.server().registryAccess().createSerializationContext(JsonOps.INSTANCE);
		data.id = ReplayStore.newId();
		data.created = System.currentTimeMillis();
		for (int i = 0; i < 2; i++) {
			data.names[i] = duel.names[i];
			data.uuids[i] = duel.ids[i];
			java.util.Arrays.fill(lastStack[i], ItemStack.EMPTY);
		}
		data.config = duel.config;
		double[] spawn = duel.space.spawn(0);
		data.refX = duel.space.centerX();
		data.refZ = duel.space.centerZ();
		data.refY = spawn[1];
		paletteIndex.put("", 0);
	}

	String id() {
		return data.id;
	}

	int frames() {
		return data.frames;
	}

	private int indexOf(ItemStack stack) {
		if (stack.isEmpty()) return 0;
		ItemStack one = stack.copy();
		one.setCount(1);
		String text = ItemStack.OPTIONAL_CODEC.encodeStart(ops, one).result().map(JsonElement::toString).orElse("");
		Integer known = paletteIndex.get(text);
		if (known != null) return known;
		if (data.palette.size() >= Short.MAX_VALUE) return 0;
		int index = data.palette.size();
		data.palette.add(text);
		paletteIndex.put(text, index);
		return index;
	}

	/** Called once per tick while the duel is not decided yet. */
	void sample() {
		if (data.frames >= ReplayData.MAX_FRAMES) {
			data.truncated = true;
			return;
		}
		int f = data.frames;
		data.ensure(f + 1);
		for (int i = 0; i < 2; i++) {
			ServerPlayer p = duel.player(i);
			if (p == null || duel.isRestored(i)) {
				// nobody there any more: the last picture stays
				if (f > 0) copyFrame(i, f - 1, f);
				continue;
			}
			data.x[i][f] = (float) (p.getX() - data.refX);
			data.y[i][f] = (float) (p.getY() - data.refY);
			data.z[i][f] = (float) (p.getZ() - data.refZ);
			data.yaw[i][f] = ReplayData.toByte(p.getYRot());
			data.pitch[i][f] = ReplayData.toByte(p.getXRot());
			data.pose[i][f] = (byte) p.getPose().ordinal();
			int flags = 0;
			if (p.swinging) flags |= ReplayData.FLAG_SWING;
			if (p.isSprinting()) flags |= ReplayData.FLAG_SPRINT;
			if (p.isUsingItem()) flags |= ReplayData.FLAG_USING;
			if (p.hurtTime >= 9) flags |= ReplayData.FLAG_HURT;
			data.flags[i][f] = (byte) flags;
			data.health[i][f] = (byte) Math.max(0, Math.min(255, Math.round((p.getHealth() + p.getAbsorptionAmount()) * 2f)));
			for (int s = 0; s < SLOTS.length; s++) {
				ItemStack now = p.getItemBySlot(SLOTS[s]);
				if (!ItemStack.matches(now, lastStack[i][s])) {
					lastStack[i][s] = now.copy();
					lastIndex[i][s] = indexOf(now);
				}
				data.equip[i][s][f] = (short) lastIndex[i][s];
			}
		}
		data.frames++;
	}

	private void copyFrame(int i, int from, int to) {
		data.x[i][to] = data.x[i][from];
		data.y[i][to] = data.y[i][from];
		data.z[i][to] = data.z[i][from];
		data.yaw[i][to] = data.yaw[i][from];
		data.pitch[i][to] = data.pitch[i][from];
		data.pose[i][to] = data.pose[i][from];
		data.flags[i][to] = 0;
		data.health[i][to] = data.health[i][from];
		for (int s = 0; s < SLOTS.length; s++) data.equip[i][s][to] = data.equip[i][s][from];
	}

	void roundStart(int round) {
		data.events.add(new ReplayData.Event(data.frames, ReplayData.EV_ROUND_START, round, 0, 0f));
	}

	/** {@code victim} is the duelist who was hurt. */
	void hit(int victim, float amount) {
		data.events.add(new ReplayData.Event(data.frames, ReplayData.EV_HIT, victim, 0, amount));
	}

	void roundEnd(int winnerIdx, int points0, int points1) {
		data.events.add(new ReplayData.Event(data.frames, ReplayData.EV_ROUND_END, winnerIdx, points0 * 1000 + points1, 0f));
	}

	/** The duel is decided: the data to keep. */
	ReplayData finish(int winner, int[] points) {
		data.winner = winner;
		data.points[0] = points[0];
		data.points[1] = points[1];
		return data;
	}

	UUID owner(int i) {
		return duel.ids[i];
	}
}
