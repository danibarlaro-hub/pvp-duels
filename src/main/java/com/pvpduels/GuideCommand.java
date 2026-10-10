package com.pvpduels;

import com.mojang.brigadier.CommandDispatcher;
import com.pvpduels.util.Msg;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

public final class GuideCommand {
	private static final String[] LINES = {
		"/kit save <name>  - save your current inventory as a kit",
		"/kit load <name>  - replace your inventory with a kit",
		"/kit list  - list your kits",
		"/kit delete <name>  - delete a kit",
		"/kit profile <kit> <profile|none>  - sort a kit with a layout profile when it is loaded",
		"/layout slot <1-9> <rules...>  - set the priority list of a hotbar slot (e.g. sword spear)",
		"/layout clear <1-9|all>  - clear one slot or the whole profile",
		"/layout show | list | new <name> | use <name> | delete <name>  - manage profiles",
		"/layout sort  - sort your hotbar now (same as the Sort hotbar key, set in Controls)",
		"Rules can be categories (sword, spear, axe, mace, bow, crossbow, shield, totem, crystal, obsidian, gapple, pearl, potion, food, block...), exact item ids, or 'hand'."
	};

	private GuideCommand() {}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("guide").executes(ctx -> {
			ctx.getSource().sendSuccess(() -> Msg.title("PvP Duels - commands"), false);
			for (String line : LINES) {
				ctx.getSource().sendSuccess(() -> Msg.info(line), false);
			}
			return 1;
		}));
	}
}
