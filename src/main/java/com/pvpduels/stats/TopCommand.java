package com.pvpduels.stats;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.pvpduels.util.Msg;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Locale;

/**
 * /top [elo|wins|streak]: the ranking of everybody who has played a duel (practice duels against a bot don't count).
 * /top kit [name] [elo|wins|streak]: the same, only counting the duels played with one kit; without a name it lists the kits that have a ranking.
 */
public final class TopCommand {
	private static final int SHOWN = 10;

	private TopCommand() {}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("top")
			.executes(ctx -> show(ctx.getSource(), "elo"))
			.then(Commands.literal("kit")
				.executes(ctx -> listKits(ctx.getSource()))
				.then(Commands.argument("name", StringArgumentType.word())
					.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(StatsStore.kitsWithStats(), builder))
					.executes(ctx -> showKit(ctx.getSource(), StringArgumentType.getString(ctx, "name"), "elo"))
					.then(Commands.argument("by", StringArgumentType.word())
						.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(List.of("elo", "wins", "streak"), builder))
						.executes(ctx -> showKit(ctx.getSource(), StringArgumentType.getString(ctx, "name"),
							StringArgumentType.getString(ctx, "by").toLowerCase(Locale.ROOT))))))
			.then(Commands.argument("by", StringArgumentType.word())
				.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(List.of("elo", "wins", "streak"), builder))
				.executes(ctx -> show(ctx.getSource(), StringArgumentType.getString(ctx, "by").toLowerCase(Locale.ROOT)))));
	}

	private static String nameOf(CommandSourceStack source, StatsStore.Entry entry) {
		ServerPlayer online = source.getServer().getPlayerList().getPlayer(entry.id());
		if (online != null) return online.getName().getString();
		return entry.name() == null || entry.name().isEmpty() ? "Unknown" : entry.name();
	}

	private static String value(String by, StatsStore.Stats s) {
		return switch (by) {
			case "wins" -> s.wins + (s.wins == 1 ? " win" : " wins");
			case "streak" -> "best streak " + s.bestStreak;
			default -> s.elo + " Elo";
		};
	}

	private static int show(CommandSourceStack source, String by) {
		if (!validBy(by)) {
			source.sendFailure(Msg.err("Use /top, /top wins or /top streak. For one kit: /top kit <name>."));
			return 0;
		}
		return print(source, StatsStore.ranking(by), by, "Nobody has played a duel yet.", "");
	}

	private static boolean validBy(String by) {
		return by.equals("elo") || by.equals("wins") || by.equals("streak");
	}

	/** /top kit: the kits that have a ranking, the most played first. */
	private static int listKits(CommandSourceStack source) {
		List<String> kits = StatsStore.kitsWithStats();
		if (kits.isEmpty()) {
			source.sendSuccess(() -> Msg.info("No kit has a ranking yet: play a duel and it appears here."), false);
			return 1;
		}
		source.sendSuccess(() -> Msg.title("Kits with a ranking (" + kits.size() + ")"), false);
		for (String kit : kits.subList(0, Math.min(20, kits.size()))) {
			source.sendSuccess(() -> Msg.info("  " + kit + "  -  /top kit " + kit), false);
		}
		return kits.size();
	}

	private static int showKit(CommandSourceStack source, String kit, String by) {
		if (!validBy(by)) {
			source.sendFailure(Msg.err("Use /top kit " + kit + ", /top kit " + kit + " wins or /top kit " + kit + " streak."));
			return 0;
		}
		return print(source, StatsStore.kitRanking(kit, by), by, "Nobody has played a ranked duel with the kit '" + kit + "'. /top kit lists the kits that have a ranking.",
			" with kit " + kit);
	}

	private static int print(CommandSourceStack source, List<StatsStore.Entry> all, String by, String emptyText, String suffix) {
		if (all.isEmpty()) {
			source.sendSuccess(() -> Msg.info(emptyText), false);
			return 1;
		}
		int shown = Math.min(SHOWN, all.size());
		String what = by.equals("elo") ? "Elo" : (by.equals("wins") ? "wins" : "best win streak");
		source.sendSuccess(() -> Msg.title("Top " + shown + " by " + what + suffix), false);
		for (int i = 0; i < shown; i++) {
			StatsStore.Entry entry = all.get(i);
			String line = String.format(Locale.ROOT, "%2d. %s  -  %s  (%dW %dL)", i + 1, nameOf(source, entry), value(by, entry.stats()),
				entry.stats().wins, entry.stats().losses);
			source.sendSuccess(() -> Msg.info(line), false);
		}
		ServerPlayer me = source.getPlayer();
		if (me != null) {
			for (int i = 0; i < all.size(); i++) {
				if (all.get(i).id().equals(me.getUUID())) {
					String mine = "You: #" + (i + 1) + " of " + all.size() + "  -  " + value(by, all.get(i).stats());
					source.sendSuccess(() -> Msg.ok(mine), false);
					break;
				}
			}
		}
		return 1;
	}
}
