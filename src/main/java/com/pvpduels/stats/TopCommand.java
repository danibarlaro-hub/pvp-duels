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

/** /top [elo|wins|streak]: the ranking of everybody who has played a duel (practice duels against a bot don't count). */
public final class TopCommand {
	private static final int SHOWN = 10;

	private TopCommand() {}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("top")
			.executes(ctx -> show(ctx.getSource(), "elo"))
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
		if (!by.equals("elo") && !by.equals("wins") && !by.equals("streak")) {
			source.sendFailure(Msg.err("Use /top, /top wins or /top streak."));
			return 0;
		}
		List<StatsStore.Entry> all = StatsStore.ranking(by);
		if (all.isEmpty()) {
			source.sendSuccess(() -> Msg.info("Nobody has played a duel yet."), false);
			return 1;
		}
		int shown = Math.min(SHOWN, all.size());
		String what = by.equals("elo") ? "Elo" : (by.equals("wins") ? "wins" : "best win streak");
		source.sendSuccess(() -> Msg.title("Top " + shown + " by " + what), false);
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
