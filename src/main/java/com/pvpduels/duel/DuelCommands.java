package com.pvpduels.duel;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.pvpduels.util.Msg;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.level.ServerPlayer;

import java.util.Arrays;

/** /duel, /accept, /deny, /leave and /again. */
public final class DuelCommands {
	private static final SuggestionProvider<CommandSourceStack> ONLINE = (ctx, builder) ->
		SharedSuggestionProvider.suggest(Arrays.asList(ctx.getSource().getServer().getPlayerList().getPlayerNamesArray()), builder);

	private static final SuggestionProvider<CommandSourceStack> INVITERS = (ctx, builder) -> {
		ServerPlayer p = ctx.getSource().getPlayer();
		if (p == null) return builder.buildFuture();
		return SharedSuggestionProvider.suggest(DuelManager.pendingFrom(p.getUUID()), builder);
	};

	private DuelCommands() {}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("duel")
			.then(Commands.literal("accept")
				.executes(ctx -> accept(ctx, null))
				.then(Commands.argument("player", StringArgumentType.word()).suggests(INVITERS)
					.executes(ctx -> accept(ctx, StringArgumentType.getString(ctx, "player")))))
			.then(Commands.literal("deny")
				.executes(ctx -> deny(ctx, null))
				.then(Commands.argument("player", StringArgumentType.word()).suggests(INVITERS)
					.executes(ctx -> deny(ctx, StringArgumentType.getString(ctx, "player")))))
			.then(Commands.argument("player", StringArgumentType.word()).suggests(ONLINE)
				.executes(DuelCommands::open)));

		dispatcher.register(Commands.literal("accept")
			.executes(ctx -> accept(ctx, null))
			.then(Commands.argument("player", StringArgumentType.word()).suggests(INVITERS)
				.executes(ctx -> accept(ctx, StringArgumentType.getString(ctx, "player")))));

		dispatcher.register(Commands.literal("deny")
			.executes(ctx -> deny(ctx, null))
			.then(Commands.argument("player", StringArgumentType.word()).suggests(INVITERS)
				.executes(ctx -> deny(ctx, StringArgumentType.getString(ctx, "player")))));

		dispatcher.register(Commands.literal("leave").executes(ctx -> {
			ServerPlayer p = ctx.getSource().getPlayerOrException();
			if (com.pvpduels.workshop.Workshop.isIn(p.getUUID())) com.pvpduels.workshop.Workshop.exit(p);
			else DuelManager.leave(p);
			return 1;
		}));

		dispatcher.register(Commands.literal("again").executes(ctx -> {
			DuelManager.again(ctx.getSource().getPlayerOrException());
			return 1;
		}));
	}

	private static int open(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer me = ctx.getSource().getPlayerOrException();
		String name = StringArgumentType.getString(ctx, "player");
		ServerPlayer target = ctx.getSource().getServer().getPlayerList().getPlayerByName(name);
		if (target == null) {
			ctx.getSource().sendFailure(Msg.err("No player named '" + name + "' is online."));
			return 0;
		}
		if (target.getUUID().equals(me.getUUID())) {
			ctx.getSource().sendFailure(Msg.err("You can't duel yourself."));
			return 0;
		}
		if (DuelManager.isBusy(me.getUUID())) {
			ctx.getSource().sendFailure(Msg.err("You are busy: finish your duel or leave the kit workshop (/leave) first."));
			return 0;
		}
		if (DuelManager.isBusy(target.getUUID())) {
			ctx.getSource().sendFailure(Msg.err(target.getName().getString() + " is busy right now (in a duel or the kit workshop)."));
			return 0;
		}
		DuelMenus.open(me, target);
		return 1;
	}

	private static int accept(CommandContext<CommandSourceStack> ctx, String from) throws CommandSyntaxException {
		DuelManager.accept(ctx.getSource().getPlayerOrException(), from);
		return 1;
	}

	private static int deny(CommandContext<CommandSourceStack> ctx, String from) throws CommandSyntaxException {
		DuelManager.deny(ctx.getSource().getPlayerOrException(), from);
		return 1;
	}
}
