package com.pvpduels.util;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.pvpduels.PvpDuelsMod;
import com.pvpduels.duel.Cinema;
import com.pvpduels.duel.DuelManager;
import com.pvpduels.effects.Effects;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * /bugreport &lt;description&gt;: anybody writes what went wrong and it is saved in a file on the computer that runs the world (the
 * folder "pvpduels-owner/Bugs" inside the game folder), together with who wrote it, when, and where they were.
 */
public final class BugReports {
	/** The longest description that is kept. */
	static final int MAX_LENGTH = 600;
	private static final int MIN_LENGTH = 5;
	private static final long COOLDOWN_MS = 5000;
	private static final Map<UUID, Long> LAST = new HashMap<>();
	private static int savedNow;

	private BugReports() {}

	public static Path folder() {
		return FabricLoader.getInstance().getGameDir().resolve("pvpduels-owner").resolve("Bugs");
	}

	public static Path file() {
		return folder().resolve("bug-reports.txt");
	}

	/** How many reports were saved since the game started (the self-test reads it). */
	public static int saved() {
		return savedNow;
	}

	public static void resetForTest() {
		LAST.clear();
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("bugreport")
			.executes(ctx -> {
				ctx.getSource().sendFailure(Msg.err("Write what went wrong: /bugreport <description of the bug>"));
				return 0;
			})
			.then(Commands.argument("description", StringArgumentType.greedyString())
				.executes(ctx -> report(ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx, "description")))));
	}

	private static int report(ServerPlayer p, String description) {
		String problem = save(p, description);
		if (problem != null) {
			p.sendSystemMessage(Msg.err(problem));
			return 0;
		}
		return 1;
	}

	/** Saves the report and tells the player. Returns what is wrong in plain words, or null when it was saved. */
	static synchronized String save(ServerPlayer p, String description) {
		String text = description.replaceAll("[\\r\\n\\t]+", " ").trim();
		if (text.length() < MIN_LENGTH) return "Describe the bug a little more (at least " + MIN_LENGTH + " characters).";
		if (text.length() > MAX_LENGTH) text = text.substring(0, MAX_LENGTH) + "...";
		long now = System.currentTimeMillis();
		Long before = LAST.get(p.getUUID());
		if (before != null && now - before < COOLDOWN_MS) return "Wait a few seconds before you send another report.";
		try {
			Files.createDirectories(folder());
			Path file = file();
			int number = 1;
			if (Files.exists(file)) {
				for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) if (line.startsWith("=== Bug #")) number++;
			}
			StringBuilder entry = new StringBuilder();
			entry.append("=== Bug #").append(number).append(" ===\n");
			entry.append("When: ").append(ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'xxx"))).append('\n');
			entry.append("Who: ").append(p.getName().getString()).append(" (").append(p.getUUID()).append(")\n");
			entry.append("Mod: PvP Duels ").append(FabricLoader.getInstance().getModContainer(PvpDuelsMod.MOD_ID)
				.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?")).append('\n');
			entry.append("Where: ").append(p.level().dimension().identifier()).append(String.format(java.util.Locale.ROOT, " at %.1f %.1f %.1f", p.getX(), p.getY(), p.getZ()))
				.append(", game mode ").append(p.gameMode().getName()).append('\n');
			entry.append("State: ").append(DuelManager.isBusy(p.getUUID()) ? "in a duel, a queue, a replay or a film" : "free")
				.append(Cinema.isFree(p.getUUID()) ? " (free camera)" : "").append(", explosion ").append(emptyAsNone(Effects.explosionSlot(p.getUUID()))).append('\n');
			entry.append("Bug: ").append(text).append("\n\n");
			Files.writeString(file, entry.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			LAST.put(p.getUUID(), now);
			savedNow++;
			PvpDuelsMod.LOGGER.info("Bug report #{} from {}: {}", number, p.getName().getString(), text);
			p.sendSystemMessage(Msg.ok("Bug #" + number + " saved. Thank you! The owner of the world will see it."));
			return null;
		} catch (IOException e) {
			PvpDuelsMod.LOGGER.warn("Could not save a bug report: {}", e.toString());
			return "The report could not be saved. Tell the owner of the world.";
		}
	}

	private static String emptyAsNone(String s) {
		return s == null || s.isEmpty() ? "none" : s;
	}
}
