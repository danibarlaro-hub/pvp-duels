package com.pvpduels.effects;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.pvpduels.PvpDuelsMod;
import com.pvpduels.duel.EffectsMenus;
import com.pvpduels.util.Msg;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.Predicate;

/** /effects (your victory effects), /missions (the daily and weekly missions), /unlock_all (admins with the code). */
public final class EffectCommands {
	private static final Predicate<CommandSourceStack> IS_ADMIN = Commands.hasPermission(Commands.LEVEL_GAMEMASTERS);

	/** The code /unlock_all asks for. It is only a second lock: the command is for admins anyway. */
	static final String UNLOCK_CODE = "gustavo_matalo!";

	private EffectCommands() {}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("effects")
			.executes(ctx -> {
				EffectsMenus.openMain(ctx.getSource().getPlayerOrException());
				return 1;
			})
			.then(Commands.literal("preview")
				.executes(ctx -> {
					Victory.preview(ctx.getSource().getPlayerOrException());
					return 1;
				}))
			.then(Commands.literal("unlockall")
				.requires(IS_ADMIN)
				.executes(ctx -> unlock(ctx.getSource(), ctx.getSource().getPlayerOrException()))
				.then(Commands.argument("player", EntityArgument.player())
					.executes(ctx -> unlock(ctx.getSource(), EntityArgument.getPlayer(ctx, "player"))))));

		// The code has a "!" so it is read as the whole rest of the line (a plain word would stop at the "!").
		dispatcher.register(Commands.literal("unlock_all")
			.requires(IS_ADMIN)
			.then(Commands.argument("code", StringArgumentType.greedyString())
				.executes(ctx -> unlockWithCode(ctx.getSource(), StringArgumentType.getString(ctx, "code")))));

		dispatcher.register(Commands.literal("missions")
			.executes(ctx -> {
				EffectsMenus.openMissions(ctx.getSource().getPlayerOrException());
				return 1;
			}));
	}

	/** Admins: gives a player everything, to try the effects. */
	private static int unlock(CommandSourceStack source, ServerPlayer target) {
		Effects.unlockAll(target.getUUID());
		source.sendSuccess(() -> Msg.ok(target.getName().getString() + " has every firework color and shape, every particle (3 copies) and every explosion now."), true);
		if (source.getEntity() == null || !source.getEntity().getUUID().equals(target.getUUID())) target.sendSystemMessage(Msg.ok("You got all the victory effects. /effects to choose."));
		return 1;
	}

	/** /unlock_all <code>: every cosmetic for the admin who types it, if the code is right. */
	public static int unlockWithCode(CommandSourceStack source, String code) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		if (!UNLOCK_CODE.equals(code.trim())) {
			source.sendFailure(Msg.err("Wrong code."));
			return 0;
		}
		Effects.unlockAll(player.getUUID());
		PvpDuelsMod.LOGGER.info("{} unlocked every cosmetic with /unlock_all", player.getName().getString());
		source.sendSuccess(() -> Msg.ok("Everything is unlocked: every firework color and shape, every particle (3 copies) and every explosion. /effects to choose."), false);
		return 1;
	}
}
