package com.pvpduels.effects;

import com.pvpduels.util.Msg;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The daily and the weekly missions. Five daily missions are fixed every day and one bonus mission changes with the date; five weekly
 * missions are harder and start again every Monday. Duels against real players (1v1 and 2v2) feed them; each mission completed rolls a
 * reward ({@link Effects#roll}; an explosion is a bit more likely from a weekly one). The day changes at midnight (the clock of the
 * computer that runs the world). A duel counts for the day it STARTED, even if it ends after midnight. A tie breaks the win runs.
 */
public final class Missions {
	public enum Kind { SUM, BEST, RUN, DISTINCT }

	public record Def(String id, String text, double goal, Kind kind, Item icon) {
		public int goalInt() {
			return (int) Math.round(goal);
		}
	}

	private static final List<Def> FIXED = List.of(
		new Def("heal100", "Regenerate 100 hearts in duels", 100, Kind.SUM, Items.GLISTERING_MELON_SLICE),
		new Def("combo15", "Land a 15-hit combo in one duel", 15, Kind.BEST, Items.DIAMOND_SWORD),
		new Def("eat40", "Eat 40 foods in duels", 40, Kind.SUM, Items.COOKED_BEEF),
		new Def("potions10", "Drink or throw 10 potions in duels", 10, Kind.SUM, Items.POTION),
		new Def("streak3", "Win 3 duels in a row", 3, Kind.RUN, Items.BLAZE_POWDER));

	private static final List<Def> BONUS = List.of(
		new Def("duels5", "Play 5 duels", 5, Kind.SUM, Items.IRON_SWORD),
		new Def("hits100", "Land 100 hits in duels", 100, Kind.SUM, Items.IRON_AXE),
		new Def("pearls5", "Throw 5 ender pearls in duels", 5, Kind.SUM, Items.ENDER_PEARL),
		new Def("apples5", "Eat 5 golden apples in duels", 5, Kind.SUM, Items.GOLDEN_APPLE),
		new Def("rounds10", "Win 10 rounds in duels", 10, Kind.SUM, Items.CLOCK),
		new Def("kits2", "Win with 2 different kits", 2, Kind.DISTINCT, Items.CHEST),
		new Def("team1", "Win a 2v2 duel", 1, Kind.SUM, Items.LEAD));

	private static final List<Def> WEEKLY = List.of(
		new Def("w_wins15", "Win 15 duels this week", 15, Kind.SUM, Items.DIAMOND_SWORD),
		new Def("w_kits4", "Win with 4 different kits this week", 4, Kind.DISTINCT, Items.CHEST),
		new Def("w_run5", "Win 5 duels in a row", 5, Kind.RUN, Items.BLAZE_ROD),
		new Def("w_hits500", "Land 500 hits in duels", 500, Kind.SUM, Items.IRON_AXE),
		new Def("w_team3", "Win 3 2v2 duels this week", 3, Kind.SUM, Items.LEAD));

	/** What happened to one player in one duel. */
	public static final class Facts {
		/** The day the duel started (null = today). A duel that started at 23:59 counts for that day even if it ends after midnight. */
		public LocalDate day;
		public boolean won;
		public boolean lost;
		public boolean team;
		public String kit = "";
		public int combo;
		public int hits;
		public int roundsWon;
		public double hearts;
		public int potions;
		public int foods;
		public int pearls;
		public int apples;
	}

	/** A mission that was just completed with what it gave. */
	public record Completion(Def mission, List<Effects.Gained> rewards) {}

	/** The tick from which a player is told about their rewards. */
	private static final Map<UUID, Integer> DUE = new HashMap<>();
	private static final int ANNOUNCE_DELAY = 120;
	private static int ticks;
	/** For the self-test: a fake date. */
	private static LocalDate fakeDay;

	private Missions() {}

	public static void setDayForTest(LocalDate day) {
		fakeDay = day;
	}

	public static LocalDate today() {
		return fakeDay != null ? fakeDay : LocalDate.now();
	}

	public static List<Def> fixed() {
		return FIXED;
	}

	public static List<Def> bonusPool() {
		return BONUS;
	}

	/** The bonus mission of this date. */
	public static Def bonusOf(LocalDate day) {
		// 3 and 7 have nothing in common, so the seven bonus missions come one after another in any 7 days
		return BONUS.get((int) Math.floorMod(day.toEpochDay() * 3 + 1, (long) BONUS.size()));
	}

	/** The six missions of today. */
	public static List<Def> today(UUID id) {
		return dailyOf(today());
	}

	/** The six daily missions of a date. */
	public static List<Def> dailyOf(LocalDate day) {
		List<Def> all = new ArrayList<>(FIXED);
		all.add(bonusOf(day));
		return all;
	}

	/** The five weekly missions (the same all week). */
	public static List<Def> weekly() {
		return WEEKLY;
	}

	public static boolean isWeekly(Def m) {
		return m.id().startsWith("w_");
	}

	/** The Monday of the week of this date, as text. */
	public static String weekOf(LocalDate day) {
		return day.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)).toString();
	}

	/** Starts a new day for this player when the duel is of a new date. Only {@link #record} changes the data; looking never does. */
	private static void rolloverDay(Effects.Data d, LocalDate date) {
		String day = date.toString();
		if (day.equals(d.day)) return;
		d.day = day;
		d.progress.clear();
		d.done.clear();
		d.winRun = 0;
		d.kitsWon.clear();
	}

	private static void rolloverWeek(Effects.Data d, LocalDate date) {
		String week = weekOf(date);
		if (week.equals(d.week)) return;
		d.week = week;
		d.weekProgress.clear();
		d.weekDone.clear();
		d.weekRun = 0;
		d.weekKits.clear();
	}

	public static synchronized double progress(UUID id, Def m) {
		Effects.Data d = Effects.data(id, null);
		if (isWeekly(m)) return weekOf(today()).equals(d.week) ? d.weekProgress.getOrDefault(m.id(), 0.0) : 0.0;
		return today().toString().equals(d.day) ? d.progress.getOrDefault(m.id(), 0.0) : 0.0;
	}

	public static synchronized boolean isDone(UUID id, Def m) {
		Effects.Data d = Effects.data(id, null);
		if (isWeekly(m)) return weekOf(today()).equals(d.week) && d.weekDone.contains(m.id());
		return today().toString().equals(d.day) && d.done.contains(m.id());
	}

	public static synchronized int doneCount(UUID id) {
		Effects.Data d = Effects.data(id, null);
		return today().toString().equals(d.day) ? d.done.size() : 0;
	}

	public static synchronized int weeklyDoneCount(UUID id) {
		Effects.Data d = Effects.data(id, null);
		return weekOf(today()).equals(d.week) ? d.weekDone.size() : 0;
	}

	public static synchronized int pendingCount(UUID id) {
		return Effects.data(id, null).unannounced.size();
	}

	/**
	 * Adds what happened in a duel to the missions of the day it started (and of that week) and returns the ones it completed. The
	 * player is told a few seconds later (after the summary of the duel).
	 */
	public static synchronized List<Completion> record(UUID id, String name, Facts f) {
		LocalDate date = f.day != null ? f.day : today();
		Effects.Data d = Effects.data(id, name);
		rolloverDay(d, date);
		rolloverWeek(d, date);
		List<Completion> completed = new ArrayList<>();
		if (f.won) {
			d.winRun++;
			d.weekRun++;
		} else {
			// a loss or a tie breaks the run
			d.winRun = 0;
			d.weekRun = 0;
		}
		if (f.won && !f.kit.isEmpty()) {
			if (!d.kitsWon.contains(f.kit)) d.kitsWon.add(f.kit);
			if (!d.weekKits.contains(f.kit)) d.weekKits.add(f.kit);
		}

		for (Def m : dailyOf(date)) {
			if (d.done.contains(m.id())) continue;
			double value = d.progress.getOrDefault(m.id(), 0.0);
			switch (m.id()) {
				case "heal100" -> value += f.hearts;
				case "combo15" -> value = Math.max(value, f.combo);
				case "eat40" -> value += f.foods;
				case "potions10" -> value += f.potions;
				case "streak3" -> value = d.winRun;
				case "duels5" -> value += 1;
				case "hits100" -> value += f.hits;
				case "pearls5" -> value += f.pearls;
				case "apples5" -> value += f.apples;
				case "rounds10" -> value += f.roundsWon;
				case "kits2" -> value = d.kitsWon.size();
				case "team1" -> value += f.won && f.team ? 1 : 0;
				default -> { }
			}
			d.progress.put(m.id(), value);
			if (value + 1e-9 >= m.goal()) {
				d.done.add(m.id());
				complete(d, id, name, m, false, completed);
			}
		}
		for (Def m : WEEKLY) {
			if (d.weekDone.contains(m.id())) continue;
			double value = d.weekProgress.getOrDefault(m.id(), 0.0);
			switch (m.id()) {
				case "w_wins15" -> value += f.won ? 1 : 0;
				case "w_kits4" -> value = d.weekKits.size();
				case "w_run5" -> value = Math.max(value, d.weekRun);
				case "w_hits500" -> value += f.hits;
				case "w_team3" -> value += f.won && f.team ? 1 : 0;
				default -> { }
			}
			d.weekProgress.put(m.id(), value);
			if (value + 1e-9 >= m.goal()) {
				d.weekDone.add(m.id());
				complete(d, id, name, m, true, completed);
			}
		}
		if (!completed.isEmpty()) DUE.put(id, ticks + ANNOUNCE_DELAY);
		Effects.save();
		return completed;
	}

	private static void complete(Effects.Data d, UUID id, String name, Def m, boolean weekly, List<Completion> completed) {
		List<Effects.Gained> rewards = Effects.roll(id, name, weekly);
		completed.add(new Completion(m, rewards));
		StringBuilder what = new StringBuilder();
		for (Effects.Gained g : rewards) {
			if (!what.isEmpty()) what.append("  |  ");
			what.append(g.label());
		}
		d.unannounced.add((weekly ? "Weekly: " : "") + m.text() + "\n" + what);
	}

	// ------------------------------------------------------------------ telling the player

	private static void announce(ServerPlayer p) {
		List<String> lines;
		synchronized (Missions.class) {
			Effects.Data d = Effects.data(p.getUUID(), null);
			if (d.unannounced.isEmpty()) return;
			lines = new ArrayList<>(d.unannounced);
			d.unannounced.clear();
			Effects.save();
		}
		for (String entry : lines) {
			String[] parts = entry.split("\n", 2);
			p.sendSystemMessage(Component.literal("Mission complete: ").withStyle(ChatFormatting.GOLD)
				.append(Component.literal(parts[0]).withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD)));
			if (parts.length > 1) {
				p.sendSystemMessage(Component.literal("  Reward: ").withStyle(ChatFormatting.GRAY)
					.append(Component.literal(parts[1]).withStyle(ChatFormatting.AQUA))
					.append(Component.literal(" ").withStyle(ChatFormatting.GRAY))
					.append(Msg.button("[EFFECTS]", ChatFormatting.GREEN, "/effects", "Choose how you celebrate your wins")));
			}
		}
		p.sendOverlayMessage(Component.literal("Mission complete!").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
	}

	/** For the self-test: tell everybody about their rewards right now. */
	public static void announceNow(MinecraftServer s) {
		for (ServerPlayer p : s.getPlayerList().getPlayers()) announce(p);
	}

	public static void resetForTest() {
		DUE.clear();
		fakeDay = null;
	}

	public static void register() {
		ServerLifecycleEvents.SERVER_STOPPED.register(s -> {
			synchronized (Missions.class) {
				DUE.clear();
			}
			Effects.forgetAll();
		});
		ServerTickEvents.END_SERVER_TICK.register(s -> {
			ticks++;
			if (ticks % 10 != 0 || DUE.isEmpty()) return;
			for (Map.Entry<UUID, Integer> entry : new ArrayList<>(DUE.entrySet())) {
				if (entry.getValue() > ticks) continue;
				ServerPlayer p = s.getPlayerList().getPlayer(entry.getKey());
				if (p == null) continue;
				DUE.remove(entry.getKey());
				announce(p);
			}
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> {
			ServerPlayer p = handler.getPlayer();
			synchronized (Missions.class) {
				if (!Effects.data(p.getUUID(), p.getName().getString()).unannounced.isEmpty()) DUE.put(p.getUUID(), ticks + ANNOUNCE_DELAY);
			}
		});
	}
}
