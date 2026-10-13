package com.pvpduels.effects;

import com.mojang.brigadier.CommandDispatcher;
import com.pvpduels.duel.EffectsMenus;
import com.pvpduels.util.Msg;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.Predicate;

/** /effects (your victory effects), /missions (the daily and weekly missions). */
public final class EffectCommands {
	private static final Predicate<CommandSourceStack> IS_ADMIN = Commands.hasPermission(Commands.LEVEL_GAMEMASTERS);

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
}
