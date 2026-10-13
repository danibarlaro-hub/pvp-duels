package com.pvpduels.duel;

import com.pvpduels.effects.Effects;
import com.pvpduels.effects.Missions;
import com.pvpduels.effects.Particles;
import com.pvpduels.effects.Quality;
import com.pvpduels.effects.Victory;
import com.pvpduels.util.Msg;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The chest menus of the victory effects: fireworks, particles, explosions, the daily missions and the preview. */
public final class EffectsMenus {
	private EffectsMenus() {}

	/** The wool of a firework color, used as its icon. */
	private static Item woolOf(String color) {
		for (String suffix : new String[] {"_wool", "_dye"}) {
			Identifier id = Identifier.parse(color + suffix);
			if (BuiltInRegistries.ITEM.containsKey(id)) return BuiltInRegistries.ITEM.getValue(id);
		}
		return Items.PAPER;
	}

	private static void report(ServerPlayer p, String problem) {
		if (problem != null) p.sendSystemMessage(Msg.err(problem));
	}

	/** Runs an equip action and says so when it took something else off (you can have effects OR an explosion). */
	private static void equip(ServerPlayer p, java.util.function.Supplier<String> action) {
		UUID me = p.getUUID();
		boolean hadExplosion = !Effects.explosionSlot(me).isEmpty();
		boolean hadEffects = Effects.hasEffects(me);
		String problem = action.get();
		report(p, problem);
		if (problem != null) return;
		if (hadExplosion && Effects.explosionSlot(me).isEmpty()) {
			p.sendSystemMessage(Msg.info("Your explosion was taken off: you can have effects OR an explosion, not both."));
		} else if (hadEffects && !Effects.hasEffects(me) && !Effects.explosionSlot(me).isEmpty()) {
			p.sendSystemMessage(Msg.info("Your fireworks and particles were taken off: you can have effects OR an explosion, not both."));
		}
	}

	// ------------------------------------------------------------------ main menu

	public static void openMain(ServerPlayer p) {
		UUID me = p.getUUID();
		ChoiceMenu.Builder b = new ChoiceMenu.Builder(3);
		b.set(10, DuelMenus.icon(Items.FIREWORK_ROCKET, "Fireworks", ChatFormatting.GOLD, "Three fireworks go up in a row behind you", "when you win (the middle one is higher).",
			"Unlocked: " + Effects.ownedItemCount(me) + "/" + Effects.allFireworkItems().size()), EffectsMenus::openFireworks);
		b.set(12, DuelMenus.icon(Items.FIREWORK_STAR, "Particles", ChatFormatting.AQUA, "2 under your feet and 4 around the arena.",
			"Unlocked: " + Effects.ownedParticles(me).size() + "/" + Particles.all().size()), EffectsMenus::openParticles);
		b.set(14, DuelMenus.icon(Items.TNT, "Explosions", ChatFormatting.RED, "Very rare rewards.", "Unlocked: " + Effects.ownedExplosions(me).size() + "/" + Effects.EXPLOSIONS.length),
			EffectsMenus::openExplosions);
		b.set(16, DuelMenus.icon(Items.CLOCK, "Missions", ChatFormatting.GREEN, "Complete them to unlock new effects.",
			"Daily: " + Missions.doneCount(me) + "/" + Missions.today(me).size() + " done today",
			"Weekly: " + Missions.weeklyDoneCount(me) + "/" + Missions.weekly().size() + " done this week"), EffectsMenus::openMissions);
		boolean hide = Effects.hideOthers(me);
		b.set(22, DuelMenus.icon(Items.FIREWORK_STAR, "Preview", ChatFormatting.YELLOW, "See your effects right now."), who -> {
			who.closeContainer();
			Victory.preview(who);
		});
		Quality quality = Effects.quality(me);
		b.set(18, DuelMenus.icon(quality.icon, "Effect quality: " + quality.label, ChatFormatting.LIGHT_PURPLE, quality.description,
			"This is only for you: it changes how many particles", "you get from big effects. Lower it if the game lags.",
			"Click to change it (Potato, Low, Mid, High, Ultra Pro Max)."), who -> {
			Effects.setQuality(who.getUUID(), Effects.quality(who.getUUID()).next());
			openMain(who);
		});
		b.set(26, DuelMenus.icon(hide ? Items.ENDER_EYE : Items.ENDER_PEARL, "Other players' effects: " + (hide ? "hidden" : "shown"), hide ? ChatFormatting.RED : ChatFormatting.GREEN,
			"Click to " + (hide ? "show" : "hide") + " the particles of other players' wins."), who -> {
			Effects.setHideOthers(who.getUUID(), !hide);
			openMain(who);
		});
		ChoiceMenu.open(p, Component.literal("Victory effects"), 3, b);
	}

	// ------------------------------------------------------------------ fireworks

	public static void openFireworks(ServerPlayer p) {
		UUID me = p.getUUID();
		ChoiceMenu.Builder b = new ChoiceMenu.Builder(3);
		int[] slots = {11, 13, 15};
		for (int s = 0; s < Effects.FIREWORK_SLOTS; s++) {
			int slot = s;
			List<String> colors = Effects.fireworkColors(me, slot);
			String names = colors.isEmpty() ? "no colors (it does not go up)" : colors.stream().map(Effects::pretty).reduce((x, y) -> x + ", " + y).orElse("");
			int shape = Math.max(0, Effects.shapeIndex(Effects.fireworkShape(me, slot)));
			b.set(slots[s], DuelMenus.icon(Items.FIREWORK_ROCKET, Effects.SLOT_NAMES[slot], ChatFormatting.GOLD, "Colors: " + names,
				"Shape: " + Effects.SHAPE_NAMES[shape] + (Effects.fireworkTrail(me, slot) ? ", trail" : "") + (Effects.fireworkTwinkle(me, slot) ? ", twinkle" : ""),
				"Click to change it."), who -> openFireworkSlot(who, slot));
		}
		b.set(18, DuelMenus.icon(Items.ARROW, "Back", ChatFormatting.YELLOW), EffectsMenus::openMain);
		ChoiceMenu.open(p, Component.literal("Fireworks"), 3, b);
	}

	public static void openFireworkSlot(ServerPlayer p, int slot) {
		UUID me = p.getUUID();
		List<String> chosen = Effects.fireworkColors(me, slot);
		ChoiceMenu.Builder b = new ChoiceMenu.Builder(6);
		for (int i = 0; i < Effects.COLORS.length; i++) {
			String color = Effects.COLORS[i];
			int position = (i / 8) * 9 + (i % 8);
			if (Effects.ownsItem(me, "color:" + color)) {
				boolean on = chosen.contains(color);
				b.set(position, DuelMenus.icon(woolOf(color), Effects.pretty(color), on ? ChatFormatting.GREEN : ChatFormatting.WHITE,
					on ? "On this firework. Click to take it off." : "Click to add it (up to " + Effects.MAX_COLORS + " colors)."), who -> {
					equip(who, () -> Effects.toggleColor(who.getUUID(), slot, color));
					openFireworkSlot(who, slot);
				});
			} else {
				b.set(position, DuelMenus.icon(Items.COAL, "Locked: " + Effects.pretty(color), ChatFormatting.DARK_GRAY, "Missions can unlock it."));
			}
		}
		String shape = Effects.fireworkShape(me, slot);
		for (int i = 0; i < Effects.SHAPES.length; i++) {
			String id = Effects.SHAPES[i];
			int position = 28 + i;
			if (Effects.ownsItem(me, "shape:" + id)) {
				boolean on = shape.equals(id);
				b.set(position, DuelMenus.icon(Items.FIREWORK_STAR, Effects.SHAPE_NAMES[i], on ? ChatFormatting.GREEN : ChatFormatting.WHITE, on ? "Selected." : "Click to use this shape."), who -> {
					report(who, Effects.setShape(who.getUUID(), slot, id));
					openFireworkSlot(who, slot);
				});
			} else {
				b.set(position, DuelMenus.icon(Items.COAL, "Locked: " + Effects.SHAPE_NAMES[i], ChatFormatting.DARK_GRAY, "Missions can unlock it."));
			}
		}
		boolean trail = Effects.fireworkTrail(me, slot);
		if (Effects.ownsItem(me, "trail")) {
			b.set(34, DuelMenus.icon(Items.GLOWSTONE_DUST, "Trail: " + (trail ? "on" : "off"), trail ? ChatFormatting.GREEN : ChatFormatting.WHITE, "Click to switch it."), who -> {
				report(who, Effects.toggleTrail(who.getUUID(), slot));
				openFireworkSlot(who, slot);
			});
		} else {
			b.set(34, DuelMenus.icon(Items.COAL, "Locked: Trail", ChatFormatting.DARK_GRAY, "Missions can unlock it."));
		}
		boolean twinkle = Effects.fireworkTwinkle(me, slot);
		if (Effects.ownsItem(me, "twinkle")) {
			b.set(35, DuelMenus.icon(Items.GLOWSTONE_DUST, "Twinkle: " + (twinkle ? "on" : "off"), twinkle ? ChatFormatting.GREEN : ChatFormatting.WHITE, "Click to switch it."), who -> {
				report(who, Effects.toggleTwinkle(who.getUUID(), slot));
				openFireworkSlot(who, slot);
			});
		} else {
			b.set(35, DuelMenus.icon(Items.COAL, "Locked: Twinkle", ChatFormatting.DARK_GRAY, "Missions can unlock it."));
		}
		b.set(45, DuelMenus.icon(Items.ARROW, "Back", ChatFormatting.YELLOW), EffectsMenus::openFireworks);
		ChoiceMenu.open(p, Component.literal("Firework: " + Effects.SLOT_NAMES[slot]), 6, b);
	}

	// ------------------------------------------------------------------ particles

	public static void openParticles(ServerPlayer p) {
		UUID me = p.getUUID();
		ChoiceMenu.Builder b = new ChoiceMenu.Builder(3);
		for (int i = 0; i < Effects.FLOOR_SLOTS; i++) {
			int index = i;
			b.set(10 + i, slotIcon(me, false, index), who -> openParticlePicker(who, false, index, 0));
		}
		for (int i = 0; i < Effects.AREA_SLOTS; i++) {
			int index = i;
			b.set(13 + i, slotIcon(me, true, index), who -> openParticlePicker(who, true, index, 0));
		}
		b.set(9, DuelMenus.icon(Items.OAK_PLANKS, "Floor", ChatFormatting.GRAY, "Particles on the ground around you."));
		b.set(12, DuelMenus.icon(Items.COMPASS, "Arena", ChatFormatting.GRAY, "Particles that spread from you", "across the whole arena."));
		b.set(18, DuelMenus.icon(Items.ARROW, "Back", ChatFormatting.YELLOW), EffectsMenus::openMain);
		ChoiceMenu.open(p, Component.literal("Particles"), 3, b);
	}

	private static ItemStack slotIcon(UUID me, boolean area, int index) {
		String id = Effects.particleSlot(me, area, index);
		String where = (area ? "Arena " : "Floor ") + (index + 1);
		if (id.isEmpty()) return DuelMenus.icon(Items.COAL, where + ": empty", ChatFormatting.GRAY, "Click to pick a particle.");
		Particles.Def def = Particles.find(id);
		return DuelMenus.icon(Items.FIREWORK_STAR, where + ": " + (def == null ? id : def.name()), ChatFormatting.AQUA, "Click to change it.");
	}

	public static void openParticlePicker(ServerPlayer p, boolean area, int index, int page) {
		UUID me = p.getUUID();
		Map<String, Integer> owned = Effects.ownedParticles(me);
		List<String> ids = new ArrayList<>(owned.keySet());
		ids.sort((x, y) -> {
			Particles.Def dx = Particles.find(x);
			Particles.Def dy = Particles.find(y);
			return (dx == null ? x : dx.name()).compareToIgnoreCase(dy == null ? y : dy.name());
		});
		int pages = Math.max(1, (ids.size() + 44) / 45);
		int current = Math.max(0, Math.min(page, pages - 1));
		ChoiceMenu.Builder b = new ChoiceMenu.Builder(6);
		String now = Effects.particleSlot(me, area, index);
		for (int i = 0; i < 45; i++) {
			int n = current * 45 + i;
			if (n >= ids.size()) break;
			String id = ids.get(n);
			Particles.Def def = Particles.find(id);
			int copies = owned.get(id);
			int used = Effects.equippedCount(me, id);
			boolean here = id.equals(now);
			b.set(i, DuelMenus.icon(Items.FIREWORK_STAR, def == null ? id : def.name(), here ? ChatFormatting.GREEN : ChatFormatting.WHITE,
				"Copies: " + copies + ", equipped: " + used, here ? "In this slot." : "Click to put it here."), who -> {
				equip(who, () -> Effects.setParticle(who.getUUID(), area, index, id));
				openParticles(who);
			});
		}
		if (ids.isEmpty()) {
			b.set(22, DuelMenus.icon(Items.PAPER, "No particles yet", ChatFormatting.GRAY, "Complete daily missions to unlock them."));
		}
		if (current > 0) b.set(45, DuelMenus.icon(Items.ARROW, "Previous page", ChatFormatting.YELLOW), who -> openParticlePicker(who, area, index, current - 1));
		b.set(48, DuelMenus.icon(Items.ARROW, "Back", ChatFormatting.YELLOW), EffectsMenus::openParticles);
		b.set(49, DuelMenus.icon(Items.BARRIER, "Empty this slot", ChatFormatting.RED), who -> {
			Effects.setParticle(who.getUUID(), area, index, "");
			openParticles(who);
		});
		if (current < pages - 1) b.set(53, DuelMenus.icon(Items.ARROW, "Next page", ChatFormatting.YELLOW), who -> openParticlePicker(who, area, index, current + 1));
		ChoiceMenu.open(p, Component.literal((area ? "Arena " : "Floor ") + (index + 1) + " (" + (current + 1) + "/" + pages + ")"), 6, b);
	}

	// ------------------------------------------------------------------ explosions

	public static void openExplosions(ServerPlayer p) {
		UUID me = p.getUUID();
		String equipped = Effects.explosionSlot(me);
		ChoiceMenu.Builder b = new ChoiceMenu.Builder(4);
		int[] places = {10, 12, 14, 16, 20, 24};
		net.minecraft.world.item.Item[] icons = {Items.TNT, Items.WITHER_SKELETON_SKULL, Items.CRYING_OBSIDIAN, Items.NETHER_STAR, Items.END_PORTAL_FRAME, Items.FIRE_CHARGE};
		for (int i = 0; i < Effects.EXPLOSIONS.length; i++) {
			String id = Effects.EXPLOSIONS[i];
			int position = places[i];
			if (Effects.ownsExplosion(me, id)) {
				boolean on = id.equals(equipped);
				b.set(position, DuelMenus.icon(icons[i], Effects.EXPLOSION_NAMES[i], on ? ChatFormatting.GREEN : ChatFormatting.GOLD,
					on ? "Equipped. Click to take it off." : "Click to equip it."), who -> {
					equip(who, () -> Effects.setExplosion(who.getUUID(), on ? "" : id));
					openExplosions(who);
				});
			} else {
				b.set(position, DuelMenus.icon(Items.COAL, "Locked: ???", ChatFormatting.DARK_GRAY, "A very rare reward of the missions."));
			}
		}
		b.set(27, DuelMenus.icon(Items.ARROW, "Back", ChatFormatting.YELLOW), EffectsMenus::openMain);
		ChoiceMenu.open(p, Component.literal("Explosions"), 4, b);
	}

	// ------------------------------------------------------------------ missions

	public static void openMissions(ServerPlayer p) {
		UUID me = p.getUUID();
		List<Missions.Def> today = Missions.today(me);
		ChoiceMenu.Builder b = new ChoiceMenu.Builder(4);
		for (int i = 0; i < today.size() && i < 7; i++) {
			Missions.Def m = today.get(i);
			boolean done = Missions.isDone(me, m);
			int value = (int) Math.floor(Math.min(Missions.progress(me, m), m.goal()));
			String bonus = i == Missions.fixed().size() ? "Today's bonus mission" : "Daily mission";
			b.set(9 + i, DuelMenus.icon(done ? Items.EMERALD : m.icon(), m.text(), done ? ChatFormatting.GREEN : ChatFormatting.WHITE,
				done ? "Done! The reward is yours." : "Progress: " + value + "/" + m.goalInt(), bonus));
		}
		List<Missions.Def> weekly = Missions.weekly();
		for (int i = 0; i < weekly.size() && i < 7; i++) {
			Missions.Def m = weekly.get(i);
			boolean done = Missions.isDone(me, m);
			int value = (int) Math.floor(Math.min(Missions.progress(me, m), m.goal()));
			b.set(19 + i, DuelMenus.icon(done ? Items.EMERALD_BLOCK : m.icon(), m.text(), done ? ChatFormatting.GREEN : ChatFormatting.GOLD,
				done ? "Done! The reward is yours." : "Progress: " + value + "/" + m.goalInt(), "Weekly mission: better chance of an explosion"));
		}
		b.set(27, DuelMenus.icon(Items.ARROW, "Back", ChatFormatting.YELLOW), EffectsMenus::openMain);
		b.set(31, DuelMenus.icon(Items.PAPER, "How it works", ChatFormatting.GRAY, "Missions count in duels against players (1v1 and 2v2).",
			"Daily ones reset at midnight, weekly ones on Monday.", "A duel counts for the day it started. A tie breaks win runs.",
			"A mission can give a firework color or shape, a particle", "or, very rarely, an explosion (0.8% daily, 1.5% weekly)."));
		ChoiceMenu.open(p, Component.literal("Missions"), 4, b);
	}
}
