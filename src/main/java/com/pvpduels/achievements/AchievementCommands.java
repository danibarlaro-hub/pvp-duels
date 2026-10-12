package com.pvpduels.achievements;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.pvpduels.arena.BuilderRank;
import com.pvpduels.duel.AchievementMenus;
import com.pvpduels.stats.StatsStore;
import com.pvpduels.util.Msg;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** /achievements, /titles and /profile. */
public final class AchievementCommands {
	private AchievementCommands() {}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("achievements")
			.executes(ctx -> {
				ServerPlayer p = ctx.getSource().getPlayerOrException();
				AchievementMenus.openList(p, 0);
				return 1;
			}));

		dispatcher.register(Commands.literal("titles")
			.executes(ctx -> {
				AchievementMenus.openTitles(ctx.getSource().getPlayerOrException());
				return 1;
			})
			.then(Commands.literal("off")
				.executes(ctx -> {
					ServerPlayer p = ctx.getSource().getPlayerOrException();
					Achievements.setTitle(p, "");
					ctx.getSource().sendSuccess(() -> Msg.ok("You don't wear a title now."), false);
					return 1;
				}))
			.then(Commands.literal("wear")
				.then(Commands.argument("achievement", StringArgumentType.word())
					.suggests((ctx, builder) -> {
						List<String> ids = new ArrayList<>();
						for (Achievement a : Achievements.titlesOf(ctx.getSource().getPlayerOrException().getUUID())) ids.add(a.id());
						return SharedSuggestionProvider.suggest(ids, builder);
					})
					.executes(ctx -> {
						ServerPlayer p = ctx.getSource().getPlayerOrException();
						String problem = Achievements.setTitle(p, StringArgumentType.getString(ctx, "achievement"));
						if (problem != null) {
							ctx.getSource().sendFailure(Msg.err(problem));
							return 0;
						}
						Achievement worn = Achievements.titleOf(p.getUUID());
						ctx.getSource().sendSuccess(() -> Msg.ok("You wear the title [" + (worn == null ? "" : worn.title()) + "] now."), false);
						return 1;
					}))));

		dispatcher.register(Commands.literal("profile")
			.executes(ctx -> show(ctx.getSource(), ctx.getSource().getPlayerOrException()))
			.then(Commands.argument("player", EntityArgument.player())
				.executes(ctx -> show(ctx.getSource(), EntityArgument.getPlayer(ctx, "player")))));
	}

	private static int show(CommandSourceStack source, ServerPlayer who) {
		UUID id = who.getUUID();
		StatsStore.Stats s = StatsStore.get(id);
		Achievement title = Achievements.titleOf(id);
		Component head = Component.literal("Profile: ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
		if (title != null) head = head.copy().append(Component.literal("[" + title.title() + "] ").withStyle(title.color()));
		Component line = head.copy().append(Component.literal(who.getName().getString()).withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD));
		source.sendSuccess(() -> line, false);
		source.sendSuccess(() -> Msg.info("  Elo " + s.elo + ", " + s.wins + (s.wins == 1 ? " win" : " wins") + " and " + s.losses
			+ (s.losses == 1 ? " loss" : " losses") + " (" + s.winRate() + "), best streak " + s.bestStreak), false);
		source.sendSuccess(() -> Msg.info("  Achievements: " + Achievements.unlockedCount(id) + "/" + Achievements.all().size()
			+ (BuilderRank.hasRank(id) ? "   Builder (" + BuilderRank.timeLeft(id) + ")" : "")), false);
		return 1;
	}
}
