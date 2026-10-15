package com.pvpduels.util;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.pvpduels.PvpDuelsMod;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * /bugs: the owner of the world reads the bug reports of /bugreport inside the game (six per page, newest first), opens one, and marks
 * the ones that are fixed. The reports stay in pvpduels-owner/Bugs/bug-reports.txt as they are; what is fixed is a small list next to
 * it (fixed.txt), so nothing of a report is ever rewritten.
 */
public final class BugBoard {
	static final int PAGE = 6;
	private static final int SHORT = 70;
	private static final Predicate<CommandSourceStack> ADMIN = Commands.hasPermission(Commands.LEVEL_GAMEMASTERS);
	/** Who may read the reports: dibitss and anybody with admin rights. */
	private static final Predicate<CommandSourceStack> ALLOWED = source -> ADMIN.test(source) || (source.getEntity() instanceof ServerPlayer p && Homes.allowed(p));

	/** One report, as it was written in the file. */
	public record Entry(int number, String when, String who, String mod, String where, String state, String text) {}

	private BugBoard() {}

	public static Path fixedFile() {
		return BugReports.folder().resolve("fixed.txt");
	}

	// ------------------------------------------------------------------ the files

	/** Every report of the file, oldest first. */
	public static List<Entry> read() {
		List<Entry> out = new ArrayList<>();
		Path file = BugReports.file();
		if (!Files.exists(file)) return out;
		try {
			int number = 0;
			String when = "", who = "", mod = "", where = "", state = "", text = "";
			boolean open = false;
			for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
				if (line.startsWith("=== Bug #")) {
					if (open) out.add(new Entry(number, when, who, mod, where, state, text));
					String digits = line.substring("=== Bug #".length()).replaceAll("[^0-9].*$", "");
					number = digits.isEmpty() ? out.size() + 1 : Integer.parseInt(digits);
					when = who = mod = where = state = text = "";
					open = true;
				} else if (line.startsWith("When: ")) when = line.substring(6);
				else if (line.startsWith("Who: ")) who = line.substring(5);
				else if (line.startsWith("Mod: ")) mod = line.substring(5);
				else if (line.startsWith("Where: ")) where = line.substring(7);
				else if (line.startsWith("State: ")) state = line.substring(7);
				else if (line.startsWith("Bug: ")) text = line.substring(5);
			}
			if (open) out.add(new Entry(number, when, who, mod, where, state, text));
		} catch (IOException | RuntimeException e) {
			PvpDuelsMod.LOGGER.warn("Could not read the bug reports: {}", e.toString());
		}
		return out;
	}

	/** The fixed reports: number to "date by who". */
	public static Map<Integer, String> fixed() {
		Map<Integer, String> out = new LinkedHashMap<>();
		Path file = fixedFile();
		if (!Files.exists(file)) return out;
		try {
			for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
				int tab = line.indexOf('\t');
				if (tab <= 0) continue;
				try {
					out.put(Integer.parseInt(line.substring(0, tab).trim()), line.substring(tab + 1));
				} catch (NumberFormatException ignored) {
					// a damaged line is skipped
				}
			}
		} catch (IOException e) {
			PvpDuelsMod.LOGGER.warn("Could not read the fixed bugs: {}", e.toString());
		}
		return out;
	}

	private static void writeFixed(Map<Integer, String> fixed) throws IOException {
		Files.createDirectories(BugReports.folder());
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<Integer, String> e : fixed.entrySet()) sb.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
		Files.writeString(fixedFile(), sb.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
	}

	/** How many reports are not marked as fixed. */
	public static int openCount() {
		Map<Integer, String> fixed = fixed();
		int n = 0;
		for (Entry e : read()) if (!fixed.containsKey(e.number())) n++;
		return n;
	}

	// ------------------------------------------------------------------ commands

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		var root = Commands.literal("bugs").requires(ALLOWED).executes(ctx -> list(ctx.getSource(), "open", 1));
		for (String filter : new String[] {"open", "fixed", "all"}) {
			root.then(Commands.literal(filter)
				.executes(ctx -> list(ctx.getSource(), filter, 1))
				.then(Commands.argument("page", IntegerArgumentType.integer(1))
					.executes(ctx -> list(ctx.getSource(), filter, IntegerArgumentType.getInteger(ctx, "page")))));
		}
		root.then(Commands.literal("show").then(Commands.argument("number", IntegerArgumentType.integer(1))
			.executes(ctx -> show(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "number")))));
		root.then(Commands.literal("fix").then(Commands.argument("number", IntegerArgumentType.integer(1))
			.executes(ctx -> mark(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "number"), true))));
		root.then(Commands.literal("reopen").then(Commands.argument("number", IntegerArgumentType.integer(1))
			.executes(ctx -> mark(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "number"), false))));
		dispatcher.register(root);
	}

	/** The owner hears on joining that there are bugs waiting. */
	public static void register() {
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> tellOwner(handler.getPlayer()));
	}

	/** Tells the owner (and nobody else) how many reports are waiting, with a button to read them. */
	public static void tellOwner(ServerPlayer p) {
		if (!ALLOWED.test(p.createCommandSourceStack())) return;
		int open = openCount();
		if (open > 0) {
			p.sendSystemMessage(Msg.info(open + " bug report" + (open == 1 ? " is" : "s are") + " waiting. ")
				.copy().append(Msg.button("[SEE THEM]", ChatFormatting.AQUA, "/bugs", "Open the list of bug reports")));
		}
	}

	private static String day(String when) {
		return when.length() >= 10 ? when.substring(0, 10) : when;
	}

	private static String cut(String text, int max) {
		return text.length() <= max ? text : text.substring(0, max - 3) + "...";
	}

	private static int list(CommandSourceStack source, String filter, int page) {
		List<Entry> all = read();
		Map<Integer, String> fixed = fixed();
		List<Entry> shown = new ArrayList<>();
		int open = 0;
		for (int i = all.size() - 1; i >= 0; i--) { // newest first
			Entry e = all.get(i);
			boolean isFixed = fixed.containsKey(e.number());
			if (!isFixed) open++;
			if (filter.equals("all") || (filter.equals("fixed") == isFixed)) shown.add(e);
		}
		int pages = Math.max(1, (shown.size() + PAGE - 1) / PAGE);
		int p = Math.min(page, pages);
		source.sendSystemMessage(Msg.title("Bug reports: " + open + " open, " + (all.size() - open) + " fixed")
			.copy().append(Msg.info("  (" + filter + ", page " + p + "/" + pages + ")")));
		if (shown.isEmpty()) {
			source.sendSystemMessage(Msg.info(all.isEmpty() ? "Nobody has sent a bug report yet (/bugreport <what went wrong>)." : "Nothing to show here. Try /bugs all."));
			return 1;
		}
		for (int i = (p - 1) * PAGE; i < Math.min(shown.size(), p * PAGE); i++) {
			Entry e = shown.get(i);
			boolean isFixed = fixed.containsKey(e.number());
			MutableComponent line = Component.literal("#" + e.number() + " ").withStyle(isFixed ? ChatFormatting.GREEN : ChatFormatting.GOLD)
				.append(Component.literal(nameOf(e.who()) + ", " + day(e.when()) + ": ").withStyle(ChatFormatting.GRAY))
				.append(Component.literal(cut(e.text(), SHORT)).withStyle(isFixed ? ChatFormatting.DARK_GRAY : ChatFormatting.WHITE))
				.append(Component.literal(" "))
				.append(Msg.button("[SHOW]", ChatFormatting.AQUA, "/bugs show " + e.number(), "Read the whole report"))
				.append(Component.literal(" "))
				.append(isFixed ? Msg.button("[REOPEN]", ChatFormatting.YELLOW, "/bugs reopen " + e.number(), "It is not fixed after all")
					: Msg.button("[FIXED]", ChatFormatting.GREEN, "/bugs fix " + e.number(), "Mark this bug as fixed"));
			source.sendSystemMessage(line);
		}
		MutableComponent nav = Component.literal("");
		if (p > 1) nav.append(Msg.button("[< PREVIOUS]", ChatFormatting.AQUA, "/bugs " + filter + " " + (p - 1), "The newer reports")).append(Component.literal("  "));
		if (p < pages) nav.append(Msg.button("[NEXT >]", ChatFormatting.AQUA, "/bugs " + filter + " " + (p + 1), "The older reports"));
		if (p > 1 || p < pages) source.sendSystemMessage(nav);
		return 1;
	}

	/** "Alice (uuid)" to "Alice". */
	private static String nameOf(String who) {
		int paren = who.indexOf(" (");
		return paren > 0 ? who.substring(0, paren) : who;
	}

	private static Entry find(int number) {
		for (Entry e : read()) if (e.number() == number) return e;
		return null;
	}

	private static int show(CommandSourceStack source, int number) {
		Entry e = find(number);
		if (e == null) {
			source.sendFailure(Msg.err("There is no bug report #" + number + "."));
			return 0;
		}
		Map<Integer, String> fixed = fixed();
		boolean isFixed = fixed.containsKey(number);
		source.sendSystemMessage(Msg.title("Bug #" + number).copy().append(Component.literal(isFixed ? "  FIXED" : "  OPEN").withStyle(isFixed ? ChatFormatting.GREEN : ChatFormatting.GOLD)));
		source.sendSystemMessage(Msg.info("When: " + e.when()));
		source.sendSystemMessage(Msg.info("Who: " + nameOf(e.who())));
		source.sendSystemMessage(Msg.info("Version: " + e.mod().replace("PvP Duels ", "") + "   Where: " + e.where()));
		source.sendSystemMessage(Msg.info("State: " + e.state()));
		source.sendSystemMessage(Component.literal(e.text()).withStyle(ChatFormatting.WHITE));
		if (isFixed) {
			source.sendSystemMessage(Msg.ok("Fixed: " + fixed.get(number)).copy().append(Component.literal("  "))
				.append(Msg.button("[REOPEN]", ChatFormatting.YELLOW, "/bugs reopen " + number, "It is not fixed after all")));
		} else {
			source.sendSystemMessage(Msg.button("[MARK AS FIXED]", ChatFormatting.GREEN, "/bugs fix " + number, "Mark this bug as fixed"));
		}
		return 1;
	}

	private static int mark(CommandSourceStack source, int number, boolean fix) {
		if (find(number) == null) {
			source.sendFailure(Msg.err("There is no bug report #" + number + "."));
			return 0;
		}
		Map<Integer, String> fixed = fixed();
		if (fix == fixed.containsKey(number)) {
			source.sendSystemMessage(Msg.info("Bug #" + number + (fix ? " was already marked as fixed." : " was not marked as fixed.")));
			return 0;
		}
		if (fix) {
			String by = source.getEntity() instanceof ServerPlayer p ? p.getName().getString() : "the server";
			fixed.put(number, LocalDate.now() + " by " + by);
		} else {
			fixed.remove(number);
		}
		try {
			writeFixed(fixed);
		} catch (IOException e) {
			PvpDuelsMod.LOGGER.warn("Could not save the fixed bugs: {}", e.toString());
			source.sendFailure(Msg.err("The change could not be saved."));
			return 0;
		}
		source.sendSystemMessage(Msg.ok("Bug #" + number + (fix ? " is marked as fixed." : " is open again.")));
		return 1;
	}
}
